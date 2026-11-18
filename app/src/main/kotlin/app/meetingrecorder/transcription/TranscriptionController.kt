package app.meetingrecorder.transcription

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.meetingrecorder.AppPreferences
import app.meetingrecorder.core.model.MeetingStatus
import app.meetingrecorder.core.transcription.ProgressEstimator
import app.meetingrecorder.models.ModelRepository
import app.meetingrecorder.recording.RecordingCheck
import app.meetingrecorder.storage.FolderFiles
import app.meetingrecorder.storage.MeetingMetadataStore
import app.meetingrecorder.work.JobKind
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface StartResult {
    data object Started : StartResult
    data object RecordingInProgress : StartResult
    data object AnotherJobRunning : StartResult
    data object ModelNotInstalled : StartResult
    data object ResumeNotPossible : StartResult
    data object MeetingNotFound : StartResult
    data object MeetingStillRecording : StartResult

    /** A transcript exists and the user has not confirmed that it will be replaced (FR-033). */
    data object ReplaceNeedsConfirmation : StartResult
}

enum class ActivePhase { PREPARING, RUNNING, CANCELLING, COMMITTING }

sealed interface TranscriptionState {
    data object Idle : TranscriptionState

    data class Active(
        val folderName: String,
        val phase: ActivePhase,
        /** 0..1, never decreases within a job. */
        val progress: Float,
        /** Null until the estimate is usable. */
        val remainingMs: Long?,
        val modelId: String,
    ) : TranscriptionState
}

/** One-shot notices for the UI. */
sealed interface TranscriptionEvent {
    data class Finished(val folderName: String) : TranscriptionEvent
    data class Failed(val folderName: String, val outcome: RunOutcome) : TranscriptionEvent
    data class Cancelled(val folderName: String) : TranscriptionEvent
}

/** What the delete flow needs to know about a running job (contracts/transcription-service.md, "Ports"). */
interface JobCanceller {
    fun isRunningFor(folderName: String): Boolean

    /** Cancels the running job and waits for it to stop; true when it has stopped within [timeoutMs]. */
    suspend fun cancelAndAwait(timeoutMs: Long): Boolean
}

/**
 * The facade of transcription (contracts/transcription-service.md): at most one job at a time, started by the
 * user, run by `TranscriptionWorker` in the main process. [start] registers the request and enqueues the unique
 * work; the worker claims it and calls [execute]. Progress is recomputed once per second from one estimator,
 * so the app and the notification agree.
 */
class TranscriptionController(
    private val scope: CoroutineScope,
    private val runner: TranscriptionJobRunner,
    private val registry: TranscriptionRegistry,
    private val launchToken: String,
    private val recordingCheck: RecordingCheck,
    private val models: ModelRepository,
    private val folderFiles: (String) -> FolderFiles?,
    private val metadata: MeetingMetadataStore,
    private val preferences: AppPreferences,
    private val workManager: WorkManager,
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MS },
) : JobCanceller {

    private class Pending(val request: TranscriptionRequest, val title: String, val estimator: ProgressEstimator) {
        val stop = RaisableStop()
    }

    private val _state = MutableStateFlow<TranscriptionState>(TranscriptionState.Idle)
    val state: StateFlow<TranscriptionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TranscriptionEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<TranscriptionEvent> = _events.asSharedFlow()

    private val pending = ConcurrentHashMap<String, Pending>()

    @Volatile
    private var current: Pending? = null

    override fun isRunningFor(folderName: String): Boolean = registry.isRunningFor(folderName)

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    suspend fun start(request: TranscriptionRequest): StartResult {
        if (recordingCheck.isRecording()) return StartResult.RecordingInProgress
        if (registry.current() != null) return StartResult.AnotherJobRunning
        val model = withContext(Dispatchers.IO) { models.find(request.modelId) } ?: return StartResult.ModelNotInstalled
        val prepared = withContext(Dispatchers.IO) { prepare(request, model.sha256) }
        if (prepared is Prepared.Refused) return prepared.result
        prepared as Prepared.Ready

        val requestId = UUID.randomUUID().toString()
        if (!registry.register(requestId, request.folderName, request.modelId)) return StartResult.AnotherJobRunning
        val job = Pending(request, prepared.title, prepared.estimator)
        pending[requestId] = job
        current = job
        _state.value = TranscriptionState.Active(request.folderName, ActivePhase.PREPARING, prepared.startProgress, null, request.modelId)
        preferences.transcriptionLastModelId = request.modelId

        val work = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(workDataOf(TranscriptionWorker.KEY_REQUEST_ID to requestId, TranscriptionWorker.KEY_LAUNCH_TOKEN to launchToken))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        // REPLACE, not KEEP: after a system stop (the Android 15 timeout, a quota) WorkManager keeps the stopped work and
        // reschedules it for about 10 s later (the launch-token guard then refuses it). KEEP would drop a start made in
        // that window and leave the registered request without a worker for good (found by the timeout test). The
        // registry already allows one job at a time, so REPLACE can only remove such left-over work.
        workManager.enqueueUniqueWork(JobKind.TRANSCRIPTION.uniqueWorkName, ExistingWorkPolicy.REPLACE, work)
        return StartResult.Started
    }

    private sealed interface Prepared {
        class Refused(val result: StartResult) : Prepared
        class Ready(val title: String, val estimator: ProgressEstimator, val startProgress: Float) : Prepared
    }

    private fun prepare(request: TranscriptionRequest, modelSha256: String): Prepared {
        val files = folderFiles(request.folderName) ?: return Prepared.Refused(StartResult.MeetingNotFound)
        val meeting = metadata.read(request.folderName) ?: return Prepared.Refused(StartResult.MeetingNotFound)
        if (meeting.status == MeetingStatus.RECORDING) return Prepared.Refused(StartResult.MeetingStillRecording)
        // A committed transcript is replaced only on explicit confirmation; resuming a failed re-run already did that.
        val hasTranscript = meeting.transcription?.transcript != null
        if (hasTranscript && !request.replaceExisting && request.mode != Mode.RESUME) return Prepared.Refused(StartResult.ReplaceNeedsConfirmation)
        val duration = meeting.audio.durationFrames.coerceAtLeast(1)
        val sampleRate = meeting.audio.sampleRate
        var startFrame = 0L
        var elapsedBefore = 0L
        if (request.mode == Mode.RESUME) {
            when (val plan = ResumePlanner.plan(files, request.requestedLanguage, modelSha256, sampleRate, duration)) {
                is ResumePlan.NotPossible -> return Prepared.Refused(StartResult.ResumeNotPossible)
                is ResumePlan.Ready -> {
                    startFrame = plan.startFrame
                    elapsedBefore = plan.elapsedBeforeMs
                }
            }
        }
        return Prepared.Ready(meeting.title, ProgressEstimator(duration, sampleRate, elapsedBefore, startFrame), startFrame.toFloat() / duration)
    }

    /** "Dismiss" on a failed job: removes the work file and the job block; a committed transcript stays. */
    suspend fun dismiss(folderName: String): Boolean = withContext(Dispatchers.IO) {
        if (registry.isRunningFor(folderName)) return@withContext false
        val files = folderFiles(folderName) ?: return@withContext false
        JobDismissal.dismiss(files, metadata, folderName)
    }

    /** The user cancels: the work file is discarded and the meeting is as before. */
    fun cancel() = stop(StopReason.USER_CANCEL)

    /** The system stopped the job (quota, timeout, constraint): the work file is kept, `failed / interrupted / resumable`. */
    fun stopBySystem() = stop(StopReason.SYSTEM)

    /** A recording starts: the job stops, keeps its work file and can be resumed (FR-017). Not awaited. */
    fun stopForRecording() = stop(StopReason.RECORDING_STARTED)

    private fun stop(reason: StopReason) {
        val job = current ?: return
        job.stop.raise(reason)
        val active = _state.value as? TranscriptionState.Active
        if (active != null) _state.value = active.copy(phase = ActivePhase.CANCELLING)
        runner.abort()
    }

    override suspend fun cancelAndAwait(timeoutMs: Long): Boolean {
        if (current == null) return true
        cancel()
        return withTimeoutOrNull(timeoutMs) { state.first { it is TranscriptionState.Idle } } != null
    }

    /** True while the registered request is still waiting for its worker. */
    internal fun hasRequest(requestId: String): Boolean = pending.containsKey(requestId)

    internal fun titleOf(requestId: String): String = pending[requestId]?.title.orEmpty()

    internal fun folderOf(requestId: String): String? = pending[requestId]?.request?.folderName

    /** The worker calls this after it has claimed [requestId], from its dedicated decode thread. */
    internal suspend fun execute(requestId: String): RunOutcome {
        val job = pending[requestId] ?: return RunOutcome.Failed(app.meetingrecorder.core.model.FailureReason.ERROR)
        val ticker = startTicker(job)
        try {
            if (job.stop.isCancelled()) return finish(job, RunOutcome.Cancelled)
            job.estimator.begin(clock())
            val listener = object : RunListener {
                override fun onPhase(phase: JobPhase) {
                    val active = _state.value as? TranscriptionState.Active ?: return
                    if (active.phase == ActivePhase.CANCELLING) return
                    _state.value = active.copy(phase = if (phase == JobPhase.PREPARING) ActivePhase.PREPARING else if (phase == JobPhase.RUNNING) ActivePhase.RUNNING else ActivePhase.COMMITTING)
                }

                override fun onAnchor(framesDone: Long, durationFrames: Long, wallClockMs: Long) = job.estimator.anchor(framesDone, wallClockMs)
            }
            val outcome = runner.run(job.request, job.stop, listener)
            if (outcome == RunOutcome.Completed) job.estimator.finish(clock())
            return finish(job, outcome)
        } finally {
            ticker.cancel()
            pending.remove(requestId)
            registry.release(requestId)
            current = null
            _state.value = TranscriptionState.Idle
        }
    }

    private fun finish(job: Pending, outcome: RunOutcome): RunOutcome {
        val folder = job.request.folderName
        when (outcome) {
            RunOutcome.Completed -> _events.tryEmit(TranscriptionEvent.Finished(folder))
            RunOutcome.Cancelled -> _events.tryEmit(TranscriptionEvent.Cancelled(folder))
            else -> _events.tryEmit(TranscriptionEvent.Failed(folder, outcome))
        }
        return outcome
    }

    /** Recomputes progress and remaining time once per second from the one estimator. */
    private fun startTicker(job: Pending): Job = scope.launch {
        while (isActive) {
            val active = _state.value as? TranscriptionState.Active
            if (active != null && active.phase != ActivePhase.PREPARING) {
                val snapshot = job.estimator.snapshot(clock())
                _state.value = active.copy(progress = maxOf(active.progress, snapshot.progress), remainingMs = snapshot.remainingMs)
            }
            delay(TICK_MS)
        }
    }

    private companion object {
        const val EVENT_BUFFER = 16
        const val TICK_MS = 1_000L
        const val NANOS_PER_MS = 1_000_000L
    }
}
