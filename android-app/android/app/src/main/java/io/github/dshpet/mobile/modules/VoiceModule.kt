package io.github.dshpet.mobile.modules

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import io.github.dshpet.mobile.MainActivity
import io.github.dshpet.mobile.ModuleIds
import io.github.dshpet.mobile.PetHandle
import io.github.dshpet.mobile.VoiceEngine
import io.github.dshpet.mobile.VoiceModels
import io.github.dshpet.mobile.VoiceModelSpec
import io.github.dshpet.mobile.VoiceStore
import io.github.dshpet.mobile.VoiceRecognizer
import io.github.dshpet.mobile.SpeechPlayer
import io.github.dshpet.mobile.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The voice conversation: hold the button, speak, release - the transcript
 * goes to the chat module, and the reply is spoken aloud through the TTS
 * engine. Everything runs on device; the only network involved is the one-time
 * download of the two voice models.
 *
 * The module owns no window: the service draws the hold-to-talk button where
 * the pet is (see [VoiceBridge.button]), exactly like it hosts the chat prompt.
 */
class VoiceModule : HostBoundModule() {
    override val id = ModuleIds.VOICE

    private val worker: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "dsh-pet-voice").apply { isDaemon = true }
        }
    }
    private val main: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val alive = AtomicBoolean(true)

    private var models: VoiceStore? = null
    private var recognizer: VoiceRecognizer? = null
    private var player: SpeechPlayer? = null
    private var recording = false
    private var recordThread: Thread? = null
    private var sessionPet = 0

    override fun onEnable() {
        alive.set(true)
        VoiceBridge.speak = ::speakReply
    }

    override fun onDisable() {
        alive.set(false)
        VoiceBridge.speak = null
        VoiceBridge.button = null
        stopRecording()
        runCatching { worker.shutdownNow() }
        recognizer?.close()
        recognizer = null
        player?.close()
        player = null
    }

    override fun menuItems(pet: PetHandle): List<ModuleMenu> {
        if (!host.options.chatConfigured) return emptyList()
        return listOf(ModuleMenu(R.string.voice_talk_menu) { startConversation(pet.id) })
    }

    override fun diagnostics(): String =
        "voice=${if (asrReady()) "ready" else "missing"}"

    /** True once the ASR model sits in private storage. */
    private fun asrReady(): Boolean = models?.isReady(VoiceModels.ASR)
        ?: (VoiceStore.current()?.isReady(VoiceModels.ASR) ?: false)

    private fun speakReply(text: String) {
        if (!host.options.voiceSpeak || text.isBlank()) return
        worker.execute {
            val speak = ensureSpeaker() ?: return@execute
            if (alive.get()) speak.speak(text, host.options.voiceSpeed)
        }
    }

    /** The whole flow behind the menu entry: permission, models, then the button. */
    private fun startConversation(petId: Int) {
        val context = runCatching { host.context }.getOrNull() ?: return
        if (!hasMicPermission(context)) {
            // An overlay service cannot ask; bounce through the settings screen,
            // which shows the system dialog on arrival.
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_VOICE_PERMISSION, true))
            return
        }
        openModels()
        val manager = models ?: return
        if (!manager.isReady(VoiceModels.ASR)) {
            if (!host.options.packAuto) {
                host.pets.firstOrNull { it.id == petId }?.let { pet ->
                    host.showPhrase(pet, context.getString(R.string.voice_model_missing), 6000)
                }
                return
            }
            downloadModels(petId)
            return
        }
        openButton(petId)
    }

    private fun downloadModels(petId: Int) {
        val context = runCatching { host.context }.getOrNull() ?: return
        val manager = models ?: return
        val pet = host.pets.firstOrNull { it.id == petId } ?: host.pets.firstOrNull() ?: return
        val wanted: List<VoiceModelSpec> = listOf(VoiceModels.ASR) +
            if (host.options.voiceSpeak) listOf(VoiceModels.TTS) else emptyList()
        host.showPhrase(pet, context.getString(R.string.voice_model_downloading), 30_000)
        manager.downloadAll(wanted) { ok ->
            if (!alive.get()) return@downloadAll
            if (ok) main.post { openButton(petId) }
            else host.pets.firstOrNull()?.let {
                host.showPhrase(it, context.getString(R.string.voice_model_failed), 6000)
            }
        }
    }

    /** Asks the service to draw the hold-to-talk button beside the pet. */
    private fun openButton(petId: Int) {
        val open = VoiceBridge.button ?: return
        sessionPet = petId
        open(petId, ::beginUtterance, ::endUtterance)
    }

    private fun beginUtterance() {
        if (recording) return
        val context = runCatching { host.context }.getOrNull() ?: return
        if (!hasMicPermission(context)) return
        recording = true
        // A new line of speech replaces whatever the pet is still saying.
        player?.stop()
        worker.execute {
            val engine = ensureRecognizer() ?: run {
                recording = false
                return@execute
            }
            engine.start()
            val record = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, VoiceEngine.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    VoiceEngine.SAMPLE_RATE * 2) // one second of buffer
            } catch (e: SecurityException) {
                recording = false
                return@execute
            } catch (e: IllegalArgumentException) {
                recording = false
                return@execute
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                recording = false
                return@execute
            }
            record.startRecording()
            val samples = ShortArray(VoiceEngine.SAMPLE_RATE / 10) // 100 ms
            recordThread = Thread {
                var lastText = ""
                while (recording) {
                    val count = record.read(samples, 0, samples.size)
                    if (count <= 0) break
                    val partial = engine.accept(samples, count)
                    if (partial != lastText && partial.isNotBlank() && alive.get()) {
                        lastText = partial
                        main.post { bubble(partial, LONG_BUBBLE) }
                    }
                }
                record.stop()
                record.release()
            }.apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun endUtterance() {
        if (!recording) return
        recording = false
        recordThread?.let { runCatching { it.join(1500) } }
        recordThread = null
        val engine = recognizer ?: return
        val text = runCatching { engine.finish() }.getOrDefault("")
        if (text.isBlank()) {
            bubble(host.context.getString(R.string.voice_heard_nothing), 4000)
            return
        }
        // The transcript goes to the chat module; its reply comes back as the
        // pet's bubble and, through VoiceBridge.speak, as speech.
        val chat = ChatBridge.module ?: return
        chat.send(text, sessionPet) { }
    }

    private fun ensureRecognizer(): VoiceRecognizer? {
        recognizer?.let { return it }
        val manager = models ?: return null
        if (!manager.isReady(VoiceModels.ASR)) return null
        return runCatching {
            VoiceEngine.recognizer(manager.modelDir(VoiceModels.ASR))
        }.getOrNull()?.also { recognizer = it }
    }

    private fun ensureSpeaker(): SpeechPlayer? {
        player?.let { return it }
        val manager = models ?: return null
        if (!manager.isReady(VoiceModels.TTS)) return null
        return runCatching {
            SpeechPlayer(VoiceEngine.speaker(manager.modelDir(VoiceModels.TTS)))
        }.getOrNull()?.also { player = it }
    }

    private fun stopRecording() {
        recording = false
        recordThread?.let { runCatching { it.join(1500) } }
        recordThread = null
    }

    private fun bubble(text: String, durationMs: Long) {
        val pet = host.pets.firstOrNull { it.id == sessionPet } ?: host.pets.firstOrNull() ?: return
        host.showPhrase(pet, text, durationMs)
    }

    private fun openModels() {
        if (models != null) return
        val context = runCatching { host.context }.getOrNull() ?: return
        val custom = host.options.packBaseUrl.trim()
        val builtIn = io.github.dshpet.mobile.PackManager.current()?.baseUrls ?: emptyList()
        val urls = (if (custom.isEmpty()) emptyList() else listOf(custom)) + builtIn
        models = VoiceStore.shared(context, urls)
    }

    private companion object {
        const val LONG_BUBBLE = 30_000L
    }
}

/** The links between the voice module, the service's windows and the chat. */
object VoiceBridge {
    /**
     * Set while the overlay is up: draws the hold-to-talk button next to the
     * pet (petId, press, release).
     */
    @Volatile
    var button: ((Int, () -> Unit, () -> Unit) -> Unit)? = null

    /** Speaks one line through the TTS engine; null when voice is off. */
    @Volatile
    var speak: ((String) -> Unit)? = null
}

private fun hasMicPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
