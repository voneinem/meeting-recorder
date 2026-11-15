package app.meetingrecorder.core.wav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingLimitsTest {

    @Test
    fun `maximum length is at least six hours in stereo and twelve hours in mono`() {
        val stereoHours = RecordingLimits.maxSeconds(48_000, 2) / 3600.0
        val monoHours = RecordingLimits.maxSeconds(48_000, 1) / 3600.0
        assertTrue(stereoHours in 6.0..6.3, "stereo hours: $stereoHours")
        assertTrue(monoHours in 12.0..12.5, "mono hours: $monoHours")
    }

    @Test
    fun `the limit stays below what the header can declare`() {
        assertTrue(RecordingLimits.MAX_DATA_BYTES < WavHeader.MAX_DECLARED_DATA_BYTES)
        assertTrue(RecordingLimits.maxFrames(2) * 4 <= RecordingLimits.MAX_DATA_BYTES)
    }

    @Test
    fun `remaining seconds count down and never go negative`() {
        val max = RecordingLimits.maxFrames(1)
        assertEquals(RecordingLimits.maxSeconds(48_000, 1), RecordingLimits.remainingSeconds(0, 48_000, 1))
        assertEquals(10, RecordingLimits.remainingSeconds(max - 10 * 48_000L, 48_000, 1))
        assertEquals(0, RecordingLimits.remainingSeconds(max + 1_000, 48_000, 1))
    }

    @Test
    fun `the warning is due fifteen minutes before the limit`() {
        val max = RecordingLimits.maxFrames(2)
        assertFalse(RecordingLimits.isWarningDue(0, 48_000, 2))
        assertFalse(RecordingLimits.isWarningDue(max - 16 * 60 * 48_000L, 48_000, 2))
        assertTrue(RecordingLimits.isWarningDue(max - 15 * 60 * 48_000L, 48_000, 2))
        assertTrue(RecordingLimits.isWarningDue(max, 48_000, 2))
    }

    @Test
    fun `the limit is reached at the maximum frame count`() {
        val max = RecordingLimits.maxFrames(2)
        assertFalse(RecordingLimits.isLimitReached(max - 1, 2))
        assertTrue(RecordingLimits.isLimitReached(max, 2))
    }
}
