package app.meetingrecorder.recording

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** "Is a recording running?" as the transcription and model jobs need to ask it (FR-017). */
fun interface RecordingCheck {
    suspend fun isRecording(): Boolean
}

/**
 * Answers whether a recording is running, with the convention of the Phase 1 recovery: when the
 * `:recording` process is not alive there is no recording (answered at once); when it is alive, the UI
 * process attaches to the service and waits up to [timeoutMs] for a state other than `IDLE`, because a UI
 * process recreated during a recording starts with `IDLE` until it has bound. A timeout means "not
 * recording". The inputs are injected so that the JVM tests cover every case.
 */
class RecordingProbe(
    private val isProcessAlive: () -> Boolean,
    private val attach: () -> Unit,
    private val state: StateFlow<RecordingState>,
    private val timeoutMs: Long = ATTACH_TIMEOUT_MS,
) : RecordingCheck {

    /** The state of the running recording, or null when nothing is recording. */
    suspend fun activeState(): RecordingState? {
        if (!isProcessAlive()) return null
        attach()
        return withTimeoutOrNull(timeoutMs) { state.first { it.phase != Phase.IDLE } }
    }

    /** Name of the meeting folder a running recording writes, or null. */
    suspend fun activeFolder(): String? = activeState()?.meetingFolder

    override suspend fun isRecording(): Boolean = activeState() != null

    companion object {
        const val ATTACH_TIMEOUT_MS = 2_000L

        /** True while the `:recording` process of this app exists. */
        fun isRecordingProcessAlive(context: Context): Boolean =
            context.getSystemService(ActivityManager::class.java).runningAppProcesses
                ?.any { it.processName == "${context.packageName}:recording" } == true
    }
}
