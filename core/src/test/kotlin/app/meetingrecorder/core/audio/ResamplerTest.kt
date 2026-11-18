package app.meetingrecorder.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResamplerTest {

    private fun resample(input: FloatArray, rate: Int): FloatArray {
        val r = PolyphaseResampler(rate)
        return r.process(input) + r.finish()
    }

    private fun sine(frames: Int, rate: Int, hz: Double, amplitude: Double = 1.0) =
        FloatArray(frames) { (amplitude * sin(2 * PI * hz * it / rate)).toFloat() }

    private fun rms(x: FloatArray, from: Int, to: Int): Double = sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from))

    private fun db(ratio: Double) = 20 * log10(ratio)

    @Test
    fun `downmix is the mean of left and right`() {
        assertContentEquals(floatArrayOf(0.5f, 0f, -0.25f), Downmix.toMono(floatArrayOf(1f, 0f, 0.5f, -0.5f, -0.25f, -0.25f), 2))
        val mono = floatArrayOf(0.1f, 0.2f)
        assertContentEquals(mono, Downmix.toMono(mono, 1))
    }

    @Test
    fun `an impulse keeps its position within one output sample`() {
        for (rate in listOf(48_000, 44_100)) {
            val at = 4_000
            val input = FloatArray(8_000).also { it[at] = 1f }
            val output = resample(input, rate)
            val peak = output.indices.maxByOrNull { abs(output[it]) }!!
            val expected = at * 16_000.0 / rate
            assertTrue(abs(peak - expected) <= 1.0, "rate $rate: peak at $peak, expected $expected")
        }
    }

    @Test
    fun `the output length follows the ratio`() {
        assertEquals(16_000, resample(FloatArray(48_000), 48_000).size)
        assertEquals(16_000, resample(FloatArray(44_100), 44_100).size)
        assertEquals(1, resample(FloatArray(3), 48_000).size)
        assertEquals(0, resample(FloatArray(0), 48_000).size)
    }

    @Test
    fun `a 1 kHz sine keeps its amplitude within half a decibel`() {
        for (rate in listOf(48_000, 44_100)) {
            val out = resample(sine(rate, rate, 1_000.0), 16_000.let { rate })
            val reference = 1 / sqrt(2.0)
            val level = db(rms(out, 500, out.size - 500) / reference)
            assertTrue(abs(level) < 0.5, "rate $rate: $level dB")
        }
    }

    @Test
    fun `a 9 kHz sine is attenuated by at least 60 decibels`() {
        for (rate in listOf(48_000, 44_100)) {
            val out = resample(sine(rate, rate, 9_000.0), rate)
            val level = db(rms(out, 500, out.size - 500) / (1 / sqrt(2.0)))
            assertTrue(level <= -60.0, "rate $rate: $level dB")
        }
    }

    @Test
    fun `any partition of the input into blocks gives identical output bit for bit`() {
        val random = Random(7)
        for (rate in listOf(48_000, 44_100)) {
            val input = FloatArray(6_000) { (random.nextDouble() * 2 - 1).toFloat() }
            val whole = resample(input, rate)
            for (partition in listOf(listOf(1), listOf(7), listOf(480, 1), listOf(1_000, 3, 777), listOf(6_000))) {
                val r = PolyphaseResampler(rate)
                val out = ArrayList<Float>()
                var position = 0
                var step = 0
                while (position < input.size) {
                    val size = minOf(partition[step++ % partition.size], input.size - position)
                    out += r.process(input.copyOfRange(position, position + size)).toList()
                    position += size
                }
                out += r.finish().toList()
                assertContentEquals(whole, out.toFloatArray(), "rate $rate, partition $partition")
            }
        }
    }

    @Test
    fun `there is no group delay`() {
        // A step through the middle crosses half height at the matching output position.
        val input = FloatArray(6_000) { if (it >= 3_000) 1f else 0f }
        val out = resample(input, 48_000)
        val crossing = out.indices.first { out[it] >= 0.5f }
        assertTrue(abs(crossing - 1_000.0) <= 1.0, "crossing at $crossing")
    }

    @Test
    fun `the same rate passes the samples through and a lower rate is upsampled`() {
        val input = FloatArray(100) { it / 100f }
        assertContentEquals(input, resample(input, 16_000))
        val up = resample(sine(8_000, 8_000, 500.0), 8_000)
        assertEquals(16_000, up.size)
        assertTrue(abs(db(rms(up, 500, up.size - 500) / (1 / sqrt(2.0)))) < 0.5)
    }

    @Test
    fun `output indices are global so a resampler can start in the middle of a file`() {
        val input = sine(48_000, 48_000, 700.0)
        val whole = resample(input, 48_000)
        val start = 20_001L
        val r = PolyphaseResampler(48_000, 16_000, start)
        val part = r.process(input.copyOfRange(start.toInt(), input.size)) + r.finish()
        val first = r.firstOutputIndex.toInt()
        assertEquals(whole.size - first, part.size)
        val margin = PolyphaseResampler.spanFrames(48_000)
        for (i in (margin / 3 + 2) until part.size) assertEquals(whole[first + i], part[i], "output ${first + i}")
    }
}
