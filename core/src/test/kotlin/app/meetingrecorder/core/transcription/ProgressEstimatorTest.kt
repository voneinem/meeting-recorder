package app.meetingrecorder.core.transcription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressEstimatorTest {

    private val sr = 48_000
    private fun f(seconds: Long) = seconds * sr
    private fun estimator(durationSeconds: Long = 600, before: Long = 0, startSeconds: Long = 0) =
        ProgressEstimator(f(durationSeconds), sr, before, f(startSeconds))

    private fun assertProgress(expected: Double, actual: Float) = assertEquals(expected, actual.toDouble(), 1e-4)

    @Test
    fun `there is no estimate before the first completed window`() {
        val e = estimator()
        e.begin(1_000)
        val s = e.snapshot(5_000)
        assertProgress(0.0, s.progress)
        assertNull(s.remainingMs)
        assertEquals(4_000, s.elapsedMs)
    }

    @Test
    fun `a steady real-time factor gives a linear estimate`() {
        val e = estimator()
        e.begin(0)
        e.anchor(f(120), 60_000)
        var s = e.snapshot(60_000)
        assertProgress(0.2, s.progress)
        assertEquals(240_000, s.remainingMs)
        e.anchor(f(240), 120_000)
        s = e.snapshot(120_000)
        assertProgress(0.4, s.progress)
        assertEquals(180_000, s.remainingMs)
    }

    @Test
    fun `between anchors progress is interpolated at the measured rate`() {
        val e = estimator()
        e.begin(0)
        e.anchor(f(120), 60_000)
        // 20 s of wall clock at 0.5 s per audio second is 40 s of audio.
        assertProgress(160.0 / 600, e.snapshot(80_000).progress)
    }

    @Test
    fun `during a stall progress never runs more than one window of audio ahead`() {
        val e = estimator()
        e.begin(0)
        e.anchor(f(120), 60_000)
        assertProgress(240.0 / 600, e.snapshot(180_000).progress)
        assertProgress(240.0 / 600, e.snapshot(10_000_000).progress)
    }

    @Test
    fun `progress keeps moving every second through a whole window at a steady rate`() {
        val e = estimator()
        e.begin(0)
        e.anchor(f(120), 42_000) // real-time factor 0.35: a 120 s window takes 42 s
        var last = e.snapshot(42_000).progress
        for (wall in 43_000..84_000 step 1_000) { // the next window ends at 84 s
            val now = e.snapshot(wall.toLong()).progress
            assertTrue(now > last, "progress stood still at $wall ms")
            last = now
        }
    }

    @Test
    fun `a slowdown raises the estimate through the moving average`() {
        val e = estimator()
        e.begin(0)
        e.anchor(f(120), 60_000)
        e.anchor(f(240), 180_000) // this window took 1.0 s per audio second
        val s = e.snapshot(180_000)
        // average = 0.3 * 1.0 + 0.7 * 0.5 = 0.65; 360 s of audio left; 234 s rounds to 235 s
        assertEquals(235_000, s.remainingMs)
        assertProgress(0.4, s.progress)
    }

    @Test
    fun `progress never decreases`() {
        val e = estimator()
        e.begin(0)
        e.anchor(f(120), 60_000)
        val high = e.snapshot(120_000).progress
        e.anchor(f(150), 130_000) // an anchor behind the interpolated position
        assertTrue(e.snapshot(130_000).progress >= high)
        var last = 0f
        for (t in 0L..400_000L step 10_000L) {
            val p = e.snapshot(130_000 + t).progress
            assertTrue(p >= last)
            last = p
        }
    }

    @Test
    fun `the remaining time is rounded to 5 s above one minute and exact below`() {
        val long = estimator(durationSeconds = 601)
        long.begin(0)
        long.anchor(f(120), 60_000)
        assertEquals(240_000, long.snapshot(60_000).remainingMs) // 240_500 -> nearest 5 s
        val longer = estimator(durationSeconds = 606)
        longer.begin(0)
        longer.anchor(f(120), 60_000)
        assertEquals(245_000, longer.snapshot(60_000).remainingMs) // 243_000 -> nearest 5 s
        val short = estimator(durationSeconds = 100)
        short.begin(0)
        short.anchor(f(50), 25_000)
        assertEquals(25_000, short.snapshot(25_000).remainingMs)
        val odd = estimator(durationSeconds = 100)
        odd.begin(0)
        odd.anchor(f(51), 25_500)
        assertEquals(24_500, odd.snapshot(25_500).remainingMs)
    }

    @Test
    fun `a resumed job carries the elapsed time of the work file`() {
        val e = estimator(before = 30_000, startSeconds = 120)
        e.begin(1_000_000)
        val first = e.snapshot(1_000_000)
        assertProgress(0.2, first.progress)
        assertNull(first.remainingMs)
        assertEquals(30_000, first.elapsedMs)
        e.anchor(f(240), 1_060_000)
        val s = e.snapshot(1_060_000)
        assertEquals(90_000, s.elapsedMs)
        assertEquals(180_000, s.remainingMs)
    }

    @Test
    fun `progress stays below 1 until the commit and reaches 1 only then`() {
        val e = estimator()
        e.begin(0)
        e.anchor(f(600), 300_000)
        assertProgress(0.99, e.snapshot(300_000).progress)
        e.finish(301_000)
        val s = e.snapshot(400_000)
        assertEquals(1f, s.progress)
        assertEquals(0, s.remainingMs)
        assertEquals(301_000, s.elapsedMs)
    }

    @Test
    fun `an anchor that does not advance changes nothing and the first anchor needs begin`() {
        val e = estimator()
        assertFailsWith<IllegalStateException> { e.anchor(f(10), 1) }
        e.begin(0)
        e.anchor(f(120), 60_000)
        e.anchor(f(120), 70_000)
        // The baseline stays at 120 s / 60 s: 10 s later that is 20 s of audio ahead, 460 s left at 0.5.
        val s = e.snapshot(70_000)
        assertProgress(140.0 / 600, s.progress)
        assertEquals(230_000, s.remainingMs)
    }
}
