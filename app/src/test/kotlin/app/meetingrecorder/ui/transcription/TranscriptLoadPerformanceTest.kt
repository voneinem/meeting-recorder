package app.meetingrecorder.ui.transcription

import app.meetingrecorder.core.model.Photo
import app.meetingrecorder.core.models.ModelOrigin
import app.meetingrecorder.core.transcript.EngineInfo
import app.meetingrecorder.core.transcript.Transcript
import app.meetingrecorder.core.transcript.TranscriptAudio
import app.meetingrecorder.core.transcript.TranscriptJson
import app.meetingrecorder.core.transcript.TranscriptLanguage
import app.meetingrecorder.core.transcript.TranscriptModel
import app.meetingrecorder.core.transcript.TranscriptSegment
import app.meetingrecorder.core.transcript.TranscriptTimeline
import app.meetingrecorder.core.transcript.TranscriptValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A guard for SC-009 (opening a long meeting stays quick); the real number is measured on the phone in S15. */
class TranscriptLoadPerformanceTest {
    private val sampleRate = 48_000
    private val hours3 = 3L * 3600 * sampleRate

    private fun transcript(): Transcript {
        val step = hours3 / 3000
        val segments = (0 until 3000).map { i ->
            TranscriptSegment(i, i * step, i * step + step * 3 / 4, "Segment number $i with a sentence of a typical length for spoken text.")
        }
        return Transcript(
            generatedAt = "2026-11-17T09:00:00+01:00",
            audio = TranscriptAudio(sampleRate, hours3),
            model = TranscriptModel("small-q5_1", "Small", "a".repeat(64), ModelOrigin.CATALOG),
            language = TranscriptLanguage("auto", "de", true, 0.95),
            engine = EngineInfo(version = "1.9.5"),
            segments = segments,
        )
    }

    private fun photos(): List<Photo> = (0 until 60).map { i ->
        val frame = (i + 1) * hours3 / 61
        Photo("photos/photo-${"%03d".format(i + 1)}.jpg", frame, frame * 1000 / sampleRate)
    }

    @Test
    fun aThreeHourTranscriptWithSixtyPhotosDecodesValidatesAndInterleavesInUnderHalfASecond() {
        val json = TranscriptJson.encode(transcript())
        val photos = photos()
        TranscriptJson.decode(json) // warm up the JIT: the first decode of a process is not what a user waits for after opening

        val started = System.nanoTime()
        val decoded = (TranscriptJson.decode(json) as TranscriptJson.DecodeResult.Success).transcript
        val violations = TranscriptValidator.validate(decoded)
        val items = TranscriptTimeline.interleave(decoded.segments, photos)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(emptyList(), violations)
        assertEquals(3000 + 60, items.size)
        assertTrue(elapsedMs < 500, "decode, validate and interleave took $elapsedMs ms")
    }
}
