package app.meetingrecorder.transcription

import app.meetingrecorder.core.folder.MeetingFolderLayout
import app.meetingrecorder.core.model.MeetingEditor
import app.meetingrecorder.core.model.TranscriptInfo
import app.meetingrecorder.core.models.ModelOrigin
import app.meetingrecorder.core.models.Sha256Streaming
import app.meetingrecorder.core.transcript.Transcript
import app.meetingrecorder.core.transcript.TranscriptJson
import app.meetingrecorder.core.transcript.TranscriptMarkdown
import app.meetingrecorder.core.transcript.TranscriptValidator
import app.meetingrecorder.core.transcription.WorkFile
import app.meetingrecorder.storage.FolderFiles
import app.meetingrecorder.storage.MeetingMetadataStore
import java.io.IOException
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

sealed interface CommitOutcome {
    data object Committed : CommitOutcome

    /** There is no work file with an `end` line: a job that can be resumed or discarded, never committed. */
    data object NothingToCommit : CommitOutcome

    /** A step failed; the commit is safe to repeat. */
    data class Failed(val cause: Exception) : CommitOutcome
}

/**
 * The commit protocol of contracts/transcript-folder.md. It starts from a work file that has its `end`
 * line, and every step is safe to repeat:
 *
 * 1. build the transcript, write `transcript.json.part`, read it back and compare;
 * 2. build `transcript.md` and write `transcript.md.part`;
 * 3. replace `transcript.json` and `transcript.md` (delete the old file, rename the part file);
 * 4. update `meeting.json` (transcript set, job removed) and 5. regenerate `CLAUDE.md`, both through
 *    [MeetingMetadataStore];
 * 6. delete the work file **last**: it is the commit record, a recovery repeats the commit while it exists.
 *
 * `audio.wav` and `photos/` are never written. Does blocking storage I/O.
 */
class TranscriptCommitter(
    private val files: FolderFiles,
    private val metadata: MeetingMetadataStore,
    private val folderName: String,
    private val modelName: String,
    private val modelOrigin: ModelOrigin,
    private val now: () -> OffsetDateTime = { OffsetDateTime.now() },
) {
    fun commit(): CommitOutcome = try {
        run()
    } catch (e: IOException) {
        CommitOutcome.Failed(e)
    } catch (e: IllegalStateException) {
        CommitOutcome.Failed(e)
    }

    private fun run(): CommitOutcome {
        val bytes = files.readText(MeetingFolderLayout.TRANSCRIPT_WORK_FILE)?.toByteArray(Charsets.UTF_8) ?: return CommitOutcome.NothingToCommit
        val records = WorkFile.decode(bytes).records
        if (!WorkFile.isComplete(records)) return CommitOutcome.NothingToCommit
        val meeting = metadata.read(folderName) ?: throw IOException("meeting.json cannot be read")

        // Step 1: transcript.json.part, read back and compared.
        val generatedAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now())
        val transcript = WorkFile.toTranscript(records, modelName, modelOrigin, generatedAt)
        val violations = TranscriptValidator.validate(transcript)
        check(violations.isEmpty()) { "the transcript breaks its own rules: ${violations.joinToString { it.rule }}" }
        val json = TranscriptJson.encode(transcript)
        stage(MeetingFolderLayout.TRANSCRIPT_JSON_FILE, json)
        val back = files.readText(MeetingFolderLayout.partName(MeetingFolderLayout.TRANSCRIPT_JSON_FILE))?.let(TranscriptJson::decode)
        if ((back as? TranscriptJson.DecodeResult.Success)?.transcript != transcript) throw IOException("transcript.json.part does not read back equal")

        // Step 2: transcript.md.part, from the title and photos in meeting.json and the hash of the JSON bytes.
        val hash = Sha256Streaming().update(json.toByteArray(Charsets.UTF_8)).hex()
        val markdown = TranscriptMarkdown.generate(meeting, transcript, hash)
        stage(MeetingFolderLayout.TRANSCRIPT_MD_FILE, markdown)

        // Step 3: replace both final files (the old one stays until the staged copy is complete).
        files.replaceText(MeetingFolderLayout.TRANSCRIPT_JSON_FILE, json)
        files.delete(MeetingFolderLayout.partName(MeetingFolderLayout.TRANSCRIPT_JSON_FILE))
        files.replaceText(MeetingFolderLayout.TRANSCRIPT_MD_FILE, markdown)
        files.delete(MeetingFolderLayout.partName(MeetingFolderLayout.TRANSCRIPT_MD_FILE))

        // Steps 4 and 5: meeting.json and CLAUDE.md.
        val info = infoOf(transcript)
        if (!metadata.update(folderName, strictGuide = true) { MeetingEditor.commitTranscript(it, info) }) {
            throw IOException("meeting.json or CLAUDE.md could not be updated")
        }

        // Step 6: the commit record goes last.
        if (!files.delete(MeetingFolderLayout.TRANSCRIPT_WORK_FILE)) throw IOException("the work file could not be deleted")
        return CommitOutcome.Committed
    }

    /** Writes `<name>.part` as a new file (an older part file of an interrupted attempt is removed first). */
    private fun stage(name: String, text: String) {
        val part = MeetingFolderLayout.partName(name)
        files.delete(part)
        files.replaceText(part, text)
    }

    private fun infoOf(transcript: Transcript) = TranscriptInfo(
        modelId = transcript.model.id,
        modelName = transcript.model.name,
        requestedLanguage = transcript.language.requested,
        language = transcript.language.used,
        completedAt = transcript.generatedAt,
        processingMs = transcript.processing?.durationMs ?: 0,
        segmentCount = transcript.segments.size,
    )
}
