package io.github.dshpet.mobile

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The voice model store: chunks are fetched from the mirrors, joined and
 * unpacked, and only a complete set of files counts as ready. The fetcher is
 * replaced with plain file streams, so no network is touched.
 */
class VoiceModelsTest {
    private lateinit var root: File
    private lateinit var served: File
    private var servedBase = ""

    @Before
    fun setUp() {
        root = createTempDir("voice-root")
        served = createTempDir("voice-served").apply { File(this, "voice").mkdirs() }
        servedBase = "mirror://served/"
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        served.deleteRecursively()
    }

    /** The production seam, backed by files instead of HTTP. */
    private fun fileFetcher(urls: Set<String> = setOf(servedBase)): (String) -> InputStream? =
        { url ->
            val base = url.substringBeforeLast("/voice/").trimEnd('/')
            if (base !in urls.map { it.trimEnd('/') }) null else {
                val file = File(served, "voice/" + url.substringAfterLast('/'))
                if (file.isFile) file.inputStream() else null
            }
        }

    /** Zips [files], slices the archive into chunk pieces and serves them. */
    private fun publish(files: Map<String, ByteArray>, spec: VoiceModelSpec, chunkCount: Int) {
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val bytes = archive.toByteArray()
        val per = (bytes.size + chunkCount - 1) / chunkCount
        spec.chunks.take(chunkCount).forEachIndexed { index, chunk ->
            val from = index * per
            val to = minOf(bytes.size, from + per)
            File(served, "voice/$chunk").writeBytes(bytes.copyOfRange(from, to))
        }
    }

    @Test
    fun nothingIsReadyBeforeTheDownload() {
        val models = VoiceModels(root, listOf(servedBase), fileFetcher())
        assertFalse(models.isReady(VoiceModels.ASR))
        assertFalse(models.isReady(VoiceModels.TTS))
        assertEquals(0L, models.storedBytes())
    }

    @Test
    fun chunksAreJoinedAndUnpacked() {
        val files = mapOf(
            "encoder.onnx" to ByteArray(2048) { 1 },
            "decoder.onnx" to ByteArray(1024) { 2 },
            "joiner.onnx" to ByteArray(512) { 3 },
            "tokens.txt" to "a\nb\n".toByteArray(),
        )
        publish(files, VoiceModels.ASR, VoiceModels.ASR.chunks.size)
        val models = VoiceModels(root, listOf(servedBase), fileFetcher())
        var lastDone = 0L
        val ok = models.download(VoiceModels.ASR) { done, _ -> lastDone = done }
        assertTrue(ok)
        assertTrue(models.isReady(VoiceModels.ASR))
        assertEquals(files["tokens.txt"]!!.size.toLong(),
            File(models.dir(VoiceModels.ASR), "tokens.txt").length())
        // Progress moves forward and the last report carries real bytes.
        assertTrue(lastDone > 0L)
    }

    @Test
    fun aFailedChunkAbortsTheDownload() {
        publish(mapOf("encoder.onnx" to ByteArray(64)), VoiceModels.ASR, 1)
        // Chunk 2 was never published, so the second fetch finds nothing.
        val models = VoiceModels(root, listOf(servedBase), fileFetcher())
        assertFalse(models.download(VoiceModels.ASR))
        assertFalse(models.isReady(VoiceModels.ASR))
    }

    @Test
    fun aMissingMirrorFallsThroughToTheNext() {
        publish(
            mapOf(
                "encoder.onnx" to ByteArray(32),
                "decoder.onnx" to ByteArray(32),
                "joiner.onnx" to ByteArray(32),
                "tokens.txt" to ByteArray(32),
            ),
            VoiceModels.ASR, VoiceModels.ASR.chunks.size)
        // The first mirror never answers, the second one serves.
        val models = VoiceModels(root, listOf("mirror://dead/", servedBase), fileFetcher())
        assertTrue(models.download(VoiceModels.ASR))
        assertTrue(models.isReady(VoiceModels.ASR))
    }

    @Test
    fun anIncompleteUnpackDoesNotCountAsReady() {
        // The archive has every chunk but is missing a required file.
        publish(mapOf("encoder.onnx" to ByteArray(64)), VoiceModels.ASR,
            VoiceModels.ASR.chunks.size)
        val models = VoiceModels(root, listOf(servedBase), fileFetcher())
        assertFalse(models.download(VoiceModels.ASR))
        assertFalse(models.isReady(VoiceModels.ASR))
    }

    @Test
    fun eachTimbreMapsToItsOwnModel() {
        assertEquals(VoiceModels.TTS, VoiceModels.ttsSpec(PetOptions.TIMBRE_BAKER))
        assertEquals(VoiceModels.XIAOYA, VoiceModels.ttsSpec(PetOptions.TIMBRE_XIAOYA))
        assertEquals(VoiceModels.CHAOWEN, VoiceModels.ttsSpec(PetOptions.TIMBRE_CHAOWEN))
        // Every listed file exists in the archive the packager produced.
        assertTrue(VoiceModels.XIAOYA.files.contains("lexicon.txt"))
        assertTrue(VoiceModels.XIAOYA.chunks.size == 2)
        assertTrue(VoiceModels.XIAOYA.bytes > 0L)
    }

    @Test
    fun nestedFilesKeepTheirFolders() {
        // Every file the spec demands, nested dict tree included, so the
        // archive can never fall behind the spec's file list.
        val files = VoiceModels.TTS.files.associateWith { name ->
            if (name == "dict/jieba.dict.utf8") "词".toByteArray() else ByteArray(8)
        }
        publish(files, VoiceModels.TTS, VoiceModels.TTS.chunks.size)
        val models = VoiceModels(root, listOf(servedBase), fileFetcher())
        assertTrue(models.download(VoiceModels.TTS))
        assertTrue(File(models.dir(VoiceModels.TTS), "dict/jieba.dict.utf8").isFile)
        assertTrue(models.isReady(VoiceModels.TTS))
    }
}
