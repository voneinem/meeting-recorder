package app.meetingrecorder.core.audio

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming rational-ratio resampler (default target 16 kHz) with a Kaiser-windowed sinc low-pass:
 * cut-off at 7.8 kHz, transition band 6.8 to 8.8 kHz, at least 70 dB stop-band attenuation.
 *
 * - The filter is linear phase and centred, so there is no group delay: output sample `m` is the
 *   signal at input position `m * inputRate / outputRate`, within the resolution of one output sample.
 * - Output samples are counted globally: output `m` always means the same instant whatever block
 *   boundaries the input was cut at, and the result is bit-for-bit independent of the partition of the
 *   input into blocks (every output is the same sum over the same inputs in the same order).
 * - [startInputFrame] is the global index of the first input sample the caller will pass; inputs
 *   before it count as silence. The first [spanFrames] input frames after it are a transient.
 *
 * Call [process] for every block, then [finish] once at the end of the input.
 */
public class PolyphaseResampler(
    public val inputRate: Int,
    public val outputRate: Int = TARGET_RATE,
    startInputFrame: Long = 0,
) {
    private val design = design(inputRate, outputRate)
    private val up = design.up
    private val down = design.down
    private val half = design.half
    private val taps = design.taps

    /** The global index of the first output sample: the first one at or after [startInputFrame]. */
    public val firstOutputIndex: Long = outputIndexAt(startInputFrame)

    private var nextOutput: Long = firstOutputIndex
    private var base: Long = startInputFrame
    private var buffer = FloatArray(INITIAL_CAPACITY)
    private var size = 0

    /** The global index of the first output sample at or after the input position [inputFrame]. */
    public fun outputIndexAt(inputFrame: Long): Long = ceilDiv(inputFrame * up, down.toLong())

    /** Returns the output samples that can be computed now. */
    public fun process(block: FloatArray): FloatArray {
        append(block)
        val out = FloatBuilder()
        while (true) {
            val t = nextOutput * down
            val newest = Math.floorDiv(t + half, up.toLong())
            if (newest >= base + size) break
            out.add(compute(t, base + size))
            nextOutput++
        }
        trim()
        return out.toArray()
    }

    /** Returns the remaining outputs of the input seen so far; missing inputs after its end count as silence. */
    public fun finish(): FloatArray {
        val end = base + size
        val out = FloatBuilder()
        while (nextOutput * down < end * up) {
            out.add(compute(nextOutput * down, end))
            nextOutput++
        }
        return out.toArray()
    }

    /** `sum over n of h[t + half - n * up] * x[n]`, in a fixed order, with inputs at or after [end] taken as zero. */
    private fun compute(t: Long, end: Long): Float {
        val newest = Math.floorDiv(t + half, up.toLong())
        var n = minOf(newest, end - 1)
        var c = ((t + half) - n * up).toInt()
        var sum = 0.0
        while (c < taps.size && n >= base) {
            sum += taps[c] * buffer[(n - base).toInt()]
            n--
            c += up
        }
        return sum.toFloat()
    }

    private fun append(block: FloatArray) {
        if (size + block.size > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + block.size))
        System.arraycopy(block, 0, buffer, size, block.size)
        size += block.size
    }

    /** Drops the inputs that no later output needs. */
    private fun trim() {
        val oldestNeeded = ceilDiv(nextOutput * down - half, up.toLong()).coerceAtLeast(base)
        val drop = (minOf(oldestNeeded, base + size) - base).toInt()
        if (drop >= TRIM_THRESHOLD) {
            System.arraycopy(buffer, drop, buffer, 0, size - drop)
            size -= drop
            base += drop
        }
    }

    private class FloatBuilder {
        private var data = FloatArray(INITIAL_CAPACITY)
        private var count = 0

        fun add(value: Float) {
            if (count == data.size) data = data.copyOf(data.size * 2)
            data[count++] = value
        }

        fun toArray(): FloatArray = data.copyOf(count)
    }

    /** The filter for one pair of rates: `up / down` is the reduced ratio, [half] the half length in taps. */
    private class Design(val up: Int, val down: Int, val half: Int, val taps: DoubleArray)

    public companion object {
        public const val TARGET_RATE: Int = 16_000

        private const val CUTOFF_HZ = 7_800.0
        private const val TRANSITION_HZ = 2_000.0
        private const val ATTENUATION_DB = 70.0
        private const val INITIAL_CAPACITY = 1 shl 12
        private const val TRIM_THRESHOLD = 1 shl 14

        private val designs = ConcurrentHashMap<Pair<Int, Int>, Design>()

        /**
         * How many input frames on each side of a window an output sample depends on, plus the
         * ratio's stride: reading this many extra frames before and after a window makes its outputs
         * identical to a one-pass conversion.
         */
        public fun spanFrames(inputRate: Int, outputRate: Int = TARGET_RATE): Int {
            val d = design(inputRate, outputRate)
            return ceilDiv((d.half + d.down).toLong(), d.up.toLong()).toInt() + 1
        }

        private fun design(inputRate: Int, outputRate: Int): Design = designs.getOrPut(inputRate to outputRate) {
            require(inputRate > 0 && outputRate > 0) { "rates must be positive" }
            val divisor = gcd(inputRate, outputRate)
            val up = outputRate / divisor
            val down = inputRate / divisor
            if (up == 1 && down == 1) return@getOrPut Design(1, 1, 0, doubleArrayOf(1.0))
            val rate = inputRate.toDouble() * up
            val limit = minOf(inputRate, outputRate) / 2.0
            val cutoff = if (limit > CUTOFF_HZ + TRANSITION_HZ / 2) CUTOFF_HZ else limit * LOW_RATE_CUTOFF
            val transition = if (limit > CUTOFF_HZ + TRANSITION_HZ / 2) TRANSITION_HZ else limit * LOW_RATE_TRANSITION
            val length = ceil((ATTENUATION_DB - KAISER_A) / (KAISER_B * transition / rate)).toInt() or 1
            val half = length / 2
            val beta = KAISER_BETA_SLOPE * (ATTENUATION_DB - KAISER_BETA_OFFSET)
            val norm = bessel0(beta)
            val taps = DoubleArray(2 * half + 1)
            val fraction = 2 * cutoff / rate
            var total = 0.0
            for (k in -half..half) {
                val sinc = if (k == 0) 1.0 else sin(PI * fraction * k) / (PI * fraction * k)
                val ratio = k.toDouble() / half
                val window = bessel0(beta * sqrt(1 - ratio * ratio)) / norm
                taps[k + half] = fraction * sinc * window
                total += taps[k + half]
            }
            // Unit gain at DC after the polyphase decomposition: every phase sums to about 1.
            for (i in taps.indices) taps[i] = taps[i] * up / total
            Design(up, down, half, taps)
        }

        private const val LOW_RATE_CUTOFF = 0.9
        private const val LOW_RATE_TRANSITION = 0.2
        private const val KAISER_A = 7.95
        private const val KAISER_B = 14.36
        private const val KAISER_BETA_SLOPE = 0.1102
        private const val KAISER_BETA_OFFSET = 8.7

        private fun bessel0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            while (term > BESSEL_EPSILON * sum) {
                val half = x / (2.0 * k)
                term *= half * half
                sum += term
                k++
            }
            return sum
        }

        private const val BESSEL_EPSILON = 1e-16

        private fun gcd(a: Int, b: Int): Int = if (b == 0) abs(a) else gcd(b, a % b)
    }
}

private fun ceilDiv(a: Long, b: Long): Long = -Math.floorDiv(-a, b)
