package app.meetingrecorder.storage

import android.net.Uri
import app.meetingrecorder.core.folder.MeetingFolderLayout
import app.meetingrecorder.core.json.MeetingJson
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

/** Lists the meetings found in the storage folder. The folders are the only source of truth. */
class MeetingRepository(private val storage: SafMeetingStorage) {

    /** All meetings, newest first. Does blocking storage I/O: call it off the main thread. */
    fun list(): List<MeetingEntry> =
        storage.listChildren(storage.rootUri)
            .filter { it.isDirectory }
            .mapNotNull(::entryOf)
            .sortedByDescending { it.startedAtMs }

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
