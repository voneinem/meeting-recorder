package app.meetingrecorder.whisper

/** A failure of the native speech engine. [code] is one of the constants of the companion. */
class WhisperException(val code: Int, message: String) : RuntimeException(message) {
    companion object {
        const val LOAD_FAILED = 1
        const val BAD_ARGUMENT = 2
        const val SPECTROGRAM_FAILED = 3
        const val DETECTION_FAILED = 4
        const val DECODE_FAILED = 5
        const val NO_BACKEND = 6

        /** The native library itself could not be loaded (for example an unsupported ABI). */
        const val LIBRARY_UNAVAILABLE = 7
    }
}
