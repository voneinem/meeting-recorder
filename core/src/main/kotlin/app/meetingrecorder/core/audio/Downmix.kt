package app.meetingrecorder.core.audio

/** Stereo to mono as `(L + R) / 2` in float. Whether a gain stage helps quiet recordings is a spike question. */
public object Downmix {

    /** [interleaved] holds [channels] (1 or 2) samples per frame; mono is returned as it is. */
    public fun toMono(interleaved: FloatArray, channels: Int): FloatArray {
        require(channels in 1..2) { "channels must be 1 or 2" }
        if (channels == 1) return interleaved
        require(interleaved.size % 2 == 0) { "a stereo block must hold whole frames" }
        return FloatArray(interleaved.size / 2) { (interleaved[2 * it] + interleaved[2 * it + 1]) / 2f }
    }
}
