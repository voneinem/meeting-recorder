package app.meetingrecorder.transcription

import app.meetingrecorder.core.model.FailureReason
import app.meetingrecorder.core.transcription.CancelSignal

/** How a request treats an existing work file. */
enum class Mode {
    /** A meeting without a usable work file. */
    START,

    /** Continue the work file of an interrupted run (allowed only when `WorkFile.canResume` says so). */
    RESUME,

    /** Throw the old work file away and decode again. */
    RESTART,
}

data class TranscriptionRequest(
    val folderName: String,
    val modelId: String,
    /** `auto`, or a language code. */
    val requestedLanguage: String,
    val mode: Mode,
    /**
     * Set only after the user confirmed that an existing transcript will be replaced (FR-033). The controller
     * refuses to start over a committed transcript without it.
     */
    val replaceExisting: Boolean = false,
)

/** Why a running job is asked to stop. */
enum class StopReason {
    /** The user cancelled: the work file is deleted and the job block removed. */
    USER_CANCEL,

    /** A recording starts: the work file is kept, the job becomes `failed / recording-started / resumable`. */
    RECORDING_STARTED,

    /** The system stopped the job (quota, timeout): the work file is kept, `failed / interrupted / resumable`. */
    SYSTEM,
}

/** A [CancelSignal] that also knows why it was raised. */
interface StopSignal : CancelSignal {
    val reason: StopReason
}

/** The [StopSignal] of a job: the first reason raised wins. */
class RaisableStop : StopSignal {
    @Volatile
    var raised: StopReason? = null
        private set

    override val reason: StopReason get() = raised ?: StopReason.USER_CANCEL

    override fun isCancelled(): Boolean = raised != null

    /** Raises [why] unless a reason was raised before. */
    @Synchronized
    fun raise(why: StopReason) {
        if (raised == null) raised = why
    }
}

enum class JobPhase { PREPARING, RUNNING, COMMITTING }

/** Progress callbacks of a run. */
interface RunListener {
    fun onPhase(phase: JobPhase)

    /** Told after every window: [framesDone] of [durationFrames] are decoded. */
    fun onAnchor(framesDone: Long, durationFrames: Long, wallClockMs: Long)
}

sealed interface RunOutcome {
    data object Completed : RunOutcome

    /** The user cancelled; nothing but a committed transcript remains. */
    data object Cancelled : RunOutcome

    /** Stopped on purpose or by the system; the job block says `failed` with [reason] and the work file is kept. */
    data class Stopped(val reason: FailureReason) : RunOutcome

    data class Failed(val reason: FailureReason) : RunOutcome

    data object MeetingNotFound : RunOutcome

    /** FR-018: a meeting that is still being recorded cannot be transcribed. */
    data object MeetingStillRecording : RunOutcome
}
