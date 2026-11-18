package app.meetingrecorder.core.transcription

import app.meetingrecorder.core.models.ModelOrigin
import app.meetingrecorder.core.transcript.EngineInfo
import app.meetingrecorder.core.transcript.ProcessingInfo
import app.meetingrecorder.core.transcript.Transcript
import app.meetingrecorder.core.transcript.TranscriptAudio
import app.meetingrecorder.core.transcript.TranscriptLanguage
import app.meetingrecorder.core.transcript.TranscriptModel
import app.meetingrecorder.core.transcript.TranscriptSegment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One recognised segment in absolute frames, as stored in the work file. */
public data class WorkSegment(val startFrame: Long, val endFrame: Long, val text: String)

/** One line of `transcript.work.jsonl` (contracts/transcript-folder.md, "Work file"). */
public sealed interface WorkRecord {

    /** First line. [pipeline] is bumped whenever a change of the chunking can change the result. */
    public data class Header(
        val modelId: String,
        val modelSha256: String,
        val requestedLanguage: String,
        val vad: Boolean,
        val sampleRate: Int,
        val durationFrames: Long,
        val engineVersion: String,
        val pipeline: Int,
        val startedAt: String,
        val v: Int = WorkFile.VERSION,
    ) : WorkRecord

    /** The language chosen once for the whole meeting; [probes] are the probe positions in frames. */
    public data class Language(val code: String, val confidence: Double?, val probes: List<Long>) : WorkRecord

    /** One decoded window; [nextFrame] is where the next window starts. */
    public data class Window(
        val fromFrame: Long,
        val nextFrame: Long,
        val elapsedMs: Long,
        val segments: List<WorkSegment>,
    ) : WorkRecord

    /** Last line, written once when the whole audio is done. */
    public data class End(val segmentCount: Int, val elapsedMsTotal: Long) : WorkRecord
}

/** What the folder holds as a work file. [NONE] also covers a file whose header cannot be used. */
public enum class WorkFileState {
    NONE,

    /** A usable header, no `end` line: a job that can be resumed or discarded, never committed. */
    INCOMPLETE,

    /** An `end` line: the commit is not finished yet. */
    COMPLETE,
}

/**
 * The checkpoint file of an unfinished transcription: UTF-8, one JSON object per line, every line
 * ends with `\n`. A torn tail (a line without its newline, or one that does not parse) ends the read;
 * everything after it is ignored.
 */
public object WorkFile {

    public const val VERSION: Int = 1

    private val json = Json

    public fun encodeLine(record: WorkRecord): String = json.encodeToString(JsonObject.serializer(), toJson(record)) + "\n"

    /** [goodLength] is the byte size of the complete lines; [torn] is true when bytes remain after them. */
    public data class Decoded(val records: List<WorkRecord>, val goodLength: Int, val torn: Boolean)

    public fun decode(bytes: ByteArray): Decoded {
        val records = ArrayList<WorkRecord>()
        var goodLength = 0
        var start = 0
        while (start < bytes.size) {
            val newline = (start until bytes.size).firstOrNull { bytes[it] == NEWLINE } ?: break
            val record = parseLine(String(bytes, start, newline - start, Charsets.UTF_8)) ?: break
            if (!fits(records, record)) break
            records += record
            start = newline + 1
            goodLength = start
        }
        return Decoded(records, goodLength, torn = goodLength < bytes.size)
    }

    /** A header comes first and only once; nothing follows the `end` line. */
    private fun fits(records: List<WorkRecord>, next: WorkRecord): Boolean = when {
        records.isEmpty() -> next is WorkRecord.Header
        records.last() is WorkRecord.End -> false
        else -> next !is WorkRecord.Header
    }

    public fun isComplete(records: List<WorkRecord>): Boolean = records.lastOrNull() is WorkRecord.End

    /** The processing time: the `elapsedMs` of all windows, resume sessions included. */
    public fun elapsedMs(records: List<WorkRecord>): Long = records.filterIsInstance<WorkRecord.Window>().sumOf { it.elapsedMs }

    /** Where a resumed job continues: the `nextFrame` of the last window, `0` before the first. */
    public fun resumeFrame(records: List<WorkRecord>): Long =
        records.filterIsInstance<WorkRecord.Window>().lastOrNull()?.nextFrame ?: 0L

    /** What a new request must match for a work file to be resumed. */
    public data class ResumeRequest(
        val requestedLanguage: String,
        val vad: Boolean,
        val sampleRate: Int,
        val durationFrames: Long,
        val pipeline: Int,
    )

    public sealed interface ResumeCheck {
        public data object Allowed : ResumeCheck

        /** [field] names the header field that differs, or `model` when the model file is not installed. */
        public data class Mismatch(val field: String) : ResumeCheck
    }

    /**
     * Resume is allowed only if the model file is installed ([installedModelSha256] is its hash, or
     * `null`) and `modelSha256`, `requestedLanguage`, `vad`, `sampleRate`, `durationFrames` and
     * `pipeline` equal the new request. Otherwise only "start over" is offered.
     */
    public fun canResume(header: WorkRecord.Header, request: ResumeRequest, installedModelSha256: String?): ResumeCheck = when {
        installedModelSha256 == null -> ResumeCheck.Mismatch("model")
        header.modelSha256 != installedModelSha256 -> ResumeCheck.Mismatch("modelSha256")
        header.requestedLanguage != request.requestedLanguage -> ResumeCheck.Mismatch("requestedLanguage")
        header.vad != request.vad -> ResumeCheck.Mismatch("vad")
        header.sampleRate != request.sampleRate -> ResumeCheck.Mismatch("sampleRate")
        header.durationFrames != request.durationFrames -> ResumeCheck.Mismatch("durationFrames")
        header.pipeline != request.pipeline -> ResumeCheck.Mismatch("pipeline")
        else -> ResumeCheck.Allowed
    }

    /**
     * Builds the transcript from the records: segments in file order, collapsed by [RepetitionGuard],
     * numbered `0..n-1`; the processing time is the sum of the window times.
     */
    public fun toTranscript(
        records: List<WorkRecord>,
        modelName: String,
        modelOrigin: ModelOrigin,
        generatedAt: String,
    ): Transcript {
        val header = requireNotNull(records.filterIsInstance<WorkRecord.Header>().firstOrNull()) { "the work file has no header" }
        val language = requireNotNull(records.filterIsInstance<WorkRecord.Language>().firstOrNull()) { "the work file has no language line" }
        val segments = RepetitionGuard.collapse(records.filterIsInstance<WorkRecord.Window>().flatMap { it.segments })
        return Transcript(
            generatedAt = generatedAt,
            audio = TranscriptAudio(header.sampleRate, header.durationFrames),
            model = TranscriptModel(header.modelId, modelName, header.modelSha256, modelOrigin),
            language = TranscriptLanguage(
                requested = header.requestedLanguage,
                used = language.code,
                detected = header.requestedLanguage == "auto",
                confidence = language.confidence,
            ),
            engine = EngineInfo(version = header.engineVersion),
            processing = ProcessingInfo(durationMs = elapsedMs(records), vad = header.vad),
            segments = segments.mapIndexed { index, s -> TranscriptSegment(index, s.startFrame, s.endFrame, s.text) },
        )
    }

    private fun toJson(record: WorkRecord): JsonObject = when (record) {
        is WorkRecord.Header -> buildJsonObject {
            put("t", "header")
            put("v", record.v)
            put("modelId", record.modelId)
            put("modelSha256", record.modelSha256)
            put("requestedLanguage", record.requestedLanguage)
            put("vad", record.vad)
            put("sampleRate", record.sampleRate)
            put("durationFrames", record.durationFrames)
            put("engineVersion", record.engineVersion)
            put("pipeline", record.pipeline)
            put("startedAt", record.startedAt)
        }
        is WorkRecord.Language -> buildJsonObject {
            put("t", "language")
            put("code", record.code)
            put("confidence", record.confidence?.let { JsonPrimitive(it) } ?: JsonNull)
            put("probes", JsonArray(record.probes.map { JsonPrimitive(it) }))
        }
        is WorkRecord.Window -> buildJsonObject {
            put("t", "window")
            put("fromFrame", record.fromFrame)
            put("nextFrame", record.nextFrame)
            put("elapsedMs", record.elapsedMs)
            put(
                "segments",
                JsonArray(
                    record.segments.map {
                        buildJsonObject {
                            put("startFrame", it.startFrame)
                            put("endFrame", it.endFrame)
                            put("text", it.text)
                        }
                    },
                ),
            )
        }
        is WorkRecord.End -> buildJsonObject {
            put("t", "end")
            put("segmentCount", record.segmentCount)
            put("elapsedMsTotal", record.elapsedMsTotal)
        }
    }

    /** `null` for a line that is not valid JSON, has an unknown type, a wrong version or a missing field. */
    @Suppress("TooGenericExceptionCaught")
    private fun parseLine(line: String): WorkRecord? = try {
        val o = json.parseToJsonElement(line).jsonObject
        when (o.str("t")) {
            "header" -> if (o.int("v") == VERSION) {
                WorkRecord.Header(
                    modelId = o.str("modelId"),
                    modelSha256 = o.str("modelSha256"),
                    requestedLanguage = o.str("requestedLanguage"),
                    vad = checkNotNull(o.getValue("vad").jsonPrimitive.booleanOrNull),
                    sampleRate = o.int("sampleRate"),
                    durationFrames = o.long("durationFrames"),
                    engineVersion = o.str("engineVersion"),
                    pipeline = o.int("pipeline"),
                    startedAt = o.str("startedAt"),
                )
            } else {
                null
            }
            "language" -> WorkRecord.Language(
                code = o.str("code"),
                confidence = o.getValue("confidence").let { if (it is JsonNull) null else checkNotNull(it.jsonPrimitive.doubleOrNull) },
                probes = o.getValue("probes").jsonArray.map { checkNotNull(it.jsonPrimitive.longOrNull) },
            )
            "window" -> WorkRecord.Window(
                fromFrame = o.long("fromFrame"),
                nextFrame = o.long("nextFrame"),
                elapsedMs = o.long("elapsedMs"),
                segments = o.getValue("segments").jsonArray.map {
                    val s = it.jsonObject
                    WorkSegment(s.long("startFrame"), s.long("endFrame"), s.str("text"))
                },
            )
            "end" -> WorkRecord.End(o.int("segmentCount"), o.long("elapsedMsTotal"))
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.also { check(it.isString) }.content

    private fun JsonObject.int(key: String): Int = checkNotNull(getValue(key).jsonPrimitive.intOrNull)

    private fun JsonObject.long(key: String): Long = checkNotNull(getValue(key).jsonPrimitive.longOrNull)

    private const val NEWLINE: Byte = '\n'.code.toByte()
}
