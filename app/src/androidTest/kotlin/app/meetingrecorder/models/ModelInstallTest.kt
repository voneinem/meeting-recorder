package app.meetingrecorder.models

import android.app.NotificationManager
import android.net.Uri
import android.security.NetworkSecurityPolicy
import androidx.test.core.app.ActivityScenario
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.meetingrecorder.MainActivity
import app.meetingrecorder.MeetingRecorderApp
import app.meetingrecorder.R
import app.meetingrecorder.TestTree
import app.meetingrecorder.core.models.InstallFailure
import app.meetingrecorder.core.models.ModelCatalogEntry
import app.meetingrecorder.core.models.ModelInstallState
import app.meetingrecorder.core.models.ModelKind
import app.meetingrecorder.core.models.ModelLanguages
import app.meetingrecorder.grantRecordingPermissions
import app.meetingrecorder.recording.Phase
import app.meetingrecorder.targetContext
import app.meetingrecorder.waitUntil
import app.meetingrecorder.work.JobKind
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import java.util.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The model download through the real stack (WorkManager, `ModelInstallWorker`, foreground notification, the
 * downloader) against a loopback server in this process (T114). The debug build's network configuration permits
 * cleartext to `127.0.0.1` only; the app's model catalog is pointed at the server for the duration of a test
 * (`MeetingRecorderApp.catalogSource`). The tests install and delete only their own model id.
 *
 * Run on an emulator or any device with `adb shell am instrument`; a recording is started by one test, so do not
 * use a phone that is recording.
 */
class ModelInstallTest {

    private val app get() = targetContext.applicationContext as MeetingRecorderApp
    private val controller get() = app.modelController
    private val id = "loopback-model"
    private val cancelLabel get() = targetContext.getString(R.string.action_cancel)

    private lateinit var server: LoopbackServer
    private lateinit var originalCatalog: () -> List<ModelCatalogEntry>
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val events = Collections.synchronizedList(mutableListOf<ModelEvent>())

    @Before
    fun setUp() {
        grantRecordingPermissions()
        server = LoopbackServer()
        originalCatalog = app.catalogSource
        app.catalogSource = { listOf(entry("/model.bin"), entry("/bad.bin", "loopback-bad"), entry("/redirect", "loopback-redirect")) }
        cleanUp()
        app.modelStateStore.reload()
        scope.launch { controller.events.collect { events += it } }
    }

    @After
    fun tearDown() {
        scope.cancel()
        runBlocking { if (app.recordingController.state.value.phase != Phase.IDLE) app.recordingController.stop() }
        controller.cancel(id)
        waitUntil("no install job is registered", 20_000) { app.jobRegistry.current(JobKind.MODEL_INSTALL) == null }
        cleanUp()
        app.catalogSource = originalCatalog
        app.modelStateStore.reload()
        server.close()
    }

    private fun cleanUp() {
        listOf(id, "loopback-bad", "loopback-redirect").forEach { model ->
            app.modelRepository.delete(model)
            app.modelRepository.partFile(model).delete()
            File(app.modelRepository.directory, "$model.part.json").delete()
        }
        (targetContext.getSystemService(NotificationManager::class.java)).cancelAll()
    }

    private fun entry(path: String, modelId: String = id) = ModelCatalogEntry(
        id = modelId, kind = ModelKind.SPEECH, displayNameKey = "loopback", descriptionKey = "loopback",
        languages = ModelLanguages.MULTILINGUAL, sizeBytes = server.payload.size.toLong(), sha256 = server.sha256,
        url = "http://127.0.0.1:${server.port}$path", sourceName = "loopback", license = "MIT", approxMemoryMb = 1,
    )

    private fun state(model: String = id) = app.modelStateStore.stateOf(model)

    private fun bytesDone(): Long = when (val s = state()) {
        is ModelInstallState.Transferring -> s.bytesDone
        is ModelInstallState.Paused -> s.bytesDone
        else -> 0L
    }

    private fun progressNotification() = targetContext.getSystemService(NotificationManager::class.java).activeNotifications
        .firstOrNull { it.id == PROGRESS_NOTIFICATION_ID }

    private fun jobFinished() = app.jobRegistry.current(JobKind.MODEL_INSTALL) == null

    private fun startDownload(): ModelStartResult = runBlocking { controller.download(id) }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun assertInstalledCorrectly() {
        assertEquals(ModelInstallState.Installed, state())
        assertEquals(server.sha256, sha256Of(app.modelRepository.binFile(id)))
        assertFalse(app.modelRepository.partFile(id).exists())
        assertFalse(File(app.modelRepository.directory, "$id.part.json").exists())
    }

    @Test
    fun progressAndNotificationAppearAndTheModelInstalls() {
        assertEquals(ModelStartResult.Started, startDownload())

        waitUntil("bytes arrive") { bytesDone() > 0 }
        waitUntil("the progress notification shows", 10_000) { progressNotification() != null }
        val notification = checkNotNull(progressNotification()).notification
        assertEquals(100, notification.extras.getInt("android.progressMax"))
        assertTrue("the notification has a Cancel action", notification.actions.orEmpty().any { it.title == cancelLabel })

        waitUntil("installed", 60_000) { state() == ModelInstallState.Installed && jobFinished() }
        assertInstalledCorrectly()
        assertEquals("a first download sends no Range header", listOf<String?>(null), server.requests.filter { it.path == "/model.bin" }.map { it.range })
        assertTrue(events.any { it is ModelEvent.Installed })
        waitUntil("the progress notification is gone") { progressNotification() == null }
    }

    @Test
    fun cancelFromTheNotificationDiscardsTheDownload() {
        startDownload()
        waitUntil("some progress") { bytesDone() > 1_000_000 && progressNotification() != null }

        val cancel = checkNotNull(progressNotification()).notification.actions.single { it.title == cancelLabel }
        cancel.actionIntent.send()

        waitUntil("the job ended", 20_000) { jobFinished() }
        waitUntil("not installed again", 10_000) { state() == ModelInstallState.NotInstalled }
        assertNull(app.modelRepository.find(id))
        assertFalse("the partial file is discarded", app.modelRepository.partFile(id).exists())
        assertFalse(File(app.modelRepository.directory, "$id.part.json").exists())
        assertFalse(app.modelRepository.binFile(id).exists())
        waitUntil("the progress notification is gone") { progressNotification() == null }
    }

    @Test
    fun aResumeAfterTheProcessStateIsLostContinuesWithARangeRequest() {
        startDownload()
        waitUntil("more than 3 MiB") { bytesDone() > 3L * 1024 * 1024 }

        // What a kill leaves: the partial file and its state on disk. Pause stops the transfer and keeps both.
        controller.pauseForRecording()
        waitUntil("paused and the job ended", 20_000) { state() is ModelInstallState.Paused && jobFinished() }
        val saved = checkNotNull(app.modelRepository.partState(id)) { "no .part.json after the pause" }
        assertTrue(saved.bytesDone > 0)
        assertEquals("the partial file is exactly as long as its state says", saved.bytesDone, app.modelRepository.partFile(id).length())

        // A new process knows only the disk: derive the state again from the files.
        app.modelStateStore.reload()
        assertEquals(ModelInstallState.Paused(saved.bytesDone, server.payload.size.toLong()), state())

        assertEquals(ModelStartResult.Started, runBlocking { controller.resume(id) })
        waitUntil("installed", 60_000) { state() == ModelInstallState.Installed && jobFinished() }
        assertInstalledCorrectly()

        val resumed = server.requests.last { it.path == "/model.bin" }
        assertEquals("bytes=${saved.bytesDone}-", resumed.range)
        assertNotNull("the validator is sent with the Range", resumed.ifRange)
    }

    @Test
    fun aWrongChecksumLeavesTheModelNotInstalled() {
        assertEquals(ModelStartResult.Started, runBlocking { controller.download("loopback-bad") })

        waitUntil("the job ended", 60_000) { jobFinished() }
        assertEquals(ModelInstallState.Failed(InstallFailure.CHECKSUM), state("loopback-bad"))
        assertNull(app.modelRepository.find("loopback-bad"))
        assertFalse(app.modelRepository.binFile("loopback-bad").exists())
        assertFalse(app.modelRepository.partFile("loopback-bad").exists())
        assertTrue("the user is told: $events", events.any { it == ModelEvent.Failed(ModelError.Checksum) })
    }

    @Test
    fun aRecordingPausesTheDownloadAndRefusesNewOnesUntilItEnds() {
        TestTree(targetContext, "model-install-recording").use { tree ->
            ActivityScenario.launch(MainActivity::class.java).use {
                startDownload()
                waitUntil("some progress") { bytesDone() > 1_000_000 }

                assertTrue("recording starts", runBlocking { app.recordingController.start("Download test", tree.uri) })
                try {
                    waitUntil("the download is paused", 20_000) { state() is ModelInstallState.Paused && jobFinished() }
                    assertTrue("the partial file is kept", app.modelRepository.partFile(id).exists())

                    assertEquals(ModelStartResult.RecordingInProgress, runBlocking { controller.resume(id) })
                    assertEquals(ModelStartResult.RecordingInProgress, runBlocking { controller.download("loopback-bad") })
                    assertEquals(ModelStartResult.RecordingInProgress, runBlocking { controller.import(Uri.parse("content://app.meetingrecorder.test/none")) })
                } finally {
                    runBlocking { app.recordingController.stop() }
                }
                waitUntil("recording ended", 20_000) { app.recordingController.state.value.phase == Phase.IDLE }

                val pausedAt = checkNotNull(app.modelRepository.partState(id)).bytesDone
                assertEquals(ModelStartResult.Started, runBlocking { controller.resume(id) })
                waitUntil("installed", 60_000) { state() == ModelInstallState.Installed && jobFinished() }
                assertInstalledCorrectly()
                assertEquals("bytes=$pausedAt-", server.requests.last { it.path == "/model.bin" }.range)
            }
        }
    }

    @Test
    fun aRedirectToAForeignHostIsRefused() {
        assertEquals(ModelStartResult.Started, runBlocking { controller.download("loopback-redirect") })

        waitUntil("the job ended", 30_000) { jobFinished() }
        assertTrue("the user is told: $events", events.any { it == ModelEvent.Failed(ModelError.UnexpectedAddress) })
        assertEquals(ModelInstallState.NotInstalled, state("loopback-redirect"))
        assertEquals("only the first hop was contacted", listOf("/redirect"), server.requests.map { it.path })
        assertFalse(app.modelRepository.partFile("loopback-redirect").exists())
    }

    @Test
    fun aConnectionToAnotherHostFailsBecauseOfTheNetworkSecurityConfig() {
        val policy = NetworkSecurityPolicy.getInstance()
        assertTrue("the debug loopback server is allowed", policy.isCleartextTrafficPermitted("127.0.0.1"))
        assertFalse("cleartext to another host is not", policy.isCleartextTrafficPermitted("example.org"))
        assertFalse("not even to the model host", policy.isCleartextTrafficPermitted("huggingface.co"))

        // The 192.0.2.0/24 range is reserved for documentation: no packet may leave, the attempt must fail at the policy.
        val failure = runCatching {
            (URL("http://192.0.2.1/").openConnection() as HttpURLConnection).apply { connectTimeout = 3_000; readTimeout = 3_000 }.responseCode
        }.exceptionOrNull()
        assertTrue("expected an IOException, got $failure", failure is IOException)
        assertTrue("expected the cleartext refusal, got ${failure?.message}", failure?.message.orEmpty().contains("Cleartext", ignoreCase = true))
    }

    @Test
    fun aWorkerStartedAgainAfterAProcessRestartTouchesNothing() {
        // Files a stale job must not touch.
        val directory = app.modelRepository.directory.apply { mkdirs() }
        val sentinel = File(directory, "$id.part").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        val before = directory.listFiles().orEmpty().associate { it.name to (it.length() to sha256Of(it)) }

        val workManager = WorkManager.getInstance(targetContext)
        val requests = listOf(
            // A job persisted by an earlier process start: a token this process never issued.
            workDataOf(ModelInstallWorker.KEY_REQUEST_ID to "request-of-an-earlier-process", ModelInstallWorker.KEY_LAUNCH_TOKEN to "token-of-an-earlier-process"),
            // The token of this process but a request that was never registered here.
            workDataOf(ModelInstallWorker.KEY_REQUEST_ID to "never-registered", ModelInstallWorker.KEY_LAUNCH_TOKEN to app.launchToken),
        ).map { OneTimeWorkRequestBuilder<ModelInstallWorker>().setInputData(it).build() }
        requests.forEach { workManager.enqueue(it) }

        requests.forEach { request ->
            waitUntil("the stale worker finished", 30_000) { workManager.getWorkInfoById(request.id).get()?.state?.isFinished == true }
            assertEquals("a refused claim returns failure", WorkInfo.State.FAILED, workManager.getWorkInfoById(request.id).get()?.state)
        }
        val after = directory.listFiles().orEmpty().associate { it.name to (it.length() to sha256Of(it)) }
        assertEquals("no file was touched", before, after)
        assertTrue(sentinel.exists())
        assertTrue("no request reached the server", server.requests.isEmpty())
        assertNull("nothing was registered", app.jobRegistry.current(JobKind.MODEL_INSTALL))
        assertTrue("no notification was shown", progressNotification() == null)
    }

    private companion object {
        const val PROGRESS_NOTIFICATION_ID = 2001
    }
}

/** One request as the loopback server saw it. */
class SeenRequest(val path: String, val range: String?, val ifRange: String?)

/**
 * A small HTTP/1.1 server on 127.0.0.1 for the download tests: `/model.bin` serves the payload with an `ETag` and
 * `Range`/`If-Range` support at a limited speed (so a test can act while bytes are flowing), `/bad.bin` serves
 * other bytes of the same length, `/redirect` answers with a redirect to a foreign host.
 */
class LoopbackServer : AutoCloseable {
    val payload: ByteArray = ByteArray(PAYLOAD_BYTES).also { Random(42).nextBytes(it) }
    val sha256: String = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    val requests: MutableList<SeenRequest> = Collections.synchronizedList(mutableListOf())

    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort

    @Volatile
    private var closed = false
    private val acceptor = Thread { acceptLoop() }.apply { isDaemon = true; start() }

    private fun acceptLoop() {
        while (!closed) {
            val client = try { socket.accept() } catch (_: IOException) { return }
            Thread { client.use { handle(it) } }.apply { isDaemon = true; start() }
        }
    }

    private fun handle(client: Socket) {
        try {
            val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = reader.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            }
            val path = requestLine.split(' ')[1]
            requests += SeenRequest(path, headers["range"], headers["if-range"])
            val out = client.getOutputStream()
            when (path) {
                "/redirect" -> out.write("HTTP/1.1 302 Found\r\nLocation: http://example.org/model.bin\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                "/model.bin" -> serve(out, payload, headers)
                "/bad.bin" -> serve(out, ByteArray(payload.size) { (payload[it] + 1).toByte() }, headers)
                else -> out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            }
            out.flush()
        } catch (_: IOException) {
            // the client went away (a cancel or a pause): that is the point of several tests
        }
    }

    private fun serve(out: java.io.OutputStream, bytes: ByteArray, headers: Map<String, String>) {
        val from = headers["range"]?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
        val ranged = from != null && headers["if-range"] == ETAG
        val start = if (ranged) from!! else 0
        val head = if (ranged) {
            "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-${bytes.size - 1}/${bytes.size}\r\n"
        } else {
            "HTTP/1.1 200 OK\r\n"
        } + "ETag: $ETAG\r\nContent-Length: ${bytes.size - start}\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        var offset = start
        while (offset < bytes.size) {
            val length = minOf(CHUNK, bytes.size - offset)
            out.write(bytes, offset, length)
            out.flush()
            offset += length
            Thread.sleep(CHUNK_DELAY_MS)
        }
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
        acceptor.interrupt()
    }

    private companion object {
        const val PAYLOAD_BYTES = 16 * 1024 * 1024
        const val CHUNK = 64 * 1024
        const val CHUNK_DELAY_MS = 15L
        const val ETAG = "\"loopback-v1\""
    }
}
