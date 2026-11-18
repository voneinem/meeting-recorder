package app.meetingrecorder.core.transcript

import app.meetingrecorder.core.models.ModelOrigin
import kotlinx.serialization.Serializable

/**
 * In-memory form of `transcript.json` (schemaVersion 1, contracts/transcript.schema.json).
 *
 * Every position is a sample-frame index into `audio.wav` on the single timeline of `meeting.json`.
 * Milliseconds are derived through [app.meetingrecorder.core.time.Timeline] when the file is written
 * and are never stored here.
 */
public data class Transcript(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** ISO-8601 date-time with UTC offset. Informational only. */
    val generatedAt: String,
    val audio: TranscriptAudio,
    val model: TranscriptModel,
    val language: TranscriptLanguage,
    val engine: EngineInfo,
    val processing: ProcessingInfo? = null,
    val segments: List<TranscriptSegment>,
) {
    public companion object {
        public const val SCHEMA_VERSION: Int = 1
    }
}

@Serializable
public data class TranscriptAudio(
    val sampleRate: Int,
    val durationFrames: Long,
)

/** The model the transcript was decoded with. [id] is a catalog id or `imported-<first 12 hex of sha256>`. */
@Serializable
public data class TranscriptModel(
    val id: String,
    val name: String,
    val sha256: String,
    val origin: ModelOrigin,
)

/**
 * [requested] is what the user chose (`auto` or a code); [used] is the one language the whole
 * transcript was decoded in; [detected] is true when [requested] is `auto`.
 */
@Serializable
public data class TranscriptLanguage(
    val requested: String,
    val used: String,
    val detected: Boolean,
    val confidence: Double? = null,
)

@Serializable
public data class EngineInfo(
    val name: String = ENGINE_NAME,
    val version: String,
) {
    public companion object {
        public const val ENGINE_NAME: String = "whisper.cpp"
    }
}

/** Informational. [durationMs] is the compute time summed over all sessions of the job. */
@Serializable
public data class ProcessingInfo(
    val durationMs: Long,
    val vad: Boolean,
)

/**
 * One recognised piece of speech. [speaker] is always `null` in this phase; a reader accepts a string.
 * `startMs` and `endMs` are not properties: derive them with `Timeline.framesToMs`.
 */
public data class TranscriptSegment(
    val id: Int,
    val startFrame: Long,
    val endFrame: Long,
    val text: String,
    val speaker: String? = null,
)
