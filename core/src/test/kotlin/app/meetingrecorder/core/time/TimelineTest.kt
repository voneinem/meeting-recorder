package app.meetingrecorder.core.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimelineTest {

    @Test
    fun `frames convert to milliseconds rounded down`() {
        assertEquals(0, Timeline.framesToMs(0, 48_000))
        assertEquals(1_000, Timeline.framesToMs(48_000, 48_000))
        assertEquals(30_000, Timeline.framesToMs(1_440_000, 48_000))
        assertEquals(0, Timeline.framesToMs(47, 48_000)) // 0.979 ms
        assertEquals(1, Timeline.framesToMs(48, 48_000))
        assertEquals(22, Timeline.framesToMs(1_000, 44_100)) // 22.67 ms
    }

    @Test
    fun `milliseconds convert to frames`() {
        assertEquals(0, Timeline.msToFrames(0, 48_000))
        assertEquals(48_000, Timeline.msToFrames(1_000, 48_000))
        assertEquals(480_000, Timeline.msToFrames(10_000, 48_000))
        assertEquals(44, Timeline.msToFrames(1, 44_100))
    }

    @Test
    fun `conversion does not overflow beyond twelve hours`() {
        val twelveHoursFrames = 12L * 3600 * 48_000
        assertEquals(12L * 3600 * 1000, Timeline.framesToMs(twelveHoursFrames, 48_000))
        assertEquals(twelveHoursFrames, Timeline.msToFrames(12L * 3600 * 1000, 48_000))
    }

    @Test
    fun `invalid arguments are rejected`() {
        assertFailsWith<IllegalArgumentException> { Timeline.framesToMs(1, 0) }
        assertFailsWith<IllegalArgumentException> { Timeline.framesToMs(-1, 48_000) }
        assertFailsWith<IllegalArgumentException> { Timeline.msToFrames(-1, 48_000) }
        assertFailsWith<IllegalArgumentException> { Timeline.format(-1) }
    }

    @Test
    fun `positions are valid inside the duration only`() {
        assertTrue(Timeline.isValidPosition(0, 100))
        assertTrue(Timeline.isValidPosition(100, 100))
        assertFalse(Timeline.isValidPosition(101, 100))
        assertFalse(Timeline.isValidPosition(-1, 100))
    }

    @Test
    fun `positions are formatted as mm ss and h mm ss`() {
        assertEquals("00:00", Timeline.format(0))
        assertEquals("00:09", Timeline.format(9_999))
        assertEquals("01:05", Timeline.format(65_000))
        assertEquals("59:59", Timeline.format(3_599_999))
        assertEquals("1:00:00", Timeline.format(3_600_000))
        assertEquals("2:03:04", Timeline.format(7_384_000))
    }
}
