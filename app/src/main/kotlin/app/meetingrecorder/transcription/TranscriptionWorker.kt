package app.meetingrecorder.transcription

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.meetingrecorder.MeetingRecorderApp
import app.meetingrecorder.work.runStoppable
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * A thin shell around [TranscriptionJobRunner] (via [TranscriptionController.execute]): unique work
 * `transcription`, started expedited from the UI so that the promotion to a foreground service is allowed. Its
 * first action is to claim the request; WorkManager re-running an old job after a kill or reboot is refused
 * without touching anything. Holds a partial wake lock (12 h timeout) released in `finally`.
 */
class TranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val notification = TranscriptionNotification(context)
    private var lastNotifiedAt = 0L

    override suspend fun doWork(): Result {
        val app = applicationContext as MeetingRecorderApp
        val requestId = inputData.getString(KEY_REQUEST_ID) ?: return Result.failure()
        val token = inputData.getString(KEY_LAUNCH_TOKEN) ?: return Result.failure()
        val controller = app.transcriptionController
        // Only a request this process registered may run; anything else is WorkManager restarting an old job.
        if (!app.jobRegistry.claim(requestId, token) || !controller.hasRequest(requestId)) return Result.failure()

        val wakeLock = applicationContext.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        val title = controller.titleOf(requestId)
        val folder = controller.folderOf(requestId).orEmpty()
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "transcription-decode") }
        try {
            setForeground(foreground(initialState(controller), title))
            val outcome = coroutineScope {
                val updates = launch {
                    controller.state.collect { state -> if (state is TranscriptionState.Active) refreshNotification(state, title) }
                }
                try {
                    // WorkManager stops a worker by cancelling its coroutine. A cancel by the app (the notification's
                    // Cancel action, `cancel()`) discards the job; every other reason (quota, timeout, the 6 h limit of
                    // Android 15, a constraint) is an interruption that keeps the work file. Before Android 12 no reason
                    // is reported and a stop is taken as the user's cancel. The stop is passed on at once, see [runStoppable].
                    runStoppable(
                        executor.asCoroutineDispatcher(),
                        onStop = {
                            val discard = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                                StopReasonMapper.map(StopReasonMapper.reasonOf(this@TranscriptionWorker)) == StopOutcome.Discard
                            if (discard) controller.cancel() else controller.stopBySystem()
                        },
                    ) { controller.execute(requestId) }
                } finally {
                    updates.cancel()
                }
            }
            notification.result(folder, title, outcome)
            return Result.success()
        } finally {
            executor.shutdown()
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val controller = (applicationContext as MeetingRecorderApp).transcriptionController
        return foreground(initialState(controller), "")
    }

    /** Called from the controller's state; the system throttles faster updates, so at most every 2 s. */
    internal fun refreshNotification(state: TranscriptionState.Active, title: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotifiedAt < NOTIFICATION_INTERVAL_MS) return
        lastNotifiedAt = now
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(TranscriptionNotification.PROGRESS_NOTIFICATION_ID, notification.progress(id, state, title))
    }

    private fun initialState(controller: TranscriptionController): TranscriptionState.Active =
        (controller.state.value as? TranscriptionState.Active)
            ?: TranscriptionState.Active("", ActivePhase.PREPARING, 0f, null, "")

    private fun foreground(state: TranscriptionState.Active, title: String) = ForegroundInfo(
        TranscriptionNotification.PROGRESS_NOTIFICATION_ID,
        notification.progress(id, state, title),
        foregroundServiceType(),
    )

    companion object {
        const val KEY_REQUEST_ID = "requestId"
        const val KEY_LAUNCH_TOKEN = "launchToken"
        private const val WAKE_LOCK_TAG = "MeetingRecorder:transcription"
        private const val WAKE_LOCK_TIMEOUT_MS = 12L * 60 * 60 * 1000
        private const val NOTIFICATION_INTERVAL_MS = 2_000L

        /** `mediaProcessing` on Android 15 and newer, `dataSync` before (research R-9); one place chooses. */
        @android.annotation.SuppressLint("InlinedApi") // the constant is only used from API 35 on
        fun foregroundServiceType(sdk: Int = Build.VERSION.SDK_INT): Int =
            if (sdk >= Build.VERSION_CODES.VANILLA_ICE_CREAM) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    }
}
