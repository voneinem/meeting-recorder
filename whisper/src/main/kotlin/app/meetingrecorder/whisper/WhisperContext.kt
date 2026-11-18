package app.meetingrecorder.whisper

import java.io.File
import java.nio.FloatBuffer

/**
 * One loaded speech model. Thin wrapper over the JNI bridge (`whisper_jni.cpp`).
 *
 * Threading: one [decode] or [detectLanguage] at a time per context. [abort] may be called from any
 * thread; once a context has been aborted it stays aborted (create a new context for the next job).
 * [close] must only be called after the running decode has returned.
 */
class WhisperContext private constructor(handle: Long) : AutoCloseable {

    private val lock = Any()
    private var handle: Long = handle

    /** Probabilities of all languages the model knows, most probable first. */
    fun detectLanguage(samples: FloatBuffer, count: Int, threads: Int): List<LanguageProbability> {
        requireDirect(samples, count)
        val probabilities = nativeDetectLanguage(currentHandle(), samples, count, threads)
        return probabilities.indices
            .map { LanguageProbability(languageCodes[it], probabilities[it]) }
            .sortedByDescending { it.probability }
    }

    /**
     * Decodes [count] samples (16 kHz, mono, float in -1..1) of a direct [samples] buffer. [onSegment] is
     * called on the calling thread for every segment as soon as the engine finishes it.
     */
    fun decode(samples: FloatBuffer, count: Int, params: DecodeParams, onSegment: (RawSegment) -> Unit): DecodeStatus {
        requireDirect(samples, count)
        val callback = object : SegmentCallback {
            override fun onSegment(startCs: Long, endCs: Long, utf8: ByteArray) {
                onSegment(RawSegment(startCs, endCs, String(utf8, Charsets.UTF_8)))
            }
        }
        val status = nativeDecode(
            currentHandle(), samples, count,
            params.language.toByteArray(Charsets.UTF_8), params.threads,
            params.initialPrompt?.toByteArray(Charsets.UTF_8), params.suppressNonSpeech,
            params.vadModel?.path?.toByteArray(Charsets.UTF_8), callback,
        )
        return if (status == 0) DecodeStatus.COMPLETED else DecodeStatus.ABORTED
    }

    /** Makes a running [decode] return [DecodeStatus.ABORTED] soon. Thread-safe and idempotent. */
    fun abort() {
        synchronized(lock) {
            if (handle != 0L) nativeAbort(handle)
        }
    }

    /** Frees the model. Call only after [decode] has returned. */
    override fun close() {
        val toFree = synchronized(lock) {
            val h = handle
            handle = 0L
            h
        }
        if (toFree != 0L) nativeFree(toFree)
    }

    private fun currentHandle(): Long = synchronized(lock) {
        if (handle == 0L) throw WhisperException(WhisperException.BAD_ARGUMENT, "context is closed")
        handle
    }

    private fun requireDirect(samples: FloatBuffer, count: Int) {
        require(samples.isDirect) { "samples must be a direct FloatBuffer" }
        require(count in 1..samples.capacity()) { "count must be in 1..${samples.capacity()}" }
    }

    /** Receives segments from the native side. */
    internal interface SegmentCallback {
        fun onSegment(startCs: Long, endCs: Long, utf8: ByteArray)
    }

    companion object {
        private val libraryError: Throwable? = try {
            System.loadLibrary("whisper_jni")
            null
        } catch (e: UnsatisfiedLinkError) {
            e
        }

        private fun ensureLibrary() {
            if (libraryError != null) {
                throw WhisperException(
                    WhisperException.LIBRARY_UNAVAILABLE,
                    "the native speech engine is not available on this device: ${libraryError.message}",
                )
            }
        }

        private val languageCodes: Array<String> by lazy {
            ensureLibrary()
            nativeLanguageCodes()
        }

        /** Version of the bundled engine, for example `1.9.5`. */
        val engineVersion: String
            get() {
                ensureLibrary()
                return nativeEngineVersion()
            }

        /** Loads [modelFile]. [nativeLibraryDir] is `ApplicationInfo.nativeLibraryDir`. */
        fun load(modelFile: File, nativeLibraryDir: String, options: LoadOptions = LoadOptions()): WhisperContext {
            ensureLibrary()
            val handle = nativeInit(
                modelFile.path.toByteArray(Charsets.UTF_8),
                nativeLibraryDir.toByteArray(Charsets.UTF_8),
                options.flashAttention,
            )
            return WhisperContext(handle)
        }

        @JvmStatic private external fun nativeInit(modelPath: ByteArray, nativeLibraryDir: ByteArray, flashAttn: Boolean): Long
        @JvmStatic private external fun nativeLanguageCodes(): Array<String>
        @JvmStatic private external fun nativeDetectLanguage(handle: Long, samples: FloatBuffer, count: Int, threads: Int): FloatArray
        @JvmStatic private external fun nativeDecode(
            handle: Long, samples: FloatBuffer, count: Int, language: ByteArray, threads: Int,
            initialPrompt: ByteArray?, suppressNst: Boolean, vadModelPath: ByteArray?, callback: SegmentCallback,
        ): Int
        @JvmStatic private external fun nativeAbort(handle: Long)
        @JvmStatic private external fun nativeFree(handle: Long)
        @JvmStatic private external fun nativeEngineVersion(): String
    }
}
