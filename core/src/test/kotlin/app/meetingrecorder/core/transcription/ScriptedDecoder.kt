package app.meetingrecorder.core.transcription

import app.meetingrecorder.core.audio.WindowSource
import app.meetingrecorder.core.model.FailureReason
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A segment of the script, in absolute centiseconds of the audio. */
data class ScriptedSegment(val startCs: Long, val endCs: Long, val text: String)

/**
 * A [WindowSource] that hands out silent windows of the right size and remembers where the last
 * one started, so that [ScriptedDecoder] can answer in absolute time. Nothing is read from a file.
 */
class FakeWindowSource(
    private val sampleRate: Int,
    private val rmsOf: (Long, Long) -> Double = { _, _ -> 0.1 },
) : WindowSource {
    var lastFrom: Long = 0
        private set
    val reads = mutableListOf<Pair<Long, Long>>()
    var failWith: Exception? = null

    private fun outputIndex(frame: Long) = -Math.floorDiv(-frame * 16_000, sampleRate.toLong())

    override fun read(fromFrame: Long, toFrame: Long, minSamples: Int): PcmWindow {
        failWith?.let { throw it }
        lastFrom = fromFrame
        reads += fromFrame to toFrame
        val count = maxOf((outputIndex(toFrame) - outputIndex(fromFrame)).toInt(), minSamples)
        val buffer = ByteBuffer.allocateDirect(count * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
        return PcmWindow(buffer, count)
    }

    override fun rms(fromFrame: Long, toFrame: Long): Double = rmsOf(fromFrame, toFrame)
}

/**
 * A fake decoder that answers from a script of absolute times whatever the window boundaries are,
 * like an ideal recogniser: a segment cut by the end of the window is returned cut at the window
 * end. It can throw or fail after N calls and honours [abort] after each segment.
 */
class ScriptedDecoder(
    private val script: List<ScriptedSegment>,
    private val sampleRate: Int,
    private val source: FakeWindowSource,
    private val languages: List<LanguageProbability> = listOf(LanguageProbability("de", 0.9), LanguageProbability("en", 0.1)),
    private val throwOnCall: Int? = null,
    private val failOnCall: Pair<Int, FailureReason>? = null,
) : SpeechDecoder {
    /** Window start and sample count of every decode call. */
    val windows = mutableListOf<Pair<Long, Int>>()
    val requests = mutableListOf<DecodeRequest>()
    var detectCalls = 0
        private set
    var abortCalls = 0
        private set
    var closed = false
        private set

    @Volatile
    private var aborted = false

    override fun detectLanguage(window: PcmWindow): List<LanguageProbability> {
        detectCalls++
        return languages
    }

    override fun decode(window: PcmWindow, request: DecodeRequest, sink: SegmentSink): DecodeOutcome {
        val call = windows.size + 1
        windows += source.lastFrom to window.count
        requests += request
        aborted = false
        if (throwOnCall == call) throw IllegalStateException("scripted failure in call $call")
        failOnCall?.let { if (it.first == call) return DecodeOutcome.Failed(it.second) }

        val fromCs = source.lastFrom * 100 / sampleRate
        val toCs = fromCs + window.count * 100L / 16_000
        for (segment in script) {
            if (segment.endCs <= fromCs || segment.startCs >= toCs) continue
            sink.onSegment(
                RawSegment(maxOf(segment.startCs, fromCs) - fromCs, minOf(segment.endCs, toCs) - fromCs, " " + segment.text),
            )
            if (aborted) return DecodeOutcome.Aborted
        }
        return if (aborted) DecodeOutcome.Aborted else DecodeOutcome.Completed
    }

    override fun abort() {
        abortCalls++
        aborted = true
    }

    override fun close() {
        closed = true
    }
}
