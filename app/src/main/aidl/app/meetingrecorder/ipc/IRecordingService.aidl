package app.meetingrecorder.ipc;

import app.meetingrecorder.ipc.IRecordingCallback;
import app.meetingrecorder.ipc.RecordingStateParcel;

/**
 * Commands from the UI process to the recording service (contracts/recording-service.md). The
 * service never reads app preferences: everything it needs arrives with start().
 */
interface IRecordingService {
    void registerCallback(IRecordingCallback callback);
    void unregisterCallback(IRecordingCallback callback);
    RecordingStateParcel getState();

    /** deviceId is the Android audio device id of the chosen input, or -1 for the built-in microphone. */
    boolean start(String title, int deviceId, String storageTreeUri);
    void pause();
    void resume();
    void stop();

    /** Returns the timeline position (frames appended) at the time of the call, or -1 when idle. */
    long markPhotoPosition();
    void addPhoto(String file, long atFrame);

    /**
     * Creates the next empty photos/photo-NNN.jpg of the current meeting and returns its document
     * URI, or null when no meeting is active or the file cannot be created. The UI process writes
     * the JPEG into it and then calls addPhoto() with the file name.
     */
    String createPhotoFile();
}
