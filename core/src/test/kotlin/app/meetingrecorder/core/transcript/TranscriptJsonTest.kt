package app.meetingrecorder.core.transcript

import app.meetingrecorder.core.TestMeetings
import app.meetingrecorder.core.TestTranscripts
import app.meetingrecorder.core.json.SchemaCheck
import app.meetingrecorder.core.transcript.TranscriptJson.DecodeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TranscriptJsonTest {

    private val golden = TestMeetings.resource("/golden/transcript-german.json")
    private val example = TestMeetings.resource("/examples/transcript.example.json")

    @Test
    fun `encoding reproduces the golden bytes`() {
        assertEquals(golden, TranscriptJson.encode(TestTranscripts.german))
    }

    @Test
    fun `the golden writes speaker null and derives startMs and endMs`() {
        assertTrue("\"speaker\": null" in golden)
        assertTrue("\"startMs\": 4200" in golden)
        assertTrue("\"endMs\": 9800" in golden)
        assertEquals(4, Regex("\"speaker\": null").findAll(TranscriptJson.encode(TestTranscripts.german)).count())
    }

    @Test
    fun `the golden and the shipped example decode to the sample transcript`() {
        assertEquals(DecodeResult.Success(TestTranscripts.german), TranscriptJson.decode(golden))
        assertEquals(DecodeResult.Success(TestTranscripts.german), TranscriptJson.decode(example))
    }

    @Test
    fun `the golden and the shipped example validate against the schema`() {
        assertEquals(emptyList(), SchemaCheck.transcriptViolations(golden))
        assertEquals(emptyList(), SchemaCheck.transcriptViolations(example))
        assertEquals(emptyList(), SchemaCheck.transcriptViolations(TranscriptJson.encode(TestTranscripts.german)))
        assertEquals(emptyList(), TranscriptValidator.validate((TranscriptJson.decode(golden) as DecodeResult.Success).transcript))
    }

    @Test
    fun `the schema rejects a broken sample`() {
        assertTrue(SchemaCheck.transcriptViolations(golden.replace(TestTranscripts.SHA, "abc")).isNotEmpty())
        assertTrue(SchemaCheck.transcriptViolations(golden.replace("\"origin\": \"catalog\"", "\"origin\": \"cloud\"")).isNotEmpty())
        assertTrue(SchemaCheck.transcriptViolations(golden.replace("\"used\": \"de\"", "\"used\": \"auto\"")).isNotEmpty())
        assertTrue(SchemaCheck.transcriptViolations(golden.replace("\"segments\"", "\"parts\"")).isNotEmpty())
    }

    @Test
    fun `on read the frames win when startMs disagrees`() {
        val skewed = golden.replace("\"startMs\": 4200", "\"startMs\": 99999").replace("\"endMs\": 9800", "\"endMs\": 1")
        assertEquals(DecodeResult.Success(TestTranscripts.german), TranscriptJson.decode(skewed))
        assertEquals(golden, TranscriptJson.encode((TranscriptJson.decode(skewed) as DecodeResult.Success).transcript))
    }

    @Test
    fun `a file without startMs and endMs still decodes`() {
        val bare = golden.replace(Regex("\\s*\"(startMs|endMs)\": \\d+,"), "")
        assertEquals(DecodeResult.Success(TestTranscripts.german), TranscriptJson.decode(bare))
    }

    @Test
    fun `unknown keys are ignored`() {
        val text = golden
            .replace("\"schemaVersion\": 1,", "\"schemaVersion\": 1,\n  \"futureField\": {\"a\": 1},")
            .replace("\"vad\": false", "\"vad\": false,\n    \"extra\": true")
        assertEquals(DecodeResult.Success(TestTranscripts.german), TranscriptJson.decode(text))
    }

    @Test
    fun `a string speaker is accepted and kept`() {
        val text = golden.replaceFirst("\"speaker\": null", "\"speaker\": \"Anna\"")
        val decoded = (TranscriptJson.decode(text) as DecodeResult.Success).transcript
        assertEquals("Anna", decoded.segments.first().speaker)
        assertEquals(emptyList(), TranscriptValidator.validate(decoded))
        assertTrue("\"speaker\": \"Anna\"" in TranscriptJson.encode(decoded))
    }

    @Test
    fun `an absent processing block is omitted and a null confidence is written`() {
        val noProcessing = TestTranscripts.german.copy(
            processing = null,
            language = TestTranscripts.german.language.copy(confidence = null),
        )
        val text = TranscriptJson.encode(noProcessing)
        assertTrue("processing" !in text)
        assertTrue("\"confidence\": null" in text)
        assertEquals(emptyList(), SchemaCheck.transcriptViolations(text))
        assertEquals(DecodeResult.Success(noProcessing), TranscriptJson.decode(text))
    }

    @Test
    fun `an empty segment list round-trips`() {
        val empty = TestTranscripts.german.copy(segments = emptyList())
        val text = TranscriptJson.encode(empty)
        assertTrue("\"segments\": []" in text)
        assertEquals(DecodeResult.Success(empty), TranscriptJson.decode(text))
    }

    @Test
    fun `an unknown schema version is rejected`() {
        assertEquals(DecodeResult.UnsupportedVersion(2), TranscriptJson.decode(golden.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")))
    }

    @Test
    fun `unreadable input is reported as malformed`() {
        assertIs<DecodeResult.Malformed>(TranscriptJson.decode(""))
        assertIs<DecodeResult.Malformed>(TranscriptJson.decode(golden.take(golden.length / 2)))
        assertIs<DecodeResult.Malformed>(TranscriptJson.decode("[]"))
        assertIs<DecodeResult.Malformed>(TranscriptJson.decode("{\"title\": \"no version\"}"))
        assertIs<DecodeResult.Malformed>(TranscriptJson.decode("{\"schemaVersion\": 1}"))
        assertIs<DecodeResult.Malformed>(TranscriptJson.decode(golden.replace("\"origin\": \"catalog\"", "\"origin\": \"cloud\"")))
    }
}
