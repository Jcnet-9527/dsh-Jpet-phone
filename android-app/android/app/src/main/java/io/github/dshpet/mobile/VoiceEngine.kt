package io.github.dshpet.mobile

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
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

    /** Opens the matcha zh TTS from an unpacked TTS model. */
    fun speaker(dir: File): VoiceSpeaker {
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                matcha = OfflineTtsMatchaModelConfig(
                    acousticModel = File(dir, "model.onnx").absolutePath,
                    vocoder = File(dir, "vocoder.onnx").absolutePath,
                    lexicon = File(dir, "lexicon.txt").absolutePath,
                    tokens = File(dir, "tokens.txt").absolutePath,
                    dictDir = File(dir, "dict").absolutePath,
                ),
                numThreads = 2,
                debug = false,
            ),
            // Numbers, dates and phone numbers read properly ("2025年" is not
            // "二零二五年" by accident); the fsts come with the model.
            ruleFsts = listOf("phone.fst", "date.fst", "number.fst")
                .joinToString(",") { File(dir, it).absolutePath },
            maxNumSentences = 1,
        )
        return SherpaSpeaker(OfflineTts(config = config))
    }

    private class SherpaRecognizer(private val engine: OnlineRecognizer) : VoiceRecognizer {
        private var stream = engine.createStream()

        override fun start() {
            engine.reset(stream)
        }

        override fun accept(samples: ShortArray, count: Int): String {
            val floats = FloatArray(count)
            for (i in 0 until count) floats[i] = samples[i] / 32768f
            stream.acceptWaveform(floats, SAMPLE_RATE)
            if (engine.isReady(stream)) engine.decode(stream)
            return engine.getResult(stream).text.replace(" ", "")
        }

        override fun finish(): String {
            stream.inputFinished()
            if (engine.isReady(stream)) engine.decode(stream)
            val text = engine.getResult(stream).text.replace(" ", "")
            engine.reset(stream)
            return text
        }

        override fun close() {
            stream.release()
            engine.release()
        }
    }

    private class SherpaSpeaker(private val engine: OfflineTts) : VoiceSpeaker {
        override val sampleRate get() = engine.sampleRate()

        override fun synthesize(text: String, speed: Float): ShortArray? {
            if (text.isBlank()) return null
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
            return pcm
        }

        override fun close() {
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
 */
class SpeechPlayer(private val speaker: VoiceSpeaker) : AutoCloseable {
    private val thread = Thread(::loop, "dsh-pet-voice").apply {
        isDaemon = true
        start()
    }
    private val lock = Object()
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
            play(pcm, speaker.sampleRate)
        }
    }

    private fun play(pcm: ShortArray, sampleRate: Int) {
        stopTrack()
        val size = pcm.size * 2
        val audio = AudioTrack(AudioManager.STREAM_MUSIC, sampleRate,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            size.coerceAtLeast(8192), AudioTrack.MODE_STATIC)
        track = audio
        audio.write(pcm, 0, pcm.size)
        audio.play()
        // Sleep it out; the next line stops this track and starts fresh.
        val durationMs = pcm.size * 1000L / sampleRate.coerceAtLeast(1)
        try {
            Thread.sleep(durationMs + 120)
        } catch (_: InterruptedException) {
        }
    }

    private fun stopTrack() {
        track?.let { runCatching { it.stop() }; runCatching { it.release() } }
        track = null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) { lock.notifyAll() }
        thread.interrupt()
        stopTrack()
        speaker.close()
    }
}
