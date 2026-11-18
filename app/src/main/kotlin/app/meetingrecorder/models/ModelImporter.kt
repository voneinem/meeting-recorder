package app.meetingrecorder.models

import app.meetingrecorder.core.models.DownloadRules
import app.meetingrecorder.core.models.HeaderResult
import app.meetingrecorder.core.models.InstalledModel
import app.meetingrecorder.core.models.ModelCatalog
import app.meetingrecorder.core.models.ModelCatalogEntry
import app.meetingrecorder.core.models.ModelFileHeader
import app.meetingrecorder.core.models.ModelFiles
import app.meetingrecorder.core.models.ModelOrigin
import app.meetingrecorder.core.models.Sha256Streaming
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The file a user picked. The app implements it over a `Uri`; tests use memory. */
interface ImportSource {
    val displayName: String?

    /** The size the provider reports, or null when it does not say. */
    val sizeBytes: Long?

    fun open(): InputStream
}

interface ImportListener {
    fun onProgress(bytesDone: Long, total: Long)

    fun isCancelled(): Boolean
}

sealed interface ImportResult {
    data class Installed(val model: InstalledModel) : ImportResult

    /** A model with the same hash is installed already; nothing was changed. */
    data class AlreadyInstalled(val model: InstalledModel) : ImportResult

    /** The file is not a speech recognition model. */
    data object Rejected : ImportResult

    data class NotEnoughSpace(val missingBytes: Long) : ImportResult

    data object Cancelled : ImportResult

    data class Failed(val message: String?) : ImportResult
}

/**
 * Imports a speech recognition model from a file (contracts/model-catalog.md, "Import protocol"): the
 * header is checked before anything is copied, the free space against the provider's size, then the file is
 * copied while it is hashed. A file whose hash is a catalog entry is recognised as that entry (the origin
 * stays `imported`); any other gets the id `imported-<12 hex>` and is listed by its file name. Needs no
 * network, so it lives outside `models/download`. Does blocking I/O: call it off the main thread.
 */
class ModelImporter(
    private val repository: ModelRepository,
    private val catalog: () -> List<ModelCatalogEntry> = { ModelCatalog.entries },
    private val now: () -> String = { java.time.Instant.now().toString() },
) {
    fun import(source: ImportSource, listener: ImportListener): ImportResult {
        val staging = repository.partFile(STAGING_ID)
        try {
            source.open().use { input ->
                val head = readHead(input)
                val languages = (ModelFileHeader.parse(head) as? HeaderResult.Speech)?.languages ?: return ImportResult.Rejected

                val declared = source.sizeBytes
                if (declared != null && declared > 0) {
                    val missing = repository.missingBytes(declared, 0)
                    if (missing > 0) return ImportResult.NotEnoughSpace(missing)
                }

                repository.directory.mkdirs()
                val digest = Sha256Streaming().update(head)
                var copied = head.size.toLong()
                staging.outputStream().use { out ->
                    out.write(head)
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        if (listener.isCancelled()) {
                            staging.delete()
                            return ImportResult.Cancelled
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copied += read
                        listener.onProgress(copied, declared ?: copied)
                    }
                    out.flush()
                }
                if (declared != null && declared != copied) {
                    staging.delete()
                    return ImportResult.Failed("the file has $copied bytes, the provider said $declared")
                }
                listener.onProgress(copied, copied)
                return finish(source.displayName, languagesOf = languages, hash = digest.hex(), size = copied, staging = staging)
            }
        } catch (e: IOException) {
            staging.delete()
            return ImportResult.Failed(e.message)
        }
    }

    /** The first [ModelFileHeader.SIZE] bytes, or fewer when the file is shorter (`readNBytes` needs API 33). */
    private fun readHead(input: InputStream): ByteArray {
        val head = ByteArray(ModelFileHeader.SIZE)
        var filled = 0
        while (filled < head.size) {
            val read = input.read(head, filled, head.size - filled)
            if (read < 0) break
            filled += read
        }
        return head.copyOf(filled)
    }

    private fun finish(
        fileName: String?,
        languagesOf: app.meetingrecorder.core.models.ModelLanguages,
        hash: String,
        size: Long,
        staging: File,
    ): ImportResult {
        val entry = catalog().firstOrNull { it.sha256 == hash && it.sizeBytes == size }
        val id = entry?.id ?: InstalledModel.importedId(hash)
        repository.find(id)?.takeIf { it.sha256 == hash }?.let {
            staging.delete()
            return ImportResult.AlreadyInstalled(it)
        }
        val model = InstalledModel(
            id = id,
            displayName = entry?.displayNameKey ?: fileName?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME,
            origin = ModelOrigin.IMPORTED,
            sha256 = hash,
            sizeBytes = size,
            languages = entry?.languages ?: languagesOf,
            installedAt = now(),
        )
        Files.move(staging.toPath(), repository.binFile(id).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        repository.writeSidecar(model)
        File(repository.directory, ModelFiles.partStateName(STAGING_ID)).delete()
        return ImportResult.Installed(model)
    }

    private companion object {
        /** The id the partial copy carries until its hash, and with it the real id, is known. */
        const val STAGING_ID = "import-in-progress"
        const val DEFAULT_NAME = "model.bin"
        const val BUFFER = 64 * 1024
    }
}
