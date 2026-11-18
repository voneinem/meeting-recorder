package app.meetingrecorder.recording

import android.content.Context
import android.net.Uri
import android.util.Log
import app.meetingrecorder.core.folder.MeetingFolderLayout
import app.meetingrecorder.core.json.MeetingJson
import app.meetingrecorder.core.model.MeetingStatus
import app.meetingrecorder.core.recovery.RecoveryPlanner
import app.meetingrecorder.core.recovery.RecoveryPlanner.Action
import app.meetingrecorder.core.recovery.RecoveryPlanner.MetadataState
import app.meetingrecorder.core.wav.RepairResult
import app.meetingrecorder.core.wav.WavHeader
import app.meetingrecorder.storage.DocumentRef
import app.meetingrecorder.storage.MeetingMetadataStore
import app.meetingrecorder.storage.SafMeetingStorage
import java.io.IOException

/**
 * Repairs meetings whose recording was interrupted by a crash, a system kill or a restart (FR-025):
 * fixes the WAV header, marks the meeting `recovered` and regenerates `CLAUDE.md`. It only touches
 * folders the [RecoveryPlanner] asks it to, and never deletes or rewrites unknown files.
 */
class RecoveryRunner(context: Context, treeUri: Uri) {
    private val storage = SafMeetingStorage(context, treeUri)
    private val metadata = MeetingMetadataStore.forStorage(storage)

    private companion object {
        const val TAG = "RecoveryRunner"
        const val MAX_LIST_ATTEMPTS = 4
        const val RETRY_DELAY_MS = 1_500L
    }

    data class Outcome(
        val folderName: String,
        val recovered: Boolean,
        val unrecoverable: String?,
        val unlistedPhotos: List<String>,
    )

    /**
     * Inspects every meeting folder except [activeFolder], the one a running recording is writing.
     * Does blocking storage I/O: call it off the main thread.
     */
    fun run(activeFolder: String?): List<Outcome> {
        var root = storage.listChildren(storage.rootUri)
        var attempt = 1
        // The very first query of a fresh process can come back empty while the provider warms up.
        while (root.isEmpty() && attempt < MAX_LIST_ATTEMPTS) {
            Thread.sleep(RETRY_DELAY_MS)
            attempt++
            root = storage.listChildren(storage.rootUri)
            Log.d(TAG, "attempt $attempt: root has ${root.size} entries")
        }
        Log.d(TAG, "root has ${root.size} entries: ${root.map { it.name + (if (it.isDirectory) "/" else "") }}")
        return root
            .filter { it.isDirectory && it.name != activeFolder }
            .mapNotNull { folder ->
                runCatching { recover(folder) }
                    .onFailure { Log.w(TAG, "recovery of ${folder.name} failed", it) }
                    .getOrNull()
                    ?.also { Log.i(TAG, "${it.folderName}: recovered=${it.recovered} unrecoverable=${it.unrecoverable}") }
            }
    }

    private fun recover(folder: DocumentRef): Outcome? {
        val children = storage.listChildren(folder.uri)
        Log.d(TAG, "${folder.name}: children=${children.map { it.name }}")
        if (!MeetingFolderLayout.isMeetingFolder(children.map { it.name })) return null

        val metadataFile = children.firstOrNull { it.name == MeetingFolderLayout.METADATA_FILE }
        val metadata = readMetadata(metadataFile)
        Log.d(TAG, "${folder.name}: metadata=${metadata::class.simpleName}")
        // Finished meetings need no repair; skip the audio I/O for them.
        if (metadata is MetadataState.Readable && metadata.meeting.status != MeetingStatus.RECORDING) {
            return null
        }

        val audio = children.firstOrNull { it.name == MeetingFolderLayout.AUDIO_FILE }
        val (size, header) = audio?.let(::readAudioHead) ?: (null to null)
        val photos = children.firstOrNull { it.isDirectory && it.name == MeetingFolderLayout.PHOTOS_DIR }
            ?.let { dir -> storage.listChildren(dir.uri).filter { !it.isDirectory }.map { it.name } }
            .orEmpty()

        val plan = RecoveryPlanner.plan(
            RecoveryPlanner.FolderSnapshot(folder.name, photos, size, header, metadata),
        )
        if (plan.actions.isEmpty()) {
            return Outcome(folder.name, recovered = false, unrecoverable = plan.unrecoverable, unlistedPhotos = emptyList())
        }
        apply(plan.actions, folder, audio)
        return Outcome(folder.name, recovered = true, unrecoverable = null, unlistedPhotos = plan.unlistedPhotos)
    }

    private fun readMetadata(file: DocumentRef?): MetadataState {
        if (file == null) return MetadataState.Missing
        val text = storage.readText(file.uri) ?: return MetadataState.Unreadable("cannot read meeting.json")
        return when (val decoded = MeetingJson.decode(text)) {
            is MeetingJson.DecodeResult.Success -> MetadataState.Readable(decoded.meeting)
            is MeetingJson.DecodeResult.Malformed -> MetadataState.Unreadable(decoded.reason)
            is MeetingJson.DecodeResult.UnsupportedVersion -> MetadataState.Unreadable("unsupported schemaVersion ${decoded.version}")
        }
    }

    private fun readAudioHead(audio: DocumentRef): Pair<Long?, ByteArray?> = runCatching {
        storage.openStore(audio.uri).use { store ->
            val buffer = ByteArray(WavHeader.SIZE)
            val read = store.readAt(0, buffer).coerceAtLeast(0)
            store.length() to buffer.copyOf(read)
        }
    }.getOrDefault(null to null)

    private fun apply(actions: List<Action>, folder: DocumentRef, audio: DocumentRef?) {
        for (action in actions) {
            when (action) {
                Action.RepairWav -> {
                    val file = audio ?: throw IOException("no audio file to repair")
                    val result = storage.openStore(file.uri).use { WavHeader.repair(it) }
                    if (result is RepairResult.Unrecoverable) throw IOException(result.reason)
                }
                is Action.WriteMetadata -> {
                    // The store replaces meeting.json through the staged protocol and regenerates CLAUDE.md.
                    if (!metadata.replace(folder.name, action.meeting)) throw IOException("cannot write ${MeetingFolderLayout.METADATA_FILE}")
                }
                // Already done by the metadata store together with the metadata.
                Action.WriteGuide -> Unit
            }
        }
    }
}
