package app.meetingrecorder.storage

import android.net.Uri
import app.meetingrecorder.core.folder.MeetingFolderLayout
import app.meetingrecorder.core.json.MeetingJson
import app.meetingrecorder.core.model.MeetingMetadata
import app.meetingrecorder.core.model.MeetingStatus
import app.meetingrecorder.core.time.Timeline
import app.meetingrecorder.core.transcript.Transcript
import app.meetingrecorder.core.transcript.TranscriptJson
import app.meetingrecorder.core.transcript.TranscriptValidator
import app.meetingrecorder.core.transcription.DetailHint
import app.meetingrecorder.core.transcription.Resolved
import app.meetingrecorder.core.transcription.StatusResolver
import app.meetingrecorder.core.transcription.TranscriptState
import app.meetingrecorder.core.transcription.TranscriptionStatus
import app.meetingrecorder.core.transcription.WorkFile
import app.meetingrecorder.core.transcription.WorkFileState
import app.meetingrecorder.core.transcription.WorkRecord
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
    /** Computed from the folder listing and the `transcription` block (FR-035); never read from a cache alone. */
    val transcription: TranscriptionStatus = TranscriptionStatus.None,
    /** True when a failed job can continue from its work file. */
    val resumable: Boolean = false,
    /** Model and language of the latest job, for "Resume", "Start over" and "Retry". */
    val jobModelId: String? = null,
    val jobLanguage: String? = null,
)

/** The state of `transcript.json`: an unreadable file never shows as an empty transcript (FR-030). */
sealed interface TranscriptLoad {
    data object Missing : TranscriptLoad
    data class Valid(val transcript: Transcript) : TranscriptLoad
    data object Unreadable : TranscriptLoad
}

/** Everything the playback screen needs about one meeting. [meeting] is null when the metadata is unreadable. */
class MeetingDetail(
    val folderName: String,
    val title: String,
    val meeting: MeetingMetadata?,
    val audioUri: Uri,
    /** Photo document URIs by their path in `meeting.json` (for example `photos/photo-001.jpg`). */
    val photoUris: Map<String, Uri>,
    val transcript: TranscriptLoad = TranscriptLoad.Missing,
    /** The resolved transcription status with the hint for the detail screen (job failed, banner, ...). */
    val resolved: Resolved = Resolved(TranscriptionStatus.None, DetailHint.None),
)

/** Lists the meetings found in the storage folder. The folders are the only source of truth. */
class MeetingRepository(
    private val storage: SafMeetingStorage,
    /** Whether a transcription of the folder is running in this process (the registry's live flag). */
    private val isLive: (folderName: String) -> Boolean = { false },
) {
    private val metadata = MeetingMetadataStore.forStorage(storage)
    private val renamer = MeetingRenamer(metadata) { name ->
        storage.findChild(storage.rootUri, name)?.takeIf { it.isDirectory }?.let { SafFolderFiles(storage, it.uri) }
    }

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
        val transcript = loadTranscript(children)
        val work = workState(children)
        val resolved = StatusResolver.resolve(
            block = meeting?.transcription,
            transcript = when (transcript) {
                TranscriptLoad.Missing -> TranscriptState.MISSING
                is TranscriptLoad.Valid -> TranscriptState.VALID
                TranscriptLoad.Unreadable -> TranscriptState.UNREADABLE
            },
            work = work,
            live = isLive(folderName),
        )
        return MeetingDetail(folderName, meeting?.title ?: folderName, meeting, audio.uri, photos, transcript, resolved)
    }

    /**
     * Changes only the title in `meeting.json` and regenerates `CLAUDE.md` (and `transcript.md` when a transcript
     * exists) so they stay consistent, as one locked read-modify-write through [MeetingMetadataStore] (it cannot
     * overwrite a concurrent transcription update). The folder name never changes (FR-027). Returns false when the metadata cannot be read, the
     * title is invalid or a write fails.
     */
    fun rename(folderName: String, newTitle: String): Boolean = runCatching { renamer.rename(folderName, newTitle) }.getOrDefault(false)

    /** Deletes the whole meeting folder. Returns true when it is gone. */
    fun delete(folderName: String): Boolean {
        val folder = storage.listChildren(storage.rootUri).firstOrNull { it.isDirectory && it.name == folderName } ?: return true
        return storage.delete(folder.uri)
    }

    /** Decodes and validates `transcript.json` (off the main thread: the caller is on IO). */
    private fun loadTranscript(children: List<DocumentRef>): TranscriptLoad {
        val file = children.firstOrNull { it.name == MeetingFolderLayout.TRANSCRIPT_JSON_FILE } ?: return TranscriptLoad.Missing
        val decoded = storage.readText(file.uri)?.let(TranscriptJson::decode)
        val transcript = (decoded as? TranscriptJson.DecodeResult.Success)?.transcript ?: return TranscriptLoad.Unreadable
        return if (TranscriptValidator.validate(transcript).isEmpty()) TranscriptLoad.Valid(transcript) else TranscriptLoad.Unreadable
    }

    private fun workState(children: List<DocumentRef>): WorkFileState {
        val file = children.firstOrNull { it.name == MeetingFolderLayout.TRANSCRIPT_WORK_FILE } ?: return WorkFileState.NONE
        val records = storage.readText(file.uri)?.let { WorkFile.decode(it.toByteArray(Charsets.UTF_8)).records }.orEmpty()
        return when {
            records.firstOrNull() !is WorkRecord.Header -> WorkFileState.NONE
            WorkFile.isComplete(records) -> WorkFileState.COMPLETE
            else -> WorkFileState.INCOMPLETE
        }
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
            val names = children.map { it.name }.toSet()
            val live = isLive(folder.name)
            return MeetingEntry(
                folderName = folder.name,
                folderUri = folder.uri,
                title = meeting.title,
                startedAtMs = parseStart(meeting.startedAt) ?: folder.lastModifiedMs,
                durationMs = meeting.audio.durationMs,
                status = meeting.status,
                metadataReadable = true,
                transcription = MeetingEntryStatus.of(names, meeting.transcription, live),
                resumable = MeetingEntryStatus.resumable(names, meeting.transcription, live),
                jobModelId = meeting.transcription?.job?.modelId,
                jobLanguage = meeting.transcription?.job?.requestedLanguage,
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
            transcription = MeetingEntryStatus.of(children.map { it.name }.toSet(), null, isLive(folder.name)),
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
