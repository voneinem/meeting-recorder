package app.meetingrecorder.core.model

import app.meetingrecorder.core.channels.ChannelPolicy
import app.meetingrecorder.core.folder.MeetingFolderLayout
import app.meetingrecorder.core.time.Timeline
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Describes a microphone before it gets an id in a meeting. */
public data class SourceInfo(
    val name: String,
    val type: SourceType,
    val deliveredChannels: Int,
)

/** Pure functions producing new [MeetingMetadata] values; nothing here touches files. */
public object MeetingEditor {

    /**
     * A new meeting in state `recording`. A blank [title] becomes the start date and time; the
     * channel layout of the audio is fixed by the initial source.
     */
    public fun create(
        title: String?,
        startedAt: OffsetDateTime,
        sampleRate: Int,
        initialSource: SourceInfo,
    ): MeetingMetadata {
        require(sampleRate > 0) { "sampleRate must be positive" }
        val effectiveTitle = normalizeTitle(title?.takeIf { it.isNotBlank() }
            ?: MeetingFolderLayout.defaultTitle(startedAt.toLocalDateTime()))
        val source = toSource(0, initialSource)
        return MeetingMetadata(
            title = effectiveTitle,
            startedAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(startedAt),
            status = MeetingStatus.RECORDING,
            audio = AudioInfo(
                sampleRate = sampleRate,
                channels = source.channels,
                channelLayout = if (source.channels == 1) ChannelLayout.MONO else ChannelLayout.STEREO,
                durationFrames = 0,
                durationMs = 0,
            ),
            sources = listOf(source),
            initialSourceId = source.id,
        )
    }

    /** Returns the meeting with [info] as a known source, and the source's id (existing or new). */
    public fun addSource(meeting: MeetingMetadata, info: SourceInfo): Pair<MeetingMetadata, Int> {
        val recorded = ChannelPolicy.recordedChannels(info.type, info.deliveredChannels)
        meeting.sources.firstOrNull {
            it.name == info.name && it.type == info.type && it.deliveredChannels == info.deliveredChannels && it.channels == recorded
        }?.let { return meeting to it.id }
        val id = (meeting.sources.maxOfOrNull { it.id } ?: -1) + 1
        return meeting.copy(sources = meeting.sources + toSource(id, info)) to id
    }

    public fun addPhoto(meeting: MeetingMetadata, atFrame: Long): MeetingMetadata {
        requirePosition(meeting, atFrame, meeting.photos.lastOrNull()?.atFrame)
        val number = MeetingFolderLayout.nextPhotoNumber(meeting.photos.map { it.file.substringAfterLast('/') })
        val photo = Photo(
            file = MeetingFolderLayout.photoPath(number),
            atFrame = atFrame,
            atMs = Timeline.framesToMs(atFrame, meeting.audio.sampleRate),
        )
        return meeting.copy(photos = meeting.photos + photo)
    }

    /**
     * Logs a change from [fromSourceId] to [toSourceId] at [atFrame]. `channelsDuplicated` is derived:
     * true when the new source has fewer channels than the audio file.
     */
    public fun addSourceSwitch(
        meeting: MeetingMetadata,
        atFrame: Long,
        fromSourceId: Int,
        toSourceId: Int,
        reason: SwitchReason = SwitchReason.DEVICE_REMOVED,
    ): MeetingMetadata {
        requirePosition(meeting, atFrame, meeting.sourceSwitches.lastOrNull()?.atFrame)
        require(fromSourceId != toSourceId) { "a switch must change the source" }
        val to = meeting.sources.firstOrNull { it.id == toSourceId }
            ?: throw IllegalArgumentException("unknown source id $toSourceId")
        require(meeting.sources.any { it.id == fromSourceId }) { "unknown source id $fromSourceId" }
        val switch = SourceSwitch(
            atFrame = atFrame,
            atMs = Timeline.framesToMs(atFrame, meeting.audio.sampleRate),
            fromSourceId = fromSourceId,
            toSourceId = toSourceId,
            reason = reason,
            channelsDuplicated = ChannelPolicy.needsDuplication(meeting.audio.channels, to.channels),
        )
        return meeting.copy(sourceSwitches = meeting.sourceSwitches + switch)
    }

    public fun addDropout(meeting: MeetingMetadata, atFrame: Long, droppedFrames: Long): MeetingMetadata {
        requirePosition(meeting, atFrame, meeting.dropouts.lastOrNull()?.atFrame)
        require(droppedFrames > 0) { "droppedFrames must be > 0" }
        val dropout = Dropout(atFrame, Timeline.framesToMs(atFrame, meeting.audio.sampleRate), droppedFrames)
        return meeting.copy(dropouts = meeting.dropouts + dropout)
    }

    /** Changes only the title (trimmed, 1..200 characters). */
    public fun rename(meeting: MeetingMetadata, newTitle: String): MeetingMetadata =
        meeting.copy(title = normalizeTitle(newTitle))

    public fun setDuration(meeting: MeetingMetadata, durationFrames: Long): MeetingMetadata {
        require(durationFrames >= 0) { "durationFrames must not be negative" }
        val audio = meeting.audio.copy(
            durationFrames = durationFrames,
            durationMs = Timeline.framesToMs(durationFrames, meeting.audio.sampleRate),
        )
        return meeting.copy(audio = audio)
    }

    public fun finish(meeting: MeetingMetadata, durationFrames: Long): MeetingMetadata =
        setDuration(meeting, durationFrames).copy(status = MeetingStatus.COMPLETE)

    public fun markRecovered(meeting: MeetingMetadata, durationFrames: Long): MeetingMetadata =
        setDuration(meeting, durationFrames).copy(status = MeetingStatus.RECOVERED)

    // --- Phase 2: the `transcription` block ---

    /**
     * Records that a transcription run started. A committed `transcript` stays as it is (a
     * re-transcription keeps the old transcript until the new one is committed); an older `job` is replaced.
     */
    public fun startJob(
        meeting: MeetingMetadata,
        modelId: String,
        requestedLanguage: String,
        startedAt: OffsetDateTime,
    ): MeetingMetadata {
        val job = JobInfo(
            state = JobState.IN_PROGRESS,
            modelId = modelId,
            requestedLanguage = requestedLanguage,
            startedAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(startedAt),
        )
        return meeting.copy(transcription = (meeting.transcription ?: MeetingTranscription()).copy(job = job))
    }

    /** Marks the existing job as failed. [resumable] is true when the work file holds a usable checkpoint. */
    public fun failJob(
        meeting: MeetingMetadata,
        reason: FailureReason,
        message: String?,
        resumable: Boolean,
    ): MeetingMetadata {
        val block = meeting.transcription
        val job = requireNotNull(block?.job) { "there is no transcription job to fail" }
        val failed = job.copy(state = JobState.FAILED, failureReason = reason, failureMessage = message, resumable = resumable)
        return meeting.copy(transcription = block.copy(job = failed))
    }

    /** Removes only the job. A block that is left empty is dropped so that the file stays as in Phase 1. */
    public fun clearJob(meeting: MeetingMetadata): MeetingMetadata {
        val block = meeting.transcription ?: return meeting
        val remaining = block.copy(job = null)
        return meeting.copy(transcription = remaining.takeIf { it.transcript != null })
    }

    /** Sets the committed transcript and removes the job. */
    public fun commitTranscript(meeting: MeetingMetadata, transcript: TranscriptInfo): MeetingMetadata =
        meeting.copy(transcription = MeetingTranscription(transcript = transcript, job = null))

    private fun toSource(id: Int, info: SourceInfo): Source = Source(
        id = id,
        name = info.name,
        type = info.type,
        deliveredChannels = info.deliveredChannels,
        channels = ChannelPolicy.recordedChannels(info.type, info.deliveredChannels),
    )

    private fun normalizeTitle(title: String): String {
        val trimmed = title.trim()
        require(trimmed.isNotEmpty()) { "title must not be empty" }
        require(trimmed.length <= MeetingMetadata.MAX_TITLE_LENGTH) {
            "title must be at most ${MeetingMetadata.MAX_TITLE_LENGTH} characters"
        }
        return trimmed
    }

    /**
     * Positions must be non-negative and ascending within a list. Against the duration they are only
     * checked once the meeting is no longer being recorded (the duration on disk is written at stop).
     */
    private fun requirePosition(meeting: MeetingMetadata, atFrame: Long, previous: Long?) {
        require(atFrame >= 0) { "atFrame must not be negative" }
        if (previous != null) require(atFrame >= previous) { "positions must be ascending ($atFrame after $previous)" }
        if (meeting.status != MeetingStatus.RECORDING) {
            require(Timeline.isValidPosition(atFrame, meeting.audio.durationFrames)) {
                "atFrame $atFrame is outside the timeline [0, ${meeting.audio.durationFrames}]"
            }
        }
    }
}
