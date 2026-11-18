package app.meetingrecorder.core.audio

import app.meetingrecorder.core.transcription.PcmWindow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Windows of the meeting audio as 16 kHz mono samples, and the loudness of a stretch of it. */
public interface WindowSource {
    /**
     * 16 kHz mono samples of the input frames `[fromFrame, toFrame)`. A window shorter than
     * [minSamples] is padded with silence and its `count` is [minSamples].
     */
    public fun read(fromFrame: Long, toFrame: Long, minSamples: Int = 0): PcmWindow

    /** Linear RMS (full scale 1.0) of the mono downmix of `[fromFrame, toFrame)`. */
    public fun rms(fromFrame: Long, toFrame: Long): Double
}

/**
 * [WindowSource] over `audio.wav`: reads 1 s blocks, downmixes, resamples. Windows start at arbitrary
 * frames, so the resampler is primed with a lead-in before the window and a lead-out after it; the
 * result equals the matching slice of a one-pass conversion of the whole file, bit for bit.
 */
public class PcmWindowSource(
    private val reader: WavPcmReader,
    private val newResampler: (startInputFrame: Long) -> PolyphaseResampler =
        { PolyphaseResampler(reader.sampleRate, PolyphaseResampler.TARGET_RATE, it) },
) : WindowSource {

    private val span = PolyphaseResampler.spanFrames(reader.sampleRate)

    override fun read(fromFrame: Long, toFrame: Long, minSamples: Int): PcmWindow {
        require(fromFrame in 0 until toFrame && toFrame <= reader.frameCount) {
            "window [$fromFrame, $toFrame) is outside the audio (${reader.frameCount} frames)"
        }
        val start = (fromFrame - span).coerceAtLeast(0)
        val end = (toFrame + span).coerceAtMost(reader.frameCount)
        val resampler = newResampler(start)
        val first = resampler.outputIndexAt(fromFrame)
        val count = (resampler.outputIndexAt(toFrame) - first).toInt()
        val skip = (first - resampler.firstOutputIndex).toInt()

        val samples = FloatArray(maxOf(count, minSamples))
        var produced = 0 // outputs seen so far, counted from the resampler's first output
        fun take(out: FloatArray) {
            for (value in out) {
                val index = produced++ - skip
                if (index in 0 until count) samples[index] = value
            }
        }
        forEachBlock(start, end) { take(resampler.process(Downmix.toMono(it, reader.channels))) }
        if (end == reader.frameCount) take(resampler.finish())
        check(produced - skip >= count) { "the resampler produced too few samples ($produced for a window of $count)" }

        val direct = ByteBuffer.allocateDirect(samples.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
        direct.put(samples).rewind()
        return PcmWindow(direct, samples.size)
    }

    override fun rms(fromFrame: Long, toFrame: Long): Double {
        require(fromFrame in 0..toFrame && toFrame <= reader.frameCount) { "range is outside the audio" }
        var sumSquares = 0.0
        var frames = 0L
        forEachBlock(fromFrame, toFrame) { block ->
            for (sample in Downmix.toMono(block, reader.channels)) sumSquares += sample.toDouble() * sample
            frames += block.size / reader.channels
        }
        return if (frames == 0L) 0.0 else sqrt(sumSquares / frames)
    }

    private fun forEachBlock(from: Long, to: Long, action: (FloatArray) -> Unit) {
        var position = from
        while (position < to) {
            val block = reader.readFrames(position, minOf(reader.sampleRate.toLong(), to - position).toInt())
            if (block.isEmpty()) break
            action(block)
            position += block.size / reader.channels
        }
    }
}
