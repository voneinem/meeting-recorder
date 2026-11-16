package app.meetingrecorder.playback

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import java.io.IOException

/** What the playback screen shows. Positions and durations are on the audio timeline in milliseconds. */
data class PlayerState(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    /** True once the audio is prepared and the controls work. */
    val ready: Boolean = false,
    val failed: Boolean = false,
)

/**
 * Plays one meeting's `audio.wav` with the platform [MediaPlayer] (FR-028, research R-15). It holds
 * decoder resources only between [prepare] and [release]; the screen releases it when it leaves the
 * foreground.
 */
class MeetingPlayer(private val context: Context) {
    private var player: MediaPlayer? = null
    private var durationMs = 0L

    @Volatile
    private var completed = false

    /** Opens [audio] and seeks to [startMs]. Blocks while preparing: call it off the main thread. */
    @Synchronized
    fun prepare(audio: Uri, startMs: Long, fallbackDurationMs: Long): Boolean {
        release()
        val created = MediaPlayer()
        return try {
            created.setDataSource(context, audio)
            created.setOnCompletionListener { completed = true }
            created.prepare()
            durationMs = created.duration.toLong().takeIf { it > 0 } ?: fallbackDurationMs
            created.seekTo(startMs.coerceIn(0, durationMs), MediaPlayer.SEEK_CLOSEST)
            completed = false
            player = created
            true
        } catch (_: IOException) {
            created.release()
            false
        } catch (_: IllegalStateException) {
            created.release()
            false
        } catch (_: SecurityException) {
            created.release()
            false
        }
    }

    @Synchronized
    fun play() {
        val current = player ?: return
        if (completed) {
            current.seekTo(0, MediaPlayer.SEEK_CLOSEST)
            completed = false
        }
        current.start()
    }

    @Synchronized
    fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
    }

    /** Jumps to [positionMs], clamped to the recording; playing or paused stays as it is. */
    @Synchronized
    fun seekTo(positionMs: Long) {
        val current = player ?: return
        current.seekTo(positionMs.coerceIn(0, durationMs), MediaPlayer.SEEK_CLOSEST)
        completed = false
    }

    /** The current state; cheap enough to poll a few times a second. */
    @Synchronized
    fun snapshot(): PlayerState {
        val current = player ?: return PlayerState(durationMs = durationMs)
        val position = if (completed) durationMs else current.currentPosition.toLong().coerceIn(0, durationMs)
        return PlayerState(positionMs = position, durationMs = durationMs, playing = !completed && current.isPlaying, ready = true)
    }

    @Synchronized
    fun release() {
        player?.release()
        player = null
    }
}
