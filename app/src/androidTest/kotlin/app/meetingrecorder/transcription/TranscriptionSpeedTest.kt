package app.meetingrecorder.transcription

import android.app.NotificationManager
import android.os.SystemClock
import app.meetingrecorder.targetContext
import app.meetingrecorder.waitUntil
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The second spike pass "S-B" and scenario S14 (T136): the 10 minute German test audio through the real worker with
 * the default model, measuring the real-time factor of the whole run (pipeline and foreground service included), the
 * agreement of the percentage in the app and in the notification, and the accuracy of the remaining-time estimate at
 * 10%, 30%, 60% and 90%. Everything is logged under `SpeedTest`; the thresholds are asserted.
 *
 *     adb push spike/audio/de-10min.wav spike/models/ggml-small-q8_0.bin /data/local/tmp/
 *     adb shell am instrument -w -r -e allowDevice true -e class app.meetingrecorder.transcription.TranscriptionSpeedTest ...
 *
 * On a device it replaces the storage folder choice for its duration (see [TranscriptionTestEnvironment]).
 */
class TranscriptionSpeedTest {
    private val audio = File("/data/local/tmp/de-10min.wav")
    private val model = File("/data/local/tmp/ggml-small-q8_0.bin")
    private var environment: TranscriptionTestEnvironment? = null
    private val env get() = checkNotNull(environment)

    @Before
    fun setUp() {
        environment = TranscriptionTestEnvironment.create("transcription-speed", modelFile = model, requiredFiles = listOf(audio))
    }

    @After
    fun tearDown() {
        environment?.close()
    }

    private class Sample(val atMs: Long, val appPercent: Double, val notificationPercent: Int?, val remainingMs: Long?)

    private fun notificationPercent(): Int? = targetContext.getSystemService(NotificationManager::class.java).activeNotifications
        .firstOrNull { it.id == TranscriptionNotification.PROGRESS_NOTIFICATION_ID }?.notification?.extras?.getInt("android.progress")

    @Test
    fun theTenMinuteRecordingMeetsTheSpeedAndProgressTargets() {
        val meeting = env.buildMeetingFromWav(audio, "Speed test")
        val audioSeconds = env.readMetadata(meeting).audio.durationMs / 1000.0
        val controller = env.app.transcriptionController
        val samples = mutableListOf<Sample>()

        val begin = SystemClock.elapsedRealtime()
        assertEquals(StartResult.Started, runBlocking { controller.start(TranscriptionRequest(meeting.folderName, env.modelId, "de", Mode.START)) })
        var running = false
        waitUntil("the job finishes", timeoutMs = 30 * 60_000L) {
            val state = controller.state.value
            if (state is TranscriptionState.Active) {
                if (state.phase == ActivePhase.RUNNING) running = true
                samples += Sample(SystemClock.elapsedRealtime() - begin, state.progress * 100.0, notificationPercent(), state.remainingMs)
            }
            state == TranscriptionState.Idle && samples.isNotEmpty()
        }
        val totalMs = SystemClock.elapsedRealtime() - begin
        assertTrue("the job never reached the decoding phase", running)
        val block = checkNotNull(env.readMetadata(meeting).transcription?.transcript) { "no transcript: ${env.storage.readText(meeting.metadataUri)}" }

        val rtf = totalMs / 1000.0 / audioSeconds
        android.util.Log.i(TAG, "audio=${"%.1f".format(audioSeconds)} s total=${totalMs / 1000.0} s rtf=${"%.3f".format(rtf)} segments=${block.segmentCount} model=${env.modelId}")

        // Percentage in the app against the notification: the notification is refreshed every 2 s, so compare each
        // notification value with the app value that was current when the notification was last posted (<= 2 s earlier).
        val withNotification = samples.filter { it.notificationPercent != null }
        var worstDifference = 0.0
        for (sample in withNotification) {
            val earliest = samples.filter { it.atMs in (sample.atMs - 2_500)..sample.atMs }.minOf { it.appPercent }
            val latest = sample.appPercent
            val low = earliest.toInt() - 1
            val difference = when {
                sample.notificationPercent!! < low -> low - sample.notificationPercent.toDouble()
                sample.notificationPercent > latest -> sample.notificationPercent - latest
                else -> 0.0
            }
            worstDifference = maxOf(worstDifference, difference)
        }
        val longestStall = samples.zipWithNext().filter { (a, b) -> b.appPercent > a.appPercent }.let { moves ->
            val times = listOf(samples.first().atMs) + moves.map { it.second.atMs }
            times.zipWithNext().maxOfOrNull { (a, b) -> b - a } ?: 0L
        }
        val moveTimes = listOf(samples.first().atMs) + samples.zipWithNext().filter { (x, y) -> y.appPercent > x.appPercent }.map { it.second.atMs }
        val stalls = moveTimes.zipWithNext().filter { (x, y) -> y - x > 5_000 }.map { (x, y) -> "${x / 1000}s+${(y - x) / 1000}s" }
        android.util.Log.i(TAG, "stalls longer than 5 s (start+length): $stalls")
        android.util.Log.i(TAG, "percentage: ${withNotification.size} notification readings, worst disagreement ${"%.2f".format(worstDifference)} points, longest time without movement of the app percentage $longestStall ms")

        // The remaining-time estimate at 10, 30, 60 and 90 percent against the real remaining time.
        val estimateErrors = listOf(10, 30, 60, 90).map { mark ->
            val at = samples.firstOrNull { it.appPercent >= mark && it.remainingMs != null }
            val real = at?.let { totalMs - it.atMs }
            val error = if (at != null && real != null && real > 0) abs(at.remainingMs!! - real) * 100.0 / real else null
            android.util.Log.i(TAG, "at $mark%: estimate ${at?.remainingMs?.div(1000)} s, real ${real?.div(1000)} s, error ${error?.let { "%.1f".format(it) }}%")
            error
        }

        assertTrue("real-time factor $rtf is above 1.0 (SC-003)", rtf <= 1.0)
        assertTrue("the percentage in the app and the notification differ by $worstDifference points", worstDifference <= 2.0)
        estimateErrors.forEachIndexed { index, error ->
            assertNotNull("no estimate at ${listOf(10, 30, 60, 90)[index]}%", error)
            assertTrue("the estimate at ${listOf(10, 30, 60, 90)[index]}% is off by $error%", error!! <= 25.0)
        }
    }

    private companion object {
        const val TAG = "SpeedTest"
    }
}
