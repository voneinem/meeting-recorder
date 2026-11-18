package app.meetingrecorder.models

import app.meetingrecorder.core.models.ModelCatalogEntry
import app.meetingrecorder.core.models.ModelKind
import app.meetingrecorder.core.models.ModelLanguages
import app.meetingrecorder.core.models.ModelOrigin
import app.meetingrecorder.core.models.Sha256Streaming
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelImporterTest {
    private lateinit var root: File
    private lateinit var models: File
    private var free = 10L * 1024 * 1024 * 1024

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("importer-test").toFile()
        models = File(root, "models")
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    /** The 48-byte header followed by [payload] bytes. */
    private fun modelBytes(vocab: Int = 51_865, audioCtx: Int = 1500, textCtx: Int = 448, mels: Int = 80, payload: Int = 200 * 1024): ByteArray {
        val header = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(0x67676d6c).putInt(vocab).putInt(audioCtx).putInt(384).putInt(6).putInt(4)
        header.putInt(textCtx).putInt(384).putInt(6).putInt(4).putInt(mels).putInt(8)
        return header.array() + ByteArray(payload) { (it % 241).toByte() }
    }

    private class MemorySource(
        override val displayName: String?,
        private val bytes: ByteArray,
        override val sizeBytes: Long? = bytes.size.toLong(),
        private val failAfter: Int? = null,
    ) : ImportSource {
        var opened = 0
        override fun open(): InputStream {
            opened++
            val base = ByteArrayInputStream(bytes)
            val limit = failAfter ?: return base
            return object : InputStream() {
                private var count = 0
                override fun read(): Int = if (++count > limit) throw IOException("the provider failed") else base.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (count >= limit) throw IOException("the provider failed")
                    val read = base.read(b, off, minOf(len, limit - count))
                    if (read > 0) count += read
                    return read
                }
            }
        }
    }

    private class Listener(private val cancelAfter: Long? = null) : ImportListener {
        val progress = mutableListOf<Long>()
        var cancelled = false
        override fun onProgress(bytesDone: Long, total: Long) {
            progress += bytesDone
            if (cancelAfter != null && bytesDone >= cancelAfter) cancelled = true
        }

        override fun isCancelled() = cancelled
    }

    private fun entryFor(bytes: ByteArray) = ModelCatalogEntry(
        id = "tiny-q5_1", kind = ModelKind.SPEECH, displayNameKey = "model_tiny_name", descriptionKey = "model_tiny_description",
        languages = ModelLanguages.MULTILINGUAL, sizeBytes = bytes.size.toLong(), sha256 = Sha256Streaming().update(bytes).hex(),
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-tiny-q5_1.bin",
        sourceName = "Hugging Face (huggingface.co)", license = "MIT", approxMemoryMb = 300,
    )

    private fun importer(catalog: List<ModelCatalogEntry> = emptyList()): ModelImporter {
        val repository = ModelRepository(models, catalog = { catalog }, freeSpace = { free }, now = { "2026-11-17T08:00:00Z" })
        return ModelImporter(repository, catalog = { catalog }, now = { "2026-11-17T08:00:00Z" })
    }

    private fun files(): Set<String> = models.list()?.toSet().orEmpty()

    @Test
    fun aTextFileIsRejectedBeforeAnyByteIsCopied() {
        val source = MemorySource("notes.txt", "this is not a model, it is just a long enough text file......".toByteArray())

        val result = importer().import(source, Listener())

        assertEquals(ImportResult.Rejected, result)
        assertEquals(emptySet(), files(), "no .part may be created")
    }

    @Test
    fun theVoiceActivityModelIsRejected() {
        // Same magic, other values: n_audio_ctx and n_text_ctx do not match a speech model.
        val vad = MemorySource("ggml-silero-v6.2.0.bin", modelBytes(vocab = 0, audioCtx = 0, textCtx = 0, mels = 0, payload = 1000))

        assertEquals(ImportResult.Rejected, importer().import(vad, Listener()))
        assertEquals(emptySet(), files())
    }

    @Test
    fun aFileShorterThanTheHeaderIsRejected() {
        assertEquals(ImportResult.Rejected, importer().import(MemorySource("tiny.bin", ByteArray(10)), Listener()))
        assertEquals(emptySet(), files())
    }

    @Test
    fun freeSpaceIsCheckedAgainstTheProviderSizeBeforeCopying() {
        val bytes = modelBytes()
        free = 1024 // far less than the file and its margin

        val result = importer().import(MemorySource("ggml-tiny.bin", bytes), Listener())

        val short = assertIs<ImportResult.NotEnoughSpace>(result)
        assertTrue(short.missingBytes > bytes.size - 1024)
        assertEquals(emptySet(), files())
    }

    @Test
    fun theCopyIsHashedReportsProgressAndInstallsTheModel() {
        val bytes = modelBytes()
        val listener = Listener()

        val result = importer().import(MemorySource("my-model.bin", bytes), listener)

        val model = assertIs<ImportResult.Installed>(result).model
        assertEquals(Sha256Streaming().update(bytes).hex(), model.sha256)
        assertEquals(bytes.size.toLong(), model.sizeBytes)
        assertEquals(ModelOrigin.IMPORTED, model.origin)
        assertTrue(listener.progress.size >= 3 && listener.progress.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(bytes.size.toLong(), listener.progress.last())
        assertTrue(bytes.contentEquals(File(models, "${model.id}.bin").readBytes()))
        assertEquals(setOf("${model.id}.bin", "${model.id}.json"), files())
    }

    @Test
    fun aFileWithTheHashOfACatalogEntryIsRecognizedAsThatEntry() {
        val bytes = modelBytes()
        val entry = entryFor(bytes)

        val result = importer(listOf(entry)).import(MemorySource("renamed-by-the-user.bin", bytes), Listener())

        val model = assertIs<ImportResult.Installed>(result).model
        assertEquals("tiny-q5_1", model.id)
        assertEquals(ModelOrigin.IMPORTED, model.origin, "the origin stays imported")
        assertEquals("model_tiny_name", model.displayName, "the name comes from the catalog")
        assertEquals(ModelLanguages.MULTILINGUAL, model.languages)
    }

    @Test
    fun anUnknownEnglishOnlyFileIsListedByItsFileNameWithLanguagesFromTheVocabulary() {
        val bytes = modelBytes(vocab = 51_864)

        val model = assertIs<ImportResult.Installed>(importer().import(MemorySource("ggml-base.en.bin", bytes), Listener())).model

        assertTrue(model.id.startsWith("imported-") && model.id.length == "imported-".length + 12)
        assertEquals("ggml-base.en.bin", model.displayName)
        assertEquals(ModelLanguages.ENGLISH_ONLY, model.languages)
    }

    @Test
    fun aMissingFileNameFallsBackToAGenericOne() {
        val model = assertIs<ImportResult.Installed>(importer().import(MemorySource(null, modelBytes()), Listener())).model
        assertEquals("model.bin", model.displayName)
    }

    @Test
    fun importingAFileThatIsAlreadyInstalledIsANoOp() {
        val bytes = modelBytes()
        val importer = importer()
        val first = assertIs<ImportResult.Installed>(importer.import(MemorySource("a.bin", bytes), Listener())).model
        val before = files()

        val again = importer.import(MemorySource("b.bin", bytes), Listener())

        assertEquals(ImportResult.AlreadyInstalled(first), again)
        assertEquals(before, files())
    }

    @Test
    fun cancelRemovesThePartialFile() {
        val result = importer().import(MemorySource("big.bin", modelBytes(payload = 600 * 1024)), Listener(cancelAfter = 64 * 1024))

        assertEquals(ImportResult.Cancelled, result)
        assertEquals(emptySet(), files())
    }

    @Test
    fun aFailingProviderLeavesNoPartialFile() {
        val result = importer().import(MemorySource("flaky.bin", modelBytes(), failAfter = 100 * 1024), Listener())

        assertIs<ImportResult.Failed>(result)
        assertEquals(emptySet(), files())
    }

    @Test
    fun aFileThatIsShorterThanTheProviderSaidFails() {
        val bytes = modelBytes()
        val result = importer().import(MemorySource("short.bin", bytes, sizeBytes = bytes.size + 1000L), Listener())

        assertIs<ImportResult.Failed>(result)
        assertEquals(emptySet(), files())
    }
}
