package app.meetingrecorder.core.audio

import app.meetingrecorder.core.wav.SeekableStore
import app.meetingrecorder.core.wav.WavHeader

/** The file is not a 16-bit PCM WAV that can be read (too short, or an unknown header). */
public class UnreadableWavException(message: String) : Exception(message)

/**
 * Reads the PCM frames of `audio.wav` through a [SeekableStore], read-only. The length comes from the
 * file length, not from the header sizes, so a file cut short by a crash can be read like the
 * recovery rule does; a trailing partial frame is ignored.
 */
public class WavPcmReader(private val store: SeekableStore) {

    public val sampleRate: Int
    public val channels: Int
    public val frameCount: Long

    private val blockAlign: Int

    init {
        val length = store.length()
        if (length < WavHeader.SIZE) throw UnreadableWavException("file is shorter than a WAV header ($length bytes)")
        val header = ByteArray(WavHeader.SIZE)
        if (store.readAt(0, header) < WavHeader.SIZE) throw UnreadableWavException("the header could not be read")
        val format = WavHeader.parse(header) ?: throw UnreadableWavException("not a 16-bit PCM WAV header")
        sampleRate = format.sampleRate
        channels = format.channels
        blockAlign = format.blockAlign
        frameCount = (length - WavHeader.SIZE) / blockAlign
    }

    /**
     * Interleaved samples of up to [count] frames from [fromFrame], as floats in `[-1, 1)`. Fewer
     * frames (possibly none) are returned at the end of the file.
     */
    public fun readFrames(fromFrame: Long, count: Int): FloatArray {
        require(fromFrame >= 0) { "fromFrame must not be negative" }
        require(count >= 0) { "count must not be negative" }
        val frames = minOf(count.toLong(), (frameCount - fromFrame).coerceAtLeast(0)).toInt()
        if (frames == 0) return FloatArray(0)
        val bytes = ByteArray(frames * blockAlign)
        var done = 0
        while (done < bytes.size) {
            val read = store.readAt(WavHeader.SIZE + fromFrame * blockAlign + done, bytes, done, bytes.size - done)
            if (read <= 0) throw UnreadableWavException("the file ended while reading frame ${fromFrame + done / blockAlign}")
            done += read
        }
        val samples = FloatArray(frames * channels)
        for (i in samples.indices) {
            val low = bytes[2 * i].toInt() and BYTE_MASK
            val high = bytes[2 * i + 1].toInt() // sign-extended
            samples[i] = ((high shl BITS_PER_BYTE) or low) / SCALE
        }
        return samples
    }

    private companion object {
        const val BYTE_MASK = 0xFF
        const val BITS_PER_BYTE = 8
        const val SCALE = 32768f
    }
}
