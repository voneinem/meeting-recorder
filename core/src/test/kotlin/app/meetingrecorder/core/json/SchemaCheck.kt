package app.meetingrecorder.core.json

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion

/**
 * Validates `meeting.json` text against the documented `contracts/meeting.schema.json` so that the
 * schema and the Kotlin model cannot drift apart. The schema file is the single copy under `specs/`.
 */
object SchemaCheck {
    private val schema by lazy {
        val stream = checkNotNull(SchemaCheck::class.java.getResourceAsStream("/meeting.schema.json")) {
            "meeting.schema.json is not on the test classpath"
        }
        stream.use { SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(it) }
    }

    fun violations(json: String): List<String> =
        schema.validate(json, InputFormat.JSON).map { it.message }
}
