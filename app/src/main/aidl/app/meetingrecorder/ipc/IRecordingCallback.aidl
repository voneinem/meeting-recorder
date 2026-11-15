package app.meetingrecorder.ipc;

import app.meetingrecorder.ipc.RecordingEventParcel;
import app.meetingrecorder.ipc.RecordingStateParcel;

/** Calls from the recording service (own process) to the UI process. */
oneway interface IRecordingCallback {
    void onState(in RecordingStateParcel state);
    void onEvent(in RecordingEventParcel event);
}
