package app.meetingrecorder.transcription

import android.os.Build
import androidx.work.WorkInfo
import app.meetingrecorder.core.model.FailureReason

/** What a stopped job leaves behind: nothing (the user cancelled) or a failed job block. */
sealed interface StopOutcome {
    data object Discard : StopOutcome

    data class Fail(val reason: FailureReason, val resumable: Boolean) : StopOutcome
}

/**
 * Maps the reason WorkManager gives for stopping a worker (`ListenableWorker.getStopReason()`, API 31 and
 * newer) to what the job block records (research R-9). Everything the system does to a job (the 6 h
 * foreground-service timeout of Android 15, a doze or quota stop, a constraint, being preempted, a process kill)
 * is an interruption that keeps the work file; only a cancel by the app discards. The constants are those of
 * `WorkInfo.STOP_REASON_*` (compile-time constants, so the mapping runs on the JVM).
 */
object StopReasonMapper {
    const val NOT_STOPPED = WorkInfo.STOP_REASON_NOT_STOPPED
    const val UNKNOWN = WorkInfo.STOP_REASON_UNKNOWN
    const val CANCELLED_BY_APP = WorkInfo.STOP_REASON_CANCELLED_BY_APP
    const val PREEMPT = WorkInfo.STOP_REASON_PREEMPT
    const val TIMEOUT = WorkInfo.STOP_REASON_TIMEOUT
    const val DEVICE_STATE = WorkInfo.STOP_REASON_DEVICE_STATE
    const val CONSTRAINT_CHARGING = WorkInfo.STOP_REASON_CONSTRAINT_CHARGING
    const val QUOTA = WorkInfo.STOP_REASON_QUOTA
    const val BACKGROUND_RESTRICTION = WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION

    /** The platform stops a `dataSync` or `mediaProcessing` foreground service after 6 h on Android 15. */
    const val FOREGROUND_SERVICE_TIMEOUT = WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT

    private val interrupted = StopOutcome.Fail(FailureReason.INTERRUPTED, resumable = true)

    fun map(workManagerStopReason: Int): StopOutcome =
        if (workManagerStopReason == CANCELLED_BY_APP) StopOutcome.Discard else interrupted

    fun forRecordingStart(): StopOutcome = StopOutcome.Fail(FailureReason.RECORDING_STARTED, resumable = true)

    fun of(reason: StopReason): StopOutcome = when (reason) {
        StopReason.USER_CANCEL -> StopOutcome.Discard
        StopReason.RECORDING_STARTED -> forRecordingStart()
        StopReason.SYSTEM -> interrupted
    }

    /** The stop reason of a worker on API 31 and newer; [NOT_STOPPED] before that. */
    fun reasonOf(worker: androidx.work.ListenableWorker): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) worker.stopReason else NOT_STOPPED
}
