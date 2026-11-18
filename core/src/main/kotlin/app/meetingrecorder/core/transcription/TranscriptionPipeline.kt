package app.meetingrecorder.core.transcription

import app.meetingrecorder.core.audio.UnreadableWavException
import app.meetingrecorder.core.audio.WindowSource
import app.meetingrecorder.core.model.FailureReason
import app.meetingrecorder.core.models.ModelLanguages
import java.io.IOException

/** The port that appends one line to the work file and makes it durable. Implemented in the app, faked in tests. */
public fun interface WorkSink {
    /** Throws [IOException] when the line could not be written. */
    public fun append(record: WorkRecord)
}

/** Told after every window where the decoded audio ends, for progress and the remaining-time estimate. */
public fun interface ProgressListener {
    public fun onAnchor(framesDone: Long, wallClockMs: Long)
}

/** Everything the pipeline needs to know about one job. */
public data class PipelineRequest(
    val modelId: String,
    val modelSha256: String,
    val modelLanguages: ModelLanguages,
    /** `auto` or a language code. */
    val requestedLanguage: String,
    /** Language of the device, used when no probe finds signal. */
    val deviceLanguage: String,
    val vad: Boolean,
    val threads: Int,
    val engineVersion: String,
    /** ISO-8601 date-time with offset. */
    val startedAt: String,
    val sampleRate: Int,
    val durationFrames: Long,
)

public sealed interface PipelineOutcome {
    /** The work file ends with its `end` line; the commit can start. */
    public data object Finished : PipelineOutcome

    /** Stopped on request; the work file holds every window decoded so far. */
    public data object Cancelled : PipelineOutcome

    /** The work file is intact up to the last full window. */
    public data class Failed(val reason: FailureReason) : PipelineOutcome
}

/**
 * Decodes a meeting window by window (research R-5, R-6): the language is chosen once, each window
 * is decoded and its accepted segments are appended to the work file as one line, and one `end` line
 * is written when the whole audio is done. A run can stop at any window boundary and be resumed from
 * the work file; the result equals an uninterrupted run.
 */
public class TranscriptionPipeline(
    private val source: WindowSource,
    private val decoder: SpeechDecoder,
    private val sink: WorkSink,
    private val clock: () -> Long,
    private val listener: ProgressListener,
    private val planner: WindowPlanner = WindowPlanner(),
) {

    /**
     * @param resumeFrom the records read from an existing work file (header first), or `null` for a new job.
     *   The caller has checked [WorkFile.canResume] and truncated a torn tail.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    public fun run(request: PipelineRequest, resumeFrom: List<WorkRecord>?, cancel: CancelSignal): PipelineOutcome {
        val records = ArrayList<WorkRecord>()
        try {
            if (resumeFrom == null) {
                append(records, header(request))
            } else {
                require(resumeFrom.firstOrNull() is WorkRecord.Header) { "the work file to resume has no header" }
                records += resumeFrom
                if (WorkFile.isComplete(records)) return PipelineOutcome.Finished
            }

            if (records.none { it is WorkRecord.Language }) {
                if (cancel.isCancelled()) return PipelineOutcome.Cancelled
                val choice = when (val chosen = chooseLanguage(request, cancel)) {
                    is Chosen.Language -> chosen.choice
                    Chosen.Cancelled -> return PipelineOutcome.Cancelled
                    is Chosen.Failed -> return PipelineOutcome.Failed(chosen.reason)
                }
                append(records, WorkRecord.Language(choice.code, choice.confidence, choice.probes))
            }
            val language = records.filterIsInstance<WorkRecord.Language>().first().code

            var cursor = WorkFile.resumeFrame(records)
            var elapsedTotal = WorkFile.elapsedMs(records)
            while (cursor < request.durationFrames) {
                if (cancel.isCancelled()) return PipelineOutcome.Cancelled
                val window = planner.next(cursor, request.durationFrames, request.sampleRate)
                val pcm = source.read(window.fromFrame, window.toFrame, WindowPlanner.MIN_DECODE_SAMPLES)
                val raw = ArrayList<RawSegment>()
                val started = clock()
                val outcome = decoder.decode(pcm, DecodeRequest(language, request.threads, null, request.vad)) {
                    raw += it
                    // This runs on the decode thread: a cancel is passed on to the engine at the next segment.
                    if (cancel.isCancelled()) decoder.abort()
                }
                when (outcome) {
                    DecodeOutcome.Completed -> Unit
                    DecodeOutcome.Aborted -> return PipelineOutcome.Cancelled
                    is DecodeOutcome.Failed -> return PipelineOutcome.Failed(outcome.reason)
                }
                val elapsed = (clock() - started).coerceAtLeast(0)
                val accepted = planner.accept(window, raw, request.sampleRate)
                append(records, WorkRecord.Window(window.fromFrame, accepted.nextFrame, elapsed, accepted.segments))
                elapsedTotal += elapsed
                listener.onAnchor(accepted.nextFrame, clock())
                cursor = accepted.nextFrame
            }

            val segments = RepetitionGuard.collapse(records.filterIsInstance<WorkRecord.Window>().flatMap { it.segments })
            append(records, WorkRecord.End(segments.size, elapsedTotal))
            return PipelineOutcome.Finished
        } catch (e: UnreadableWavException) {
            return PipelineOutcome.Failed(FailureReason.AUDIO_UNREADABLE)
        } catch (e: IOException) {
            return PipelineOutcome.Failed(FailureReason.STORAGE_FULL)
        } catch (e: OutOfMemoryError) {
            return PipelineOutcome.Failed(FailureReason.OUT_OF_MEMORY)
        } catch (e: Exception) {
            return PipelineOutcome.Failed(FailureReason.ERROR)
        }
    }

    private fun append(records: MutableList<WorkRecord>, record: WorkRecord) {
        sink.append(record)
        records += record
    }

    private fun header(request: PipelineRequest) = WorkRecord.Header(
        modelId = request.modelId,
        modelSha256 = request.modelSha256,
        requestedLanguage = request.requestedLanguage,
        vad = request.vad,
        sampleRate = request.sampleRate,
        durationFrames = request.durationFrames,
        engineVersion = request.engineVersion,
        pipeline = PIPELINE_VERSION,
        startedAt = request.startedAt,
    )

    private sealed interface Chosen {
        data class Language(val choice: LanguageChoice) : Chosen

        data object Cancelled : Chosen

        data class Failed(val reason: FailureReason) : Chosen
    }

    /** A fixed language and an English-only model skip detection; otherwise the probes of research R-6 run once. */
    private fun chooseLanguage(request: PipelineRequest, cancel: CancelSignal): Chosen {
        if (request.requestedLanguage != AUTO) return Chosen.Language(LanguageChoice(request.requestedLanguage, null, emptyList()))
        if (request.modelLanguages == ModelLanguages.ENGLISH_ONLY) {
            return Chosen.Language(LanguageProbe.choose(emptyList(), request.modelLanguages, request.deviceLanguage))
        }
        val probes = LanguageProbe.select(request.durationFrames, request.sampleRate, source::rms)
        val results = ArrayList<LanguageProbe.ProbeResult>()
        for (probe in probes) {
            if (cancel.isCancelled()) return Chosen.Cancelled
            val pcm = source.read(probe.fromFrame, probe.toFrame, WindowPlanner.MIN_DECODE_SAMPLES)
            results += LanguageProbe.ProbeResult(probe, decoder.detectLanguage(pcm))
        }
        return Chosen.Language(LanguageProbe.choose(results, request.modelLanguages, request.deviceLanguage))
    }

    public companion object {
        /** Bumped whenever a change of the chunking can change the result; a work file of another version is not resumed. */
        public const val PIPELINE_VERSION: Int = 1

        public const val AUTO: String = "auto"
    }
}
