package app.meetingrecorder

import android.app.Application
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.net.toUri
import java.io.File
import app.meetingrecorder.recording.RecordingController
import app.meetingrecorder.recording.RecordingProbe
import app.meetingrecorder.recording.RecoveryRunner
import androidx.work.WorkManager
import app.meetingrecorder.core.models.HostPolicy
import app.meetingrecorder.core.models.ModelCatalog
import app.meetingrecorder.core.models.ModelCatalogEntry
import app.meetingrecorder.models.ModelController
import app.meetingrecorder.models.ModelImporter
import app.meetingrecorder.models.ModelInstallRunner
import app.meetingrecorder.models.ModelRepository
import app.meetingrecorder.models.ModelStateStore
import app.meetingrecorder.models.ModelStrings
import app.meetingrecorder.storage.FolderFiles
import app.meetingrecorder.storage.MeetingMetadataStore
import app.meetingrecorder.storage.SafFolderFiles
import app.meetingrecorder.storage.SafMeetingStorage
import app.meetingrecorder.transcription.ThreadPolicy
import app.meetingrecorder.transcription.TranscriptionRecovery
import app.meetingrecorder.transcription.TranscriptionController
import app.meetingrecorder.transcription.TranscriptionJobRunner
import app.meetingrecorder.transcription.TranscriptionRegistry
import app.meetingrecorder.transcription.WhisperDecoderFactory
import app.meetingrecorder.whisper.WhisperContext
import app.meetingrecorder.models.download.ModelDownloader
import app.meetingrecorder.work.JobKind
import app.meetingrecorder.work.JobRegistry
import app.meetingrecorder.work.LaunchToken
import app.meetingrecorder.work.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MeetingRecorderApp : Application() {
    /** The UI-side client of the recording service. Created lazily, so the service process never builds it. */
    val recordingController: RecordingController by lazy {
        // The recording has priority: a running transcription stops (and can be resumed), a download pauses.
        RecordingController(this, onBeforeStart = {
            transcriptionController.stopForRecording()
            modelController.pauseForRecording()
        })
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _recoveryRuns = MutableStateFlow(0)

    /** Counts finished recovery runs; screens that list meetings reload when it changes. */
    val recoveryRuns: StateFlow<Int> = _recoveryRuns.asStateFlow()

    /** One random token per start of the main process; jobs carry it so re-run work can be refused. */
    lateinit var launchToken: String
        private set

    /** The jobs this process has accepted; empty after every process start. Main process only. */
    lateinit var jobRegistry: JobRegistry
        private set

    private val preferences by lazy { AppPreferences(this) }

    /** Speech recognition models in `<filesDir>/models`, outside every meeting folder (FR-038). Main process only. */
    val modelRepository: ModelRepository by lazy {
        ModelRepository(
            directory = File(filesDir, "models"),
            runningModelId = { jobRegistry.runningTranscriptionModelId() },
            onDeleted = { id ->
                if (preferences.transcriptionDefaultModelId == id) preferences.transcriptionDefaultModelId = null
                if (preferences.transcriptionLastModelId == id) preferences.transcriptionLastModelId = null
            },
        )
    }

    /**
     * The catalog the model code reads. Always [ModelCatalog.entries] in the app; the instrumented download test
     * points it at a loopback server (the debug build allows `http://127.0.0.1` and nothing else).
     */
    @Volatile
    @VisibleForTesting
    var catalogSource: () -> List<ModelCatalogEntry> = { ModelCatalog.entries }

    val modelStateStore: ModelStateStore by lazy { ModelStateStore(modelRepository) { catalogSource() } }

    /** Downloads, imports and deletes of models. Created lazily and only in the main process. */
    val modelController: ModelController by lazy {
        val policy = if (BuildConfig.DEBUG) HostPolicy.DEBUG_LOOPBACK else HostPolicy.RELEASE
        val importer = ModelImporter(modelRepository)
        val runner = ModelInstallRunner(
            store = modelStateStore,
            repository = modelRepository,
            catalog = { catalogSource() },
            download = { entry, name, listener -> ModelDownloader(modelRepository, policy).download(entry, name, listener) },
            importModel = importer::import,
            displayName = { entry -> ModelStrings.res(entry.displayNameKey)?.let(::getString) ?: entry.id },
        )
        ModelController(
            store = modelStateStore,
            repository = modelRepository,
            runner = runner,
            catalog = { catalogSource() },
            registry = jobRegistry,
            launchToken = launchToken,
            recordingCheck = recordingProbe,
            workManager = WorkManager.getInstance(this),
            resolver = contentResolver,
        )
    }

    private val recoveryTranscribeThreads by lazy { ThreadPolicy.current() }

    /** The files of a meeting folder in the storage folder, or null when there is no storage or no such folder. */
    private fun meetingFiles(storage: SafMeetingStorage?, name: String): FolderFiles? =
        storage?.let { s -> s.findChild(s.rootUri, name)?.takeIf { it.isDirectory }?.let { SafFolderFiles(s, it.uri) } }

    /** Transcription of meetings. Created lazily and only in the main process. */
    val transcriptionController: TranscriptionController by lazy {
        val folderFiles: (String) -> FolderFiles? = { name ->
            meetingFiles(preferences.storageTreeUri?.let { SafMeetingStorage(this, it.toUri()) }, name)
        }
        val metadata = MeetingMetadataStore(folderFiles = folderFiles)
        val runner = TranscriptionJobRunner(
            folderFiles = folderFiles,
            metadata = metadata,
            models = { id -> modelRepository.find(id) },
            decoders = WhisperDecoderFactory(modelRepository, applicationInfo.nativeLibraryDir) { null },
            recordingCheck = recordingProbe,
            threads = recoveryTranscribeThreads,
            engineVersion = runCatching { WhisperContext.engineVersion }.getOrDefault("unknown"),
            deviceLanguage = { java.util.Locale.getDefault().language },
            vad = { false },
            clock = { System.nanoTime() / NANOS_PER_MS },
            now = { java.time.OffsetDateTime.now() },
            modelName = { model -> ModelStrings.res(model.displayName)?.let(::getString) ?: model.displayName },
        )
        TranscriptionController(
            scope = appScope,
            runner = runner,
            registry = TranscriptionRegistry(jobRegistry),
            launchToken = launchToken,
            recordingCheck = recordingProbe,
            models = modelRepository,
            folderFiles = folderFiles,
            metadata = metadata,
            preferences = preferences,
            workManager = WorkManager.getInstance(this),
        )
    }

    override fun onCreate() {
        super.onCreate()
        // The recording service lives in its own process and must not repair folders it may be writing.
        if (getProcessName() == packageName) {
            launchToken = LaunchToken.newToken()
            jobRegistry = JobRegistry(launchToken)
            Notifications.createChannels(this)
            cancelStaleWork()
            appScope.launch { recoverInterruptedMeetings() }
            appScope.launch { cleanUpModelInstalls() }
        }
    }

    /**
     * WorkManager persists work, but a job is always the user's decision: work left over from an earlier process
     * is cancelled (the launch-token guard would refuse it anyway). This runs first in `onCreate`, so the cancel is
     * queued before any job of this process can be enqueued; WorkManager executes these operations in order. Doing it
     * later, from the recovery, would cancel a download the user had just started (found by `ModelInstallTest`).
     */
    private fun cancelStaleWork() {
        JobKind.entries.forEach { WorkManager.getInstance(this).cancelUniqueWork(it.uniqueWorkName) }
    }

    /** The model files are repaired and the model states come from disk. */
    private fun cleanUpModelInstalls() {
        runCatching { modelRepository.repair() }.onFailure { Log.w(TAG, "model repair failed", it) }
        modelStateStore.reload()
    }

    /** Repairs meetings left behind by a crash or kill, except the one a running recording is writing. */
    private suspend fun recoverInterruptedMeetings() {
        val tree = AppPreferences(this).storageTreeUri ?: return
        val active = activeRecordingFolder()
        Log.i(TAG, "recovery starts (active recording folder: $active)")
        runCatching { RecoveryRunner(this, tree.toUri()).run(active) }
            .onFailure { Log.w(TAG, "recovery failed", it) }
        // Then the transcription recovery: stale jobs, unfinished commits, orphaned staging files.
        runCatching { transcriptionRecovery(tree.toUri(), active).run() }
            .onFailure { Log.w(TAG, "transcription recovery failed", it) }
        _recoveryRuns.value += 1
    }

    /** Shared by the recovery and (later) the transcription and model jobs: one implementation of "is a recording running". */
    val recordingProbe: RecordingProbe by lazy {
        RecordingProbe(
            isProcessAlive = { RecordingProbe.isRecordingProcessAlive(this) },
            attach = { recordingController.attachIfRunning() },
            state = recordingController.state,
        )
    }

    private fun transcriptionRecovery(tree: android.net.Uri, activeRecording: String?): TranscriptionRecovery {
        val storage = SafMeetingStorage(this, tree)
        val metadata = MeetingMetadataStore.forStorage(storage)
        return TranscriptionRecovery(
            folderNames = { storage.listChildren(storage.rootUri).filter { it.isDirectory && it.name != activeRecording }.map { it.name } },
            folderFiles = { name -> meetingFiles(storage, name) },
            metadata = metadata,
            isLive = { jobRegistry.isLive(it) },
            // Stale work was cancelled in onCreate; a job this process already accepted must not be cancelled now.
            cancelWork = { name ->
                if (JobKind.entries.none { it.uniqueWorkName == name && jobRegistry.current(it) != null }) {
                    WorkManager.getInstance(this).cancelUniqueWork(name)
                }
            },
            modelInfo = { id ->
                val model = modelRepository.find(id)
                val name = model?.let { m -> ModelStrings.res(m.displayName)?.let(::getString) ?: m.displayName } ?: id
                name to (model?.origin ?: app.meetingrecorder.core.models.ModelOrigin.CATALOG)
            },
        )
    }

    private suspend fun activeRecordingFolder(): String? = recordingProbe.activeFolder()

    private companion object {
        const val TAG = "MeetingRecorderApp"
        const val NANOS_PER_MS = 1_000_000L
    }
}
