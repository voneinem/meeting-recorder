package app.meetingrecorder

import android.app.Application
import app.meetingrecorder.recording.RecordingController

class MeetingRecorderApp : Application() {
    /** The UI-side client of the recording service. Created lazily, so the service process never builds it. */
    val recordingController: RecordingController by lazy { RecordingController(this) }
}
