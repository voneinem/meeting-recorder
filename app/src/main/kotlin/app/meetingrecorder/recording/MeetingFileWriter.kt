package app.meetingrecorder.recording

import android.net.Uri
import app.meetingrecorder.core.folder.MeetingFolderLayout
import app.meetingrecorder.core.guide.ClaudeGuide
import app.meetingrecorder.core.json.MeetingJson
import app.meetingrecorder.core.model.MeetingEditor
import app.meetingrecorder.core.model.MeetingMetadata
import app.meetingrecorder.core.model.SourceInfo
import app.meetingrecorder.storage.ParcelSeekableStore
import app.meetingrecorder.storage.SafMeetingStorage
import java.io.IOException
import java.time.OffsetDateTime

/**
 * Creates, updates and finalizes the meeting folder of a recording (FR-018, FR-019, FR-023). The
 * folder and `meeting.json` exist from the first second, so an interrupted recording leaves a valid
 * folder behind.
 */
class MeetingFileWriter(private val storage: SafMeetingStorage) {

    /** A meeting folder that is being recorded. [meeting] is only changed through this writer. */
    class OpenMeeting(
        val folderName: String,
        val folderUri: Uri,
        val photosUri: Uri,
        val metadataUri: Uri,
        val audioStore: ParcelSeekableStore,
        var meeting: MeetingMetadata,
    )

    fun create(title: String?, startedAt: OffsetDateTime, sampleRate: Int, initialSource: SourceInfo): OpenMeeting {
        val existing = storage.listChildren(storage.rootUri).map { it.name }
        val folderName = MeetingFolderLayout.uniqueFolderName(
            MeetingFolderLayout.folderName(startedAt.toLocalDateTime()),
            existing,
        )
        val folder = storage.createDirectory(storage.rootUri, folderName)
            ?: throw IOException("cannot create the meeting folder")
        try {
            val photos = storage.createDirectory(folder, MeetingFolderLayout.PHOTOS_DIR)
                ?: throw IOException("cannot create the photos folder")
            val metadata = storage.createFile(folder, MeetingFolderLayout.METADATA_FILE, SafMeetingStorage.MIME_JSON)
                ?: throw IOException("cannot create meeting.json")
            val audio = storage.createFile(folder, MeetingFolderLayout.AUDIO_FILE, SafMeetingStorage.MIME_WAV)
                ?: throw IOException("cannot create the audio file")
            val meeting = MeetingEditor.create(title, startedAt, sampleRate, initialSource)
            val open = OpenMeeting(folderName, folder, photos, metadata, storage.openStore(audio), meeting)
            try {
                writeMetadata(open)
            } catch (e: IOException) {
                open.audioStore.close()
                throw e
            }
            return open
        } catch (e: IOException) {
            storage.delete(folder)
            throw e
        }
    }

    /** Rewrites `meeting.json` in one truncating write. */
    fun writeMetadata(open: OpenMeeting) {
        storage.writeText(open.metadataUri, MeetingJson.encode(open.meeting))
    }

    /**
     * Finishes the meeting: sets the final duration and status, writes `meeting.json` and `CLAUDE.md`
     * and closes the audio file. The WAV header must already be final (the writer is closed).
     */
    fun finalize(open: OpenMeeting, framesRecorded: Long): MeetingMetadata {
        open.audioStore.close()
        val finished = MeetingEditor.finish(open.meeting, framesRecorded)
        open.meeting = finished
        writeMetadata(open)
        val guide = storage.findChild(open.folderUri, MeetingFolderLayout.GUIDE_FILE)?.uri
            ?: storage.createFile(open.folderUri, MeetingFolderLayout.GUIDE_FILE, SafMeetingStorage.MIME_MARKDOWN)
            ?: throw IOException("cannot create CLAUDE.md")
        storage.writeText(guide, ClaudeGuide.generate(finished))
        return finished
    }

    /** Removes the whole folder, for example when nothing was recorded. */
    fun discard(open: OpenMeeting) {
        runCatching { open.audioStore.close() }
        storage.delete(open.folderUri)
    }
}
