package app.meetingrecorder.transcription

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.WorkManager
import app.meetingrecorder.MainActivity
import app.meetingrecorder.R
import app.meetingrecorder.core.model.FailureReason
import app.meetingrecorder.work.Notifications
import java.util.UUID

/**
 * The notifications of a transcription (contracts/transcription-service.md, "Notifications"): the foreground
 * notification on `transcription_progress` (percentage bar, remaining time, **Cancel**, a tap opens the
 * meeting) and the results on `transcription_result`. A cancelled run gets no notification: the user did it.
 */
class TranscriptionNotification(private val context: Context) {

    fun progress(workId: UUID, state: TranscriptionState.Active, title: String): Notification {
        val percent = (state.progress * PERCENT).toInt().coerceIn(0, PERCENT)
        val text = state.remainingMs?.let { context.getString(R.string.transcription_notification_percent_remaining, percent, remaining(it)) }
            ?: context.getString(R.string.transcription_notification_percent, percent)
        return NotificationCompat.Builder(context, Notifications.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(context.getColor(R.color.notification_accent))
            .setContentTitle(context.getString(R.string.transcription_notification_title, title))
            .setContentText(text)
            .setProgress(PERCENT, percent, false)
            .setContentIntent(open(state.folderName))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, context.getString(R.string.action_cancel), WorkManager.getInstance(context).createCancelPendingIntent(workId))
            .build()
    }

    /** Posts the result of a run; nothing for a cancel. */
    fun result(folderName: String, title: String, outcome: RunOutcome) {
        val (heading, text) = when (outcome) {
            RunOutcome.Completed -> R.string.transcription_done_title to context.getString(R.string.transcription_done_text, title)
            is RunOutcome.Failed -> R.string.transcription_failed_title to context.getString(reasonText(outcome.reason))
            is RunOutcome.Stopped -> R.string.transcription_interrupted_title to context.getString(reasonText(outcome.reason))
            RunOutcome.Cancelled, RunOutcome.MeetingNotFound, RunOutcome.MeetingStillRecording -> return
        }
        val builder = NotificationCompat.Builder(context, Notifications.CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(context.getColor(R.color.notification_accent))
            .setContentTitle(context.getString(heading))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open(folderName))
            .setAutoCancel(true)
        val resumable = (outcome as? RunOutcome.Stopped) != null
        if (resumable) builder.addAction(0, context.getString(R.string.action_resume), open(folderName))
        context.getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, builder.build())
    }

    private fun open(folderName: String): PendingIntent = PendingIntent.getActivity(
        context,
        folderName.hashCode(),
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_FOLDER, folderName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun remaining(ms: Long): String {
        val seconds = (ms / MS_PER_SECOND).toInt()
        return if (seconds >= SECONDS_PER_MINUTE) {
            context.getString(R.string.transcription_remaining_minutes, seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
        } else {
            context.getString(R.string.transcription_remaining_seconds, seconds)
        }
    }

    companion object {
        /** Intent extra that makes `MainActivity` open the meeting with this folder name. */
        const val EXTRA_FOLDER = "folder"
        const val PROGRESS_NOTIFICATION_ID = 2101
        private const val RESULT_NOTIFICATION_ID = 2102
        private const val PERCENT = 100
        private const val MS_PER_SECOND = 1000L
        private const val SECONDS_PER_MINUTE = 60

        fun reasonText(reason: FailureReason): Int = when (reason) {
            FailureReason.INTERRUPTED -> R.string.transcription_reason_interrupted
            FailureReason.RECORDING_STARTED -> R.string.transcription_reason_recording_started
            FailureReason.MODEL_MISSING -> R.string.transcription_reason_model_missing
            FailureReason.MODEL_INVALID -> R.string.transcription_reason_model_invalid
            FailureReason.OUT_OF_MEMORY -> R.string.transcription_reason_out_of_memory
            FailureReason.STORAGE_FULL -> R.string.transcription_reason_storage_full
            FailureReason.AUDIO_UNREADABLE -> R.string.transcription_reason_audio_unreadable
            FailureReason.ERROR -> R.string.transcription_reason_error
        }
    }
}
