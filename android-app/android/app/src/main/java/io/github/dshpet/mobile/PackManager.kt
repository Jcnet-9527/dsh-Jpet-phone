package io.github.dshpet.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** One download's observable state; ``total`` counts clips, not bytes. */
data class PackProgress(
    val clipId: String = "",
    val label: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val bytes: Long = 0,
    val failed: Boolean = false,
)

/** Shared download state, so the settings screen and the pet agree. */
object PackStatus {
    val progress = MutableStateFlow<PackProgress?>(null)

    /** Bumped whenever the set of downloaded clips changes. */
    val revision = MutableStateFlow(0)
}

/**
 * Animation packs that live outside the APK.
 *
 * The APK carries a handful of core clips; everything else is a ``clips/<id>.zip``
 * on a static host (a GitHub repo through a CDN by default). The first time an
 * action is asked for - by the wander loop or by the action menu - its pack is
 * downloaded, unpacked into private storage and decoded from there afterwards.
 * Frames are never re-encoded, so a downloaded clip is pixel-identical to a
 * bundled one.
 */
class PackManager(
    private val context: Context,
    /** Mirrors tried in order; the first one that serves the pack wins. */
    val baseUrls: List<String>,
) : AutoCloseable {
    private val root = File(context.filesDir, "packs")
    private val staging = File(context.cacheDir, "packs")
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pet-pack").apply { isDaemon = true }
    }
    private val pending = ArrayList<ClipSpec>()
    private val lock = Any()

    init {
        root.mkdirs()
        staging.mkdirs()
    }

    /** The decoded frame file, or null when the app should read the APK asset. */
    fun frameFile(clip: ClipSpec, index: Int): File? {
        if (clip.bundled) return null
        if (!isReady(clip)) return null
        val file = File(File(root, clip.path), frameName(index))
        return if (file.isFile) file else null
    }

    fun isReady(clip: ClipSpec) =
        clip.bundled || File(File(root, clip.path), frameName(0)).isFile

    fun cachedBytes(): Long {
        fun size(file: File): Long =
            if (file.isDirectory) (file.listFiles()?.sumOf { size(it) } ?: 0L) else file.length()
        return size(root)
    }

    /** Queues one clip; [onDone] runs on the main thread with success or failure. */
    fun ensure(clip: ClipSpec, onDone: ((Boolean) -> Unit)? = null) = submit(listOf(clip), onDone)

    /** Queues every clip that is not on the device yet, in manifest order. */
    fun downloadAll(clips: List<ClipSpec>) = submit(clips.filter { !isReady(it) }, null)

    fun hasPending(): Boolean = synchronized(lock) { pending.isNotEmpty() }

    private fun submit(clips: List<ClipSpec>, onDone: ((Boolean) -> Unit)?) {
        val todo = clips.filter { !isReady(it) }
        if (todo.isEmpty()) {
            onDone?.let { handler.post { it(true) } }
            return
        }
        synchronized(lock) {
            val fresh = todo.filter { clip -> pending.none { it.id == clip.id } }
            pending.addAll(fresh)
        }
        executor.execute {
            var done = 0
            val total = todo.size
            for (clip in todo) {
                val ok = fetch(clip)
                done++
                synchronized(lock) { pending.removeAll { it.id == clip.id } }
                publish(PackProgress(clip.id, clip.title, done, total, clip.bytes, !ok))
                onDone?.let { callback -> handler.post { callback(ok) } }
                if (ok) bump()
            }
            publish(null)
        }
    }

    /** Removes every downloaded pack; the core clips inside the APK stay. */
    fun clear() {
        executor.execute {
            root.listFiles()?.forEach { it.deleteRecursively() }
            staging.listFiles()?.forEach { it.delete() }
            root.mkdirs()
            bump()
        }
    }

    private fun publish(progress: PackProgress?) {
        handler.post { PackStatus.progress.value = progress }
    }

    private fun bump() {
        handler.post { PackStatus.revision.value = PackStatus.revision.value + 1 }
    }

    /** Tries every configured mirror until one serves the archive. */
    private fun fetch(clip: ClipSpec): Boolean {
        val name = "${clip.id}.zip"
        for (base in baseUrls) {
            val trimmed = base.trim()
            if (trimmed.isEmpty()) continue
            val url = URL(if (trimmed.endsWith("/")) trimmed else "$trimmed/").toString() + "clips/$name"
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.instanceFollowRedirects = true
                    connection.setRequestProperty("User-Agent", "dsh-pet-mobile")
                    connection.connect()
                    if (connection.responseCode !in 200..299) continue
                    val archive = File(staging, "$name.part")
                    connection.inputStream.use { input ->
                        FileOutputStream(archive).use { output -> input.copyTo(output) }
                    }
                    val ok = unpack(clip, archive)
                    archive.delete()
                    if (ok) return true
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "pack download failed: $url", e)
            }
        }
        return false
    }

    private fun unpack(clip: ClipSpec, archive: File): Boolean {
        val target = File(root, clip.path)
        val partial = File(root, clip.path + PART_SUFFIX)
        partial.deleteRecursively()
        partial.mkdirs()
        return try {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".webp")) {
                        // Only the file's own name: a hostile path cannot escape.
                        val name = entry.name.substringAfterLast('/')
                        if (name.isNotEmpty()) {
                            FileOutputStream(File(partial, name)).use { zip.copyTo(it) }
                        }
                    }
                    entry = zip.nextEntry
                }
            }
            if (!File(partial, frameName(0)).isFile) {
                partial.deleteRecursively()
                return false
            }
            target.deleteRecursively()
            if (partial.renameTo(target)) true else {
                target.mkdirs()
                partial.listFiles()?.forEach { it.copyTo(File(target, it.name), overwrite = true) }
                partial.deleteRecursively()
                File(target, frameName(0)).isFile
            }
        } catch (e: Exception) {
            Log.w(TAG, "pack unpack failed: ${clip.id}", e)
            partial.deleteRecursively()
            false
        }
    }

    override fun close() {
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "PetPack"
        private const val PART_SUFFIX = ".part"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000

        /**
         * One downloader for the whole app: the pet's overlay and the settings
         * screen share it so a clip is never fetched twice.
         */
        @Volatile
        private var shared: PackManager? = null

        fun shared(context: Context, baseUrls: List<String>): PackManager {
            val existing = shared
            if (existing != null && existing.baseUrls == baseUrls) return existing
            existing?.close()
            return PackManager(context.applicationContext, baseUrls).also { shared = it }
        }

        fun current(): PackManager? = shared

        private fun frameName(index: Int) = "f_${(index + 1).toString().padStart(4, '0')}.webp"

        /** Where unpacked frames live; the settings screen reads this too. */
        private fun root(context: Context) = File(context.filesDir, "packs")

        fun isDownloaded(context: Context, clip: ClipSpec) =
            File(File(File(context.filesDir, "packs"), clip.path), frameName(0)).isFile

        fun storedBytes(context: Context): Long {
            fun size(file: File): Long = if (file.isDirectory)
                (file.listFiles()?.sumOf { size(it) } ?: 0L) else file.length()
            return size(File(context.filesDir, "packs"))
        }
    }
}
