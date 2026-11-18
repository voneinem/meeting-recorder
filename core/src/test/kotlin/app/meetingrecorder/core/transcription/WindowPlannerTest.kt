package app.meetingrecorder.core.transcription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WindowPlannerTest {

    private val sr = 48_000
    private val planner = WindowPlanner()
    private val duration = 600L * sr

    private fun raw(startCs: Long, endCs: Long, text: String = "x") = RawSegment(startCs, endCs, text)

    private fun window(from: Long = 0, to: Long = 120L * sr, last: Boolean = false) = WindowPlanner.Window(from, to, last)

    @Test
    fun `the constants are those of research R-5`() {
        assertEquals(120, WindowPlanner.WINDOW_SECONDS)
        assertEquals(2, WindowPlanner.TAIL_GUARD_SECONDS)
        assertEquals(5, WindowPlanner.MIN_ADVANCE_SECONDS)
    }

    @Test
    fun `a window is 120 seconds and the last one ends at the end of the audio`() {
        assertEquals(WindowPlanner.Window(0, 120L * sr, false), planner.next(0, duration, sr))
        val tail = planner.next(duration - 50L * sr, duration, sr)
        assertEquals(WindowPlanner.Window(duration - 50L * sr, duration, true), tail)
        assertEquals(WindowPlanner.Window(0, 20L * sr, false), WindowPlanner(20).next(0, duration, sr))
        assertFailsWith<IllegalArgumentException> { planner.next(duration, duration, sr) }
        assertFailsWith<IllegalArgumentException> { WindowPlanner(5) }
    }

    @Test
    fun `an empty window accepts nothing and advances to the window end`() {
        assertEquals(WindowPlanner.Accepted(emptyList(), 120L * sr), planner.accept(window(), emptyList(), sr))
        // Segments that are empty after trimming do not count as speech.
        assertEquals(WindowPlanner.Accepted(emptyList(), 120L * sr), planner.accept(window(), listOf(raw(0, 500, "  \n")), sr))
    }

    @Test
    fun `the last window of the audio accepts all segments`() {
        val last = window(from = 500L * sr, to = duration, last = true)
        val accepted = planner.accept(last, listOf(raw(0, 4000), raw(9000, 9999)), sr)
        assertEquals(2, accepted.segments.size)
        assertEquals(duration, accepted.nextFrame)
    }

    @Test
    fun `when speech ended before the tail guard all segments are accepted and the cursor moves to the window end`() {
        val accepted = planner.accept(window(), listOf(raw(0, 1000), raw(2000, 11_800)), sr)
        assertEquals(2, accepted.segments.size)
        assertEquals(120L * sr, accepted.nextFrame)
    }

    @Test
    fun `a last segment inside the tail guard is dropped and the cursor moves to its start`() {
        val accepted = planner.accept(window(), listOf(raw(1000, 2000, "keep"), raw(10_000, 11_801, "cut")), sr)
        assertEquals(listOf("keep"), accepted.segments.map { it.text })
        assertEquals(100L * sr, accepted.nextFrame)
    }

    @Test
    fun `a segment that spans the whole window is accepted so that the cursor always advances`() {
        val accepted = planner.accept(window(), listOf(raw(0, 12_000, "long")), sr)
        assertEquals(listOf("long"), accepted.segments.map { it.text })
        assertEquals(120L * sr, accepted.nextFrame)
    }

    @Test
    fun `the cursor advances by at least the minimum advance`() {
        // The last segment starts 3 s into the window and touches its end: dropping it would advance by 3 s.
        val accepted = planner.accept(window(), listOf(raw(300, 11_950, "late start")), sr)
        assertEquals(1, accepted.segments.size)
        assertEquals(119L * sr + sr / 2, accepted.nextFrame) // the end of that segment, 119.5 s
        // Exactly the minimum advance is allowed to drop the segment.
        val exact = planner.accept(window(), listOf(raw(1, 2, "a"), raw(500, 11_950, "b")), sr)
        assertEquals(listOf("a"), exact.segments.map { it.text })
        assertEquals(5L * sr, exact.nextFrame)
    }

    @Test
    fun `segment frames are cursor plus cs times the sample rate over 100 in integer arithmetic`() {
        val from = 7L * sr + 123
        val w = WindowPlanner.Window(from, from + 120L * sr, false)
        val a = planner.accept(w, listOf(raw(1000, 1500, "a")), sr).segments.single()
        assertEquals(WorkSegment(from + 480_000, from + 720_000, "a"), a)
        val w441 = WindowPlanner.Window(0, 120L * 44_100, false)
        assertEquals(1323L, planner.accept(w441, listOf(raw(3, 4, "b")), 44_100).segments.single().startFrame)
        val w22 = WindowPlanner.Window(0, 120L * 22_050, false)
        assertEquals(220L, planner.accept(w22, listOf(raw(1, 2, "c")), 22_050).segments.single().startFrame)
    }

    @Test
    fun `segment frames are clamped to the window and to the audio`() {
        val to = 10L * sr
        val last = WindowPlanner.Window(0, to, true)
        val accepted = planner.accept(last, listOf(raw(900, 1100, "padded tail"), raw(1200, 1300, "beyond")), sr)
        assertEquals(WorkSegment(9L * sr, to, "padded tail"), accepted.segments[0])
        assertEquals(WorkSegment(to, to, "beyond"), accepted.segments[1])
    }

    @Test
    fun `text is trimmed and a remainder shorter than 1_1 s is padded`() {
        assertEquals("hello", planner.accept(window(), listOf(raw(0, 100, "  hello \n")), sr).segments.single().text)
        assertEquals(17_600, WindowPlanner.MIN_DECODE_SAMPLES)
        assertEquals(17_600, WindowPlanner.paddedSampleCount(100))
        assertEquals(17_600, WindowPlanner.paddedSampleCount(0))
        assertEquals(20_000, WindowPlanner.paddedSampleCount(20_000))
    }
}
