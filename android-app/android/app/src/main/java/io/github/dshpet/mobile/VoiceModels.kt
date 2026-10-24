package io.github.dshpet.mobile

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * One downloadable voice model: the files it unpacks to, and the byte-sliced
 * chunks it travels as. The chunks exist because the static hosts that serve
 * the animation packs (jsDelivr, raw.githubusercontent) cap or throttle large
 * files, so a ~120 MB model ships as several ~18 MB pieces that are joined
 * before unpacking.
 */
data class VoiceModelSpec(
    val id: String,
    /** Files the model needs on disk, relative to its directory. */
    val files: List<String>,
    /** Chunk names in order, served as `voice/<name>` from the pack mirrors. */
    val chunks: List<String>,
    /** Total bytes across all chunks, for progress reporting. */
    val bytes: Long,
    /**
     * Hosts that only serve this model, tried before the shared pack mirrors.
     * The models that are not in the upstream assets repo (melo) live in our
     * own voice repo, and the pack mirrors would just 404 on them.
     */
    val mirrors: List<String> = emptyList(),
)

/**
 * The on-device voice models: a streaming zipformer ASR (Chinese + English)
 * and a matcha TTS, both from the sherpa-onnx project. They are far too big
 * for the APK, so like the animation packs they live on the same static hosts
 * and land in private storage on first use.
 *
 * [openStream] is the seam the unit tests replace: production tries every
 * mirror over HTTP, the tests hand back plain file streams.
 */
class VoiceModels(
    /** Where unpacked models live, e.g. `filesDir/voice`. */
    private val root: File,
    /** Mirrors tried in order, same list the animation packs use. */
    private val baseUrls: List<String>,
    /** Opens one chunk URL, or null when that mirror has nothing. */
    private val openStream: (String) -> InputStream? = ::defaultOpenStream,
) {
    private val downloading = AtomicBoolean(false)

    fun dir(spec: VoiceModelSpec): File = File(root, spec.id)

    /** True when every file the spec lists exists and is non-empty. */
    fun isReady(spec: VoiceModelSpec): Boolean =
        spec.files.all { File(dir(spec), it).let { f -> f.isFile && f.length() > 0L } }

    fun storedBytes(): Long {
        fun size(file: File): Long =
            if (file.isDirectory) (file.listFiles()?.sumOf { size(it) } ?: 0L) else file.length()
        return size(root)
    }

    /** Removes every downloaded model. */
    fun clear() {
        root.listFiles()?.forEach { it.deleteRecursively() }
    }

    /**
     * Downloads and unpacks [spec]; blocking, call off the main thread.
     * [progress] receives (bytesSoFar, totalBytes). Returns false when every
     * mirror failed or the download was already running.
     */
    fun download(spec: VoiceModelSpec, progress: (Long, Long) -> Unit = { _, _ -> }): Boolean {
        if (!downloading.compareAndSet(false, true)) return false
        try {
            val target = dir(spec)
            val partial = File(root, spec.id + ".part")
            partial.deleteRecursively()
            partial.mkdirs()
            var done = 0L
            for (chunk in spec.chunks) {
                val wrote = fetchChunk(chunk, File(partial, "bundle.zip"), done != 0L, spec)
                if (wrote < 0L) return false
                done += wrote
                progress(done, spec.bytes)
            }
            if (!unpack(spec, File(partial, "bundle.zip"))) return false
            partial.deleteRecursively()
            return isReady(spec)
        } finally {
            downloading.set(false)
        }
    }

    /**
     * Appends one chunk to the bundle and returns the bytes written, or -1 when
     * every mirror failed. [append] is false for the first chunk so a stale
     * bundle from an earlier attempt can never be grown by mistake.
     */
    private fun fetchChunk(name: String, bundle: File, append: Boolean, spec: VoiceModelSpec): Long {
        for (base in spec.mirrors + baseUrls) {
            val trimmed = base.trim().trimEnd('/')
            if (trimmed.isEmpty()) continue
            try {
                val input = openStream("$trimmed/voice/$name") ?: continue
                var bytes = 0L
                input.use { stream ->
                    FileOutputStream(bundle, append).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            bytes += read
                        }
                    }
                }
                if (bytes > 0L) return bytes
            } catch (e: Exception) {
                Log.w(TAG, "voice chunk failed: $name", e)
            }
        }
        return -1L
    }

    private fun unpack(spec: VoiceModelSpec, bundle: File): Boolean {
        val target = dir(spec)
        val staging = File(root, spec.id + ".unpack")
        staging.deleteRecursively()
        staging.mkdirs()
        return try {
            ZipInputStream(bundle.inputStream().buffered()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        // Only simple relative names: a hostile path cannot escape.
                        val name = entry.name.replace('\\', '/')
                        if (name.isNotBlank() && !name.startsWith("/") && !name.contains("..")) {
                            val out = File(staging, name)
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { zip.copyTo(it) }
                        }
                    }
                    entry = zip.nextEntry
                }
            }
            if (!spec.files.all { File(staging, it).isFile }) return false
            target.deleteRecursively()
            if (staging.renameTo(target)) true else {
                target.mkdirs()
                staging.copyRecursively(target, overwrite = true)
                staging.deleteRecursively()
                spec.files.all { File(target, it).isFile }
            }
        } catch (e: Exception) {
            Log.w(TAG, "voice unpack failed: ${spec.id}", e)
            staging.deleteRecursively()
            false
        }
    }

    companion object {
        private const val TAG = "PetVoice"

        /** One chunk over HTTP: redirects honoured, timeouts, UA, or null. */
        fun defaultOpenStream(url: String): InputStream? = try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "dsh-pet-mobile")
            if (connection.responseCode !in 200..299) {
                connection.disconnect(); null
            } else {
                connection.inputStream
            }
        } catch (e: Exception) {
            Log.w(TAG, "voice mirror unreachable: $url", e)
            null
        }

        /**
         * The ASR: sherpa-onnx streaming zipformer, small bilingual zh+en,
         * int8 encoder/joiner. Roughly 50 MB unpacked.
         */
        val ASR = VoiceModelSpec(
            id = "asr",
            files = listOf(
                "encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"),
            chunks = listOf("asr.zip.001", "asr.zip.002", "asr.zip.003"),
            bytes = 49_752_751L,
        )

        /**
         * The TTS: matcha-icefall-zh-baker (one female voice, 22.05 kHz) with
         * the vocos vocoder, plus the jieba dict and the number/date/phone
         * rule fsts so chat replies with numbers read correctly.
         */
        val TTS = VoiceModelSpec(
            id = "tts",
            files = listOf(
                "model.onnx", "vocoder.onnx", "tokens.txt", "lexicon.txt",
                "phone.fst", "date.fst", "number.fst",
                "dict/jieba.dict.utf8", "dict/hmm_model.utf8", "dict/user.dict.utf8",
                "dict/idf.utf8", "dict/stop_words.utf8",
                "dict/pos_dict/char_state_tab.utf8", "dict/pos_dict/prob_start.utf8",
                "dict/pos_dict/prob_emit.utf8", "dict/pos_dict/prob_trans.utf8"),
            chunks = listOf(
                "tts.zip.001", "tts.zip.002", "tts.zip.003", "tts.zip.004",
                "tts.zip.005", "tts.zip.006", "tts.zip.007", "tts.zip.008"),
            bytes = 145_676_731L,
        )

        /**
         * The piper voices: one int8 onnx per voice plus a lexicon, ~14 MB
         * each. Same 22.05 kHz quality as the baker, a very different timbre.
         */
        val XIAOYA = VoiceModelSpec(
            id = "tts-xiaoya",
            files = listOf(
                "model.onnx", "tokens.txt", "lexicon.txt",
                "phone.fst", "date.fst", "number.fst"),
            chunks = listOf("xiaoya.zip.001", "xiaoya.zip.002"),
            bytes = 20_930_645L,
        )

        val CHAOWEN = VoiceModelSpec(
            id = "tts-chaowen",
            files = listOf(
                "model.onnx", "tokens.txt", "lexicon.txt",
                "phone.fst", "date.fst", "number.fst"),
            chunks = listOf("chaowen.zip.001", "chaowen.zip.002"),
            bytes = 20_930_646L,
        )

        /**
         * melo-tts (zh+en, 44.1 kHz, int8): the natural-sounding voice. It is not
         * in the upstream assets repo, so it ships from our own voice repo and
         * carries its own mirror list. The lexicon is large (~55 MB unpacked)
         * but it is what gives the model its proper polyphone handling.
         */
        val MELO = VoiceModelSpec(
            id = "tts-melo",
            files = listOf("model.onnx", "lexicon.txt", "tokens.txt"),
            chunks = listOf("melo.zip.001", "melo.zip.002", "melo.zip.003"),
            bytes = 46_846_604L,
            mirrors = listOf(
                "https://gcore.jsdelivr.net/gh/ranw524/dsh-Jpet-voice@main/",
                "https://testingcf.jsdelivr.net/gh/ranw524/dsh-Jpet-voice@main/",
                "https://cdn.jsdelivr.net/gh/ranw524/dsh-Jpet-voice@main/",
                "https://raw.githubusercontent.com/ranw524/dsh-Jpet-voice/main/",
                "https://ghfast.top/raw.githubusercontent.com/ranw524/dsh-Jpet-voice/main/",
            ),
        )

        /** The TTS model behind a timbre id from [PetOptions.TIMBRES]. */
        fun ttsSpec(timbre: String): VoiceModelSpec = when (timbre) {
            PetOptions.TIMBRE_XIAOYA -> XIAOYA
            PetOptions.TIMBRE_CHAOWEN -> CHAOWEN
            PetOptions.TIMBRE_MELO -> MELO
            else -> TTS
        }

        /** Chunk name -> exact size; filled by the packager, read for progress. */
        val CHUNK_BYTES: Map<String, Long> = emptyMap()
    }
}

/** Shared voice download state, so the settings screen and the pet agree. */
object VoiceStatus {
    /** (bytesDone, bytesTotal) while a download runs, else null. */
    val progress = MutableStateFlow<Pair<Long, Long>?>(null)

    /** Bumped whenever a finished download changes what is on disk. */
    val revision = MutableStateFlow(0)
}

/**
 * One downloader for the whole app, mirroring [PackManager]'s pattern: the
 * settings screen and the pet's voice module share it, so the models are
 * never fetched twice and never unpacked concurrently.
 */
class VoiceStore private constructor(context: Context, val baseUrls: List<String>) {
    private val models = VoiceModels(File(context.filesDir, "voice"), baseUrls)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pet-voice-store").apply { isDaemon = true }
    }

    fun isReady(spec: VoiceModelSpec) = models.isReady(spec)

    /** Where an unpacked model lives; the engines read their files from here. */
    fun modelDir(spec: VoiceModelSpec): File = models.dir(spec)

    fun storedBytes() = models.storedBytes()

    fun clear() {
        executor.execute {
            models.clear()
            bump()
        }
    }

    /** Downloads every not-yet-ready spec, publishing progress as it goes. */
    fun downloadAll(specs: List<VoiceModelSpec>, onDone: ((Boolean) -> Unit)? = null) {
        val todo = specs.filter { !models.isReady(it) }
        if (todo.isEmpty()) {
            onDone?.let { it(true) }
            return
        }
        executor.execute {
            var ok = true
            for (spec in todo) {
                val fine = models.download(spec) { done, total ->
                    VoiceStatus.progress.value = done to total
                }
                if (!fine) ok = false
                if (!ok) break
            }
            VoiceStatus.progress.value = null
            if (ok) bump()
            onDone?.let { it(ok) }
        }
    }

    private fun bump() {
        VoiceStatus.revision.value = VoiceStatus.revision.value + 1
    }

    companion object {
        @Volatile
        private var shared: VoiceStore? = null

        fun shared(context: Context, baseUrls: List<String>): VoiceStore {
            val existing = shared
            if (existing != null && existing.baseUrls == baseUrls) return existing
            return VoiceStore(context.applicationContext, baseUrls).also { shared = it }
        }

        fun current(): VoiceStore? = shared
    }
}
