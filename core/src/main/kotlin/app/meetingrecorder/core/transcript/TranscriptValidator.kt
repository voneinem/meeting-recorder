package app.meetingrecorder.core.transcript

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** The rules of contracts/transcript-folder.md beyond the JSON schema. */
public object TranscriptValidator {

    public data class Violation(val rule: String, val message: String)

    private val sha256 = Regex("^[0-9a-f]{64}$")
    private val languageCode = Regex("^[a-z]{2,3}$")
    private val requestedLanguage = Regex("^(auto|[a-z]{2,3})$")

    public fun validate(transcript: Transcript): List<Violation> {
        val violations = mutableListOf<Violation>()
        fun fail(rule: String, message: String) {
            violations += Violation(rule, message)
        }

        if (transcript.schemaVersion != Transcript.SCHEMA_VERSION) {
            fail("schemaVersion", "unsupported schemaVersion ${transcript.schemaVersion}")
        }
        try {
            OffsetDateTime.parse(transcript.generatedAt)
        } catch (_: DateTimeParseException) {
            fail("generatedAt", "generatedAt is not an ISO-8601 date-time with offset: ${transcript.generatedAt}")
        }
        if (transcript.audio.sampleRate < 1) fail("audio", "sampleRate must be >= 1")
        if (transcript.audio.durationFrames < 0) fail("audio", "durationFrames must be >= 0")

        val model = transcript.model
        if (model.id.isEmpty()) fail("model", "model.id must not be empty")
        if (model.name.isEmpty()) fail("model", "model.name must not be empty")
        if (!sha256.matches(model.sha256)) fail("model", "model.sha256 must be 64 lower-case hex digits")

        val language = transcript.language
        if (!requestedLanguage.matches(language.requested)) {
            fail("language", "language.requested must be auto or a language code: ${language.requested}")
        }
        if (!languageCode.matches(language.used)) {
            fail("language", "language.used must be a language code and never auto: ${language.used}")
        }
        language.confidence?.let {
            if (it.isNaN() || it < 0.0 || it > 1.0) fail("language", "language.confidence must be within 0..1")
        }

        if (transcript.engine.name != EngineInfo.ENGINE_NAME) {
            fail("engine", "engine.name must be ${EngineInfo.ENGINE_NAME}")
        }
        if (transcript.engine.version.isEmpty()) fail("engine", "engine.version must not be empty")
        transcript.processing?.let {
            if (it.durationMs < 0) fail("processing", "processing.durationMs must be >= 0")
        }

        validateSegments(transcript, ::fail)
        return violations
    }

    private fun validateSegments(transcript: Transcript, fail: (String, String) -> Unit) {
        val duration = transcript.audio.durationFrames
        var previousStart = Long.MIN_VALUE
        for ((index, segment) in transcript.segments.withIndex()) {
            if (segment.id != index) fail("segments.id", "segment at index $index has id ${segment.id}, expected $index")
            if (segment.startFrame < 0 || segment.startFrame > segment.endFrame || segment.endFrame > duration) {
                fail(
                    "segments.range",
                    "segment $index: need 0 <= startFrame <= endFrame <= durationFrames " +
                        "(${segment.startFrame}, ${segment.endFrame}, $duration)",
                )
            }
            if (segment.startFrame < previousStart) {
                fail("segments.order", "segment $index starts at ${segment.startFrame}, before the previous segment ($previousStart)")
            }
            previousStart = segment.startFrame
            if (segment.text.isEmpty() || segment.text != segment.text.trim()) {
                fail("segments.text", "segment $index: text must be trimmed and not empty")
            }
        }
    }
}
