package app.meetingrecorder.ui.playback

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.meetingrecorder.AppPreferences
import app.meetingrecorder.MeetingRecorderApp
import app.meetingrecorder.core.time.Timeline
import app.meetingrecorder.core.transcript.TranscriptItem
import app.meetingrecorder.core.transcript.TranscriptTimeline
import app.meetingrecorder.core.transcription.DetailHint
import app.meetingrecorder.core.transcription.Resolved
import app.meetingrecorder.core.transcription.TranscriptionStatus
import app.meetingrecorder.playback.MeetingPlayer
import app.meetingrecorder.playback.PhotoLoader
import app.meetingrecorder.playback.PlayerState
import app.meetingrecorder.storage.MeetingDetail
import app.meetingrecorder.storage.MeetingRepository
import app.meetingrecorder.storage.SafMeetingStorage
import app.meetingrecorder.storage.TranscriptLoad
import app.meetingrecorder.transcription.Mode
import app.meetingrecorder.transcription.StartResult
import app.meetingrecorder.transcription.TranscriptionEvent
import app.meetingrecorder.transcription.TranscriptionRequest
import app.meetingrecorder.transcription.TranscriptionState
import app.meetingrecorder.ui.transcription.startResultMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One photo in the strip. [atMs] is its position on the audio timeline. */
data class PhotoItem(val path: String, val atMs: Long, val thumbnail: Bitmap?)

/** A row of the transcript view, with its position on the timeline already in milliseconds. */
sealed interface TranscriptRow {
    data class Segment(val id: Int, val startMs: Long, val text: String) : TranscriptRow

    data class Photo(val path: String, val number: Int, val atMs: Long) : TranscriptRow
}

data class PlaybackUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val title: String = "",
    val photos: List<PhotoItem> = emptyList(),
    val player: PlayerState = PlayerState(),
    val playbackFailed: Boolean = false,
    val selectedPhoto: PhotoItem? = null,
    val selectedBitmap: Bitmap? = null,
    val transcript: TranscriptLoad = TranscriptLoad.Missing,
    /** Segments and photos in the order of the folder's `transcript.md` (the photo placement rule). */
    val transcriptRows: List<TranscriptRow> = emptyList(),
    val resolved: Resolved = Resolved(TranscriptionStatus.None, DetailHint.None),
    /** Progress in percent of a transcription of this meeting that runs right now, or null. */
    val jobPercent: Int? = null,
    /** Whether the model of the failed job is still installed (a banner offers only "Start over" otherwise). */
    val modelInstalled: Boolean = true,
    /** A message for the user, for example the answer to a refused resume. */
    val message: Int? = null,
)

/**
 * Playback of one meeting (FR-028, FR-029). The player only exists while the screen is in the
 * foreground; leaving it pauses and releases the player and coming back restores the position.
 */
class PlaybackViewModel(application: Application, savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val folderName: String = checkNotNull(savedState.get<String>(FOLDER_ARG))

    /** The folder name of the meeting shown here. */
    val folder: String get() = folderName
    private val preferences = AppPreferences(application)
    private val player = MeetingPlayer(application)

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var detail: MeetingDetail? = null
    private var foreground = false
    private var preparing = false
    private var resumeAtMs = 0L
    private var ticker: Job? = null

    init {
        viewModelScope.launch { load() }
        // Live progress of a job on this meeting; a finished, failed or cancelled job reloads the transcript.
        val app = recorderApp
        viewModelScope.launch {
            app.transcriptionController.state.collect { job ->
                val active = (job as? TranscriptionState.Active)?.takeIf { it.folderName == folderName }
                _state.value = _state.value.copy(jobPercent = active?.let { (it.progress * PERCENT).toInt().coerceIn(0, PERCENT) })
            }
        }
        viewModelScope.launch {
            app.transcriptionController.events.collect { event ->
                val folder = when (event) {
                    is TranscriptionEvent.Finished -> event.folderName
                    is TranscriptionEvent.Failed -> event.folderName
                    is TranscriptionEvent.Cancelled -> event.folderName
                }
                if (folder == folderName) reloadTranscript()
            }
        }
    }

    private val recorderApp: MeetingRecorderApp get() = getApplication<Application>() as MeetingRecorderApp

    /** Reads `transcript.json` and the status again, for example when a job on this meeting has ended. */
    private suspend fun reloadTranscript() {
        val treeUri = preferences.storageTreeUri?.toUri() ?: return
        val loaded = withContext(Dispatchers.IO) { runCatching { repository(treeUri).detail(folderName) }.getOrNull() } ?: return
        detail = loaded
        _state.value = _state.value.copy(
            transcript = loaded.transcript,
            transcriptRows = rowsOf(loaded),
            resolved = loaded.resolved,
            modelInstalled = loaded.meeting?.transcription?.job?.modelId?.let { recorderApp.modelRepository.find(it) != null } ?: true,
        )
    }

    private fun repository(tree: Uri): MeetingRepository =
        MeetingRepository(SafMeetingStorage(recorderApp, tree)) { folder -> recorderApp.transcriptionController.isRunningFor(folder) }

    /** Segments and photos interleaved by the shared placement rule, positions in milliseconds. */
    private fun rowsOf(loaded: MeetingDetail): List<TranscriptRow> {
        val valid = loaded.transcript as? TranscriptLoad.Valid ?: return emptyList()
        val rate = valid.transcript.audio.sampleRate
        val photos = loaded.meeting?.photos.orEmpty().filter { it.file in loaded.photoUris }
        return TranscriptTimeline.interleave(valid.transcript.segments, photos).map { item ->
            when (item) {
                is TranscriptItem.SegmentItem ->
                    TranscriptRow.Segment(item.segment.id, Timeline.framesToMs(item.segment.startFrame, rate), item.segment.text)
                is TranscriptItem.PhotoItem -> TranscriptRow.Photo(item.photo.file, item.number, item.photo.atMs)
            }
        }
    }

    private suspend fun load() {
        val treeUri = preferences.storageTreeUri?.toUri()
        val loaded = withContext(Dispatchers.IO) {
            treeUri?.let { runCatching { repository(it).detail(folderName) }.getOrNull() }
        }
        if (loaded == null) {
            _state.value = PlaybackUiState(loading = false, notFound = true)
            return
        }
        detail = loaded
        val photos = loaded.meeting?.photos.orEmpty().filter { it.file in loaded.photoUris }
        _state.value = PlaybackUiState(
            loading = false,
            title = loaded.title,
            photos = photos.map { PhotoItem(it.file, it.atMs, null) },
            transcript = loaded.transcript,
            transcriptRows = rowsOf(loaded),
            resolved = loaded.resolved,
            jobPercent = _state.value.jobPercent,
            modelInstalled = loaded.meeting?.transcription?.job?.modelId?.let { recorderApp.modelRepository.find(it) != null } ?: true,
        )
        maybePrepare()
        // Thumbnails are decoded one by one so the screen is usable straight away.
        photos.forEachIndexed { index, photo ->
            val bitmap = withContext(Dispatchers.IO) {
                loaded.photoUris[photo.file]?.let { PhotoLoader.load(getApplication(), it, THUMBNAIL_EDGE) }
            }
            _state.value = _state.value.copy(
                photos = _state.value.photos.mapIndexed { i, item -> if (i == index) item.copy(thumbnail = bitmap) else item },
            )
        }
    }

    /** The screen became visible: restore the player at the last position and start polling. */
    fun onStart() {
        foreground = true
        maybePrepare()
        startTicker()
    }

    /** The screen left the foreground: pause, remember the position and free the player. */
    fun onStop() {
        foreground = false
        ticker?.cancel()
        player.snapshot().takeIf { it.ready }?.let { resumeAtMs = it.positionMs }
        player.pause()
        player.release()
        _state.value = _state.value.copy(player = PlayerState(positionMs = resumeAtMs, durationMs = _state.value.player.durationMs))
    }

    fun playPause() {
        if (player.snapshot().playing) player.pause() else player.play()
        publish()
    }

    /**
     * Tapping a transcript segment: a paused player starts playing from there, a playing one jumps without
     * stopping (FR-031).
     */
    fun seekAndPlay(startMs: Long) {
        player.seekTo(startMs)
        if (!player.snapshot().playing) player.play()
        publish()
    }

    /** "Resume" on the banner: continue the work file with the same model and language. */
    fun resumeJob() = restart(Mode.RESUME)

    /** "Start over" on the banner: decode again from the beginning with the same model and language. */
    fun startOverJob() = restart(Mode.RESTART)

    private fun restart(mode: Mode) {
        val job = detail?.meeting?.transcription?.job ?: return
        viewModelScope.launch {
            val result = recorderApp.transcriptionController.start(
                TranscriptionRequest(folderName, job.modelId, job.requestedLanguage, mode),
            )
            startResultMessage(result)?.takeIf { result != StartResult.Started }
                ?.let { _state.value = _state.value.copy(message = it) }
            reloadTranscript()
        }
    }

    /** "Dismiss" on the banner: removes the job and its work file; a committed transcript stays. */
    fun dismissJob() {
        viewModelScope.launch {
            recorderApp.transcriptionController.dismiss(folderName)
            reloadTranscript()
        }
    }

    fun messageShown() {
        _state.value = _state.value.copy(message = null)
    }

    /** Selects the photo of a transcript row, like tapping its thumbnail. */
    fun selectPhotoByPath(path: String) {
        _state.value.photos.firstOrNull { it.path == path }?.let(::selectPhoto)
    }

    /** Seeks to a position chosen on the seek bar. */
    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        publish()
    }

    /** Jumps to the photo's position on the timeline and shows the photo large (FR-029). */
    fun selectPhoto(photo: PhotoItem) {
        player.seekTo(photo.atMs)
        publish()
        _state.value = _state.value.copy(selectedPhoto = photo, selectedBitmap = photo.thumbnail)
        val uri = detail?.photoUris?.get(photo.path) ?: return
        viewModelScope.launch {
            val large = withContext(Dispatchers.IO) { PhotoLoader.load(getApplication(), uri, LARGE_EDGE) }
            if (_state.value.selectedPhoto == photo && large != null) _state.value = _state.value.copy(selectedBitmap = large)
        }
    }

    fun closePhoto() {
        _state.value = _state.value.copy(selectedPhoto = null, selectedBitmap = null)
    }

    private fun maybePrepare() {
        val current = detail ?: return
        if (!foreground || preparing || player.snapshot().ready) return
        preparing = true
        viewModelScope.launch {
            val fallbackMs = current.meeting?.audio?.durationMs ?: 0L
            val ok = withContext(Dispatchers.IO) { player.prepare(current.audioUri, resumeAtMs, fallbackMs) }
            preparing = false
            _state.value = _state.value.copy(playbackFailed = !ok)
            publish()
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                publish()
                delay(POLL_MS)
            }
        }
    }

    private fun publish() {
        val snapshot = player.snapshot()
        if (snapshot.ready) _state.value = _state.value.copy(player = snapshot)
    }

    override fun onCleared() {
        player.release()
    }

    companion object {
        private const val PERCENT = 100
        const val FOLDER_ARG = "folder"
        private const val POLL_MS = 200L
        private const val THUMBNAIL_EDGE = 320
        private const val LARGE_EDGE = 1600
    }
}
