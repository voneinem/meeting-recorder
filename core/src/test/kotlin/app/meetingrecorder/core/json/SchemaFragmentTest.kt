package app.meetingrecorder.core.json

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `meeting.schema.json` is the file `core` reads. The Phase 2 standalone fragment
 * (`meeting-transcription.schema.json`) must stay identical to its `$defs/transcription`, apart from
 * the documentation keys.
 */
class SchemaFragmentTest {

    private fun resource(name: String): String =
        checkNotNull(SchemaFragmentTest::class.java.getResourceAsStream(name)) { "missing test resource $name" }
            .readBytes().toString(Charsets.UTF_8)

    private fun withoutDocs(o: JsonObject, vararg extra: String): JsonObject =
        JsonObject(o.filterKeys { it !in setOf("\$schema", "\$id", "title", "description") + extra })

    @Test
    fun `the standalone fragment equals the merged definition`() {
        val fragment = Json.parseToJsonElement(resource("/meeting-transcription.schema.json")).jsonObject
        val merged = Json.parseToJsonElement(resource("/meeting.schema.json")).jsonObject
        val definition = merged["\$defs"]!!.jsonObject["transcription"]!!.jsonObject
        assertEquals(withoutDocs(fragment), withoutDocs(definition))
        assertEquals("#/\$defs/transcription", merged["properties"]!!.jsonObject["transcription"]!!.jsonObject["\$ref"]!!.toString().trim('"'))
        assertEquals("1", merged["properties"]!!.jsonObject["schemaVersion"]!!.jsonObject["const"]!!.toString())
    }

    @Test
    fun `the shipped example validates against the fragment`() {
        val schema = checkNotNull(SchemaFragmentTest::class.java.getResourceAsStream("/meeting-transcription.schema.json")).use {
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(it)
        }
        val example = resource("/examples/meeting-transcription.example.json")
        assertEquals(emptyList(), schema.validate(example, InputFormat.JSON).map { it.message })
        val broken = example.replace("\"language\": \"de\"", "\"language\": \"DE\"")
        assertTrue(schema.validate(broken, InputFormat.JSON).isNotEmpty())
    }
}
