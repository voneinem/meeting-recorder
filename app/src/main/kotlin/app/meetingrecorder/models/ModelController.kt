package app.meetingrecorder.models

import android.content.ContentResolver
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.meetingrecorder.core.models.InstallEvent
import app.meetingrecorder.core.models.ModelCatalogEntry
import app.meetingrecorder.core.models.ModelInstallState
import app.meetingrecorder.core.models.ModelKind
import app.meetingrecorder.models.download.DownloadControl
import app.meetingrecorder.models.download.Stop
import app.meetingrecorder.recording.RecordingCheck
import app.meetingrecorder.work.JobKind
import app.meetingrecorder.work.JobRegistry
import app.meetingrecorder.work.JobRequest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Answer of the actions that start an install. */
sealed interface ModelStartResult {
    data object Started : ModelStartResult
    data object RecordingInProgress : ModelStartResult
    data object AnotherInstallRunning : ModelStartResult
    data object UnknownModel : ModelStartResult
    data class NotEnoughSpace(val missingBytes: Long) : ModelStartResult
}

/** What the UI shows besides the per-model state: the running import and one-shot notices. */
sealed interface ModelEvent {
    data class Failed(val error: ModelError) : ModelEvent
    data class Installed(val id: String) : ModelEvent
    data class Deleted(val id: String) : ModelEvent
}

/**
 * The facade of model downloads, imports and deletes (contracts/transcription-service.md). It lives in the
 * main process, is created lazily by `MeetingRecorderApp`, and runs at most one install at a time (FR-017).
 * The work itself runs in `ModelInstallWorker` (a foreground job) which asks [run] to execute the request
 * this controller registered; a request that this process did not register is refused by the worker.
 */
class ModelController(
    private val store: ModelStateStore,
    private val repository: ModelRepository,
    private val runner: ModelInstallRunner,
    private val catalog: () -> List<ModelCatalogEntry>,
    private val registry: JobRegistry,
    private val launchToken: String,
    private val recordingCheck: RecordingCheck,
    private val workManager: WorkManager,
    private val resolver: ContentResolver,
) {
    private sealed interface Pending {
        val control: DownloadControl

        class Download(val id: String, override val control: DownloadControl = DownloadControl()) : Pending

        class Import(val uri: Uri, override val control: DownloadControl = DownloadControl()) : Pending
    }

    val states: StateFlow<Map<String, ModelInstallState>> = store.states

    private val _importPercent = MutableStateFlow<Int?>(null)

    /** Percent of a running import, or null. */
    val importPercent: StateFlow<Int?> = _importPercent.asStateFlow()

    private val _events = MutableSharedFlow<ModelEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<ModelEvent> = _events.asSharedFlow()

    private val pending = ConcurrentHashMap<String, Pending>()

    /** The catalog entries offered to the user, speech models only. */
    fun offered(): List<ModelCatalogEntry> = catalog().filter { it.kind == ModelKind.SPEECH }

    suspend fun download(id: String): ModelStartResult = start(id)

    /** Continues a paused download of [id]. */
    suspend fun resume(id: String): ModelStartResult = start(id)

    private suspend fun start(id: String): ModelStartResult {
        if (recordingCheck.isRecording()) return ModelStartResult.RecordingInProgress
        val entry = offered().firstOrNull { it.id == id } ?: return ModelStartResult.UnknownModel
        if (!store.mayStart()) return ModelStartResult.AnotherInstallRunning
        val done = (store.stateOf(id) as? ModelInstallState.Paused)?.bytesDone ?: 0L
        val companion = entry.vadCompanionId?.let { cid -> catalog().firstOrNull { it.id == cid } }?.takeIf { repository.find(it.id) == null }
        val needed = entry.sizeBytes + (companion?.sizeBytes ?: 0L)
        val missing = repository.missingBytes(needed.coerceAtLeast(1), done.coerceAtMost(needed))
        if (missing > 0) return ModelStartResult.NotEnoughSpace(missing)
        return enqueue(Pending.Download(id), id)
    }

    /** Imports the file at [uri]. The header is checked before anything is copied. */
    suspend fun import(uri: Uri): ModelStartResult {
        if (recordingCheck.isRecording()) return ModelStartResult.RecordingInProgress
        if (!store.mayStart()) return ModelStartResult.AnotherInstallRunning
        return enqueue(Pending.Import(uri), "import")
    }

    private fun enqueue(job: Pending, modelId: String): ModelStartResult {
        val requestId = UUID.randomUUID().toString()
        if (!registry.register(JobRequest(JobKind.MODEL_INSTALL, requestId, null, modelId))) return ModelStartResult.AnotherInstallRunning
        pending[requestId] = job
        // An optimistic state keeps a second tap from starting a second job before the worker runs.
        if (job is Pending.Download) {
            val state = store.stateOf(job.id)
            val entry = catalog().first { it.id == job.id }
            store.apply(job.id, if (state is ModelInstallState.Paused) InstallEvent.Resume else InstallEvent.Start(app.meetingrecorder.core.models.TransferKind.DOWNLOAD, entry.sizeBytes))
        }
        val request = OneTimeWorkRequestBuilder<ModelInstallWorker>()
            .setInputData(workDataOf(ModelInstallWorker.KEY_REQUEST_ID to requestId, ModelInstallWorker.KEY_LAUNCH_TOKEN to launchToken))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        // REPLACE, not KEEP: after a system stop (the Android 15 timeout, a quota) WorkManager keeps the stopped work and
        // reschedules it for about 10 s later (the launch-token guard then refuses it). KEEP would drop a start made in
        // that window and leave the registered request without a worker for good (found by the timeout test). The
        // registry already allows one job at a time, so REPLACE can only remove such left-over work.
        workManager.enqueueUniqueWork(JobKind.MODEL_INSTALL.uniqueWorkName, ExistingWorkPolicy.REPLACE, request)
        return ModelStartResult.Started
    }

    /** Cancels the running install of [id], or discards its partial download. */
    fun cancel(id: String) {
        val running = pending.values.filterIsInstance<Pending.Download>().firstOrNull { it.id == id }
        if (running != null) {
            running.control.stop(Stop.CANCEL)
        } else {
            store.apply(id, InstallEvent.DeletePartial)
        }
    }

    /** Cancels a running import. */
    fun cancelImport() {
        pending.values.filterIsInstance<Pending.Import>().forEach { it.control.stop(Stop.CANCEL) }
    }

    /** Acknowledges a failed state (for example after a checksum mismatch). */
    fun dismiss(id: String) {
        store.apply(id, InstallEvent.Dismiss)
    }

    /** Deletes an installed model; refused while the running transcription uses it. */
    fun delete(id: String): DeleteResult {
        val inUse = registry.runningTranscriptionModelId() == id
        if (inUse) return DeleteResult.IN_USE
        val state = store.stateOf(id)
        if (state == ModelInstallState.Installed) store.apply(id, InstallEvent.Delete(inUse = false)) else repository.delete(id)
        store.reload()
        _events.tryEmit(ModelEvent.Deleted(id))
        return DeleteResult.DELETED
    }

    /** A recording starts: a download is interrupted and stays resumable, an import is cancelled (FR-017). */
    fun pauseForRecording() {
        pending.values.forEach { job ->
            job.control.stop(if (job is Pending.Download) Stop.PAUSE else Stop.CANCEL)
        }
    }

    /**
     * Called by the worker after it has claimed [requestId]: executes the registered request on the calling
     * (background) thread. [onProgress] gets percent values for the notification.
     */
    internal fun run(requestId: String, onProgress: (percent: Int) -> Unit): InstallOutcome {
        val job = pending[requestId] ?: return InstallOutcome.Failed(ModelError.Unknown)
        try {
            val outcome = when (job) {
                is Pending.Download -> runner.runDownload(job.id, job.control) { done, total -> onProgress(percentOf(done, total)) }
                is Pending.Import -> {
                    _importPercent.value = 0
                    runner.runImport(UriImportSource(resolver, job.uri), job.control) { done, total ->
                        val percent = percentOf(done, total)
                        _importPercent.value = percent
                        onProgress(percent)
                    }
                }
            }
            when (outcome) {
                is InstallOutcome.Installed -> _events.tryEmit(ModelEvent.Installed(outcome.id))
                is InstallOutcome.Failed -> _events.tryEmit(ModelEvent.Failed(outcome.error))
                is InstallOutcome.Paused -> if (outcome.connectionLost) _events.tryEmit(ModelEvent.Failed(ModelError.Network))
                InstallOutcome.Cancelled -> Unit
            }
            return outcome
        } finally {
            _importPercent.value = null
            pending.remove(requestId)
            registry.release(requestId)
        }
    }

    /** The control of [requestId], so the worker can stop it when WorkManager cancels the job. */
    internal fun controlOf(requestId: String): DownloadControl? = pending[requestId]?.control

    private fun percentOf(done: Long, total: Long): Int = if (total <= 0) 0 else ((done * PERCENT) / total).toInt().coerceIn(0, PERCENT)

    private companion object {
        const val EVENT_BUFFER = 16
        const val PERCENT = 100
    }
}
