package app.meetingrecorder.models

import app.meetingrecorder.core.models.InstallEffect
import app.meetingrecorder.core.models.InstallEvent
import app.meetingrecorder.core.models.InstallFailure
import app.meetingrecorder.core.models.InstalledModel
import app.meetingrecorder.core.models.ModelCatalogEntry
import app.meetingrecorder.core.models.ModelFiles
import app.meetingrecorder.core.models.ModelInstallState
import app.meetingrecorder.core.models.ModelKind
import app.meetingrecorder.core.models.ModelLanguages
import app.meetingrecorder.core.models.ModelOrigin
import app.meetingrecorder.core.models.PartState
import app.meetingrecorder.core.models.TransferKind
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelStateStoreTest {
    private lateinit var root: File
    private lateinit var models: File
    private val sha = "a".repeat(64)

    private fun entry(id: String, kind: ModelKind = ModelKind.SPEECH) = ModelCatalogEntry(
        id = id, kind = kind, displayNameKey = "name_$id", descriptionKey = "description_$id", languages = ModelLanguages.MULTILINGUAL,
        sizeBytes = 1000, sha256 = sha, url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/$id.bin",
        sourceName = "Hugging Face (huggingface.co)", license = "MIT", approxMemoryMb = 100,
    )

    private val tiny = entry("tiny")
    private val base = entry("base")

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("state-store").toFile()
        models = File(root, "models").apply { mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun store(): Pair<ModelStateStore, ModelRepository> {
        val repository = ModelRepository(models, catalog = { listOf(tiny, base) }, freeSpace = { Long.MAX_VALUE / 2 })
        return ModelStateStore(repository) { listOf(tiny, base) } to repository
    }

    private fun install(id: String, size: Long = 1000, origin: ModelOrigin = ModelOrigin.CATALOG) {
        File(models, "$id.bin").writeBytes(ByteArray(size.toInt()))
        File(models, "$id.json").writeText(
            ModelFiles.encodeSidecar(InstalledModel(id, "n", origin, sha, size, ModelLanguages.MULTILINGUAL, "2026-11-17T08:00:00Z")),
        )
    }

    private fun pause(id: String, entry: ModelCatalogEntry, bytes: Long) {
        File(models, "$id.part").writeBytes(ByteArray(bytes.toInt()))
        File(models, "$id.part.json").writeText(ModelFiles.encodePartState(PartState(entry.url, entry.sha256, entry.sizeBytes, "\"e\"", bytes)))
    }

    @Test
    fun theInitialStateComesFromTheFilesOnDisk() {
        install("tiny")
        pause("base", base, 400)

        val (store, _) = store()

        assertEquals(ModelInstallState.Installed, store.stateOf("tiny"))
        assertEquals(ModelInstallState.Paused(400, 1000), store.stateOf("base"))
    }

    @Test
    fun aPartialFileWithoutAMatchingStateIsNotPaused() {
        File(models, "tiny.part").writeBytes(ByteArray(400))
        File(models, "tiny.part.json").writeText("garbage")
        assertEquals(ModelInstallState.NotInstalled, store().first.stateOf("tiny"))
    }

    @Test
    fun aPartialFileForAnotherUrlIsNotPaused() {
        pause("tiny", tiny.copy(url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/other/tiny.bin"), 400)
        assertEquals(ModelInstallState.NotInstalled, store().first.stateOf("tiny"))
    }

    @Test
    fun anImportedModelOutsideTheCatalogIsListed() {
        install("imported-0123456789ab", origin = ModelOrigin.IMPORTED)

        val (store, _) = store()

        assertEquals(ModelInstallState.Installed, store.stateOf("imported-0123456789ab"))
        assertTrue("imported-0123456789ab" in store.states.value.keys)
    }

    @Test
    fun applyingEventsFollowsTheReducerAndTheStateFlowUpdates() {
        val (store, _) = store()

        store.apply("tiny", InstallEvent.Start(TransferKind.DOWNLOAD, 1000))
        store.apply("tiny", InstallEvent.Progress(500))

        assertEquals(ModelInstallState.Transferring(TransferKind.DOWNLOAD, 500, 1000), store.states.value["tiny"])
    }

    @Test
    fun anInterruptedDownloadKeepsItsPartialFiles() {
        val (store, _) = store()
        pause("tiny", tiny, 400)
        store.reload()
        store.apply("tiny", InstallEvent.Resume)

        val transition = store.apply("tiny", InstallEvent.Interrupted)

        assertEquals(listOf(InstallEffect.KEEP_PARTIAL), transition.effects)
        assertTrue(File(models, "tiny.part").exists())
        assertEquals(ModelInstallState.Paused(400, 1000), store.stateOf("tiny"))
    }

    @Test
    fun cancelAndDeletePartialRemoveThePartialFiles() {
        val (store, _) = store()
        pause("tiny", tiny, 400)
        store.reload()

        store.apply("tiny", InstallEvent.DeletePartial)

        assertFalse(File(models, "tiny.part").exists())
        assertFalse(File(models, "tiny.part.json").exists())
        assertEquals(ModelInstallState.NotInstalled, store.stateOf("tiny"))
    }

    @Test
    fun aChecksumFailureIsKeptUntilDismissed() {
        val (store, _) = store()
        store.apply("tiny", InstallEvent.Start(TransferKind.DOWNLOAD, 1000))
        store.apply("tiny", InstallEvent.Progress(1000))
        store.apply("tiny", InstallEvent.SizeReached)
        store.apply("tiny", InstallEvent.HashMismatch)
        assertEquals(ModelInstallState.Failed(InstallFailure.CHECKSUM), store.stateOf("tiny"))

        store.apply("tiny", InstallEvent.Dismiss)
        assertEquals(ModelInstallState.NotInstalled, store.stateOf("tiny"))
    }

    @Test
    fun deletingAnInstalledModelRemovesItsFiles() {
        install("tiny")
        val (store, _) = store()

        store.apply("tiny", InstallEvent.Delete(inUse = false))

        assertEquals(emptySet(), models.list()!!.toSet())
        assertEquals(ModelInstallState.NotInstalled, store.stateOf("tiny"))
    }

    @Test
    fun deletingAModelInUseIsRejectedAndKeepsTheFiles() {
        install("tiny")
        val (store, _) = store()

        val transition = store.apply("tiny", InstallEvent.Delete(inUse = true))

        assertTrue(transition.rejected)
        assertTrue(File(models, "tiny.bin").exists())
    }

    @Test
    fun onlyOneInstallMayRunAtATime() {
        val (store, _) = store()
        assertTrue(store.mayStart())

        store.apply("tiny", InstallEvent.Start(TransferKind.DOWNLOAD, 1000))
        assertFalse(store.mayStart())

        store.apply("tiny", InstallEvent.Interrupted)
        assertTrue(store.mayStart(), "a paused download does not block another install")
    }

    @Test
    fun theVoiceActivityModelIsNotOffered() {
        install("tiny")
        val vad = entry("vad", ModelKind.VAD)
        val repository = ModelRepository(models, catalog = { listOf(tiny, vad) }, freeSpace = { Long.MAX_VALUE / 2 })
        install("vad")

        val store = ModelStateStore(repository) { listOf(tiny, vad) }

        assertEquals(setOf("tiny"), store.states.value.keys)
    }
}
