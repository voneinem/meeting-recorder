package app.meetingrecorder.core.json

import app.meetingrecorder.core.model.AudioInfo
import app.meetingrecorder.core.model.ChannelLayout
import app.meetingrecorder.core.model.MeetingMetadata
import app.meetingrecorder.core.model.MeetingStatus
import app.meetingrecorder.core.time.Timeline
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Checks a decoded [MeetingMetadata] against the validation rules of data-model.md.
 * A meeting with violations is still listed by the app with what can be determined.
 */
public object MeetingValidator {

    public data class Violation(val rule: String, val message: String)

    private val photoFile = Regex("""^photos/photo-(\d{3,})\.jpg$""")

    public fun validate(meeting: MeetingMetadata): List<Violation> {
        val violations = mutableListOf<Violation>()
        fun fail(rule: String, message: String) {
            violations += Violation(rule, message)
        }

        if (meeting.schemaVersion != MeetingMetadata.SCHEMA_VERSION) {
            fail("schemaVersion", "unsupported schemaVersion ${meeting.schemaVersion}")
        }
        val trimmed = meeting.title.trim()
        if (trimmed.isEmpty() || trimmed.length > MeetingMetadata.MAX_TITLE_LENGTH) {
            fail("title", "title must be 1..${MeetingMetadata.MAX_TITLE_LENGTH} characters after trimming")
        }
        try {
            OffsetDateTime.parse(meeting.startedAt)
        } catch (_: DateTimeParseException) {
            fail("startedAt", "startedAt is not an ISO-8601 date-time with offset: ${meeting.startedAt}")
        }

        val audio = meeting.audio
        validateAudio(audio, ::fail)
        val inProgress = meeting.status == MeetingStatus.RECORDING
        val sampleRate = if (audio.sampleRate > 0) audio.sampleRate else null

        // Sources.
        val ids = meeting.sources.map { it.id }
        if (meeting.sources.isEmpty()) fail("sources", "at least one source is required")
        if (ids.toSet().size != ids.size) fail("sources", "source ids must be unique")
        if (ids.any { it < 0 }) fail("sources", "source ids must not be negative")
        for (source in meeting.sources) {
            if (source.name.isBlank()) fail("sources", "source ${source.id} has an empty name")
            if (source.deliveredChannels < 1) fail("sources", "source ${source.id}: deliveredChannels must be >= 1")
            if (source.channels !in 1..2) fail("sources", "source ${source.id}: channels must be 1 or 2")
            if (source.channels > source.deliveredChannels) {
                fail("sources", "source ${source.id}: channels exceeds deliveredChannels")
            }
        }
        if (meeting.initialSourceId !in ids) fail("sources", "initialSourceId ${meeting.initialSourceId} is unknown")

        // Source switches.
        checkPositions("sourceSwitches", meeting.sourceSwitches.map { it.atFrame to it.atMs }, audio, sampleRate, inProgress, ::fail)
        for (switch in meeting.sourceSwitches) {
            if (switch.fromSourceId !in ids) fail("sourceSwitches", "unknown fromSourceId ${switch.fromSourceId}")
            if (switch.toSourceId !in ids) fail("sourceSwitches", "unknown toSourceId ${switch.toSourceId}")
            if (switch.fromSourceId == switch.toSourceId) {
                fail("sourceSwitches", "a switch must change the source (id ${switch.fromSourceId})")
            }
        }

        // Dropouts.
        checkPositions("dropouts", meeting.dropouts.map { it.atFrame to it.atMs }, audio, sampleRate, inProgress, ::fail)
        for (dropout in meeting.dropouts) {
            if (dropout.droppedFrames <= 0) fail("dropouts", "droppedFrames must be > 0 (at ${dropout.atFrame})")
        }

        // Photos.
        checkPositions("photos", meeting.photos.map { it.atFrame to it.atMs }, audio, sampleRate, inProgress, ::fail)
        var previousNumber = 0
        for ((index, photo) in meeting.photos.withIndex()) {
            val number = photoFile.matchEntire(photo.file)?.groupValues?.get(1)?.toIntOrNull()
            if (number == null) {
                fail("photos", "photo file name must match photos/photo-NNN.jpg: ${photo.file}")
                continue
            }
            if (index == 0 && number != 1) fail("photos", "the first photo must be photo-001.jpg, was ${photo.file}")
            if (number <= previousNumber) fail("photos", "photo numbers must strictly increase: ${photo.file}")
            previousNumber = number
        }

        return violations
    }

    private fun validateAudio(audio: AudioInfo, fail: (String, String) -> Unit) {
        if (audio.file != AudioInfo.AUDIO_FILE_NAME) fail("audio", "audio.file must be ${AudioInfo.AUDIO_FILE_NAME}")
        if (audio.format != AudioInfo.AUDIO_FORMAT) fail("audio", "audio.format must be ${AudioInfo.AUDIO_FORMAT}")
        if (audio.sampleRate <= 0) fail("audio", "sampleRate must be > 0")
        if (audio.channels !in 1..2) fail("audio", "channels must be 1 or 2")
        val expectedLayout = if (audio.channels == 1) ChannelLayout.MONO else ChannelLayout.STEREO
        if (audio.channels in 1..2 && audio.channelLayout != expectedLayout) {
            fail("audio", "channelLayout ${audio.channelLayout} does not match channels ${audio.channels}")
        }
        if (audio.durationFrames < 0) fail("audio", "durationFrames must be >= 0")
        if (audio.sampleRate > 0 && audio.durationFrames >= 0) {
            val expectedMs = Timeline.framesToMs(audio.durationFrames, audio.sampleRate)
            if (audio.durationMs != expectedMs) {
                fail("audio", "durationMs ${audio.durationMs} does not match durationFrames (expected $expectedMs)")
            }
        }
    }

    /**
     * Rules 3 and 4: every position is on the timeline (not checked while the meeting is still being
     * recorded, because the duration on disk is only written at stop), lists are ascending and `atMs`
     * equals the value derived from `atFrame`.
     */
    private fun checkPositions(
        list: String,
        positions: List<Pair<Long, Long>>,
        audio: AudioInfo,
        sampleRate: Int?,
        inProgress: Boolean,
        fail: (String, String) -> Unit,
    ) {
        var previous = -1L
        for ((atFrame, atMs) in positions) {
            if (atFrame < 0) fail(list, "atFrame must not be negative: $atFrame")
            if (!inProgress && atFrame >= 0 && !Timeline.isValidPosition(atFrame, audio.durationFrames)) {
                fail(list, "atFrame $atFrame is outside the timeline [0, ${audio.durationFrames}]")
            }
            if (atFrame < previous) fail(list, "entries must be sorted by atFrame ($atFrame after $previous)")
            previous = atFrame
            if (sampleRate != null && atFrame >= 0) {
                val expectedMs = Timeline.framesToMs(atFrame, sampleRate)
                if (atMs != expectedMs) fail(list, "atMs $atMs does not match atFrame $atFrame (expected $expectedMs)")
            }
        }
    }
}
