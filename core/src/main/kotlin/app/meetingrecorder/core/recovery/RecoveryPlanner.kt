package app.meetingrecorder.core.recovery

import app.meetingrecorder.core.model.AudioInfo
import app.meetingrecorder.core.model.ChannelLayout
import app.meetingrecorder.core.model.MeetingEditor
import app.meetingrecorder.core.model.MeetingMetadata
import app.meetingrecorder.core.model.MeetingStatus
import app.meetingrecorder.core.model.Source
import app.meetingrecorder.core.model.SourceType
import app.meetingrecorder.core.time.Timeline
import app.meetingrecorder.core.wav.WavFormat
import app.meetingrecorder.core.wav.WavHeader
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Decides what to do with a meeting folder after an interrupted recording (FR-025, research R-9).
 * It is a pure function of a snapshot of the folder; the app applies the resulting actions.
 *
 * Only a folder whose `meeting.json` says `recording`, or whose metadata is missing or unreadable,
 * is touched. Finished (`complete`, `recovered`) folders get an empty plan.
 */
public object RecoveryPlanner {

    public sealed interface MetadataState {
        public data object Missing : MetadataState
        public data class Unreadable(val reason: String) : MetadataState
        public data class Readable(val meeting: MeetingMetadata) : MetadataState
    }

    /** What the app saw in the folder. [wavHeader] holds the first bytes of `audio.wav` (up to 44). */
    public class FolderSnapshot(
        public val folderName: String,
        public val photoFileNames: List<String>,
        public val audioSizeBytes: Long?,
        public val wavHeader: ByteArray?,
        public val metadata: MetadataState,
    )

    public sealed interface Action {
        /** Fix the WAV header sizes and drop a trailing partial frame (`WavHeader.repair`). */
        public data object RepairWav : Action

        /** Write this `meeting.json`. */
        public data class WriteMetadata(val meeting: MeetingMetadata) : Action

        /** Regenerate `CLAUDE.md` for the recovered meeting. */
        public data object WriteGuide : Action
    }

    public class Plan(
        public val actions: List<Action>,
        /** Why the folder cannot be recovered (it is kept untouched), or null. */
        public val unrecoverable: String? = null,
        /** Photo files in `photos/` that the metadata does not list; their positions are unknown. */
        public val unlistedPhotos: List<String> = emptyList(),
    )

    private val folderTime = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    private const val FOLDER_TIME_LENGTH = 19

    public fun plan(snapshot: FolderSnapshot, zone: ZoneId = ZoneId.systemDefault()): Plan {
        val readable = (snapshot.metadata as? MetadataState.Readable)?.meeting
        if (readable != null && readable.status != MeetingStatus.RECORDING) return Plan(emptyList())

        val size = snapshot.audioSizeBytes
            ?: return Plan(emptyList(), unrecoverable = "audio.wav is missing")
        if (size < WavHeader.SIZE) return Plan(emptyList(), unrecoverable = "audio.wav is shorter than a WAV header")
        val format = snapshot.wavHeader?.let(WavHeader::parse)
            ?: return Plan(emptyList(), unrecoverable = "audio.wav does not have a valid WAV header")

        val frames = (size - WavHeader.SIZE) / format.blockAlign
        val recovered = if (readable != null) {
            repairMetadata(readable, format, frames)
        } else {
            reconstruct(snapshot.folderName, format, frames, zone)
        }
        val listed = recovered.photos.map { it.file.substringAfterLast('/') }.toSet()
        return Plan(
            actions = listOf(Action.RepairWav, Action.WriteMetadata(recovered), Action.WriteGuide),
            unlistedPhotos = snapshot.photoFileNames.filter { it !in listed }.sorted(),
        )
    }

    /** Keeps what the metadata knows, takes the format from the file and clamps positions to the audio. */
    private fun repairMetadata(meeting: MeetingMetadata, format: WavFormat, frames: Long): MeetingMetadata {
        val audio = meeting.audio.copy(
            sampleRate = format.sampleRate,
            channels = format.channels,
            channelLayout = layoutOf(format.channels),
        )
        val sampleRate = format.sampleRate
        fun clamp(atFrame: Long) = minOf(atFrame, frames)
        val fixed = meeting.copy(
            audio = audio,
            sourceSwitches = meeting.sourceSwitches.map {
                it.copy(atFrame = clamp(it.atFrame), atMs = Timeline.framesToMs(clamp(it.atFrame), sampleRate))
            },
            photos = meeting.photos.map {
                it.copy(atFrame = clamp(it.atFrame), atMs = Timeline.framesToMs(clamp(it.atFrame), sampleRate))
            },
            dropouts = meeting.dropouts.map {
                it.copy(atFrame = clamp(it.atFrame), atMs = Timeline.framesToMs(clamp(it.atFrame), sampleRate))
            },
        )
        return MeetingEditor.markRecovered(fixed, frames)
    }

    /** Builds minimal metadata when `meeting.json` is gone: only what the folder name and the WAV header tell. */
    private fun reconstruct(folderName: String, format: WavFormat, frames: Long, zone: ZoneId): MeetingMetadata {
        val source = Source(
            id = 0,
            name = UNKNOWN_SOURCE_NAME,
            type = SourceType.OTHER,
            deliveredChannels = format.channels,
            channels = format.channels,
        )
        return MeetingMetadata(
            title = folderName,
            startedAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(startFromFolderName(folderName, zone)),
            status = MeetingStatus.RECOVERED,
            audio = AudioInfo(
                sampleRate = format.sampleRate,
                channels = format.channels,
                channelLayout = layoutOf(format.channels),
                durationFrames = frames,
                durationMs = Timeline.framesToMs(frames, format.sampleRate),
            ),
            sources = listOf(source),
            initialSourceId = source.id,
        )
    }

    private fun startFromFolderName(name: String, zone: ZoneId): OffsetDateTime = try {
        LocalDateTime.parse(name.take(FOLDER_TIME_LENGTH), folderTime).atZone(zone).toOffsetDateTime()
    } catch (_: DateTimeParseException) {
        OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
    }

    private fun layoutOf(channels: Int) = if (channels == 1) ChannelLayout.MONO else ChannelLayout.STEREO

    public const val UNKNOWN_SOURCE_NAME: String = "Unknown microphone"
}
