package app.meetingrecorder.storage

import android.net.Uri
import app.meetingrecorder.core.folder.MeetingFolderLayout
import app.meetingrecorder.core.guide.ClaudeGuide
import app.meetingrecorder.core.json.MeetingJson
import app.meetingrecorder.core.model.MeetingEditor
import app.meetingrecorder.core.model.MeetingMetadata
import app.meetingrecorder.core.model.MeetingStatus
import app.meetingrecorder.core.time.Timeline
import app.meetingrecorder.core.wav.WavHeader
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** One row of the meeting list. A folder with unreadable metadata is still listed (FR-026). */
data class MeetingEntry(
    val folderName: String,
    val folderUri: Uri,
    val title: String,
    /** Start in epoch milliseconds, or the folder's modification time when the metadata is unreadable. */
    val startedAtMs: Long,
    val durationMs: Long?,
    val status: MeetingStatus?,
    /** False when `meeting.json` is missing, unreadable or of an unknown version. */
    val metadataReadable: Boolean,
)

/** Everything the playback screen needs about one meeting. [meeting] is null when the metadata is unreadable. */
class MeetingDetail(
    val folderName: String,
    val title: String,
    val meeting: MeetingMetadata?,
    val audioUri: Uri,
    /** Photo document URIs by their path in `meeting.json` (for example `photos/photo-001.jpg`). */
    val photoUris: Map<String, Uri>,
)

/** Lists the meetings found in the storage folder. The folders are the only source of truth. */
class MeetingRepository(private val storage: SafMeetingStorage) {

    /** All meetings, newest first. Does blocking storage I/O: call it off the main thread. */
    fun list(): List<MeetingEntry> =
        storage.listChildren(storage.rootUri)
            .filter { it.isDirectory }
            .mapNotNull(::entryOf)
            .sortedByDescending { it.startedAtMs }

    /** Loads one meeting for playback, or null when the folder or its audio file is gone. */
    fun detail(folderName: String): MeetingDetail? {
        val folder = storage.listChildren(storage.rootUri).firstOrNull { it.isDirectory && it.name == folderName } ?: return null
        val children = storage.listChildren(folder.uri)
        val audio = children.firstOrNull { it.name == MeetingFolderLayout.AUDIO_FILE } ?: return null
        val meeting = readMeeting(children)
        val photos = children.firstOrNull { it.isDirectory && it.name == MeetingFolderLayout.PHOTOS_DIR }
            ?.let { dir -> storage.listChildren(dir.uri).associate { "${MeetingFolderLayout.PHOTOS_DIR}/${it.name}" to it.uri } }
            .orEmpty()
        return MeetingDetail(folderName, meeting?.title ?: folderName, meeting, audio.uri, photos)
    }

    /**
     * Changes only the title in `meeting.json` (one truncating write) and regenerates `CLAUDE.md` so it
     * stays consistent. The folder name never changes (FR-027). Returns false when the metadata cannot
     * be read, the title is invalid or a write fails.
     */
    fun rename(folderName: String, newTitle: String): Boolean = runCatching {
        val folder = storage.listChildren(storage.rootUri).firstOrNull { it.isDirectory && it.name == folderName }
            ?: return@runCatching false
        val children = storage.listChildren(folder.uri)
        val metadataFile = children.firstOrNull { it.name == MeetingFolderLayout.METADATA_FILE } ?: return@runCatching false
        val meeting = readMeeting(children) ?: return@runCatching false
        val renamed = MeetingEditor.rename(meeting, newTitle)
        storage.writeText(metadataFile.uri, MeetingJson.encode(renamed))
        val guide = children.firstOrNull { it.name == MeetingFolderLayout.GUIDE_FILE }?.uri
            ?: storage.createFile(folder.uri, MeetingFolderLayout.GUIDE_FILE, SafMeetingStorage.MIME_MARKDOWN)
        guide?.let { storage.writeText(it, ClaudeGuide.generate(renamed)) }
        true
    }.getOrDefault(false)

    /** Deletes the whole meeting folder. Returns true when it is gone. */
    fun delete(folderName: String): Boolean {
        val folder = storage.listChildren(storage.rootUri).firstOrNull { it.isDirectory && it.name == folderName } ?: return true
        return storage.delete(folder.uri)
    }

    private fun readMeeting(children: List<DocumentRef>): MeetingMetadata? {
        val file = children.firstOrNull { it.name == MeetingFolderLayout.METADATA_FILE } ?: return null
        val decoded = storage.readText(file.uri)?.let(MeetingJson::decode)
        return (decoded as? MeetingJson.DecodeResult.Success)?.meeting
    }

    private fun entryOf(folder: DocumentRef): MeetingEntry? {
        val children = storage.listChildren(folder.uri)
        if (!MeetingFolderLayout.isMeetingFolder(children.map { it.name })) return null
        val metadataFile = children.firstOrNull { it.name == MeetingFolderLayout.METADATA_FILE }
        val decoded = metadataFile?.let { storage.readText(it.uri) }?.let(MeetingJson::decode)
        if (decoded is MeetingJson.DecodeResult.Success) {
            val meeting = decoded.meeting
            return MeetingEntry(
                folderName = folder.name,
                folderUri = folder.uri,
                title = meeting.title,
                startedAtMs = parseStart(meeting.startedAt) ?: folder.lastModifiedMs,
                durationMs = meeting.audio.durationMs,
                status = meeting.status,
                metadataReadable = true,
            )
        }
        val audio = children.firstOrNull { it.name == MeetingFolderLayout.AUDIO_FILE }
        return MeetingEntry(
            folderName = folder.name,
            folderUri = folder.uri,
            title = folder.name,
            startedAtMs = startFromFolderName(folder.name) ?: folder.lastModifiedMs,
            durationMs = audio?.let { wavDurationMs(it) },
            status = null,
            metadataReadable = false,
        )
    }

    private fun parseStart(startedAt: String): Long? = try {
        OffsetDateTime.parse(startedAt).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    private fun startFromFolderName(name: String): Long? = try {
        LocalDateTime.parse(name.take(FOLDER_TIME_LENGTH), FOLDER_TIME).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    /** Duration from the file length and the WAV header's format, when no metadata is available. */
    private fun wavDurationMs(audio: DocumentRef): Long? = runCatching {
        storage.openStore(audio.uri).use { store ->
            val header = ByteArray(WavHeader.SIZE)
            if (store.readAt(0, header) < WavHeader.SIZE) return@use null
            val format = WavHeader.parse(header) ?: return@use null
            val frames = (store.length() - WavHeader.SIZE) / format.blockAlign
            Timeline.framesToMs(frames.coerceAtLeast(0), format.sampleRate)
        }
    }.getOrNull()

    private companion object {
        val FOLDER_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        const val FOLDER_TIME_LENGTH = 19
    }
}
