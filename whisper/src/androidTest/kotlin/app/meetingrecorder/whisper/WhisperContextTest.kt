package app.meetingrecorder.whisper

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the real engine with the tiny model. Skipped when the model or the sample is missing:
 *
 *     adb push ggml-tiny-q5_1.bin /data/local/tmp/
 *     adb push jfk.wav /data/local/tmp/        (16 kHz, mono, 16 bit; public domain)
 */
class WhisperContextTest {

    private val model = File("/data/local/tmp/ggml-tiny-q5_1.bin")
    private val sample = File("/data/local/tmp/jfk.wav")
    private val libraryDir get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo.nativeLibraryDir

    private fun requireFiles() = assumeTrue("model and sample not pushed", model.isFile && sample.isFile)

    /** Reads a 16 kHz mono 16 bit WAV into a direct float buffer. */
    private fun readSamples(repeat: Int = 1, file: File = sample): Pair<FloatBuffer, Int> {
        val bytes = file.readBytes()
        val dataStart = 44
        val frames = (bytes.size - dataStart) / 2
        val total = frames * repeat
        val buffer = ByteBuffer.allocateDirect(total * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val pcm = ByteBuffer.wrap(bytes, dataStart, frames * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        repeat(repeat) {
            pcm.rewind()
            while (pcm.hasRemaining()) buffer.put(pcm.get() / 32768f)
        }
        buffer.rewind()
        return buffer to total
    }

    @Test
    fun decodesTheKnownSentenceAndDetectsEnglish() {
        requireFiles()
        val (samples, count) = readSamples()
        WhisperContext.load(model, libraryDir).use { context ->
            val languages = context.detectLanguage(samples, count, threads = 4)
            assertEquals("en", languages.first().code)

            val text = StringBuilder()
            val status = context.decode(samples, count, DecodeParams(language = "en", threads = 4)) {
                text.append(it.text)
            }
            assertEquals(DecodeStatus.COMPLETED, status)
            assertTrue(text.toString(), text.toString().lowercase().contains("ask not what your country can do for you"))
        }
    }

    /**
     * Logs the decode time of `jfk.wav` with the tiny model (research R-2, case S-7: the native code is compiled the same
     * way in the debug and the release build, so the times of both builds must be within 5%). Read the line
     * `WhisperTest: jfk decode ms` with `adb logcat -s WhisperTest`; the ggml variant is in the `ggml` log lines.
     */
    @Test
    fun logsTheDecodeTimeOfTheSample() {
        requireFiles()
        val (samples, count) = readSamples()
        WhisperContext.load(model, libraryDir).use { context ->
            val times = (1..RUNS).map {
                val start = System.nanoTime()
                val status = context.decode(samples, count, DecodeParams(language = "en", threads = THREADS)) { }
                assertEquals(DecodeStatus.COMPLETED, status)
                (System.nanoTime() - start) / NANOS_PER_MS
            }
            // The first run warms caches and the CPU; the median of the rest is the figure.
            val loaded = File("/proc/self/maps").readLines().filter { "libggml-cpu" in it }.map { it.substringAfterLast('/') }.distinct()
            android.util.Log.i("WhisperTest", "ggml CPU backend library loaded: $loaded")
            val median = times.drop(1).sorted()[(times.size - 1) / 2]
            android.util.Log.i("WhisperTest", "jfk decode ms: all=$times median=$median threads=$THREADS debuggable=${(InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0}")
        }
    }

    /**
     * Speech must come back in full when it spans several 30 s windows of unrelated sentences, with and without
     * flash attention. The spike first measured sentences being skipped, but that was `whisper-cli -nt` (decoding
     * without timestamp tokens, a mode the app never uses); this test pins the app's real configuration. Needs the
     * spike audio and the `small-q8_0` model, because the tiny model and repeated identical speech never skipped:
     *
     *     adb push spike/audio/de-3min.wav spike/audio/de-3min.ref.txt spike/models/ggml-small-q8_0.bin /data/local/tmp/
     */
    @Test
    fun unrelatedSentencesAcrossSeveralWindowsAreNotSkipped() {
        val germanModel = File("/data/local/tmp/ggml-small-q8_0.bin")
        val germanSample = File("/data/local/tmp/de-3min.wav")
        val germanReference = File("/data/local/tmp/de-3min.ref.txt")
        assumeTrue("german spike files not pushed", germanModel.isFile && germanSample.isFile && germanReference.isFile)
        val (samples, count) = readSamples(file = germanSample)
        val referenceWords = germanReference.readText().trim().split(Regex("\\s+")).size
        for (flashAttention in listOf(false, true)) {
            WhisperContext.load(germanModel, libraryDir, LoadOptions(flashAttention)).use { context ->
                val text = StringBuilder()
                val status = context.decode(samples, count, DecodeParams(language = "de", threads = 2)) { text.append(it.text).append(' ') }
                assertEquals(DecodeStatus.COMPLETED, status)
                val words = text.toString().trim().split(Regex("\\s+")).size
                android.util.Log.i("WhisperTest", "flashAttention=$flashAttention words=$words of $referenceWords")
                assertTrue("flashAttention=$flashAttention: only $words of $referenceWords words came back: $text", words >= referenceWords * 8 / 10)
            }
        }
    }

    @Test
    fun abortFromAnotherThreadStopsDecodingWithinTwoSeconds() {
        requireFiles()
        val (samples, count) = readSamples(repeat = 60)
        WhisperContext.load(model, libraryDir).use { context ->
            val started = java.util.concurrent.CountDownLatch(1)
            var status: DecodeStatus? = null
            var returnedAt = 0L
            val decoder = thread {
                started.countDown()
                status = context.decode(samples, count, DecodeParams(language = "en", threads = 4)) {}
                returnedAt = System.nanoTime()
            }
            started.await()
            Thread.sleep(500)
            val abortedAt = System.nanoTime()
            context.abort()
            decoder.join(10_000)
            assertEquals(DecodeStatus.ABORTED, status)
            assertTrue("abort took too long", (returnedAt - abortedAt) / 1_000_000 < 2_000)
        }
        // use { } above already called close() after the abort: reaching this line means it was clean.
    }

    @Test
    fun loadingATextFileThrows() {
        val text = File.createTempFile("not-a-model", ".txt").apply { writeText("this is not a model"); deleteOnExit() }
        try {
            WhisperContext.load(text, libraryDir).close()
            fail("expected WhisperException")
        } catch (e: WhisperException) {
            assertEquals(WhisperException.LOAD_FAILED, e.code)
        }
    }

    private companion object {
        const val RUNS = 7
        const val THREADS = 2
        const val NANOS_PER_MS = 1_000_000L
    }
}
