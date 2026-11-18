package app.meetingrecorder.models

import android.os.StatFs
import app.meetingrecorder.core.models.DownloadRules
import app.meetingrecorder.core.models.InstalledModel
import app.meetingrecorder.core.models.ModelCatalog
import app.meetingrecorder.core.models.ModelCatalogEntry
import app.meetingrecorder.core.models.ModelFiles
import app.meetingrecorder.core.models.ModelOrigin
import app.meetingrecorder.core.models.PartState
import app.meetingrecorder.core.models.Sha256Streaming
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class DeleteResult { DELETED, IN_USE }

/**
 * The speech recognition models on the device: `<filesDir>/models/{<id>.bin, <id>.json, <id>.part,
 * <id>.part.json}` (contracts/model-catalog.md). Models live in app-private storage, outside every meeting
 * folder and outside any backup, so deleting a meeting never deletes a model and deleting a model never
 * touches a transcript (FR-038). Does blocking file I/O: call it off the main thread.
 *
 * @param runningModelId the model of the running transcription, or null (a resumable work file in a
 *   meeting is no reason to refuse a delete: that meeting then offers only "start over")
 * @param onDeleted called after a model was deleted, to clear the default-model preference
 * @param freeSpace bytes free where [directory] lives (`StatFs` in the app)
 */
class ModelRepository(
    val directory: File,
    private val catalog: () -> List<ModelCatalogEntry> = { ModelCatalog.entries },
    private val runningModelId: () -> String? = { null },
    private val onDeleted: (id: String) -> Unit = {},
    private val freeSpace: (File) -> Long = { StatFs(it.path).availableBytes },
    private val now: () -> String = { java.time.Instant.now().toString() },
) {
    /** Models that are installed by the rule of [ModelFiles.installedModel], sorted by id. */
    fun installed(): List<InstalledModel> =
        sidecarIds().mapNotNull { find(it) }.sortedBy { it.id }

    fun find(id: String): InstalledModel? {
        val bin = File(directory, ModelFiles.binName(id))
        val sidecar = File(directory, ModelFiles.sidecarName(id))
        return ModelFiles.installedModel(
            id = id,
            binLength = bin.takeIf { it.isFile }?.length(),
            sidecarText = sidecar.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() },
        )
    }

    fun binFile(id: String): File = File(directory, ModelFiles.binName(id))

    fun partFile(id: String): File = File(directory, ModelFiles.partName(id))

    /** The `.part.json` of [id], or null when missing or unreadable. */
    fun partState(id: String): PartState? =
        File(directory, ModelFiles.partStateName(id)).takeIf { it.isFile }
            ?.let { runCatching { it.readText() }.getOrNull() }
            ?.let(ModelFiles::decodePartState)

    fun writePartState(id: String, state: PartState) {
        stagedWrite(File(directory, ModelFiles.partStateName(id)), ModelFiles.encodePartState(state))
    }

    /** Writes `<id>.json` through a staged rename in the same directory: the model counts as installed only after this. */
    fun writeSidecar(model: InstalledModel) {
        stagedWrite(File(directory, ModelFiles.sidecarName(model.id)), ModelFiles.encodeSidecar(model))
    }

    /**
     * Deletes `.bin`, `.json` and any `.part` files of [id], unless the running transcription uses it. No
     * transcript is touched.
     */
    fun delete(id: String): DeleteResult {
        if (runningModelId() == id) return DeleteResult.IN_USE
        listOf(ModelFiles.binName(id), ModelFiles.sidecarName(id), ModelFiles.partName(id), ModelFiles.partStateName(id))
            .forEach { File(directory, it).delete() }
        onDeleted(id)
        return DeleteResult.DELETED
    }

    fun freeBytes(): Long = freeSpace(directory.takeIf { it.exists() } ?: directory.parentFile ?: directory)

    /** True when [sizeBytes] minus the [bytesAlreadyDownloaded] plus the safety margin fit (FR-007). */
    fun hasSpaceFor(sizeBytes: Long, bytesAlreadyDownloaded: Long): Boolean = missingBytes(sizeBytes, bytesAlreadyDownloaded) == 0L

    /** How many bytes are missing for the transfer; 0 when there is enough room. */
    fun missingBytes(sizeBytes: Long, bytesAlreadyDownloaded: Long): Long =
        (DownloadRules.requiredFreeBytes(sizeBytes, bytesAlreadyDownloaded) - freeBytes()).coerceAtLeast(0)

    /**
     * Cleans up after an interrupted install: a `.bin` without a valid sidecar is hashed once and, when it
     * matches a catalog entry, its sidecar is rebuilt, otherwise the file is deleted; stale staging files and
     * a `.part.json` without its `.part` are removed.
     */
    fun repair() {
        val files = directory.listFiles()?.toList().orEmpty()
        files.filter { it.name.endsWith(STAGING_SUFFIX) }.forEach { it.delete() }
        files.filter { it.name.endsWith(".part.json") }
            .filter { !File(directory, it.name.removeSuffix(".json")).exists() }
            .forEach { it.delete() }
        files.filter { it.isFile && it.name.endsWith(".bin") }.forEach { bin ->
            val id = bin.name.removeSuffix(".bin")
            if (find(id) != null) return@forEach
            val entry = catalog().firstOrNull { it.id == id && it.sizeBytes == bin.length() }
            val hash = if (entry != null) hashOf(bin) else null
            if (entry != null && hash == entry.sha256) {
                writeSidecar(
                    InstalledModel(
                        id = id,
                        displayName = entry.displayNameKey,
                        origin = ModelOrigin.CATALOG,
                        sha256 = hash,
                        sizeBytes = bin.length(),
                        languages = entry.languages,
                        installedAt = now(),
                    ),
                )
            } else {
                bin.delete()
            }
        }
    }

    private fun sidecarIds(): List<String> =
        directory.listFiles()?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(".json") && !it.name.endsWith(".part.json") }
            ?.map { it.name.removeSuffix(".json") }
            ?.toList()
            .orEmpty()

    private fun stagedWrite(target: File, text: String) {
        directory.mkdirs()
        val staging = File(directory, target.name + STAGING_SUFFIX)
        staging.writeText(text)
        try {
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            staging.delete()
            throw e
        }
    }

    private fun hashOf(file: File): String {
        val digest = Sha256Streaming()
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.hex()
    }

    private companion object {
        const val STAGING_SUFFIX = ".tmp"
        const val BUFFER = 64 * 1024
    }
}
