package app.meetingrecorder.core

import app.meetingrecorder.core.model.AudioInfo
import app.meetingrecorder.core.model.ChannelLayout
import app.meetingrecorder.core.model.Dropout
import app.meetingrecorder.core.model.FailureReason
import app.meetingrecorder.core.model.JobInfo
import app.meetingrecorder.core.model.JobState
import app.meetingrecorder.core.model.MeetingMetadata
import app.meetingrecorder.core.model.MeetingStatus
import app.meetingrecorder.core.model.MeetingTranscription
import app.meetingrecorder.core.model.Photo
import app.meetingrecorder.core.model.Source
import app.meetingrecorder.core.model.SourceSwitch
import app.meetingrecorder.core.model.SourceType
import app.meetingrecorder.core.model.SwitchReason
import app.meetingrecorder.core.model.TranscriptInfo

/** Meetings used by several tests; they match the files in `src/test/resources/golden`. */
object TestMeetings {

    const val SAMPLE_RATE = 48_000

    val builtInSource = Source(id = 0, name = "Built-in microphone", type = SourceType.BUILTIN, deliveredChannels = 1, channels = 1)

    /** One minute, mono, built-in microphone, complete. */
    val mono = MeetingMetadata(
        title = "2026-11-15 09:05",
        startedAt = "2026-11-15T09:05:12+01:00",
        status = MeetingStatus.COMPLETE,
        audio = AudioInfo(
            sampleRate = SAMPLE_RATE,
            channels = 1,
            channelLayout = ChannelLayout.MONO,
            durationFrames = 2_880_000,
            durationMs = 60_000,
        ),
        sources = listOf(builtInSource),
        initialSourceId = 0,
    )

    /** Five minutes, stereo, an external source that was unplugged at 30 s, two photos, one dropout. */
    val stereo = MeetingMetadata(
        title = "Weekly sync",
        startedAt = "2026-11-15T14:30:00+01:00",
        status = MeetingStatus.COMPLETE,
        audio = AudioInfo(
            sampleRate = SAMPLE_RATE,
            channels = 2,
            channelLayout = ChannelLayout.STEREO,
            durationFrames = 14_400_000,
            durationMs = 300_000,
        ),
        sources = listOf(
            Source(id = 0, name = "RØDE Wireless PRO", type = SourceType.USB, deliveredChannels = 2, channels = 2),
            Source(id = 1, name = "Built-in microphone", type = SourceType.BUILTIN, deliveredChannels = 1, channels = 1),
        ),
        initialSourceId = 0,
        sourceSwitches = listOf(
            SourceSwitch(
                atFrame = 1_440_000,
                atMs = 30_000,
                fromSourceId = 0,
                toSourceId = 1,
                reason = SwitchReason.DEVICE_REMOVED,
                channelsDuplicated = true,
            ),
        ),
        photos = listOf(
            Photo(file = "photos/photo-001.jpg", atFrame = 480_000, atMs = 10_000),
            Photo(file = "photos/photo-002.jpg", atFrame = 1_200_000, atMs = 25_000),
        ),
        dropouts = listOf(Dropout(atFrame = 2_400_000, atMs = 50_000, droppedFrames = 4_800)),
    )

    /** [mono] with a committed transcript and a failed re-transcription job (golden `meeting-with-transcription.json`). */
    val monoTranscribed = mono.copy(
        transcription = MeetingTranscription(
            transcript = TranscriptInfo(
                modelId = "small-q5_1",
                modelName = "Small (q5_1)",
                requestedLanguage = "auto",
                language = "de",
                completedAt = "2026-11-16T22:31:08+01:00",
                processingMs = 14_200,
                segmentCount = 4,
            ),
            job = JobInfo(
                state = JobState.FAILED,
                modelId = "medium-q5_0",
                requestedLanguage = "de",
                startedAt = "2026-11-16T22:40:00+01:00",
                failureReason = FailureReason.RECORDING_STARTED,
                failureMessage = "stopped because a recording was started",
                resumable = true,
            ),
        ),
    )

    fun resource(path: String): String =
        checkNotNull(TestMeetings::class.java.getResourceAsStream(path)) { "missing test resource $path" }
            .readBytes().toString(Charsets.UTF_8)
}
