package app.meetingrecorder.transcription

import app.meetingrecorder.core.model.FailureReason
import app.meetingrecorder.core.models.InstalledModel
import app.meetingrecorder.core.transcription.DecodeOutcome
import app.meetingrecorder.core.transcription.DecodeRequest
import app.meetingrecorder.core.transcription.LanguageProbability
import app.meetingrecorder.core.transcription.PcmWindow
import app.meetingrecorder.core.transcription.RawSegment
import app.meetingrecorder.core.transcription.SegmentSink
import app.meetingrecorder.core.transcription.SpeechDecoder
import app.meetingrecorder.models.ModelRepository
import app.meetingrecorder.whisper.DecodeParams
import app.meetingrecorder.whisper.DecodeStatus
import app.meetingrecorder.whisper.WhisperContext
import app.meetingrecorder.whisper.WhisperException
import java.io.File

/** The model could not be loaded; [reason] says why, in the vocabulary of the job block. */
class DecoderLoadException(val reason: FailureReason, message: String?) : Exception(message)

/** Creates the decoder of a job. Implemented over the native engine in the app and faked in tests. */
fun interface DecoderFactory {
    /** Loads [model]. Throws [DecoderLoadException]. */
    fun create(model: InstalledModel, threads: Int): SpeechDecoder
}

/**
 * [SpeechDecoder] over [WhisperContext]: maps segments and statuses and never logs text. Native failures
 * become [DecodeOutcome.Failed]; a stop request arrives through [abort] from another thread.
 */
class WhisperSpeechDecoder(
    private val context: WhisperContext,
    private val threads: Int,
    private val vadModel: File?,
) : SpeechDecoder {

    override fun detectLanguage(window: PcmWindow): List<LanguageProbability> =
        context.detectLanguage(window.buffer, window.count, threads).map { LanguageProbability(it.code, it.probability.toDouble()) }

    @Suppress("TooGenericExceptionCaught")
    override fun decode(window: PcmWindow, request: DecodeRequest, sink: SegmentSink): DecodeOutcome = try {
        val params = DecodeParams(
            language = request.language,
            threads = request.threads,
            initialPrompt = request.initialPrompt,
            suppressNonSpeech = true,
            vadModel = vadModel.takeIf { request.vad },
        )
        when (context.decode(window.buffer, window.count, params) { sink.onSegment(RawSegment(it.startCs, it.endCs, it.text)) }) {
            DecodeStatus.COMPLETED -> DecodeOutcome.Completed
            DecodeStatus.ABORTED -> DecodeOutcome.Aborted
        }
    } catch (e: OutOfMemoryError) {
        DecodeOutcome.Failed(FailureReason.OUT_OF_MEMORY)
    } catch (e: WhisperException) {
        DecodeOutcome.Failed(FailureReason.ERROR)
    }

    override fun abort() = context.abort()

    override fun close() = context.close()
}

/** Loads installed models with the native engine. [vadModel] is the voice-activity model file, when one is installed. */
class WhisperDecoderFactory(
    private val repository: ModelRepository,
    private val nativeLibraryDir: String,
    private val vadModel: () -> File?,
) : DecoderFactory {
    override fun create(model: InstalledModel, threads: Int): SpeechDecoder {
        val context = try {
            WhisperContext.load(repository.binFile(model.id), nativeLibraryDir)
        } catch (e: WhisperException) {
            val reason = if (e.code == WhisperException.LOAD_FAILED) FailureReason.MODEL_INVALID else FailureReason.ERROR
            throw DecoderLoadException(reason, e.message)
        } catch (e: OutOfMemoryError) {
            throw DecoderLoadException(FailureReason.OUT_OF_MEMORY, e.message)
        }
        return WhisperSpeechDecoder(context, threads, vadModel())
    }
}
