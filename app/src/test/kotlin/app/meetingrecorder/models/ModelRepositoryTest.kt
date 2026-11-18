package app.meetingrecorder.models

import app.meetingrecorder.core.models.DownloadRules
import app.meetingrecorder.core.models.InstalledModel
import app.meetingrecorder.core.models.ModelCatalogEntry
import app.meetingrecorder.core.models.ModelFiles
import app.meetingrecorder.core.models.ModelKind
import app.meetingrecorder.core.models.ModelLanguages
import app.meetingrecorder.core.models.ModelOrigin
import app.meetingrecorder.core.models.PartState
import app.meetingrecorder.core.models.Sha256Streaming
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelRepositoryTest {
    private lateinit var root: File
    private lateinit var models: File
    private var usedBy: String? = null
    private var free = 10L * 1024 * 1024 * 1024
    private val clearedDefaults = mutableListOf<String>()
    private val content = "pretend this is a model".toByteArray()
    private val contentHash = Sha256Streaming().update(content).hex()

    private val entry = ModelCatalogEntry(
        id = "tiny-q5_1", kind = ModelKind.SPEECH, displayNameKey = "model_tiny_name", descriptionKey = "model_tiny_description",
        languages = ModelLanguages.MULTILINGUAL, sizeBytes = content.size.toLong(), sha256 = contentHash,
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-tiny-q5_1.bin",
        sourceName = "Hugging Face (huggingface.co)", license = "MIT", approxMemoryMb = 300,
    )

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("models-test").toFile()
        models = File(root, "models")
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun repository(catalog: List<ModelCatalogEntry> = listOf(entry)) = ModelRepository(
        directory = models,
        catalog = { catalog },
        runningModelId = { usedBy },
        onDeleted = { clearedDefaults += it },
        freeSpace = { free },
        now = { "2026-11-17T08:00:00Z" },
    )

    private fun model(id: String = "tiny-q5_1", size: Long = content.size.toLong(), sha: String = contentHash) = InstalledModel(
        id = id, displayName = "Tiny", origin = ModelOrigin.CATALOG, sha256 = sha, sizeBytes = size,
        languages = ModelLanguages.MULTILINGUAL, installedAt = "2026-11-17T08:00:00Z",
    )

    private fun install(id: String = "tiny-q5_1", bytes: ByteArray = content, sidecar: InstalledModel? = model(id)) {
        models.mkdirs()
        File(models, ModelFiles.binName(id)).writeBytes(bytes)
        if (sidecar != null) File(models, ModelFiles.sidecarName(id)).writeText(ModelFiles.encodeSidecar(sidecar))
    }

    @Test
    fun listsInstalledModelsByTheRuleOfModelFiles() {
        install("tiny-q5_1")
        install("wrong-size", sidecar = model("wrong-size", size = 999))
        install("no-sidecar", sidecar = null)
        models.mkdirs()
        File(models, "broken.bin").writeBytes(content)
        File(models, "broken.json").writeText("{ not a sidecar")
        File(models, "only-sidecar.json").writeText(ModelFiles.encodeSidecar(model("only-sidecar")))

        val repository = repository()

        assertEquals(listOf("tiny-q5_1"), repository.installed().map { it.id })
        assertNotNull(repository.find("tiny-q5_1"))
        assertNull(repository.find("wrong-size"))
    }

    @Test
    fun anEmptyOrMissingDirectoryListsNothing() {
        assertTrue(repository().installed().isEmpty())
        models.mkdirs()
        assertTrue(repository().installed().isEmpty())
    }

    @Test
    fun partialFilesAreNotListedAsInstalled() {
        models.mkdirs()
        File(models, "tiny-q5_1.part").writeBytes(content)
        File(models, "tiny-q5_1.part.json").writeText("{}")
        assertTrue(repository().installed().isEmpty())
    }

    @Test
    fun aBinWithoutASidecarIsRecognizedByItsHashAndTheSidecarIsRebuilt() {
        install(sidecar = null)

        repository().repair()

        val rebuilt = repository().find("tiny-q5_1")
        assertNotNull(rebuilt)
        assertEquals(contentHash, rebuilt.sha256)
        assertEquals(ModelOrigin.CATALOG, rebuilt.origin)
        assertEquals(ModelLanguages.MULTILINGUAL, rebuilt.languages)
        // A second repair does not need the catalog any more: the sidecar exists, nothing is hashed again.
        repository(catalog = emptyList()).repair()
        assertNotNull(repository(catalog = emptyList()).find("tiny-q5_1"))
    }

    @Test
    fun aBinWithoutASidecarThatMatchesNoCatalogEntryIsDeleted() {
        install(id = "mystery", bytes = "something else".toByteArray(), sidecar = null)

        repository().repair()

        assertFalse(File(models, "mystery.bin").exists())
    }

    @Test
    fun aBinWhoseHashDiffersFromTheCatalogEntryOfItsNameIsDeleted() {
        install(bytes = "tampered".toByteArray(), sidecar = null)

        repository().repair()

        assertFalse(File(models, "tiny-q5_1.bin").exists())
    }

    @Test
    fun repairRemovesStaleStagingFilesAndOrphanedPartStates() {
        models.mkdirs()
        File(models, "tiny-q5_1.json.tmp").writeText("half")
        File(models, "other.part.json").writeText("{}") // no .part next to it
        File(models, "kept.part").writeBytes(content)
        File(models, "kept.part.json").writeText("{}")

        repository().repair()

        assertEquals(setOf("kept.part", "kept.part.json"), models.list()!!.toSet())
    }

    @Test
    fun deleteRemovesBinSidecarAndPartFilesAndClearsTheDefaultModel() {
        install()
        File(models, "tiny-q5_1.part").writeBytes(content)
        File(models, "tiny-q5_1.part.json").writeText("{}")
        File(models, "other.bin").writeBytes(content)

        assertEquals(DeleteResult.DELETED, repository().delete("tiny-q5_1"))

        assertEquals(setOf("other.bin"), models.list()!!.toSet())
        assertEquals(listOf("tiny-q5_1"), clearedDefaults)
    }

    @Test
    fun deleteIsRefusedWhileTheRunningJobUsesTheModel() {
        install()
        usedBy = "tiny-q5_1"

        assertEquals(DeleteResult.IN_USE, repository().delete("tiny-q5_1"))

        assertTrue(File(models, "tiny-q5_1.bin").exists())
        assertTrue(clearedDefaults.isEmpty())
    }

    @Test
    fun aResumableWorkFileInAMeetingIsNoReasonToRefuseTheDelete() {
        install()
        usedBy = "another-model"
        assertEquals(DeleteResult.DELETED, repository().delete("tiny-q5_1"))
    }

    @Test
    fun freeSpaceIsComparedWithTheRequiredBytes() {
        val repository = repository()
        val size = 1_000_000_000L
        val required = DownloadRules.requiredFreeBytes(size, 0)

        free = required
        assertTrue(repository.hasSpaceFor(size, 0))
        assertEquals(0L, repository.missingBytes(size, 0))

        free = required - 1234
        assertFalse(repository.hasSpaceFor(size, 0))
        assertEquals(1234L, repository.missingBytes(size, 0))

        // Bytes that are already on disk need no new space.
        assertTrue(repository.hasSpaceFor(size, size - 1))
    }

    @Test
    fun theSidecarIsWrittenThroughAStagedRename() {
        val repository = repository()
        models.mkdirs()
        File(models, "tiny-q5_1.bin").writeBytes(content)

        repository.writeSidecar(model())
        repository.writeSidecar(model().copy(displayName = "Tiny (renamed)"))

        assertEquals("Tiny (renamed)", repository.find("tiny-q5_1")?.displayName)
        assertEquals(setOf("tiny-q5_1.bin", "tiny-q5_1.json"), models.list()!!.toSet())
    }

    @Test
    fun partStateRoundTripsAndAnUnreadableOneIsNull() {
        val repository = repository()
        val state = PartState(url = entry.url, expectedSha256 = contentHash, expectedSize = 100, etag = "\"abc\"", bytesDone = 40)

        repository.writePartState("tiny-q5_1", state)
        assertEquals(state, repository.partState("tiny-q5_1"))

        File(models, "tiny-q5_1.part.json").writeText("garbage")
        assertNull(repository.partState("tiny-q5_1"))
        assertNull(repository.partState("unknown"))
    }

    @Test
    fun aModelFileOutsideTheModelDirectoryIsNeverListed() {
        // FR-038: models live outside every meeting folder; a stray file in one is not a model.
        val meeting = File(root, "meetings/2026-11-16_10-00-00").apply { mkdirs() }
        File(meeting, "tiny-q5_1.bin").writeBytes(content)
        File(meeting, "tiny-q5_1.json").writeText(ModelFiles.encodeSidecar(model()))

        assertTrue(repository().installed().isEmpty())
    }
}
