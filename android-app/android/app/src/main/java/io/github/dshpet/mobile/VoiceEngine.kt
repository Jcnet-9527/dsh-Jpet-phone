package io.github.dshpet.mobile

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One utterance being recognised. Feeding happens while the user holds the
 * button; [finish] closes the utterance and returns the final text. The
 * implementation may reuse its stream between utterances.
 */
interface VoiceRecognizer : AutoCloseable {
    /** Begins a fresh utterance; call before the first feed of each turn. */
    fun start()

    /** Feeds [count] samples and returns the running transcript. */
    fun accept(samples: ShortArray, count: Int): String

    /** Ends the utterance and returns the final transcript. */
    fun finish(): String
}

/** Speaks text; the caller owns threading and playback order. */
interface VoiceSpeaker : AutoCloseable {
    /** Synthesises [text]; blocking. Returns 16-bit mono PCM or null. */
    fun synthesize(text: String, speed: Float): ShortArray?

    val sampleRate: Int
}

/**
 * The sherpa-onnx engines. Everything JNI lives here and nowhere else, and
 * both interfaces stay mockable for the JVM unit tests, which have no native
 * library to load.
 */
object VoiceEngine {

    /** Opens the streaming zipformer recognizer from an unpacked ASR model. */
    fun recognizer(dir: File): VoiceRecognizer {
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(dir, "encoder.onnx").absolutePath,
                    decoder = File(dir, "decoder.onnx").absolutePath,
                    joiner = File(dir, "joiner.onnx").absolutePath),
                tokens = File(dir, "tokens.txt").absolutePath,
                numThreads = 2,
                debug = false,
            ),
        )
        return SherpaRecognizer(OnlineRecognizer(config = config))
    }

    /**
     * Opens the TTS model of [timbre] from its unpacked directory. The default
     * baker voice is a matcha model with a separate vocoder and a jieba dict;
     * the alternative voices are piper VITS models that carry everything in
     * one onnx plus a lexicon.
     */
    fun speaker(dir: File, timbre: String = PetOptions.TIMBRE_BAKER): VoiceSpeaker {
        val model = if (timbre == PetOptions.TIMBRE_BAKER) {
            OfflineTtsModelConfig(
                matcha = OfflineTtsMatchaModelConfig(
                    acousticModel = File(dir, "model.onnx").absolutePath,
                    vocoder = File(dir, "vocoder.onnx").absolutePath,
                    lexicon = File(dir, "lexicon.txt").absolutePath,
                    tokens = File(dir, "tokens.txt").absolutePath,
                    dictDir = File(dir, "dict").absolutePath,
                ),
                numThreads = 2,
                debug = false,
            )
        } else {
            OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = File(dir, "model.onnx").absolutePath,
                    lexicon = File(dir, "lexicon.txt").absolutePath,
                    tokens = File(dir, "tokens.txt").absolutePath,
                ),
                numThreads = 2,
                debug = false,
            )
        }
        val config = OfflineTtsConfig(
            model = model,
            // Numbers, dates and phone numbers read properly ("2025年" is not
            // "二零二五年" by accident); the fsts come with the model.
            ruleFsts = listOf("phone.fst", "date.fst", "number.fst")
                .joinToString(",") { File(dir, it).absolutePath },
            maxNumSentences = 1,
        )
        return SherpaSpeaker(OfflineTts(config = config))
    }

    /**
     * The gate every native call goes through. Releasing a model while another
     * thread is inside it is a use-after-free - and that is exactly what closing
     * the module on the main thread used to do - so [close] waits its turn and
     * every later call is a no-op instead of a crash.
     */
    private class SherpaRecognizer(private val engine: OnlineRecognizer) : VoiceRecognizer {
        private val gate = Any()
        private var stream = engine.createStream()
        private var closed = false

        override fun start() = synchronized(gate) {
            if (!closed) engine.reset(stream)
        }

        override fun accept(samples: ShortArray, count: Int): String = synchronized(gate) {
            if (closed) return ""
            val floats = FloatArray(count)
            for (i in 0 until count) floats[i] = samples[i] / 32768f
            stream.acceptWaveform(floats, SAMPLE_RATE)
            if (engine.isReady(stream)) engine.decode(stream)
            engine.getResult(stream).text.replace(" ", "")
        }

        override fun finish(): String = synchronized(gate) {
            if (closed) return ""
            stream.inputFinished()
            if (engine.isReady(stream)) engine.decode(stream)
            val text = engine.getResult(stream).text.replace(" ", "")
            engine.reset(stream)
            text
        }

        override fun close() = synchronized(gate) {
            if (closed) return
            closed = true
            stream.release()
            engine.release()
        }
    }

    private class SherpaSpeaker(private val engine: OfflineTts) : VoiceSpeaker {
        private val gate = Any()
        private var closed = false

        override val sampleRate: Int get() = synchronized(gate) { if (closed) 0 else engine.sampleRate() }

        override fun synthesize(text: String, speed: Float): ShortArray? = synchronized(gate) {
            if (closed || text.isBlank()) return null
            val audio = try {
                engine.generate(text, sid = 0, speed = speed)
            } catch (e: RuntimeException) {
                return null
            }
            val samples = audio.samples ?: return null
            val pcm = ShortArray(samples.size)
            for (i in samples.indices) {
                val scaled = (samples[i] * 32767f).coerceIn(-32768f, 32767f)
                pcm[i] = scaled.toInt().toShort()
            }
            pcm
        }

        override fun close() = synchronized(gate) {
            if (closed) return
            closed = true
            engine.release()
        }
    }

    /** The AudioRecord sample rate both engines are configured for. */
    const val SAMPLE_RATE = 16000
}

/**
 * Plays synthesized PCM on a dedicated thread. A new line replaces the one
 * being spoken, so a quick follow-up question never queues behind a long
 * answer.
 *
 * Closing is the delicate part: [close] waits for the playback thread to leave
 * `synthesize()` before the model is released. The old version released it
 * first, and switching voice mode off mid-sentence took the whole app down.
 */
class SpeechPlayer(private val speaker: VoiceSpeaker) : AutoCloseable {
    private val thread = Thread(::loop, "dsh-pet-voice").apply {
        isDaemon = true
        start()
    }
    private val lock = Object()
    private val trackLock = Any()
    private var pending: String? = null
    private var speed = 1.0f
    private val closed = AtomicBoolean(false)
    private var track: AudioTrack? = null

    /** Replaces whatever is playing or pending with [text]. */
    fun speak(text: String, speed: Float) {
        if (closed.get() || text.isBlank()) return
        synchronized(lock) {
            pending = text
            this.speed = speed
            lock.notifyAll()
        }
    }

    /** Drops the pending line and stops playback; the speaker stays open. */
    fun stop() {
        if (closed.get()) return
        synchronized(lock) { pending = null }
        stopTrack()
    }

    private fun loop() {
        while (!closed.get()) {
            val line: String
            val rate: Float
            synchronized(lock) {
                while (pending == null && !closed.get()) lock.wait()
                if (closed.get()) return
                line = pending!!
                rate = speed
                pending = null
            }
            val pcm = runCatching { speaker.synthesize(line, rate) }.getOrNull() ?: continue
            if (closed.get()) return
            runCatching { play(pcm, speaker.sampleRate) }
        }
    }

    private fun play(pcm: ShortArray, sampleRate: Int) {
        stopTrack()
        if (closed.get() || pcm.isEmpty() || sampleRate <= 0) return
        val size = pcm.size * 2
        val audio = AudioTrack(AudioManager.STREAM_MUSIC, sampleRate,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            size.coerceAtLeast(8192), AudioTrack.MODE_STATIC)
        synchronized(trackLock) {
            if (closed.get()) {
                runCatching { audio.release() }
                return
            }
            track = audio
        }
        runCatching {
            audio.write(pcm, 0, pcm.size)
            audio.play()
        }.onFailure {
            stopTrack()
            return
        }
        // Sleep it out; the next line stops this track and starts fresh.
        val durationMs = pcm.size * 1000L / sampleRate
        try {
            Thread.sleep(durationMs + 120)
        } catch (_: InterruptedException) {
        }
    }

    private fun stopTrack() {
        val audio = synchronized(trackLock) { track.also { track = null } } ?: return
        runCatching { audio.pause() }
        runCatching { audio.stop() }
        runCatching { audio.release() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) { lock.notifyAll() }
        thread.interrupt()
        // Wait for the thread to leave native code before releasing the model.
        if (Thread.currentThread() !== thread) {
            runCatching { thread.join(CLOSE_WAIT_MS) }
        }
        stopTrack()
        speaker.close()
    }

    private companion object {
        /** Longest a synthesis may still run while its model waits to be freed. */
        const val CLOSE_WAIT_MS = 4_000L
    }
}
