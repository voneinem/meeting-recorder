# Contract: Recording Service (internal UI ↔ service)

Internal contract between the Compose UI and the foreground `RecordingService` in `:app`. The UI
renders state and sends commands; the service owns capture, writing and metadata. There is no
external or network interface in Phase 1.

## Commands (UI → service)

| Command | Allowed in state | Effect |
|---------|------------------|--------|
| `start(title: String?, sourceId: String?)` | Idle | Creates the meeting folder, `meeting.json` (`status: recording`) and `audio.wav`; starts the foreground service, then capture. Empty title becomes the default title. Fails with an error event if no storage folder, no permission or no source. |
| `pause()` | Recording | Stops capture, updates header and syncs. |
| `resume()` | Paused, Interrupted | Restarts capture on the active source (built-in if the external one is gone). |
| `stop()` | Recording, Paused, Interrupted | Finalizes the meeting; zero frames discards the folder. |
| `markPhotoPosition(): Long` | Recording, Paused, Interrupted | Returns `framesAppended` at call time; the UI passes it to the photo save callback which then calls `addPhoto(file, atFrame)`. |
| `addPhoto(file: String, atFrame: Long)` | Recording, Paused, Interrupted | Appends a photo entry and rewrites `meeting.json`. |

## State (service → UI), observable as a flow

```text
RecordingState {
  state: Idle | Recording | Paused | Interrupted | Finalizing
  meetingFolder: reference to the current folder (or none)
  framesAppended: Long             // timeline position
  elapsedMs: Long                  // derived from framesAppended and sampleRate
  channels: 1 | 2
  levels: List<ChannelLevel>       // peak and RMS in dBFS, one per channel
  activeSource: SourceInfo
  availableSources: List<SourceInfo>
  suggestedSource: SourceInfo?
  photoCount: Int
}
```

`levels` is emitted about 20 times per second; all other fields only on change. Elapsed time is
always `framesAppended * 1000 / sampleRate`.

## One-shot events (service → UI)

| Event | Trigger | UI reaction |
|-------|---------|-------------|
| `SourceSwitched(from, to, atFrame)` | Active external source removed | Visible notice, stays until acknowledged or 10 s. |
| `ExternalAvailableAgain(source)` | External source reconnected during recording | Notice: used from the next recording. |
| `Interrupted(reason)` | Call, other app, capture error | Notice with Resume and Stop actions. |
| `StorageLow(bytesLeft)` | Free space under threshold | Warning; recording continues. |
| `StorageFull` | Write failed for lack of space | Recording finalized with data so far; notice. |
| `LimitApproaching(secondsLeft)` | 15 min before the WAV size limit | Warning. |
| `LimitReached` | WAV size limit | Recording finalized with data so far; notice. |
| `Error(code)` | Unrecoverable problem | Notice; meeting data written so far stays valid. |

## Invariants

1. Every state transition that changes the audio file also updates the WAV header and calls
   `sync()` (pause, stop, source switch) or happens on the 1 s periodic tick.
2. `framesAppended` never decreases and is the only source of timeline positions.
3. A notification with a Stop action exists whenever state is Recording, Paused or Interrupted.
4. If the service process dies, no code path is required to run for the audio to remain
   recoverable (see research R-3 and R-9).
5. Commands in a disallowed state are ignored and reported as `Error`; they never corrupt state.
