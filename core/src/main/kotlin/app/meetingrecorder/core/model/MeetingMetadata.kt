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
public enum class JobState {
    @SerialName("in-progress") IN_PROGRESS,
    @SerialName("failed") FAILED,
}

/** Why a transcription job did not finish (contracts/meeting-transcription.schema.json). */
@Serializable
public enum class FailureReason {
    @SerialName("interrupted") INTERRUPTED,
    @SerialName("recording-started") RECORDING_STARTED,
    @SerialName("model-missing") MODEL_MISSING,
    @SerialName("model-invalid") MODEL_INVALID,
    @SerialName("out-of-memory") OUT_OF_MEMORY,
    @SerialName("storage-full") STORAGE_FULL,
    @SerialName("audio-unreadable") AUDIO_UNREADABLE,
    @SerialName("error") ERROR,
}

/** The transcript that is committed in the folder. [requestedLanguage] is `auto` or a language code. */
@Serializable
public data class TranscriptInfo(
    val modelId: String,
    val modelName: String,
    val requestedLanguage: String,
    val language: String,
    /** ISO-8601 date-time with UTC offset. */
    val completedAt: String,
    val processingMs: Long,
    val segmentCount: Int,
)

/** The most recent transcription run that is running or did not finish. */
@Serializable
public data class JobInfo(
    val state: JobState,
    val modelId: String,
    val requestedLanguage: String,
    val startedAt: String,
    val failureReason: FailureReason? = null,
    /** Short English technical detail for people reading the folder. */
    val failureMessage: String? = null,
    /** True when `transcript.work.jsonl` holds a usable checkpoint. */
    val resumable: Boolean = false,
)

/** The optional `transcription` block of `meeting.json`. The folder content wins over it. */
@Serializable
public data class MeetingTranscription(
    val transcript: TranscriptInfo? = null,
    val job: JobInfo? = null,
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
    /** Phase 2. `null` (and then absent from the file) for a meeting that was never transcribed. */
    val transcription: MeetingTranscription? = null,
) {
    public companion object {
        public const val SCHEMA_VERSION: Int = 1
        public const val MAX_TITLE_LENGTH: Int = 200
    }
}
