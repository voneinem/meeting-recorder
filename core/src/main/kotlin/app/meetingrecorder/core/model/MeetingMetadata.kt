package app.meetingrecorder.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * In-memory form of `meeting.json` (schemaVersion 1). Field names and rules are documented in
 * `specs/001-meeting-recording-mvp/data-model.md` and `contracts/meeting.schema.json`.
 *
 * All positions (`atFrame`) are sample-frame indices into `audio.wav`; `atMs` values are derived
 * from them by [app.meetingrecorder.core.time.Timeline] and are never authoritative.
 */
@Serializable
public enum class MeetingStatus {
    @SerialName("recording") RECORDING,
    @SerialName("complete") COMPLETE,
    @SerialName("recovered") RECOVERED,
}

@Serializable
public enum class ChannelLayout {
    @SerialName("mono") MONO,
    @SerialName("stereo") STEREO,
}

@Serializable
public enum class SourceType {
    @SerialName("builtin") BUILTIN,
    @SerialName("usb") USB,
    @SerialName("other") OTHER,
}

@Serializable
public enum class SwitchReason {
    @SerialName("device-removed") DEVICE_REMOVED,
}

/** Audio file description. `channels` is constant for the whole file; `durationFrames` is authoritative. */
@Serializable
public data class AudioInfo(
    val file: String = AUDIO_FILE_NAME,
    val format: String = AUDIO_FORMAT,
    val sampleRate: Int,
    val channels: Int,
    val channelLayout: ChannelLayout,
    val durationFrames: Long,
    val durationMs: Long,
) {
    public companion object {
        public const val AUDIO_FILE_NAME: String = "audio.wav"
        public const val AUDIO_FORMAT: String = "pcm_s16le"
    }
}

/**
 * A microphone used in the meeting. [deliveredChannels] is what the device delivers, [channels] is
 * what is recorded from it (1 or 2, at most [deliveredChannels]).
 */
@Serializable
public data class Source(
    val id: Int,
    val name: String,
    val type: SourceType,
    val deliveredChannels: Int,
    val channels: Int,
)

@Serializable
public data class SourceSwitch(
    val atFrame: Long,
    val atMs: Long,
    val fromSourceId: Int,
    val toSourceId: Int,
    val reason: SwitchReason,
    val channelsDuplicated: Boolean,
)

/** Audio lost because the writer buffer overflowed; the dropped frames are not part of the timeline. */
@Serializable
public data class Dropout(
    val atFrame: Long,
    val atMs: Long,
    val droppedFrames: Long,
)

@Serializable
public data class Photo(
    val file: String,
    val atFrame: Long,
    val atMs: Long,
)

@Serializable
public data class MeetingMetadata(
    val schemaVersion: Int = SCHEMA_VERSION,
    val title: String,
    /** ISO-8601 date-time with UTC offset. Informational wall-clock time only. */
    val startedAt: String,
    val status: MeetingStatus,
    val audio: AudioInfo,
    val sources: List<Source>,
    val initialSourceId: Int,
    val sourceSwitches: List<SourceSwitch> = emptyList(),
    val photos: List<Photo> = emptyList(),
    val dropouts: List<Dropout> = emptyList(),
) {
    public companion object {
        public const val SCHEMA_VERSION: Int = 1
        public const val MAX_TITLE_LENGTH: Int = 200
    }
}
