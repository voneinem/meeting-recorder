package app.meetingrecorder.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.meetingrecorder.MainActivity
import app.meetingrecorder.R

/**
 * The ongoing notification that must exist while a recording runs (FR-006): chronometer, Stop action,
 * and a tap that returns to the app.
 */
class RecordingNotification(private val context: Context) {

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun build(state: RecordingState): Notification {
        val title = when (state.phase) {
            Phase.PAUSED -> R.string.notification_title_paused
            Phase.SUSPENDED -> R.string.notification_title_suspended
            else -> R.string.notification_title_recording
        }
        val running = state.phase == Phase.RECORDING
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context,
            0,
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val resume = PendingIntent.getService(
            context,
            1,
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_RESUME),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(title))
            .setContentText(state.activeSourceName.takeIf { it.isNotEmpty() }?.let { localizedSourceName(context, it) })
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setShowWhen(running)
            .setUsesChronometer(running)
            .setWhen(System.currentTimeMillis() - state.elapsedMs)
        if (state.phase == Phase.PAUSED || state.phase == Phase.SUSPENDED) {
            builder.addAction(0, context.getString(R.string.action_resume), resume)
        }
        return builder.addAction(0, context.getString(R.string.action_stop), stop).build()
    }

    fun update(state: RecordingState) {
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(state))
    }

    companion object {
        const val CHANNEL_ID = "recording"
        const val NOTIFICATION_ID = 1
    }
}
