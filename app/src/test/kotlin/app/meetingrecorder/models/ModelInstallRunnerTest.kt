package app.meetingrecorder.models

import app.meetingrecorder.core.models.InstalledModel
import app.meetingrecorder.core.models.InstallFailure
import app.meetingrecorder.core.models.ModelCatalogEntry
import app.meetingrecorder.core.models.ModelFiles
import app.meetingrecorder.core.models.ModelInstallState
import app.meetingrecorder.core.models.ModelKind
import app.meetingrecorder.core.models.ModelLanguages
import app.meetingrecorder.core.models.ModelOrigin
import app.meetingrecorder.core.models.PartState
import app.meetingrecorder.models.download.DownloadControl
import app.meetingrecorder.models.download.DownloadFailure
import app.meetingrecorder.models.download.DownloadListener
import app.meetingrecorder.models.download.DownloadResult
import app.meetingrecorder.models.download.Stop
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelInstallRunnerTest {
    private lateinit var root: File
    private lateinit var models: File
    private val sha = "b".repeat(64)

    private fun entry(id: String, size: Long, kind: ModelKind = ModelKind.SPEECH, companion: String? = null) = ModelCatalogEntry(
        id = id, kind = kind, displayNameKey = "name_$id", descriptionKey = "description_$id", languages = ModelLanguages.MULTILINGUAL,
        sizeBytes = size, sha256 = sha,
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/$id.bin",
        sourceName = "Hugging Face (huggingface.co)", license = "MIT", approxMemoryMb = 100, vadCompanionId = companion,
    )

    private val vad = entry("vad", 100, ModelKind.VAD)
    private val tiny = entry("tiny", 1000, companion = "vad")

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("runner-test").toFile()
        models = File(root, "models").apply { mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private val downloads = mutableListOf<String>()
    private var script: (ModelCatalogEntry, DownloadListener) -> DownloadResult = { e, l -> succeed(e, l) }

    private fun succeed(entry: ModelCatalogEntry, listener: DownloadListener): DownloadResult {
        listener.onProgress(entry.sizeBytes / 2, entry.sizeBytes)
        listener.onProgress(entry.sizeBytes, entry.sizeBytes)
        File(models, "${entry.id}.bin").writeBytes(ByteArray(entry.sizeBytes.toInt()))
        val model = InstalledModel(entry.id, "n", ModelOrigin.CATALOG, sha, entry.sizeBytes, entry.languages, "2026-11-17T08:00:00Z")
        File(models, "${entry.id}.json").writeText(ModelFiles.encodeSidecar(model))
        return DownloadResult.Installed(model)
    }

    private fun parts(entry: ModelCatalogEntry, bytes: Long) {
        File(models, "${entry.id}.part").writeBytes(ByteArray(bytes.toInt()))
        File(models, "${entry.id}.part.json").writeText(ModelFiles.encodePartState(PartState(entry.url, entry.sha256, entry.sizeBytes, "\"e\"", bytes)))
    }

    private var importResult: ImportResult = ImportResult.Rejected

    private fun runner(): Pair<ModelInstallRunner, ModelStateStore> {
        val catalog = { listOf(tiny, vad) }
        val repository = ModelRepository(models, catalog = catalog, freeSpace = { Long.MAX_VALUE / 2 })
        val store = ModelStateStore(repository, catalog)
        val runner = ModelInstallRunner(
            store = store,
            repository = repository,
            catalog = catalog,
            download = { e, name, l ->
                assertEquals("name_${e.id}", name)
                downloads += e.id
                script(e, l)
            },
            importModel = { _, _ -> importResult },
            displayName = { "name_${it.id}" },
        )
        return runner to store
    }

    private val progress = mutableListOf<Pair<Long, Long>>()
    private fun onProgress(done: Long, total: Long) {
        progress += done to total
    }

    @Test
    fun theVoiceActivityCompanionIsDownloadedFirstAndProgressCoversBothFiles() {
        val (runner, store) = runner()

        val outcome = runner.runDownload("tiny", DownloadControl(), ::onProgress)

        assertEquals(InstallOutcome.Installed("tiny"), outcome)
        assertEquals(listOf("vad", "tiny"), downloads)
        assertEquals(ModelInstallState.Installed, store.stateOf("tiny"))
        assertEquals(1100L, progress.last().second, "the total is the combined size")
        assertEquals(1100L, progress.last().first)
        assertTrue(progress.map { it.first }.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun anInstalledCompanionIsNotDownloadedAgain() {
        File(models, "vad.bin").writeBytes(ByteArray(100))
        File(models, "vad.json").writeText(ModelFiles.encodeSidecar(InstalledModel("vad", "n", ModelOrigin.CATALOG, sha, 100, ModelLanguages.MULTILINGUAL, "x")))
        val (runner, _) = runner()

        runner.runDownload("tiny", DownloadControl(), ::onProgress)

        assertEquals(listOf("tiny"), downloads)
        assertEquals(1000L, progress.last().second)
    }

    @Test
    fun aPausedDownloadResumesFromItsBytes() {
        File(models, "vad.bin").writeBytes(ByteArray(100))
        File(models, "vad.json").writeText(ModelFiles.encodeSidecar(InstalledModel("vad", "n", ModelOrigin.CATALOG, sha, 100, ModelLanguages.MULTILINGUAL, "x")))
        parts(tiny, 400)
        val (runner, store) = runner()
        assertEquals(ModelInstallState.Paused(400, 1000), store.stateOf("tiny"))

        val outcome = runner.runDownload("tiny", DownloadControl(), ::onProgress)

        assertEquals(InstallOutcome.Installed("tiny"), outcome)
        assertEquals(ModelInstallState.Installed, store.stateOf("tiny"))
    }

    @Test
    fun cancelLeavesTheModelNotInstalled() {
        script = { _, _ -> DownloadResult.Cancelled }
        val (runner, store) = runner()

        assertEquals(InstallOutcome.Cancelled, runner.runDownload("tiny", DownloadControl(), ::onProgress))
        assertEquals(ModelInstallState.NotInstalled, store.stateOf("tiny"))
    }

    @Test
    fun anInterruptionLeavesTheDownloadPausedAndReportsWhyItStopped() {
        script = { e, _ ->
            if (e.id == "tiny") parts(e, 300)
            if (e.id == "tiny") DownloadResult.Interrupted("timeout") else succeed(e, object : DownloadListener {
                override val control = DownloadControl()
                override fun onProgress(bytesDone: Long, total: Long) = Unit
            })
        }
        val (runner, store) = runner()

        val outcome = runner.runDownload("tiny", DownloadControl(), ::onProgress)

        assertEquals(InstallOutcome.Paused(connectionLost = true), outcome)
        assertTrue(store.stateOf("tiny") is ModelInstallState.Paused)
        assertTrue(File(models, "tiny.part").exists())
    }

    @Test
    fun aPauseForARecordingIsNotAConnectionLoss() {
        val control = DownloadControl()
        script = { e, _ ->
            if (e.id == "tiny") {
                control.stop(Stop.PAUSE)
                DownloadResult.Interrupted("paused")
            } else {
                succeed(e, object : DownloadListener {
                    override val control = DownloadControl()
                    override fun onProgress(bytesDone: Long, total: Long) = Unit
                })
            }
        }
        val (runner, _) = runner()

        assertEquals(InstallOutcome.Paused(connectionLost = false), runner.runDownload("tiny", control, ::onProgress))
    }

    @Test
    fun aChecksumFailureEndsInTheFailedState() {
        script = { e, l -> if (e.id == "tiny") DownloadResult.Failed(DownloadFailure.CHECKSUM_MISMATCH) else succeed(e, l) }
        val (runner, store) = runner()

        val outcome = runner.runDownload("tiny", DownloadControl(), ::onProgress)

        assertEquals(InstallOutcome.Failed(ModelError.Checksum), outcome)
        assertEquals(ModelInstallState.Failed(InstallFailure.CHECKSUM), store.stateOf("tiny"))
    }

    @Test
    fun otherFailuresMapToNoticesAndLeaveTheModelNotInstalled() {
        val cases = mapOf(
            DownloadFailure.UNEXPECTED_ADDRESS to ModelError.UnexpectedAddress,
            DownloadFailure.TOO_MANY_REDIRECTS to ModelError.UnexpectedAddress,
            DownloadFailure.FILE_CHANGED_ON_SERVER to ModelError.FileChanged,
            DownloadFailure.BAD_RESPONSE to ModelError.Network,
        )
        for ((failure, error) in cases) {
            script = { e, l -> if (e.id == "tiny") DownloadResult.Failed(failure) else succeed(e, l) }
            downloads.clear()
            val (runner, store) = runner()

            assertEquals(InstallOutcome.Failed(error), runner.runDownload("tiny", DownloadControl(), ::onProgress), "for $failure")
            assertEquals(ModelInstallState.NotInstalled, store.stateOf("tiny"), "for $failure")
        }
    }

    @Test
    fun aFailingCompanionStopsBeforeTheModelIsFetched() {
        script = { e, l -> if (e.id == "vad") DownloadResult.Failed(DownloadFailure.CHECKSUM_MISMATCH) else succeed(e, l) }
        val (runner, store) = runner()

        val outcome = runner.runDownload("tiny", DownloadControl(), ::onProgress)

        assertEquals(InstallOutcome.Failed(ModelError.Checksum), outcome)
        assertEquals(listOf("vad"), downloads)
        // The failure is shown on the model the user asked for.
        assertEquals(ModelInstallState.Failed(InstallFailure.CHECKSUM), store.stateOf("tiny"))
    }

    @Test
    fun anUnknownModelFails() {
        val (runner, _) = runner()
        assertEquals(InstallOutcome.Failed(ModelError.Unknown), runner.runDownload("nope", DownloadControl(), ::onProgress))
    }

    private val source = object : ImportSource {
        override val displayName = "m.bin"
        override val sizeBytes: Long? = 10
        override fun open(): InputStream = ByteArrayInputStream(ByteArray(10))
    }

    @Test
    fun importResultsAreMappedAndTheStoreIsReloaded() {
        val (runner, store) = runner()
        val model = InstalledModel("imported-0123456789ab", "m.bin", ModelOrigin.IMPORTED, sha, 10, ModelLanguages.MULTILINGUAL, "x")

        importResult = ImportResult.Installed(model).also {
            File(models, "imported-0123456789ab.bin").writeBytes(ByteArray(10))
            File(models, "imported-0123456789ab.json").writeText(ModelFiles.encodeSidecar(model))
        }
        assertEquals(InstallOutcome.Installed(model.id), runner.runImport(source, DownloadControl(), ::onProgress))
        assertEquals(ModelInstallState.Installed, store.stateOf(model.id))

        importResult = ImportResult.AlreadyInstalled(model)
        assertEquals(InstallOutcome.Failed(ModelError.AlreadyInstalled), runner.runImport(source, DownloadControl(), ::onProgress))
        importResult = ImportResult.Rejected
        assertEquals(InstallOutcome.Failed(ModelError.NotAModel), runner.runImport(source, DownloadControl(), ::onProgress))
        importResult = ImportResult.NotEnoughSpace(123)
        assertEquals(InstallOutcome.Failed(ModelError.NotEnoughSpace(123)), runner.runImport(source, DownloadControl(), ::onProgress))
        importResult = ImportResult.Cancelled
        assertEquals(InstallOutcome.Cancelled, runner.runImport(source, DownloadControl(), ::onProgress))
        importResult = ImportResult.Failed("x")
        assertEquals(InstallOutcome.Failed(ModelError.ImportFailed), runner.runImport(source, DownloadControl(), ::onProgress))
    }

    @Test
    fun anImportStopRequestCountsAsCancelled() {
        val control = DownloadControl()
        var seen: Boolean? = null
        val repository = ModelRepository(models, catalog = { emptyList() }, freeSpace = { Long.MAX_VALUE / 2 })
        val runner = ModelInstallRunner(
            store = ModelStateStore(repository) { emptyList() },
            repository = repository,
            catalog = { emptyList() },
            download = { _, _, _ -> error("no download") },
            importModel = { _, listener ->
                control.stop(Stop.PAUSE) // a recording starts: an import is cancelled, not paused
                seen = listener.isCancelled()
                ImportResult.Cancelled
            },
            displayName = { it.id },
        )

        runner.runImport(source, control, ::onProgress)

        assertEquals(true, seen)
    }
}
