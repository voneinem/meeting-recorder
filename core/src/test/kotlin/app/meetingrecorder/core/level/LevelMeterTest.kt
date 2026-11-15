package app.meetingrecorder.core.level

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LevelMeterTest {

    private fun assertDb(expected: Float, actual: Float, delta: Float = 0.01f) =
        assertTrue(kotlin.math.abs(expected - actual) <= delta, "expected $expected but was $actual")

    @Test
    fun `silence gives the floor value`() {
        val levels = LevelMeter.measure(ShortArray(480), 480, 1)
        assertEquals(1, levels.size)
        assertEquals(LevelMeter.FLOOR_DB, levels[0].peakDb)
        assertEquals(LevelMeter.FLOOR_DB, levels[0].rmsDb)
    }

    @Test
    fun `a full scale square wave is 0 dBFS`() {
        val buffer = ShortArray(480) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        val level = LevelMeter.measure(buffer, 480, 1)[0]
        assertDb(0f, level.peakDb)
        assertDb(0f, level.rmsDb)
    }

    @Test
    fun `half scale is about minus six dB`() {
        val buffer = ShortArray(100) { 16384 }
        val level = LevelMeter.measure(buffer, 100, 1)[0]
        assertDb(-6.02f, level.peakDb)
        assertDb(-6.02f, level.rmsDb)
    }

    @Test
    fun `a one sided signal leaves the other channel silent`() {
        // interleaved stereo: left carries a signal, right is silent
        val buffer = ShortArray(200) { if (it % 2 == 0) 20_000 else 0 }
        val (left, right) = LevelMeter.measure(buffer, 100, 2)
        assertTrue(left.peakDb > -5f)
        assertEquals(LevelMeter.FLOOR_DB, right.peakDb)
        assertEquals(LevelMeter.FLOOR_DB, right.rmsDb)
    }

    @Test
    fun `only the given number of frames is measured`() {
        val buffer = ShortArray(100) { if (it < 50) 0 else 30_000 }
        assertEquals(LevelMeter.FLOOR_DB, LevelMeter.measure(buffer, 50, 1)[0].peakDb)
    }

    @Test
    fun `empty input and invalid arguments`() {
        assertEquals(LevelMeter.FLOOR_DB, LevelMeter.measure(ShortArray(0), 0, 2)[1].rmsDb)
        assertFailsWith<IllegalArgumentException> { LevelMeter.measure(ShortArray(10), 20, 1) }
        assertFailsWith<IllegalArgumentException> { LevelMeter.measure(ShortArray(10), 5, 3) }
    }
}
