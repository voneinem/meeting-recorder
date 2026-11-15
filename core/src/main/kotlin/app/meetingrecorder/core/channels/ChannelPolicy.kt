package app.meetingrecorder.core.channels

import app.meetingrecorder.core.model.SourceType

/**
 * Decides how many channels are recorded from a source and keeps the channel layout of a meeting's
 * audio file constant when the source changes (FR-011, FR-014).
 */
public object ChannelPolicy {
    private const val MAX_RECORDED_CHANNELS = 2

    /**
     * Channels to record: the built-in microphone is always mono; any other source is recorded in
     * stereo when it delivers at least two channels (using its first two), otherwise mono.
     */
    public fun recordedChannels(type: SourceType, deliveredChannels: Int): Int {
        require(deliveredChannels >= 1) { "deliveredChannels must be >= 1" }
        return if (type == SourceType.BUILTIN) 1 else minOf(deliveredChannels, MAX_RECORDED_CHANNELS)
    }

    /** True when a source with [sourceChannels] must be spread over the [fileChannels] of the file. */
    public fun needsDuplication(fileChannels: Int, sourceChannels: Int): Boolean = fileChannels > sourceChannels

    /** Copies each mono frame of [input] into the left and right channel of the result. */
    public fun duplicateMonoToStereo(input: ShortArray, frames: Int): ShortArray {
        require(frames in 0..input.size) { "frames out of range: $frames" }
        val output = ShortArray(frames * 2)
        for (frame in 0 until frames) {
            output[frame * 2] = input[frame]
            output[frame * 2 + 1] = input[frame]
        }
        return output
    }
}
