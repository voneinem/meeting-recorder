package app.meetingrecorder.models.download

import app.meetingrecorder.core.models.DownloadRules
import app.meetingrecorder.core.models.EtagCheck
import app.meetingrecorder.core.models.HopCheck
import app.meetingrecorder.core.models.HostPolicy
import app.meetingrecorder.core.models.InstalledModel
import app.meetingrecorder.core.models.ModelCatalogEntry
import app.meetingrecorder.core.models.ModelFiles
import app.meetingrecorder.core.models.ModelOrigin
import app.meetingrecorder.core.models.PartState
import app.meetingrecorder.core.models.ResponseCheck
import app.meetingrecorder.core.models.Sha256Streaming
import app.meetingrecorder.models.ModelRepository
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Why a stop was requested: discard the partial files, or keep them so the download can resume. */
enum class Stop { NONE, CANCEL, PAUSE }

/** Lets another thread stop a running download; it closes the connection so a blocked read ends at once. */
class DownloadControl {
    @Volatile
    var requested: Stop = Stop.NONE
        private set

    @Volatile
    private var connection: HttpURLConnection? = null

    fun stop(reason: Stop) {
        requested = reason
        connection?.disconnect()
    }

    internal fun attach(connection: HttpURLConnection?) {
        this.connection = connection
        if (requested != Stop.NONE) connection?.disconnect()
    }
}

/** Progress and stop requests of one download; carries no Android types. */
interface DownloadListener {
    val control: DownloadControl

    /** Called at least once per second while bytes arrive, and once when the file is complete. */
    fun onProgress(bytesDone: Long, total: Long)
}

enum class DownloadFailure { CHECKSUM_MISMATCH, UNEXPECTED_ADDRESS, TOO_MANY_REDIRECTS, FILE_CHANGED_ON_SERVER, BAD_RESPONSE }

sealed interface DownloadResult {
    data class Installed(val model: InstalledModel) : DownloadResult

    /** The user cancelled; the partial files are gone. */
    data object Cancelled : DownloadResult

    /** The transfer ended early (network lost, timeout, paused for a recording); the partial files are kept. */
    data class Interrupted(val cause: String?) : DownloadResult

    data class Failed(val reason: DownloadFailure, val detail: String? = null) : DownloadResult
}

/**
 * The model download protocol (contracts/model-catalog.md, steps 3 to 8). This package is the only place
 * that may use `java.net`; `NetworkSourceScannerTest` enforces it.
 *
 * `HttpURLConnection` with automatic redirects off; every hop is checked against [policy]. The body is
 * streamed into `<id>.part` while a SHA-256 digest is fed (on resume the existing bytes are read through the
 * digest first), `bytesDone` is persisted at most every [stateWriteBytes] or [stateWriteIntervalMs], and
 * only a verified file becomes `<id>.bin`. Does blocking network and file I/O: call it off the main thread.
 */
class ModelDownloader(
    private val repository: ModelRepository,
    private val policy: HostPolicy,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    private val progressIntervalMs: Long = PROGRESS_INTERVAL_MS,
    private val stateWriteBytes: Long = STATE_WRITE_BYTES,
    private val stateWriteIntervalMs: Long = STATE_WRITE_INTERVAL_MS,
    private val onPartStateWritten: () -> Unit = {},
    private val now: () -> String = { java.time.Instant.now().toString() },
) {
    private class Response(val connection: HttpURLConnection, val firstHopLinkedEtag: String?)

    private class Abort(val failure: DownloadResult) : Exception()

    /** Downloads [entry] into the repository. [displayName] is stored in the sidecar. */
    fun download(entry: ModelCatalogEntry, displayName: String, listener: DownloadListener): DownloadResult {
        val control = listener.control
        return try {
            run(entry, displayName, listener)
        } catch (abort: Abort) {
            abort.failure
        } catch (e: IOException) {
            if (control.requested == Stop.CANCEL) discard(entry.id).let { DownloadResult.Cancelled } else DownloadResult.Interrupted(e.message)
        } finally {
            control.attach(null)
        }
    }

    private fun run(entry: ModelCatalogEntry, displayName: String, listener: DownloadListener): DownloadResult {
        val control = listener.control
        val id = entry.id
        if (policy.isAllowedHop(entry.url).not()) return DownloadResult.Failed(DownloadFailure.UNEXPECTED_ADDRESS, entry.url)
        repository.directory.mkdirs()
        val part = repository.partFile(id)

        var resumeFrom = resumableBytes(entry)
        var etag = if (resumeFrom > 0) repository.partState(id)?.etag else null
        var restarted = false
        while (true) {
            val response = open(entry, resumeFrom, etag, control)
            val connection = response.connection
            val status = connection.responseCode
            if (status == STATUS_RANGE_NOT_SATISFIABLE && resumeFrom > 0 && !restarted) {
                connection.disconnect()
                discard(id)
                resumeFrom = 0
                etag = null
                restarted = true
                continue
            }
            val check = DownloadRules.validateResponse(
                status = status,
                contentLength = connection.getHeaderField("Content-Length")?.toLongOrNull(),
                contentRange = connection.getHeaderField("Content-Range"),
                requestedFrom = resumeFrom,
                expectedSize = entry.sizeBytes,
            )
            when (check) {
                is ResponseCheck.Reject -> {
                    connection.disconnect()
                    if (resumeFrom > 0 && status == STATUS_PARTIAL) discard(id)
                    return DownloadResult.Failed(DownloadFailure.BAD_RESPONSE, check.reason)
                }
                ResponseCheck.Restart -> resumeFrom = 0
                is ResponseCheck.Accept -> Unit
            }
            val responseEtag = connection.getHeaderField("ETag") ?: response.firstHopLinkedEtag
            return transfer(entry, displayName, listener, connection, startAt = resumeFrom, etag = responseEtag, part = part)
        }
    }

    /** Bytes of an existing partial file that can be continued, or 0 after clearing leftovers that cannot. */
    private fun resumableBytes(entry: ModelCatalogEntry): Long {
        val id = entry.id
        val part = repository.partFile(id)
        val state = repository.partState(id)
        val resumable = ModelFiles.isResumable(
            partLength = part.takeIf { it.isFile }?.length(),
            state = state,
            url = entry.url,
            expectedSize = entry.sizeBytes,
            expectedSha256 = entry.sha256,
        )
        if (!resumable || state == null || state.etag == null || state.bytesDone == 0L) {
            discard(id)
            return 0
        }
        return state.bytesDone
    }

    /** Opens the connection and follows redirects by hand, checking every address. */
    private fun open(entry: ModelCatalogEntry, from: Long, etag: String?, control: DownloadControl): Response {
        val visited = mutableListOf(entry.url)
        var linkedEtag: String? = null
        while (true) {
            when (val hops = DownloadRules.checkHops(policy, visited)) {
                HopCheck.Ok -> Unit
                HopCheck.TooManyHops -> throw Abort(DownloadResult.Failed(DownloadFailure.TOO_MANY_REDIRECTS))
                is HopCheck.UnexpectedAddress -> throw Abort(DownloadResult.Failed(DownloadFailure.UNEXPECTED_ADDRESS, hops.url))
            }
            val connection = (URL(visited.last()).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Encoding", "identity")
                if (from > 0) {
                    setRequestProperty("Range", "bytes=$from-")
                    if (etag != null) setRequestProperty("If-Range", etag)
                }
            }
            control.attach(connection)
            val status = connection.responseCode
            if (visited.size == 1) {
                linkedEtag = connection.getHeaderField("x-linked-etag")
                if (DownloadRules.earlyEtagCheck(linkedEtag, entry.sha256) == EtagCheck.Mismatch) {
                    connection.disconnect()
                    throw Abort(DownloadResult.Failed(DownloadFailure.FILE_CHANGED_ON_SERVER))
                }
            }
            if (status in REDIRECTS) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location == null) throw Abort(DownloadResult.Failed(DownloadFailure.BAD_RESPONSE, "a redirect without a Location"))
                visited += URL(URL(visited.last()), location).toString()
                continue
            }
            return Response(connection, linkedEtag)
        }
    }

    private fun transfer(
        entry: ModelCatalogEntry,
        displayName: String,
        listener: DownloadListener,
        connection: HttpURLConnection,
        startAt: Long,
        etag: String?,
        part: File,
    ): DownloadResult {
        val control = listener.control
        val id = entry.id
        val digest = Sha256Streaming()
        if (startAt > 0) {
            part.inputStream().use { input ->
                var remaining = startAt
                val buffer = ByteArray(BUFFER)
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) throw IOException("the partial file is shorter than its state")
                    digest.update(buffer, 0, read)
                    remaining -= read
                }
            }
        }
        var bytesDone = startAt
        var lastStateBytes = startAt
        var lastStateAt = System.currentTimeMillis()
        var lastProgressAt = 0L
        val total = entry.sizeBytes

        fun persist(out: FileOutputStream) {
            out.flush()
            out.fd.sync()
            repository.writePartState(id, PartState(entry.url, entry.sha256, total, etag, bytesDone))
            onPartStateWritten()
            lastStateBytes = bytesDone
            lastStateAt = System.currentTimeMillis()
        }

        FileOutputStream(part, startAt > 0).use { out ->
            if (startAt > 0) out.channel.truncate(startAt)
            persist(out) // the state with the validator exists from the first byte on
            try {
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER)
                    while (bytesDone < total) {
                        if (control.requested != Stop.NONE) break
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), total - bytesDone).toInt())
                        if (read < 0) throw IOException("the connection ended after $bytesDone of $total bytes")
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        bytesDone += read
                        val time = System.currentTimeMillis()
                        if (bytesDone - lastStateBytes >= stateWriteBytes || time - lastStateAt >= stateWriteIntervalMs) persist(out)
                        if (time - lastProgressAt >= progressIntervalMs || bytesDone == total) {
                            listener.onProgress(bytesDone, total)
                            lastProgressAt = time
                        }
                    }
                }
            } catch (e: IOException) {
                if (control.requested == Stop.NONE) {
                    persist(out)
                    throw e
                }
            }
            if (control.requested != Stop.NONE) {
                if (control.requested == Stop.PAUSE) persist(out)
            }
        }
        connection.disconnect()

        when (control.requested) {
            Stop.CANCEL -> {
                discard(id)
                return DownloadResult.Cancelled
            }
            Stop.PAUSE -> return DownloadResult.Interrupted("paused")
            Stop.NONE -> Unit
        }
        return verifyAndInstall(entry, displayName, digest.hex(), part)
    }

    private fun verifyAndInstall(entry: ModelCatalogEntry, displayName: String, hash: String, part: File): DownloadResult {
        val id = entry.id
        if (!hash.equals(entry.sha256, ignoreCase = true)) {
            discard(id)
            return DownloadResult.Failed(DownloadFailure.CHECKSUM_MISMATCH)
        }
        val bin = repository.binFile(id)
        Files.move(part.toPath(), bin.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        val model = InstalledModel(
            id = id,
            displayName = displayName,
            origin = ModelOrigin.CATALOG,
            sha256 = entry.sha256,
            sizeBytes = entry.sizeBytes,
            languages = entry.languages,
            installedAt = now(),
        )
        repository.writeSidecar(model)
        File(repository.directory, ModelFiles.partStateName(id)).delete()
        return DownloadResult.Installed(model)
    }

    private fun discard(id: String) {
        repository.partFile(id).delete()
        File(repository.directory, ModelFiles.partStateName(id)).delete()
    }

    private companion object {
        const val USER_AGENT = "MeetingRecorder"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val PROGRESS_INTERVAL_MS = 500L
        const val STATE_WRITE_BYTES = 4L * 1024 * 1024
        const val STATE_WRITE_INTERVAL_MS = 2_000L
        const val BUFFER = 64 * 1024
        const val STATUS_PARTIAL = 206
        const val STATUS_RANGE_NOT_SATISFIABLE = 416
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
