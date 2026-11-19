package app.meetingrecorder.models

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.meetingrecorder.MainActivity
import app.meetingrecorder.MeetingRecorderApp
import app.meetingrecorder.R
import app.meetingrecorder.models.download.Stop
import app.meetingrecorder.transcription.StopOutcome
import app.meetingrecorder.transcription.StopReasonMapper
import app.meetingrecorder.work.Notifications
import app.meetingrecorder.work.runStoppable
import kotlinx.coroutines.Dispatchers

/**
 * The foreground job behind [ModelController]: unique work `model-install`, one install at a time. Its first
 * action is to claim the request; WorkManager re-running an old job after a kill or reboot is refused
 * without touching any file (the launch-token guard). It holds a partial wake lock while it works and shows
 * the percentage with a Cancel action in a foreground notification of type `dataSync`.
 */
class ModelInstallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as MeetingRecorderApp
        val requestId = inputData.getString(KEY_REQUEST_ID) ?: return Result.failure()
        val token = inputData.getString(KEY_LAUNCH_TOKEN) ?: return Result.failure()
        // The worker runs in the main process, so the registry exists; a refused claim touches nothing.
        if (!app.jobRegistry.claim(requestId, token)) return Result.failure()

        val controller = app.modelController
        val control = controller.controlOf(requestId) ?: return Result.failure()
        val wakeLock = applicationContext.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        try {
            setForeground(foreground(0))
            // A cancel by the app (the notification's Cancel action) discards the download; any other stop (quota, the
            // Android 15 timeout, a constraint) keeps it resumable. The stop is passed on at once, see [runStoppable].
            val outcome = runStoppable(
                Dispatchers.IO,
                onStop = {
                    val discard = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        StopReasonMapper.map(StopReasonMapper.reasonOf(this)) == StopOutcome.Discard
                    control.stop(if (discard) Stop.CANCEL else Stop.PAUSE)
                },
            ) { controller.run(requestId) { percent -> setForegroundAsyncCompat(percent) } }
            showResult(outcome)
            return Result.success()
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(0)

    private var lastPercent = -1

    private fun setForegroundAsyncCompat(percent: Int) {
        if (percent == lastPercent) return
        lastPercent = percent
        applicationContext.getSystemService(android.app.NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(percent))
    }

    private fun foreground(percent: Int) =
        ForegroundInfo(NOTIFICATION_ID, notification(percent), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun notification(percent: Int): android.app.Notification {
        val open = android.app.PendingIntent.getActivity(
            applicationContext,
            0,
            android.content.Intent(applicationContext, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        return NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(applicationContext.getColor(R.color.notification_accent))
            .setContentTitle(applicationContext.getString(R.string.model_notification_title))
            .setContentText(applicationContext.getString(R.string.model_notification_text, percent))
            .setProgress(PERCENT, percent, false)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, applicationContext.getString(R.string.action_cancel), cancel)
            .build()
    }

    private fun showResult(outcome: InstallOutcome) {
        val text = when (outcome) {
            is InstallOutcome.Installed -> R.string.model_notification_done
            is InstallOutcome.Failed -> R.string.model_notification_failed
            is InstallOutcome.Paused -> if (outcome.connectionLost) R.string.model_notification_failed else return
            InstallOutcome.Cancelled -> return
        }
        val notification = NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(applicationContext.getColor(R.color.notification_accent))
            .setContentTitle(applicationContext.getString(R.string.models_title))
            .setContentText(applicationContext.getString(text))
            .setAutoCancel(true)
            .build()
        applicationContext.getSystemService(android.app.NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, notification)
    }

    companion object {
        const val KEY_REQUEST_ID = "requestId"
        const val KEY_LAUNCH_TOKEN = "launchToken"
        private const val NOTIFICATION_ID = 2001
        private const val RESULT_NOTIFICATION_ID = 2002
        private const val WAKE_LOCK_TAG = "MeetingRecorder:model-install"
        private const val WAKE_LOCK_TIMEOUT_MS = 12L * 60 * 60 * 1000
        private const val PERCENT = 100
    }
}
