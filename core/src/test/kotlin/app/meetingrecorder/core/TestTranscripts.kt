package app.meetingrecorder.core

import app.meetingrecorder.core.models.ModelOrigin
import app.meetingrecorder.core.model.MeetingMetadata
import app.meetingrecorder.core.model.Photo
import app.meetingrecorder.core.transcript.EngineInfo
import app.meetingrecorder.core.transcript.ProcessingInfo
import app.meetingrecorder.core.transcript.Transcript
import app.meetingrecorder.core.transcript.TranscriptAudio
import app.meetingrecorder.core.transcript.TranscriptLanguage
import app.meetingrecorder.core.transcript.TranscriptModel
import app.meetingrecorder.core.transcript.TranscriptSegment

/** The transcript of `contracts/examples/transcript.example.json` and the meeting it belongs to. */
object TestTranscripts {

    const val SHA = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"

    val german = Transcript(
        generatedAt = "2026-11-16T22:31:08+01:00",
        audio = TranscriptAudio(sampleRate = 48_000, durationFrames = 960_000),
        model = TranscriptModel(id = "small-q5_1", name = "Small (q5_1)", sha256 = SHA, origin = ModelOrigin.CATALOG),
        language = TranscriptLanguage(requested = "auto", used = "de", detected = true, confidence = 0.97),
        engine = EngineInfo(version = "1.9.5"),
        processing = ProcessingInfo(durationMs = 14_200, vad = false),
        segments = listOf(
            TranscriptSegment(0, 0, 201_600, "Guten Morgen zusammen, wir fangen mit dem Rückblick an."),
            TranscriptSegment(1, 201_600, 470_400, "Die Zahlen aus dem letzten Quartal sehen gut aus."),
            TranscriptSegment(2, 576_000, 744_000, "Hier auf dem Whiteboard sieht man die neue Planung."),
            TranscriptSegment(3, 744_000, 912_000, "Gibt es dazu Fragen?"),
        ),
    )

    /** Twenty seconds, mono, "Weekly sync", photos at frames 300000, 576000 and 912000. */
    val germanMeeting: MeetingMetadata = TestMeetings.mono.copy(
        title = "Weekly sync",
        audio = TestMeetings.mono.audio.copy(durationFrames = 960_000, durationMs = 20_000),
        photos = listOf(
            Photo("photos/photo-001.jpg", 300_000, 6_250),
            Photo("photos/photo-002.jpg", 576_000, 12_000),
            Photo("photos/photo-003.jpg", 912_000, 19_000),
        ),
    )
}
