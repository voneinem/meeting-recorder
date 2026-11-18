package app.meetingrecorder.models.download

import app.meetingrecorder.core.models.HostPolicy
import app.meetingrecorder.core.models.ModelCatalogEntry
import app.meetingrecorder.core.models.ModelKind
import app.meetingrecorder.core.models.ModelLanguages
import app.meetingrecorder.core.models.ModelOrigin
import app.meetingrecorder.core.models.Sha256Streaming
import app.meetingrecorder.models.ModelRepository
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The downloader against a loopback `HttpServer`; the debug-only loopback host policy is injected. */
class ModelDownloaderTest {
    private lateinit var root: File
    private lateinit var models: File
    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<Map<String, String>>()

    private val body = ByteArray(300 * 1024) { (it * 31 + it / 7).toByte() }
    private val bodyHash = Sha256Streaming().update(body).hex()

    /** Behavior switches of the test server. */
    @Volatile private var servedBody = body
    @Volatile private var supportsRange = true
    @Volatile private var cutAfter: Int? = null
    @Volatile private var chunkDelayMs = 0L
    @Volatile private var chunkSize = 64 * 1024
    @Volatile private var wrongContentRange = false
    @Volatile private var linkedEtag: String? = null
    @Volatile private var etag: String? = "\"$bodyHash\""
    @Volatile private var status = 200

    private val base get() = "http://127.0.0.1:${server.address.port}"

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("downloader-test").toFile()
        models = File(root, "models")
        server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        root.deleteRecursively()
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            requests += exchange.requestHeaders.entries.associate { it.key to it.value.joinToString(",") } +
                mapOf("::path" to exchange.requestURI.path)
            val path = exchange.requestURI.path
            if (path.startsWith("/hop/")) {
                val n = path.removePrefix("/hop/").toInt()
                val target = if (n <= 0) "$base/model.bin" else "$base/hop/${n - 1}"
                exchange.responseHeaders.add("Location", target)
                exchange.sendResponseHeaders(302, -1)
                return
            }
            if (path == "/to-foreign") {
                exchange.responseHeaders.add("Location", "http://example.com/model.bin")
                exchange.sendResponseHeaders(302, -1)
                return
            }
            if (status != 200) {
                exchange.sendResponseHeaders(status, -1)
                return
            }
            linkedEtag?.let { exchange.responseHeaders.add("x-linked-etag", it) }
            etag?.let { exchange.responseHeaders.add("ETag", it) }
            val data = servedBody
            val range = exchange.requestHeaders.getFirst("Range")?.takeIf { supportsRange }
            val start = range?.removePrefix("bytes=")?.removeSuffix("-")?.toLongOrNull() ?: 0L
            if (range != null) {
                exchange.responseHeaders.add("Content-Range", if (wrongContentRange) "bytes 0-${data.size - 1}/${data.size}" else "bytes $start-${data.size - 1}/${data.size}")
                exchange.sendResponseHeaders(206, data.size - start)
            } else {
                exchange.sendResponseHeaders(200, data.size.toLong())
            }
            val out = exchange.responseBody
            var position = start.toInt()
            var sent = 0
            while (position < data.size) {
                val count = minOf(chunkSize, data.size - position)
                val limit = cutAfter
                if (limit != null && sent + count > limit) {
                    out.write(data, position, limit - sent)
                    out.flush()
                    exchange.close() // the connection ends before the announced length
                    return
                }
                out.write(data, position, count)
                out.flush()
                position += count
                sent += count
                if (chunkDelayMs > 0) Thread.sleep(chunkDelayMs)
            }
        }
    }

    private fun entry(path: String = "/model.bin", size: Long = body.size.toLong(), sha: String = bodyHash) = ModelCatalogEntry(
        id = "tiny-q5_1", kind = ModelKind.SPEECH, displayNameKey = "model_tiny_name", descriptionKey = "model_tiny_description",
        languages = ModelLanguages.MULTILINGUAL, sizeBytes = size, sha256 = sha, url = base + path,
        sourceName = "Hugging Face (huggingface.co)", license = "MIT", approxMemoryMb = 300,
    )

    private fun repository() = ModelRepository(directory = models, now = { "2026-11-17T08:00:00Z" })

    private fun downloader(
        repository: ModelRepository = repository(),
        progressIntervalMs: Long = 500,
        stateWriteBytes: Long = 4L * 1024 * 1024,
        stateWriteIntervalMs: Long = 2_000,
        onStateWritten: () -> Unit = {},
    ) = ModelDownloader(
        repository = repository,
        policy = HostPolicy.DEBUG_LOOPBACK,
        progressIntervalMs = progressIntervalMs,
        stateWriteBytes = stateWriteBytes,
        stateWriteIntervalMs = stateWriteIntervalMs,
        onPartStateWritten = onStateWritten,
    )

    private class Recorder(private val cancelAfterBytes: Long? = null) : DownloadListener {
        override val control = DownloadControl()
        val reports = CopyOnWriteArrayList<Pair<Long, Long>>() // time, bytesDone

        override fun onProgress(bytesDone: Long, total: Long) {
            reports += System.currentTimeMillis() to bytesDone
            if (cancelAfterBytes != null && bytesDone >= cancelAfterBytes) control.stop(Stop.CANCEL)
        }
    }

    private fun filesInModels(): Set<String> = models.list()?.toSet().orEmpty()

    @Test
    fun aFullDownloadIsVerifiedAndInstalled() {
        val result = downloader().download(entry(), "Tiny", Recorder())

        val installed = assertIs<DownloadResult.Installed>(result).model
        assertEquals(bodyHash, installed.sha256)
        assertEquals(ModelOrigin.CATALOG, installed.origin)
        assertEquals(setOf("tiny-q5_1.bin", "tiny-q5_1.json"), filesInModels())
        assertTrue(body.contentEquals(File(models, "tiny-q5_1.bin").readBytes()))
        assertNotNull(repository().find("tiny-q5_1"))
    }

    @Test
    fun progressIsReportedAtLeastOncePerSecondAndAtTheEnd() {
        servedBody = body
        chunkSize = 50 * 1024
        chunkDelayMs = 450 // six chunks, about 2.7 s in total
        val recorder = Recorder()

        assertIs<DownloadResult.Installed>(downloader(progressIntervalMs = 500).download(entry(), "Tiny", recorder))

        val times = recorder.reports.map { it.first }
        assertTrue(times.size >= 3, "expected several reports, got ${times.size}")
        assertTrue(times.zipWithNext().all { (a, b) -> b - a < 1_000 }, "a gap of one second or more between reports")
        assertEquals(body.size.toLong(), recorder.reports.last().second)
        assertTrue(recorder.reports.map { it.second }.zipWithNext().all { (a, b) -> b >= a }, "progress never decreases")
    }

    @Test
    fun theTransferredBytesAreWrittenToThePartStateAtMostEveryFourMibOrTwoSeconds() {
        val large = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        servedBody = large
        val largeHash = Sha256Streaming().update(large).hex()
        var writes = 0

        assertIs<DownloadResult.Installed>(
            downloader(onStateWritten = { writes++ }).download(entry(size = large.size.toLong(), sha = largeHash), "Tiny", Recorder()),
        )

        assertTrue(writes <= 2, "a quick 3 MiB download wrote the state $writes times (limit: at the start and at most once more)")
    }

    @Test
    fun theStateIsWrittenAgainAfterTheConfiguredNumberOfBytes() {
        chunkSize = 16 * 1024
        var writes = 0

        assertIs<DownloadResult.Installed>(
            downloader(stateWriteBytes = 64 * 1024, onStateWritten = { writes++ }).download(entry(), "Tiny", Recorder()),
        )

        assertTrue(writes >= 4, "expected a state write every 64 KiB, got $writes")
    }

    @Test
    fun cancelDeletesThePartialFilesAndInstallsNothing() {
        chunkSize = 50 * 1024
        chunkDelayMs = 100

        val result = downloader().download(entry(), "Tiny", Recorder(cancelAfterBytes = 100 * 1024))

        assertIs<DownloadResult.Cancelled>(result)
        assertEquals(emptySet(), filesInModels())
    }

    @Test
    fun anInterruptedTransferKeepsTheFilesAndTheNextCallResumesWithRangeAndIfRange() {
        cutAfter = 150 * 1024
        val interrupted = downloader().download(entry(), "Tiny", Recorder())

        assertIs<DownloadResult.Interrupted>(interrupted)
        assertTrue(File(models, "tiny-q5_1.part").isFile)
        val state = assertNotNull(repository().partState("tiny-q5_1"))
        assertTrue(state.bytesDone > 0 && state.bytesDone <= File(models, "tiny-q5_1.part").length())
        assertNull(repository().find("tiny-q5_1"), "an interrupted model is not installed")

        cutAfter = null
        requests.clear()
        val resumed = downloader().download(entry(), "Tiny", Recorder())

        assertIs<DownloadResult.Installed>(resumed)
        val request = requests.single { it["::path"] == "/model.bin" }
        assertEquals("bytes=${state.bytesDone}-", request["Range"])
        assertEquals("\"$bodyHash\"", request["If-range"])
        assertTrue(body.contentEquals(File(models, "tiny-q5_1.bin").readBytes()))
        assertEquals(setOf("tiny-q5_1.bin", "tiny-q5_1.json"), filesInModels())
    }

    @Test
    fun aServerThatIgnoresTheRangeRestartsTheDownload() {
        cutAfter = 150 * 1024
        assertIs<DownloadResult.Interrupted>(downloader().download(entry(), "Tiny", Recorder()))

        cutAfter = null
        supportsRange = false
        val result = downloader().download(entry(), "Tiny", Recorder())

        assertIs<DownloadResult.Installed>(result)
        assertTrue(body.contentEquals(File(models, "tiny-q5_1.bin").readBytes()))
    }

    @Test
    fun aPartialAnswerWithAWrongContentRangeIsAnError() {
        cutAfter = 150 * 1024
        assertIs<DownloadResult.Interrupted>(downloader().download(entry(), "Tiny", Recorder()))

        cutAfter = null
        wrongContentRange = true
        val result = downloader().download(entry(), "Tiny", Recorder())

        val failed = assertIs<DownloadResult.Failed>(result)
        assertEquals(DownloadFailure.BAD_RESPONSE, failed.reason)
        assertNull(repository().find("tiny-q5_1"))
    }

    @Test
    fun aWrongChecksumDeletesThePartialFilesAndReportsIt() {
        servedBody = ByteArray(body.size) { 1 } // same size, other content

        val result = downloader().download(entry(), "Tiny", Recorder())

        val failed = assertIs<DownloadResult.Failed>(result)
        assertEquals(DownloadFailure.CHECKSUM_MISMATCH, failed.reason)
        assertEquals(emptySet(), filesInModels())
    }

    @Test
    fun aRedirectToADisallowedHostIsRefused() {
        val result = downloader().download(entry(path = "/to-foreign"), "Tiny", Recorder())

        val failed = assertIs<DownloadResult.Failed>(result)
        assertEquals(DownloadFailure.UNEXPECTED_ADDRESS, failed.reason)
        assertEquals(emptySet(), filesInModels())
    }

    @Test
    fun anAddressTheReleasePolicyDoesNotAllowIsRefusedWithoutAnyRequest() {
        val release = ModelDownloader(repository(), HostPolicy.RELEASE)

        val result = release.download(entry(), "Tiny", Recorder())

        assertEquals(DownloadFailure.UNEXPECTED_ADDRESS, assertIs<DownloadResult.Failed>(result).reason)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun fiveAddressesAreFollowedButMoreAreRefused() {
        // /hop/3 is the original address plus three redirects, then the file: five addresses, the limit.
        assertIs<DownloadResult.Installed>(downloader().download(entry(path = "/hop/3"), "Tiny", Recorder()))
        File(models, "tiny-q5_1.bin").delete()
        File(models, "tiny-q5_1.json").delete()

        val tooMany = downloader().download(entry(path = "/hop/4"), "Tiny", Recorder())

        assertEquals(DownloadFailure.TOO_MANY_REDIRECTS, assertIs<DownloadResult.Failed>(tooMany).reason)
        assertEquals(emptySet(), filesInModels())
    }

    @Test
    fun aDifferingLinkedEtagAbortsBeforeAnyBytesAreStored() {
        linkedEtag = "\"${"0".repeat(64)}\""

        val result = downloader().download(entry(), "Tiny", Recorder())

        assertEquals(DownloadFailure.FILE_CHANGED_ON_SERVER, assertIs<DownloadResult.Failed>(result).reason)
        assertEquals(emptySet(), filesInModels())
    }

    @Test
    fun aMatchingLinkedEtagIsAccepted() {
        linkedEtag = "\"$bodyHash\""
        assertIs<DownloadResult.Installed>(downloader().download(entry(), "Tiny", Recorder()))
    }

    @Test
    fun aFileOfAnotherSizeIsRefused() {
        val result = downloader().download(entry(size = body.size + 5L), "Tiny", Recorder())

        assertEquals(DownloadFailure.BAD_RESPONSE, assertIs<DownloadResult.Failed>(result).reason)
        assertEquals(emptySet(), filesInModels())
    }

    @Test
    fun aServerErrorFailsWithoutLeavingFiles() {
        status = 404

        val result = downloader().download(entry(), "Tiny", Recorder())

        assertEquals(DownloadFailure.BAD_RESPONSE, assertIs<DownloadResult.Failed>(result).reason)
        assertEquals(emptySet(), filesInModels())
    }

    @Test
    fun theRequestCarriesOnlyTheGenericUserAgentAndNothingThatIdentifiesTheUser() {
        assertIs<DownloadResult.Installed>(downloader().download(entry(), "Tiny", Recorder()))

        val headers = requests.flatMap { it.keys }.filter { !it.startsWith("::") }.map { it.lowercase() }.toSet()
        assertEquals("MeetingRecorder", requests.first()["User-agent"])
        val allowed = setOf("host", "user-agent", "accept", "connection", "range", "if-range", "accept-encoding")
        assertTrue(headers.all { it in allowed }, "unexpected request headers: ${headers - allowed}")
        assertFalse(headers.any { it in setOf("cookie", "authorization", "referer", "x-requested-with") })
    }

    @Test
    fun stoppingForARecordingKeepsThePartialFilesSoTheDownloadCanResume() {
        chunkSize = 50 * 1024
        chunkDelayMs = 100
        val recorder = object : DownloadListener {
            override val control = DownloadControl()
            override fun onProgress(bytesDone: Long, total: Long) {
                if (bytesDone >= 100 * 1024) control.stop(Stop.PAUSE)
            }
        }

        val result = downloader().download(entry(), "Tiny", recorder)

        assertIs<DownloadResult.Interrupted>(result)
        assertTrue(File(models, "tiny-q5_1.part").isFile)
        assertNotNull(repository().partState("tiny-q5_1"))
    }
}
