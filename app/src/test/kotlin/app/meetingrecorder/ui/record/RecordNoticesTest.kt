package app.meetingrecorder.ui.record

import app.meetingrecorder.R
import app.meetingrecorder.recording.RecordingEvent
import app.meetingrecorder.recording.RecordingSession
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the user is told for each event of the recording service: interruptions, storage and size limits (T065). */
class RecordNoticesTest {

    private fun notice(event: RecordingEvent): Notice =
        event.toNotice(formatBytes = { "$it B" }, sourceName = { "<$it>" })

    // --- interruptions ---

    @Test
    fun `a microphone taken by a call or another app says how to continue`() {
        assertEquals(
            Notice.Text(R.string.record_notice_suspended_taken),
            notice(RecordingEvent.Suspended(RecordingSession.REASON_MICROPHONE_TAKEN)),
        )
    }

    @Test
    fun `a read error and a failed fallback each have their own message`() {
        assertEquals(
            Notice.Text(R.string.record_notice_suspended_read_error),
            notice(RecordingEvent.Suspended(RecordingSession.REASON_READ_ERROR)),
        )
        assertEquals(
            Notice.Text(R.string.record_notice_suspended_fallback),
            notice(RecordingEvent.Suspended(RecordingSession.REASON_FALLBACK_FAILED)),
        )
    }

    @Test
    fun `an unknown suspension reason is still shown, with the reason`() {
        assertEquals(
            Notice.Text(R.string.record_notice_suspended, listOf("something new")),
            notice(RecordingEvent.Suspended("something new")),
        )
    }

    // --- storage ---

    @Test
    fun `low storage names the space that is left`() {
        assertEquals(
            Notice.Text(R.string.storage_low, listOf("52428800 B")),
            notice(RecordingEvent.StorageLow(50L * 1024 * 1024)),
        )
    }

    @Test
    fun `a full storage says the recording was stopped and saved`() {
        assertEquals(Notice.Text(R.string.storage_full), notice(RecordingEvent.StorageFull))
    }

    @Test
    fun `too little storage to start explains why nothing started`() {
        assertEquals(
            Notice.Text(R.string.record_error_storage_too_small),
            notice(RecordingEvent.Error("storage_too_small")),
        )
    }

    // --- size limit ---

    @Test
    fun `the limit warning counts whole minutes and rounds up`() {
        fun minutes(secondsLeft: Long) = (notice(RecordingEvent.LimitApproaching(secondsLeft)) as Notice.Plural).quantity
        assertEquals(15, minutes(900))
        assertEquals(15, minutes(841)) // 14 min 1 s is shown as 15
        assertEquals(1, minutes(60))
        assertEquals(1, minutes(1)) // never "0 minutes"
        assertEquals(2, minutes(61))
    }

    @Test
    fun `the limit warning uses the plural resource`() {
        val shown = notice(RecordingEvent.LimitApproaching(600)) as Notice.Plural
        assertEquals(R.plurals.limit_approaching, shown.message)
    }

    @Test
    fun `reaching the limit says the recording was stopped and saved`() {
        assertEquals(Notice.Text(R.string.limit_reached), notice(RecordingEvent.LimitReached))
    }

    // --- everything else the service can report ---

    @Test
    fun `errors map to their messages and an unknown error shows its code`() {
        assertEquals(Notice.Text(R.string.record_error_no_microphone), notice(RecordingEvent.Error("no_microphone_permission")))
        assertEquals(Notice.Text(R.string.record_error_start_failed, listOf("boom")), notice(RecordingEvent.Error("start_failed", "boom")))
        assertEquals(Notice.Text(R.string.record_error_start_failed, listOf("")), notice(RecordingEvent.Error("start_failed")))
        assertEquals(Notice.Text(R.string.record_error_nothing_recorded), notice(RecordingEvent.Error("nothing_recorded")))
        assertEquals(Notice.Text(R.string.photo_failed), notice(RecordingEvent.Error("photo_file_failed")))
        assertEquals(Notice.Text(R.string.record_error_generic, listOf("weird_code")), notice(RecordingEvent.Error("weird_code")))
    }

    @Test
    fun `dropped audio and microphone changes are announced`() {
        assertEquals(Notice.Text(R.string.record_notice_dropped), notice(RecordingEvent.AudioDropped(atFrame = 480_000, frames = 4_800)))
        assertEquals(
            Notice.Text(R.string.record_notice_source_switched, listOf("<USB mic>", "<Built-in microphone>")),
            notice(RecordingEvent.SourceSwitched("USB mic", "Built-in microphone", atFrame = 1)),
        )
        assertEquals(
            Notice.Text(R.string.record_notice_source_available, listOf("USB mic")),
            notice(RecordingEvent.ExternalAvailableAgain("USB mic")),
        )
    }
}
