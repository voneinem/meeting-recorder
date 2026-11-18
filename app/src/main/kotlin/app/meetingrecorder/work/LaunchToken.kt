package app.meetingrecorder.work

import java.util.UUID

/**
 * One random token per start of the main process, created in `MeetingRecorderApp.onCreate` (only when
 * `getProcessName() == packageName`). Jobs carry it so that work WorkManager re-runs after a process death
 * or a reboot can be recognized and refused (contracts/transcription-service.md).
 */
object LaunchToken {
    fun newToken(): String = UUID.randomUUID().toString()
}
