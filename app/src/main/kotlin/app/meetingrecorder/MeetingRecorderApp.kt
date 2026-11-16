package app.meetingrecorder

import android.app.ActivityManager
import android.app.Application
import android.util.Log
import androidx.core.net.toUri
import app.meetingrecorder.recording.Phase
import app.meetingrecorder.recording.RecordingController
import app.meetingrecorder.recording.RecoveryRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MeetingRecorderApp : Application() {
    /** The UI-side client of the recording service. Created lazily, so the service process never builds it. */
    val recordingController: RecordingController by lazy { RecordingController(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _recoveryRuns = MutableStateFlow(0)

    /** Counts finished recovery runs; screens that list meetings reload when it changes. */
    val recoveryRuns: StateFlow<Int> = _recoveryRuns.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        // The recording service lives in its own process and must not repair folders it may be writing.
        if (getProcessName() == packageName) appScope.launch { recoverInterruptedMeetings() }
    }

    /** Repairs meetings left behind by a crash or kill, except the one a running recording is writing. */
    private suspend fun recoverInterruptedMeetings() {
        val tree = AppPreferences(this).storageTreeUri ?: return
        val active = activeRecordingFolder()
        Log.i(TAG, "recovery starts (active recording folder: $active)")
        runCatching { RecoveryRunner(this, tree.toUri()).run(active) }
            .onFailure { Log.w(TAG, "recovery failed", it) }
        _recoveryRuns.value += 1
    }

    private suspend fun activeRecordingFolder(): String? {
        if (!isRecordingProcessAlive()) return null
        recordingController.attachIfRunning()
        return withTimeoutOrNull(ATTACH_TIMEOUT_MS) {
            recordingController.state.first { it.phase != Phase.IDLE }
        }?.meetingFolder
    }

    private fun isRecordingProcessAlive(): Boolean =
        getSystemService(ActivityManager::class.java).runningAppProcesses
            ?.any { it.processName == "$packageName:recording" } == true

    private companion object {
        const val TAG = "MeetingRecorderApp"
        const val ATTACH_TIMEOUT_MS = 2_000L
    }
}
