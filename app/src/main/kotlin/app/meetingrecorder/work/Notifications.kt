package app.meetingrecorder.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import app.meetingrecorder.R

/** The notification channels of transcription and model install (contracts/transcription-service.md). */
object Notifications {
    /** Foreground notification of a running job: silent, with progress and a Cancel action. */
    const val CHANNEL_PROGRESS = "transcription_progress"

    /** Finished, failed, interrupted, stopped for a recording. */
    const val CHANNEL_RESULT = "transcription_result"

    /** Creates both channels; safe to call repeatedly (the names follow the current locale on each call). */
    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                context.getString(R.string.channel_transcription_progress),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setSound(null, null) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULT,
                context.getString(R.string.channel_transcription_result),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }
}
