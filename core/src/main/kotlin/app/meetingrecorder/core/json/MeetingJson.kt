package app.meetingrecorder.core.json

import app.meetingrecorder.core.model.MeetingMetadata
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** Reads and writes `meeting.json`. Output is pretty-printed with a stable key order. */
public object MeetingJson {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    public fun encode(meeting: MeetingMetadata): String =
        json.encodeToString(MeetingMetadata.serializer(), meeting) + "\n"

    public fun decode(text: String): DecodeResult {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: SerializationException) {
            return DecodeResult.Malformed("not a JSON object: ${e.message}")
        } catch (e: IllegalArgumentException) {
            return DecodeResult.Malformed("not a JSON object: ${e.message}")
        }
        val version = schemaVersionOf(root)
            ?: return DecodeResult.Malformed("missing or non-integer schemaVersion")
        if (version != MeetingMetadata.SCHEMA_VERSION) {
            return DecodeResult.UnsupportedVersion(version)
        }
        return try {
            DecodeResult.Success(json.decodeFromJsonElement(MeetingMetadata.serializer(), root))
        } catch (e: SerializationException) {
            DecodeResult.Malformed(e.message ?: "invalid meeting.json")
        } catch (e: IllegalArgumentException) {
            DecodeResult.Malformed(e.message ?: "invalid meeting.json")
        }
    }

    private fun schemaVersionOf(root: JsonObject): Int? =
        (root["schemaVersion"] as? JsonPrimitive)?.intOrNull

    public sealed interface DecodeResult {
        public data class Success(val meeting: MeetingMetadata) : DecodeResult

        /** The file is valid JSON of a schema version this reader does not know. */
        public data class UnsupportedVersion(val version: Int) : DecodeResult

        public data class Malformed(val reason: String) : DecodeResult
    }
}
