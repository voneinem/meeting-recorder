package app.meetingrecorder.core.recording

import app.meetingrecorder.core.recording.RecordingGuard.Signal
import app.meetingrecorder.core.wav.RecordingLimits
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingGuardTest {
    private val sampleRate = 48_000
    private val mib = 1024L * 1024
    private var now = 0L

    private fun guard(channels: Int = 1) = RecordingGuard(sampleRate, channels, { now })

    // --- before and at the start ---

    @Test
    fun `a recording does not start when the free space is below the minimum`() {
        val guard = guard()
        assertFalse(guard.canStart(10 * mib - 1))
        assertTrue(guard.canStart(10 * mib))
        assertTrue(guard.canStart(50 * mib))
    }

    @Test
    fun `unknown free space does not block a start`() {
        assertTrue(guard().canStart(null))
    }

    @Test
    fun `a start with low but sufficient space warns right away and only once`() {
        val guard = guard()
        assertEquals(Signal.StorageLow(60 * mib), guard.onStarted(60 * mib))
        // The periodic check must not repeat the warning that was just given.
        now = 11_000
        assertEquals(emptyList(), guard.check(0) { 60 * mib })
    }

    @Test
    fun `a start with plenty of space or unknown space gives no warning`() {
        assertEquals(null, guard().onStarted(500 * mib))
        assertEquals(null, guard().onStarted(null))
    }

    // --- free space while recording ---

    @Test
    fun `the free space is not read before the check interval has passed`() {
        val guard = guard()
        var reads = 0
        now = 9_999
        guard.check(1000) { reads++; 500 * mib }
        assertEquals(0, reads)
        now = 10_000
        guard.check(1000) { reads++; 500 * mib }
        assertEquals(1, reads)
    }

    @Test
    fun `the free space is read again after every interval`() {
        val guard = guard()
        var reads = 0
        for (second in 10..60 step 10) {
            now = second * 1000L
            guard.check(1000) { reads++; 500 * mib }
        }
        assertEquals(6, reads)
    }

    @Test
    fun `low storage is reported once when the space drops below the threshold`() {
        val guard = guard()
        now = 10_000
        assertEquals(emptyList(), guard.check(1000) { 150 * mib })
        now = 20_000
        assertEquals(listOf(Signal.StorageLow(99 * mib)), guard.check(1000) { 99 * mib })
        now = 30_000
        assertEquals(emptyList(), guard.check(1000) { 40 * mib })
    }

    @Test
    fun `an unreadable free space gives no warning`() {
        val guard = guard()
        now = 10_000
        assertEquals(emptyList(), guard.check(1000) { null })
    }

    // --- the WAV size limit ---

    @Test
    fun `there is no limit signal early in a recording`() {
        assertEquals(emptyList(), guard().check(sampleRate * 3600L) { null })
    }

    @Test
    fun `the limit warning comes once, 15 minutes before the end, with the remaining time`() {
        val guard = guard(channels = 2)
        val atWarning = RecordingLimits.maxFrames(2) - RecordingLimits.WARNING_SECONDS * sampleRate
        assertEquals(emptyList(), guard.check(atWarning - sampleRate) { null }) // one second too early
        val signals = guard.check(atWarning) { null }
        assertEquals(1, signals.size)
        val warning = signals.single() as Signal.LimitApproaching
        assertTrue(warning.secondsLeft in RecordingLimits.WARNING_SECONDS - 1..RecordingLimits.WARNING_SECONDS)
        assertEquals(emptyList(), guard.check(atWarning + 10 * sampleRate) { null }) // not repeated
    }

    @Test
    fun `reaching the limit asks for a graceful stop exactly once`() {
        val guard = guard(channels = 2)
        val max = RecordingLimits.maxFrames(2)
        assertEquals(listOf(Signal.LimitApproaching(RecordingLimits.remainingSeconds(max - 1, sampleRate, 2))), guard.check(max - 1) { null }.filterIsInstance<Signal.LimitApproaching>())
        val atLimit = guard.check(max) { null }
        assertEquals(listOf<Signal>(Signal.LimitReached), atLimit)
        assertEquals(emptyList(), guard.check(max + 4800) { null })
    }

    @Test
    fun `jumping straight to the limit gives the warning and the stop together, in that order`() {
        val signals = guard().check(RecordingLimits.maxFrames(1)) { null }
        assertEquals(2, signals.size)
        assertTrue(signals[0] is Signal.LimitApproaching)
        assertEquals(Signal.LimitReached, signals[1])
    }

    @Test
    fun `stereo reaches the limit in half the time of mono`() {
        val mono = RecordingLimits.maxFrames(1)
        val stereo = RecordingLimits.maxFrames(2)
        assertEquals(mono / 2, stereo)
        assertTrue(guard(channels = 2).check(stereo) { null }.contains(Signal.LimitReached))
        assertFalse(guard(channels = 1).check(stereo) { null }.contains(Signal.LimitReached))
    }

    @Test
    fun `a limit signal and a storage warning can come in the same call`() {
        val guard = guard(channels = 2)
        now = 10_000
        val signals = guard.check(RecordingLimits.maxFrames(2)) { 5 * mib }
        assertEquals(
            setOf(Signal.LimitReached, Signal.StorageLow(5 * mib)),
            signals.filter { it !is Signal.LimitApproaching }.toSet(),
        )
    }

    // --- out of space errors ---

    @Test
    fun `a write that fails for lack of space is recognised`() {
        assertTrue(RecordingGuard.isOutOfSpace(IOException("write failed: ENOSPC (No space left on device)")))
        assertTrue(RecordingGuard.isOutOfSpace(IOException("No space left on device")))
    }

    @Test
    fun `an out of space cause deeper in the chain is recognised`() {
        assertTrue(RecordingGuard.isOutOfSpace(IOException("write failed", IOException("ENOSPC"))))
    }

    @Test
    fun `other write failures are not mistaken for a full disk`() {
        assertFalse(RecordingGuard.isOutOfSpace(IOException("Broken pipe")))
        assertFalse(RecordingGuard.isOutOfSpace(IOException()))
        assertFalse(RecordingGuard.isOutOfSpace(IOException("permission denied", RuntimeException("EACCES"))))
    }
}
