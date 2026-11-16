package app.meetingrecorder.ui.playback

import android.app.Application
import android.graphics.Bitmap
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.meetingrecorder.AppPreferences
import app.meetingrecorder.playback.MeetingPlayer
import app.meetingrecorder.playback.PhotoLoader
import app.meetingrecorder.playback.PlayerState
import app.meetingrecorder.storage.MeetingDetail
import app.meetingrecorder.storage.MeetingRepository
import app.meetingrecorder.storage.SafMeetingStorage
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

data class PlaybackUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val title: String = "",
    val photos: List<PhotoItem> = emptyList(),
    val player: PlayerState = PlayerState(),
    val playbackFailed: Boolean = false,
    val selectedPhoto: PhotoItem? = null,
    val selectedBitmap: Bitmap? = null,
)

/**
 * Playback of one meeting (FR-028, FR-029). The player only exists while the screen is in the
 * foreground; leaving it pauses and releases the player and coming back restores the position.
 */
class PlaybackViewModel(application: Application, savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val folderName: String = checkNotNull(savedState.get<String>(FOLDER_ARG))
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
    }

    private suspend fun load() {
        val treeUri = preferences.storageTreeUri?.toUri()
        val loaded = withContext(Dispatchers.IO) {
            treeUri?.let { runCatching { MeetingRepository(SafMeetingStorage(getApplication(), it)).detail(folderName) }.getOrNull() }
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
        const val FOLDER_ARG = "folder"
        private const val POLL_MS = 200L
        private const val THUMBNAIL_EDGE = 320
        private const val LARGE_EDGE = 1600
    }
}
