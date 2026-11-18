package app.meetingrecorder.core.transcript

import app.meetingrecorder.core.time.Timeline
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Reads and writes `transcript.json`. Output is pretty-printed with a stable key order, `speaker`
 * is always written (as `null` in this phase) and `startMs`/`endMs` are derived from the frames.
 * On read the frames win; the millisecond values are ignored.
 */
public object TranscriptJson {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = true
    }

    @Serializable
    private data class SegmentDto(
        val id: Int,
        val startFrame: Long,
        val endFrame: Long,
        val startMs: Long? = null,
        val endMs: Long? = null,
        val text: String,
        val speaker: String? = null,
    )

    @Serializable
    private data class TranscriptDto(
        val schemaVersion: Int = Transcript.SCHEMA_VERSION,
        val generatedAt: String,
        val audio: TranscriptAudio,
        val model: TranscriptModel,
        val language: TranscriptLanguage,
        val engine: EngineInfo,
        val processing: ProcessingInfo? = null,
        val segments: List<SegmentDto>,
    )

    public fun encode(transcript: Transcript): String {
        val rate = transcript.audio.sampleRate
        val dto = TranscriptDto(
            schemaVersion = transcript.schemaVersion,
            generatedAt = transcript.generatedAt,
            audio = transcript.audio,
            model = transcript.model,
            language = transcript.language,
            engine = transcript.engine,
            processing = transcript.processing,
            segments = transcript.segments.map {
                SegmentDto(
                    id = it.id,
                    startFrame = it.startFrame,
                    endFrame = it.endFrame,
                    startMs = Timeline.framesToMs(it.startFrame, rate),
                    endMs = Timeline.framesToMs(it.endFrame, rate),
                    text = it.text,
                    speaker = it.speaker,
                )
            },
        )
        // `processing` is optional in the schema (an object, never null): leave it out when absent.
        val tree = json.encodeToJsonElement(TranscriptDto.serializer(), dto).jsonObject
        val cleaned = JsonObject(tree.filterNot { (key, value) -> key == "processing" && value is JsonNull })
        return json.encodeToString(JsonObject.serializer(), cleaned) + "\n"
    }

    public fun decode(text: String): DecodeResult {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: SerializationException) {
            return DecodeResult.Malformed("not a JSON object: ${e.message}")
        } catch (e: IllegalArgumentException) {
            return DecodeResult.Malformed("not a JSON object: ${e.message}")
        }
        val version = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull
            ?: return DecodeResult.Malformed("missing or non-integer schemaVersion")
        if (version != Transcript.SCHEMA_VERSION) return DecodeResult.UnsupportedVersion(version)
        return try {
            val dto = json.decodeFromJsonElement(TranscriptDto.serializer(), root)
            DecodeResult.Success(
                Transcript(
                    schemaVersion = dto.schemaVersion,
                    generatedAt = dto.generatedAt,
                    audio = dto.audio,
                    model = dto.model,
                    language = dto.language,
                    engine = dto.engine,
                    processing = dto.processing,
                    segments = dto.segments.map { TranscriptSegment(it.id, it.startFrame, it.endFrame, it.text, it.speaker) },
                ),
            )
        } catch (e: SerializationException) {
            DecodeResult.Malformed(e.message ?: "invalid transcript.json")
        } catch (e: IllegalArgumentException) {
            DecodeResult.Malformed(e.message ?: "invalid transcript.json")
        }
    }

    public sealed interface DecodeResult {
        public data class Success(val transcript: Transcript) : DecodeResult

        /** The file is valid JSON of a schema version this reader does not know. */
        public data class UnsupportedVersion(val version: Int) : DecodeResult

        public data class Malformed(val reason: String) : DecodeResult
    }
}
