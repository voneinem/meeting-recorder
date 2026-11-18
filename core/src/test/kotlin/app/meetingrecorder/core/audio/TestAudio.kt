package app.meetingrecorder.core.audio

import app.meetingrecorder.core.wav.InMemorySeekableStore
import app.meetingrecorder.core.wav.SeekableStore
import app.meetingrecorder.core.wav.WavHeader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Builders for WAV files in memory. */
object TestAudio {

    /** A canonical WAV file: [samples] are interleaved 16-bit values. */
    fun wav(sampleRate: Int, channels: Int, samples: ShortArray, declaredDataBytes: Long = samples.size * 2L): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort(it) }
        return WavHeader.build(sampleRate, channels, declaredDataBytes) + data.array()
    }

    fun store(sampleRate: Int, channels: Int, samples: ShortArray) = InMemorySeekableStore(wav(sampleRate, channels, samples))

    /** A tone plus a little seeded noise, as 16-bit samples. */
    fun tone(frames: Int, sampleRate: Int, frequency: Double, amplitude: Double = 0.5, channels: Int = 1, noise: Double = 0.0, seed: Int = 1): ShortArray {
        val random = Random(seed)
        return ShortArray(frames * channels) { i ->
            val frame = i / channels
            val value = amplitude * sin(2 * PI * frequency * frame / sampleRate) + noise * (random.nextDouble() - 0.5)
            (value * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** A store that refuses every change and counts the bytes read. */
    class ReadOnlyStore(private val inner: SeekableStore) : SeekableStore {
        var bytesRead = 0L
            private set

        override fun length() = inner.length()

        override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
            val read = inner.readAt(offset, buffer, bufferOffset, length)
            if (read > 0) bytesRead += read
            return read
        }

        override fun writeAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int) = throw AssertionError("write to a read-only store")

        override fun truncate(length: Long) = throw AssertionError("truncate of a read-only store")

        override fun sync() = throw AssertionError("sync of a read-only store")
    }
}
