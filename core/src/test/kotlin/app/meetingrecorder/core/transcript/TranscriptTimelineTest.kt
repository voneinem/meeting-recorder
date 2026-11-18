package app.meetingrecorder.core.transcript

import app.meetingrecorder.core.TestTranscripts
import app.meetingrecorder.core.model.Photo
import app.meetingrecorder.core.transcript.TranscriptItem.PhotoItem
import app.meetingrecorder.core.transcript.TranscriptItem.SegmentItem
import kotlin.test.Test
import kotlin.test.assertEquals

class TranscriptTimelineTest {

    private fun seg(id: Int, start: Long, end: Long) = TranscriptSegment(id, start, end, "s$id")
    private fun photo(n: Int, at: Long) = Photo("photos/photo-%03d.jpg".format(n), at, at / 48)

    /** `s0 s1 p1 ...` for readable assertions. */
    private fun order(items: List<TranscriptItem>): String = items.joinToString(" ") {
        when (it) {
            is SegmentItem -> "s${it.segment.id}"
            is PhotoItem -> "p${it.number}"
        }
    }

    private val segments = listOf(seg(0, 1000, 2000), seg(1, 2000, 3000), seg(2, 5000, 6000))

    @Test
    fun `a photo goes immediately before the first segment that starts after it`() {
        // 1500 is inside segment 0 (1000..2000): the first segment starting after it is segment 1.
        assertEquals("s0 p1 s1 s2", order(TranscriptTimeline.interleave(segments, listOf(photo(1, 1500)))))
        // 2500 is inside segment 1 (2000..3000): the first segment starting after it is segment 2.
        assertEquals("s0 s1 p1 s2", order(TranscriptTimeline.interleave(segments, listOf(photo(1, 2500)))))
    }

    @Test
    fun `a photo taken during a segment comes right after that segment`() {
        assertEquals("s0 s1 p1 s2", order(TranscriptTimeline.interleave(segments, listOf(photo(1, 2001)))))
        assertEquals("s0 s1 s2 p1", order(TranscriptTimeline.interleave(segments, listOf(photo(1, 5500)))))
    }

    @Test
    fun `a segment starting exactly at the photo comes before the photo`() {
        assertEquals("s0 s1 s2 p1", order(TranscriptTimeline.interleave(segments, listOf(photo(1, 5000)))))
        assertEquals("s0 s1 p1 s2", order(TranscriptTimeline.interleave(segments, listOf(photo(1, 2000)))))
    }

    @Test
    fun `photos with equal position keep the meeting json order`() {
        assertEquals("s0 s1 p1 p2 p3 s2", order(TranscriptTimeline.interleave(segments, listOf(photo(1, 3000), photo(2, 3000), photo(3, 3000)))))
    }

    @Test
    fun `photos before the first segment come first`() {
        assertEquals("p1 p2 s0 s1 s2", order(TranscriptTimeline.interleave(segments, listOf(photo(1, 0), photo(2, 999)))))
        assertEquals("p1 s0 s1 s2", order(TranscriptTimeline.interleave(segments, listOf(photo(1, 500)))))
    }

    @Test
    fun `with zero segments the photos are listed alone`() {
        assertEquals("p1 p2", order(TranscriptTimeline.interleave(emptyList(), listOf(photo(1, 10), photo(2, 99)))))
        assertEquals("", order(TranscriptTimeline.interleave(emptyList(), emptyList())))
    }

    @Test
    fun `photos after the last segment come last`() {
        assertEquals("s0 s1 s2 p1 p2", order(TranscriptTimeline.interleave(segments, listOf(photo(1, 9000), photo(2, 9500)))))
    }

    @Test
    fun `without photos the segments stay as they are`() {
        assertEquals("s0 s1 s2", order(TranscriptTimeline.interleave(segments, emptyList())))
    }

    @Test
    fun `the photo number is the 1-based index in meeting json`() {
        val items = TranscriptTimeline.interleave(segments, listOf(photo(7, 10), photo(9, 20)))
        assertEquals(listOf(1, 2), items.filterIsInstance<PhotoItem>().map { it.number })
    }

    @Test
    fun `the golden example yields the documented order`() {
        val items = TranscriptTimeline.interleave(TestTranscripts.german.segments, TestTranscripts.germanMeeting.photos)
        assertEquals("s0 s1 p1 s2 p2 s3 p3", order(items))
    }
}
