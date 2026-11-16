package app.meetingrecorder.ui.record

import app.meetingrecorder.R
import app.meetingrecorder.recording.RecordingEvent
import app.meetingrecorder.recording.RecordingSession

private const val SECONDS_PER_MINUTE = 60L

/**
 * The message the user sees for an event of the recording service: error, interruption, storage and
 * size-limit notices, microphone changes (FR-012, FR-024, FR-034). Every event has one, so none can
 * go unseen. [formatBytes] and [sourceName] turn raw values into text for the user's language.
 */
internal fun RecordingEvent.toNotice(formatBytes: (Long) -> String, sourceName: (String) -> String): Notice = when (this) {
    is RecordingEvent.Error -> when (code) {
        "no_microphone_permission" -> Notice.Text(R.string.record_error_no_microphone)
        "start_failed" -> Notice.Text(R.string.record_error_start_failed, listOf(detail.orEmpty()))
        "nothing_recorded" -> Notice.Text(R.string.record_error_nothing_recorded)
        "storage_too_small" -> Notice.Text(R.string.record_error_storage_too_small)
        "photo_file_failed" -> Notice.Text(R.string.photo_failed)
        else -> Notice.Text(R.string.record_error_generic, listOf(code))
    }
    is RecordingEvent.AudioDropped -> Notice.Text(R.string.record_notice_dropped)
    is RecordingEvent.Suspended -> when (reason) {
        RecordingSession.REASON_MICROPHONE_TAKEN -> Notice.Text(R.string.record_notice_suspended_taken)
        RecordingSession.REASON_READ_ERROR -> Notice.Text(R.string.record_notice_suspended_read_error)
        RecordingSession.REASON_FALLBACK_FAILED -> Notice.Text(R.string.record_notice_suspended_fallback)
        else -> Notice.Text(R.string.record_notice_suspended, listOf(reason))
    }
    is RecordingEvent.StorageLow -> Notice.Text(R.string.storage_low, listOf(formatBytes(bytesLeft)))
    RecordingEvent.StorageFull -> Notice.Text(R.string.storage_full)
    is RecordingEvent.LimitApproaching -> {
        // Round up, so "1 minute left" is never shown for 90 seconds and "0 minutes" never at all.
        val minutes = ((secondsLeft + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE).toInt()
        Notice.Plural(R.plurals.limit_approaching, minutes)
    }
    RecordingEvent.LimitReached -> Notice.Text(R.string.limit_reached)
    is RecordingEvent.SourceSwitched ->
        Notice.Text(R.string.record_notice_source_switched, listOf(sourceName(fromName), sourceName(toName)))
    is RecordingEvent.ExternalAvailableAgain -> Notice.Text(R.string.record_notice_source_available, listOf(name))
}
