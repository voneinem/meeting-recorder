package app.meetingrecorder.transcription

import app.meetingrecorder.core.folder.MeetingFolderLayout
import app.meetingrecorder.core.transcription.WorkFile
import app.meetingrecorder.core.transcription.WorkRecord
import app.meetingrecorder.core.transcription.WorkSink
import app.meetingrecorder.storage.FileStore
import app.meetingrecorder.storage.FolderFiles

/** The app implementations of the pipeline's ports on [FolderFiles]. */
object TranscriptionIo {
    /** The audio, opened **read-only**: a write through it throws, so a transcription cannot damage a recording. */
    fun openAudio(files: FolderFiles): FileStore = files.openReadOnlyStore(MeetingFolderLayout.AUDIO_FILE)

    /** Reads `transcript.work.jsonl`: the records up to the first torn or unparsable line, or null when there is no file. */
    fun readWorkFile(files: FolderFiles): WorkRead? {
        val text = files.readText(MeetingFolderLayout.TRANSCRIPT_WORK_FILE) ?: return null
        val bytes = text.toByteArray(Charsets.UTF_8)
        return WorkRead(bytes, WorkFile.decode(bytes))
    }

    /** Cuts a torn tail off the work file so that the next line is appended after the last good one. */
    fun truncateTornTail(files: FolderFiles, read: WorkRead) {
        if (!read.decoded.torn) return
        files.replaceText(MeetingFolderLayout.TRANSCRIPT_WORK_FILE, String(read.bytes, 0, read.decoded.goodLength, Charsets.UTF_8))
    }

    /** A new, empty work file. */
    fun createWorkFile(files: FolderFiles) {
        files.delete(MeetingFolderLayout.TRANSCRIPT_WORK_FILE)
        files.replaceText(MeetingFolderLayout.TRANSCRIPT_WORK_FILE, "")
    }

    data class WorkRead(val bytes: ByteArray, val decoded: WorkFile.Decoded) {
        val records: List<WorkRecord> get() = decoded.records
    }
}

/** Appends one line per call to the work file and syncs it, so that a kill loses at most the window in progress. */
class WorkFileSink(private val files: FolderFiles) : WorkSink {
    override fun append(record: WorkRecord) {
        files.appendStore(MeetingFolderLayout.TRANSCRIPT_WORK_FILE).use { store ->
            store.append(WorkFile.encodeLine(record).toByteArray(Charsets.UTF_8))
            store.sync()
        }
    }
}
