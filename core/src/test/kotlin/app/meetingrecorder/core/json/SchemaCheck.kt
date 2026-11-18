package app.meetingrecorder.core.json

import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion

/**
 * Validates JSON text against the documented schemas so that the schema and the Kotlin model cannot
 * drift apart. The schema files are the single copies under `specs/`: `meeting.schema.json`
 * (Phase 1, with the Phase 2 `transcription` definition merged in) and `transcript.schema.json`.
 */
object SchemaCheck {
    private fun load(name: String): Schema {
        val stream = checkNotNull(SchemaCheck::class.java.getResourceAsStream(name)) {
            "$name is not on the test classpath"
        }
        return stream.use { SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(it) }
    }

    private val meetingSchema by lazy { load("/meeting.schema.json") }
    private val transcriptSchema by lazy { load("/transcript.schema.json") }

    /** Violations of `meeting.schema.json`. */
    fun violations(json: String): List<String> =
        meetingSchema.validate(json, InputFormat.JSON).map { it.message }

    /** Violations of `transcript.schema.json`. */
    fun transcriptViolations(json: String): List<String> =
        transcriptSchema.validate(json, InputFormat.JSON).map { it.message }
}
