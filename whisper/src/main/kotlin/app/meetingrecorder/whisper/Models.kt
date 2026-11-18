package app.meetingrecorder.whisper

import java.io.File

/**
 * Options of [WhisperContext.load]. Flash attention is on by default: on the reference phone it decodes about 15% faster
 * and needs 136 MB less memory (spike S-1).
 */
data class LoadOptions(val flashAttention: Boolean = true)

/** Parameters of one [WhisperContext.decode] call. */
data class DecodeParams(
    /** ISO 639-1 code of the spoken language; the engine is never asked to detect it here. */
    val language: String,
    val threads: Int,
    val initialPrompt: String? = null,
    val suppressNonSpeech: Boolean = true,
    /** Voice activity model, or null to decode every sample. */
    val vadModel: File? = null,
)

/** A segment as the engine reports it: positions in centiseconds from the start of the decoded samples. */
data class RawSegment(val startCs: Long, val endCs: Long, val text: String)

/** One entry of the language detection result. */
data class LanguageProbability(val code: String, val probability: Float)

enum class DecodeStatus { COMPLETED, ABORTED }
