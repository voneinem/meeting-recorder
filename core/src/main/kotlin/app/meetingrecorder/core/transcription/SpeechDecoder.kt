package app.meetingrecorder.core.transcription

import app.meetingrecorder.core.model.FailureReason
import java.nio.FloatBuffer

/**
 * 16 kHz mono samples of one window. [buffer] MUST be a direct buffer (the native engine reads it
 * without copying); [count] is the number of valid samples from position 0.
 */
public class PcmWindow(public val buffer: FloatBuffer, public val count: Int) {
    init {
        require(buffer.isDirect) { "the sample buffer must be direct" }
        require(count in 0..buffer.capacity()) { "count $count is outside the buffer (${buffer.capacity()})" }
    }
}

/** The probability of one language for a 30 s probe window. */
public data class LanguageProbability(val code: String, val probability: Double)

/** [language] is a language code; [initialPrompt] is `null` unless the spike decided to carry text across windows. */
public data class DecodeRequest(
    val language: String,
    val threads: Int,
    val initialPrompt: String?,
    val vad: Boolean,
)

/** A finished segment as the engine reports it: centiseconds relative to the window start. */
public data class RawSegment(val startCs: Long, val endCs: Long, val text: String)

public fun interface SegmentSink {
    public fun onSegment(segment: RawSegment)
}

public sealed interface DecodeOutcome {
    public data object Completed : DecodeOutcome

    public data object Aborted : DecodeOutcome

    public data class Failed(val reason: FailureReason) : DecodeOutcome
}

/** Polled between windows. `:core` uses no coroutines; the app adapts its job cancellation to this. */
public fun interface CancelSignal {
    public fun isCancelled(): Boolean
}

/**
 * The boundary to the speech engine (contracts/transcription-service.md). The implementation lives
 * in the app module over the `:whisper` module; the pipeline is tested with a scripted fake.
 */
public interface SpeechDecoder : AutoCloseable {
    /** Probability per language for one 30 s window. */
    public fun detectLanguage(window: PcmWindow): List<LanguageProbability>

    /** Decodes one window; the sink gets each finished segment. Returns when done, aborted or failed. */
    public fun decode(window: PcmWindow, request: DecodeRequest, sink: SegmentSink): DecodeOutcome

    /** Thread-safe: makes a running [decode] return [DecodeOutcome.Aborted] soon. */
    public fun abort()
}
