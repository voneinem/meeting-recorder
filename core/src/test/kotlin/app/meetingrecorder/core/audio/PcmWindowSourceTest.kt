package app.meetingrecorder.core.audio

import app.meetingrecorder.core.audio.TestAudio.ReadOnlyStore
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PcmWindowSourceTest {

    private fun samplesOf(window: app.meetingrecorder.core.transcription.PcmWindow): FloatArray {
        val out = FloatArray(window.count)
        val copy = window.buffer.duplicate().also { it.rewind() }
        copy.get(out, 0, window.count)
        return out
    }

    private fun onePass(reader: WavPcmReader): FloatArray {
        val r = PolyphaseResampler(reader.sampleRate)
        val all = Downmix.toMono(reader.readFrames(0, Int.MAX_VALUE), reader.channels)
        return r.process(all) + r.finish()
    }

    private fun ceilDiv(a: Long, b: Long) = -Math.floorDiv(-a, b)

    @Test
    fun `the sample count follows the window length within one sample`() {
        val reader = WavPcmReader(TestAudio.store(48_000, 1, TestAudio.tone(5 * 48_000, 48_000, 500.0)))
        val source = PcmWindowSource(reader)
        assertEquals(16_000, source.read(0, 48_000).count)
        for ((from, to) in listOf(12_345L to 77_001L, 100L to 101_000L, 1L to 239_999L)) {
            val expected = (to - from) * 16_000.0 / 48_000
            assertTrue(abs(source.read(from, to).count - expected) <= 1.0, "$from..$to")
        }
    }

    @Test
    fun `windows at arbitrary frames match the slice of a one-pass conversion`() {
        for ((rate, up, down) in listOf(Triple(48_000, 1, 3), Triple(44_100, 160, 441))) {
            val samples = TestAudio.tone(3 * rate, rate, 700.0, channels = 2, noise = 0.2)
            val reader = WavPcmReader(TestAudio.store(rate, 2, samples))
            val reference = onePass(reader)
            val source = PcmWindowSource(reader)
            val windows = listOf(0L to 50_000L, 12_345L to 77_001L, 100_000L to reader.frameCount, 50_001L to 50_100L, 0L to reader.frameCount)
            for ((from, to) in windows) {
                val got = samplesOf(source.read(from, to))
                val first = ceilDiv(from * up, down.toLong()).toInt()
                val expected = reference.copyOfRange(first, ceilDiv(to * up, down.toLong()).toInt())
                assertEquals(expected.size, got.size, "rate $rate $from..$to")
                for (i in got.indices) assertEquals(expected[i], got[i], "rate $rate $from..$to at $i")
            }
        }
    }

    @Test
    fun `adjacent windows tile without a gap or an overlap`() {
        val reader = WavPcmReader(TestAudio.store(44_100, 1, TestAudio.tone(2 * 44_100, 44_100, 300.0, noise = 0.1)))
        val source = PcmWindowSource(reader)
        val total = source.read(0, reader.frameCount).count
        val parts = source.read(0, 30_001).count + source.read(30_001, 60_000).count + source.read(60_000, reader.frameCount).count
        assertEquals(total, parts)
    }

    @Test
    fun `a window is never larger than itself plus the padding`() {
        val reader = WavPcmReader(TestAudio.store(48_000, 1, ShortArray(120 * 48_000)))
        val window = PcmWindowSource(reader).read(0, reader.frameCount)
        assertEquals(120 * 16_000, window.count)
        assertEquals(window.count, window.buffer.capacity())
        assertTrue(window.buffer.isDirect)
    }

    @Test
    fun `a short window is padded with silence to the minimum`() {
        val reader = WavPcmReader(TestAudio.store(48_000, 1, TestAudio.tone(48_000, 48_000, 500.0)))
        val window = PcmWindowSource(reader).read(40_000, 48_000, minSamples = 17_600)
        assertEquals(17_600, window.count)
        val samples = samplesOf(window)
        // 8000 input frames at 48 kHz are 2666 output samples; the rest is padding.
        assertTrue(samples.take(2_666).any { it != 0f })
        assertTrue(samples.drop(2_666).all { it == 0f })
    }

    @Test
    fun `the file is only read through the store and only around the window`() {
        val store = ReadOnlyStore(TestAudio.store(48_000, 2, TestAudio.tone(10 * 48_000, 48_000, 500.0, channels = 2)))
        val reader = WavPcmReader(store)
        val before = store.bytesRead
        PcmWindowSource(reader).read(240_000, 288_000)
        val windowBytes = 48_000L * 4
        val extra = 2L * (PolyphaseResampler.spanFrames(48_000) + 1) * 4
        assertTrue(store.bytesRead - before <= windowBytes + extra, "read ${store.bytesRead - before} bytes")
    }

    @Test
    fun `rms is the loudness of the mono downmix`() {
        val loud = WavPcmReader(TestAudio.store(48_000, 1, TestAudio.tone(48_000, 48_000, 440.0, amplitude = 0.5)))
        assertEquals(0.5 / sqrt(2.0), PcmWindowSource(loud).rms(0, 48_000), 0.01)
        val silent = WavPcmReader(TestAudio.store(48_000, 1, ShortArray(48_000)))
        assertEquals(0.0, PcmWindowSource(silent).rms(0, 48_000))
        // Opposite channels cancel in the downmix.
        val opposite = ShortArray(2_000) { if (it % 2 == 0) 10_000 else -10_000 }
        assertEquals(0.0, PcmWindowSource(WavPcmReader(TestAudio.store(48_000, 2, opposite))).rms(0, 1_000), 1e-9)
    }

    @Test
    fun `a window outside the audio is refused`() {
        val reader = WavPcmReader(TestAudio.store(48_000, 1, ShortArray(1_000)))
        val source = PcmWindowSource(reader)
        assertFailsWith<IllegalArgumentException> { source.read(0, 1_001) }
        assertFailsWith<IllegalArgumentException> { source.read(500, 500) }
        assertFailsWith<IllegalArgumentException> { source.read(-1, 10) }
    }
}
