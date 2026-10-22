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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The voice feature, both halves of it.
 *
 * Input: hold the microphone button, speak, release - the transcript goes to the
 * chat module. Output: voice mode speaks *every* line that reaches the pet's
 * bubble, whether it came from a typed question, a spoken one, a tap or the
 * balance query. The speaking itself hangs off the service's showPhrase, so no
 * module has to remember to call it.
 *
 * Everything runs on device; the only network involved is the one-time download
 * of the models.
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
    private var modelUrls = ""
    @Volatile private var recognizer: VoiceRecognizer? = null
    @Volatile private var player: SpeechPlayer? = null
    @Volatile private var speakerTimbre = ""
    @Volatile private var recording = false
    @Volatile private var recordThread: Thread? = null
    private var sessionPet = 0
    private var buttonTries = 0

    override fun onEnable() {
        alive.set(true)
        VoiceBridge.speak = ::speakLine
        ensureVoiceModels()
        maybeShowButton()
    }

    /**
     * Stops the feature without touching a native engine.
     *
     * This runs on the main thread, and at this very moment the recorder thread
     * may be inside `accept()` and the player thread inside `synthesize()`.
     * Releasing either model here was a use-after-free - the app died the
     * instant voice mode was switched off mid-sentence, and only survived when
     * the pet happened to be paused. So: drop the flags and the bridges now,
     * then hand the teardown to a worker that waits for both threads first.
     */
    override fun onDisable() {
        if (!alive.compareAndSet(true, false)) return
        VoiceBridge.speak = null
        VoiceBridge.hide?.invoke()
        recording = false
        Thread(::teardown, "dsh-pet-voice-stop").apply { isDaemon = true }.start()
    }

    /** Runs on the shutdown worker: nothing here may overlap a native call. */
    private fun teardown() {
        // Let queued work drain, so nothing new is built behind our back.
        runCatching { worker.shutdown() }
        runCatching { worker.awaitTermination(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS) }
        // The recorder feeds the recogniser; close it only once it has stopped.
        val feeder = recordThread
        recordThread = null
        runCatching { feeder?.join(CLOSE_WAIT_MS) }
        val closingPlayer = player
        val closingRecognizer = recognizer
        player = null
        recognizer = null
        speakerTimbre = ""
        runCatching { closingRecognizer?.close() }
        runCatching { closingPlayer?.close() }
    }

    /** Queues [block] unless the module is gone; a shut-down worker must not throw. */
    private fun onWorker(block: () -> Unit) {
        if (!alive.get()) return
        runCatching { worker.execute(block) }
    }

    override fun menuItems(pet: PetHandle): List<ModuleMenu> {
        if (!host.options.chatConfigured) return emptyList()
        return listOf(ModuleMenu(R.string.voice_talk_menu) {
            // The button is a mode, not a dialogue: the entry toggles it.
            if (VoiceBridge.opened?.invoke() == true) VoiceBridge.hide?.invoke()
            else startVoiceInput(pet.id)
        })
    }

    override fun onOptionsChanged() {
        val options = host.options
        if (options.packBaseUrl.trim() != modelUrls) openModels()
        if (options.voiceTimbre != speakerTimbre && player != null) {
            // Releasing a TTS model on the main thread is the same use-after-free
            // that made switching voice mode off crash: hand it to the worker.
            val stale = player
            player = null
            speakerTimbre = ""
            onWorker { runCatching { stale?.close() } }
        }
        if (!alive.get()) return
        ensureVoiceModels()
        if (options.voiceChat) maybeShowButton() else VoiceBridge.hide?.invoke()
    }

    override fun diagnostics(): String =
        "voice=${if (asrReady()) "ready" else "missing"}" +
            ", timbre=${host.options.voiceTimbre}"

    /** True once the ASR model sits in private storage. */
    private fun asrReady(): Boolean = models?.isReady(VoiceModels.ASR)
        ?: (VoiceStore.current()?.isReady(VoiceModels.ASR) ?: false)

    /** The TTS model of the timbre the user picked. */
    private fun ttsSpec(): VoiceModelSpec = VoiceModels.ttsSpec(host.options.voiceTimbre)

    /** Speaks one bubble line; called from the service, on the main thread. */
    private fun speakLine(text: String) {
        if (!alive.get() || !host.options.voiceSpeak || text.isBlank()) return
        onWorker {
            if (!alive.get()) return@onWorker
            val speak = ensureSpeaker() ?: return@onWorker
            if (alive.get()) speak.speak(text, host.options.voiceSpeed)
        }
    }

    /**
     * Fetches whichever halves are switched on and still missing, so the first
     * bubble after enabling voice mode is not silent.
     */
    private fun ensureVoiceModels() {
        if (!host.options.packAuto) return
        openModels()
        val manager = models ?: return
        val wanted = mutableListOf<VoiceModelSpec>()
        if (host.options.voiceChat && !manager.isReady(VoiceModels.ASR)) {
            wanted += VoiceModels.ASR
        }
        if (host.options.voiceSpeak && !manager.isReady(ttsSpec())) wanted += ttsSpec()
        if (wanted.isEmpty()) return
        manager.downloadAll(wanted) { }
    }

    // ---- input ------------------------------------------------------------

    /**
     * Puts the hold-to-talk button next to the pet and leaves it there: voice
     * input is a mode the user switches on, not a one-shot dialogue.
     */
    private fun maybeShowButton() {
        if (!alive.get()) return
        val pet = host.pets.firstOrNull() ?: return
        if (!host.options.voiceChat || !host.options.chatConfigured) return
        val context = runCatching { host.context }.getOrNull() ?: return
        if (!hasMicPermission(context)) return
        openModels()
        val manager = models ?: return
        if (!manager.isReady(VoiceModels.ASR)) {
            // No model, no button: the settings screen (or the auto download
            // below) is the only way to get one.
            if (host.options.packAuto && buttonTries == 0) downloadModels(pet.id, thenButton = true)
            return
        }
        sessionPet = pet.id
        val open = VoiceBridge.button ?: return
        if (open(pet.id, ::beginUtterance, ::endUtterance)) {
            buttonTries = 0
            return
        }
        // The service may not have laid the pet out yet at startup; retry a
        // handful of times instead of losing the button for the whole session.
        if (buttonTries++ < 10) main.postDelayed({ maybeShowButton() }, 300)
    }

    /** The menu entry / settings tap: permission, models, then the button. */
    private fun startVoiceInput(petId: Int) {
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
                    host.showPhrase(pet, context.getString(R.string.voice_model_missing), 6000,
                        spoken = false)
                }
                return
            }
            downloadModels(petId, thenButton = true)
            return
        }
        sessionPet = petId
        VoiceBridge.button?.invoke(petId, ::beginUtterance, ::endUtterance)
    }

    private fun downloadModels(petId: Int, thenButton: Boolean) {
        val context = runCatching { host.context }.getOrNull() ?: return
        val manager = models ?: return
        val pet = host.pets.firstOrNull { it.id == petId } ?: host.pets.firstOrNull() ?: return
        val wanted = mutableListOf(VoiceModels.ASR)
        // Voice mode needs the chosen timbre; without it the button would record
        // but never answer out loud.
        if (host.options.voiceSpeak) wanted += ttsSpec()
        host.showPhrase(pet, context.getString(R.string.voice_model_downloading), 30_000,
            spoken = false)
        manager.downloadAll(wanted) { ok ->
            if (!alive.get()) return@downloadAll
            if (ok && thenButton) main.post { maybeShowButton() }
            else if (!ok) main.post {
                host.pets.firstOrNull()?.let {
                    host.showPhrase(it, context.getString(R.string.voice_model_failed), 6000,
                        spoken = false)
                }
            }
        }
    }

    private fun beginUtterance() {
        if (recording) return
        val context = runCatching { host.context }.getOrNull() ?: return
        if (!hasMicPermission(context)) return
        recording = true
        onWorker {
            // A new line of speech replaces whatever the pet is still saying.
            player?.stop()
            val engine = ensureRecognizer() ?: run {
                recording = false
                return@onWorker
            }
            if (!alive.get()) {
                recording = false
                return@onWorker
            }
            engine.start()
            val record = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, VoiceEngine.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    VoiceEngine.SAMPLE_RATE * 2) // one second of buffer
            } catch (e: SecurityException) {
                recording = false
                return@onWorker
            } catch (e: IllegalArgumentException) {
                recording = false
                return@onWorker
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                recording = false
                return@onWorker
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
                runCatching { record.stop() }
                runCatching { record.release() }
            }.apply {
                isDaemon = true
                start()
            }
        }
    }

    /**
     * The tail of an utterance: wait for the last 100 ms of audio, decode, and
     * hand the transcript to the chat. All of that used to run on the main
     * thread - a join plus a native decode in a touch handler - and moved here
     * with the crash fix.
     */
    private fun endUtterance() {
        if (!recording) return
        recording = false
        val feeder = recordThread
        recordThread = null
        onWorker {
            runCatching { feeder?.join(FINISH_WAIT_MS) }
            if (!alive.get()) return@onWorker
            val engine = recognizer ?: return@onWorker
            val text = runCatching { engine.finish() }.getOrDefault("")
            if (text.isBlank()) {
                main.post { bubble(host.context.getString(R.string.voice_heard_nothing), 4000) }
                return@onWorker
            }
            // The transcript goes to the chat module; its reply comes back as the
            // pet's bubble and voice mode reads it out like any other line.
            main.post { ChatBridge.module?.send(text, sessionPet) { } }
        }
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
        val timbre = host.options.voiceTimbre
        player?.let { if (speakerTimbre == timbre) return it }
        val manager = models ?: return null
        val spec = ttsSpec()
        if (!manager.isReady(spec)) return null
        // Runs on the worker only: the old player may still be synthesising.
        player?.close()
        return runCatching {
            SpeechPlayer(VoiceEngine.speaker(manager.modelDir(spec), timbre))
        }.getOrNull()?.also {
            player = it
            speakerTimbre = timbre
        }
    }

    private fun bubble(text: String, durationMs: Long) {
        val pet = host.pets.firstOrNull { it.id == sessionPet } ?: host.pets.firstOrNull() ?: return
        // Never read the user's own half-finished transcript back to them.
        host.showPhrase(pet, text, durationMs, spoken = false)
    }

    private fun openModels() {
        val context = runCatching { host.context }.getOrNull() ?: return
        val custom = host.options.packBaseUrl.trim()
        val builtIn = io.github.dshpet.mobile.PackManager.current()?.baseUrls ?: emptyList()
        val urls = ((if (custom.isEmpty()) emptyList() else listOf(custom)) + builtIn)
        if (models != null && urls.joinToString("|") == modelUrls) return
        modelUrls = urls.joinToString("|")
        models = VoiceStore.shared(context, urls)
    }

    private companion object {
        const val LONG_BUBBLE = 30_000L

        /** How long teardown waits for the worker and the recorder to go quiet. */
        const val CLOSE_WAIT_MS = 3_000L

        /** How long the end of an utterance waits for the last chunk of audio. */
        const val FINISH_WAIT_MS = 1_500L
    }
}

/** The links between the voice module, the service's windows and the chat. */
object VoiceBridge {
    /**
     * Set while the overlay is up: draws the hold-to-talk button next to the
     * pet (petId, press, release). Returns false when the pet is not on screen
     * yet, so the caller can retry.
     */
    @Volatile
    var button: ((Int, () -> Unit, () -> Unit) -> Boolean)? = null

    /** Removes the button (voice input switched off). */
    @Volatile
    var hide: (() -> Unit)? = null

    /** True while the button is on screen. */
    @Volatile
    var opened: (() -> Boolean)? = null

    /** Speaks one line through the TTS engine; null when voice is off. */
    @Volatile
    var speak: ((String) -> Unit)? = null
}

private fun hasMicPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
