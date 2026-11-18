# Contract: transcription and model-install jobs (UI ↔ background work)

Unlike Phase 1's recording service this is **not** AIDL: transcription and model install run in the
**main process** (a crash of the native engine takes the app down, which is acceptable because all
state is checkpointed in the folder; the recording lives in its own process and is unaffected).
The contract is the Kotlin API of two facades plus the rules for the WorkManager jobs behind them.

## Facades (app module)

```kotlin
class TranscriptionController(context: Context) {
    val state: StateFlow<TranscriptionState>          // at most one job at a time (FR-017)
    val events: SharedFlow<TranscriptionEvent>        // one-shot notices for the UI

    suspend fun start(request: TranscriptionRequest): StartResult   // suspends: RecordingProbe may wait up to 2 s
    fun cancel()                                      // user cancel: discard, meeting back to its previous state
    fun stopForRecording()                            // called by RecordingController before it starts
    fun isRunningFor(folderName: String): Boolean
}

data class TranscriptionRequest(
    val folderName: String,
    val modelId: String,
    val requestedLanguage: String,                    // "auto" | "de" | "en" (| other codes later)
    val mode: Mode,                                   // START, RESUME, RESTART
)

sealed interface StartResult {
    object Started : StartResult
    object RecordingInProgress : StartResult          // FR-017: not during a recording
    object AnotherJobRunning : StartResult            // FR-017: one at a time
    object ModelNotInstalled : StartResult            // FR-013: the UI then opens the model settings
    object ResumeNotPossible : StartResult            // header mismatch / model gone / no usable checkpoint
    object MeetingNotFound : StartResult
    object MeetingStillRecording : StartResult        // FR-018
}

sealed interface TranscriptionState {
    object Idle : TranscriptionState
    data class Active(
        val folderName: String,
        val phase: Phase,                             // PREPARING, RUNNING, CANCELLING, COMMITTING
        val progress: Float,                          // 0..1, never decreases within a job
        val remainingMs: Long?,                       // null until the estimate is usable
        val modelId: String,
    ) : TranscriptionState
}
```

`ModelController` has the same shape for downloads and imports (`ModelInstallState` per model:
`NotInstalled`, `Downloading(bytesDone, total)`, `Paused(bytesDone, total)` after an interruption,
`Verifying`, `Importing`, `Installed`, `Failed(reason)`), one install at a time.

Both facades are created lazily by `MeetingRecorderApp`, like `recordingController`, and only in the
main process.

## Liveness registry and the launch token

WorkManager persists work and re-runs it after a process death. The spec forbids that for
transcription (FR-017, FR-022: resume is always the user's decision), and a foreground service
cannot be started from the background on Android 12+ anyway. So every job carries a **launch
token**:

- `MeetingRecorderApp` creates one random token per process start. `TranscriptionRegistry` (an
  in-memory singleton) holds `{requestId, folderName, modelId, phase}` for the job that this process
  has accepted.
- `start()` registers the request, then enqueues unique work `transcription` with the input
  `{requestId, folderName, modelId, requestedLanguage, mode, launchToken}`.
- The worker's first action: `registry.claim(requestId, launchToken)`. It succeeds only if the
  request was registered **by this process**. Otherwise the worker returns `Result.failure()` at
  once, touching nothing: this is WorkManager restarting an old job after a kill or reboot.
- At app start (after the Phase 1 recovery run) `TranscriptionRecovery` calls
  `cancelUniqueWork("transcription")` and `cancelUniqueWork("model-install")` and then repairs the
  folders (below).
- "live" in the status table of [transcript-folder.md](transcript-folder.md) means *the registry
  holds the folder*. After a process death the registry is empty, so any `in-progress` job block is
  stale by definition.

## Job: `TranscriptionWorker`

`CoroutineWorker`, unique work name `transcription`, `ExistingWorkPolicy.KEEP`, enqueued **expedited**
with `OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST`, so that promoting to a foreground service
is allowed because the user started it from the UI. No constraints (no network, no charging
requirement). It overrides `getForegroundInfo()` and calls `setForeground` before any heavy work.

Foreground service type, chosen at run time:

| Android | Type passed to `ForegroundInfo` | Permission |
|---------|-------------------------------|------------|
| 15 (API 35) and newer | `FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING` | `FOREGROUND_SERVICE_MEDIA_PROCESSING` |
| 11 to 14 | `FOREGROUND_SERVICE_TYPE_DATA_SYNC` | `FOREGROUND_SERVICE_DATA_SYNC` (API 34) |

The manifest overrides WorkManager's `SystemForegroundService` entry
(`tools:node="merge"`, `android:foregroundServiceType="dataSync|mediaProcessing"`). The model
download job uses `DATA_SYNC` on every version.

Run sequence (each step checks cancellation):

1. `claim` (see above). Acquire a `PARTIAL_WAKE_LOCK` with a 12 h timeout, released in `finally`.
2. Read `meeting.json`, check the meeting exists, is not `recording`, and the model is installed.
3. Write `transcription.job = {state: in-progress, …}` (the first of only three `meeting.json`
   writes of a job).
4. Open `audio.wav` read-only; `RESUME` loads the work file, validates the header, truncates a torn
   tail; `START`/`RESTART` deletes any old work file and writes a fresh header.
5. Load the model (PREPARING). Detect the language once if requested `auto` (see research R-6).
6. Window loop (RUNNING): per window read → downmix → resample → decode → append the `window` line
   to the work file → update progress. Details: research R-5.
7. COMMITTING: the commit protocol of [transcript-folder.md](transcript-folder.md).
8. Result notification, `registry.release`, wake lock released.

**Cancel** (`cancel()` or the notification's action, `WorkManager.createCancelPendingIntent`): the
coroutine is cancelled; its handler calls the decoder's `abort()`, joins the decode thread (at most
3 s), frees the model, **deletes the work file**, removes the `job` block from `meeting.json` and
leaves any committed transcript as it was. Target: everything stopped within 5 s (FR-020, SC-006).

**Stopped for a recording** (`stopForRecording()`, called by `RecordingController.start` *before* it
binds the recording service, but not awaited; the recording never waits for transcription): same
stop path, but the work file is **kept** and the job block becomes
`{state: failed, failureReason: recording-started, resumable: true}`. A notification tells the user.

**Stopped by the system** (`onStopped`, with `stopReason` where the platform reports one): the
foreground-service timeout of Android 15 (6 hours for `dataSync` and `mediaProcessing`), a doze or
quota stop, or the process being killed. The worker keeps the work file and writes
`failed/interrupted/resumable: true`. A kill that gives no chance to write leaves `in-progress`,
which recovery turns into the same state. The notification then offers "Resume" (a user tap starts
the job from the foreground, which is allowed).

**Failures** write `job = {state: failed, failureReason, failureMessage, resumable}` and keep the
work file when it is usable. Reasons: `interrupted`, `recording-started`, `model-missing`,
`model-invalid`, `out-of-memory`, `storage-full`, `audio-unreadable`, `error`. Cancel is not a
failure.

## Recovery at app start (`TranscriptionRecovery`)

Runs once per process start, on the same background scope as Phase 1's `RecoveryRunner`, for the
meetings of the storage folder:

1. Delete `*.part` staging files that have no `transcript.work.jsonl` next to them.
2. Work file **with** an `end` line: repeat the commit (no decoding, seconds). The meeting is shown
   as "in progress" until it finishes.
3. `job.state == in-progress` and no live job: set `failed / interrupted`, `resumable` = whether the
   work file decodes with a usable header.
4. `meeting.json.part` present and `meeting.json` unreadable: promote the `.part` file.

Every step is idempotent; a second run changes nothing.

## Progress and ETA

`ProgressEstimator` (pure, in `:core`) takes anchors `(audioFramesDone, wallClockMs)` from decoded
segments and window ends and returns `(progress, remainingMs)`:

- progress = `framesDone / durationFrames` at the last anchor, **interpolated** between anchors at
  the measured real-time factor, never decreasing, and never more than one decode window (120 s) of audio
  ahead of the last anchor, so a stall does not show false progress (the cap was 60 s until the phone run S-B showed
  the percentage standing still for up to 25 s between window ends);
- remaining time = remaining audio × the recent real-time factor (exponential moving average over
  the last windows, first estimate after the first completed window), rounded to 5 s above one
  minute; `null` before that;
- the app UI recomputes every second and the notification every 2 s (the system throttles faster
  updates), both from the same estimator instance, so they agree (SC-004: within 2 percentage points).

## Notifications

| Channel id | Importance | Used for |
|-----------|-----------|----------|
| `transcription_progress` | low (silent) | the foreground notification of a running transcription or model install: title, percentage bar, remaining time, **Cancel** action; tapping opens the meeting |
| `transcription_result` | default | finished, failed, interrupted ("Resume" action), stopped for a recording |

All texts are string resources in `values/` and `values-de/`. With the notification permission
denied the jobs still run (the foreground service exists, the notification is not shown); the
in-app progress and the one-time hint (FR-019) remain.

## Recording priority (FR-017, SC-012)

`RecordingController.start()` calls its `onBeforeStart` hook, which is wired in `MeetingRecorderApp` to
`transcriptionController.stopForRecording()` and `modelController.pauseForRecording()` (a download is
interrupted and stays resumable; an import is cancelled), as its first statement, then proceeds without waiting. The decode threads run at normal
priority (see research R-9), so the capture thread (audio priority) is not starved while they stop.
While a recording is running, `start(...)` answers `RecordingInProgress`. The same holds for `ModelController.download`, `resume` and `import` (all `suspend`), and starting a recording pauses a running download and cancels a running import. `isRecording` comes from
`RecordingProbe`, not from the raw `RecordingController.state`: the probe uses the convention of Phase 1's
recovery (if the `:recording` process is not alive there is no recording; if it is alive, attach with
`attachIfRunning()` and wait up to 2 s for a state other than `IDLE`; a timeout means not recording), so a
UI process that was recreated during a recording is not fooled by an initial `IDLE`. The runner checks
the probe again right before it loads the model.
`RecordingController` receives its pre-start hook as a constructor parameter
(`onBeforeStart: () -> Unit = {}`) from `MeetingRecorderApp`, which avoids a construction cycle.

## Decoder boundary (`:core` interface, `:whisper` implementation in the app module's adapter)

```kotlin
interface SpeechDecoder : AutoCloseable {
    /** Probability per language for one 30 s window of 16 kHz mono samples. */
    fun detectLanguage(window: PcmWindow): List<LanguageProbability>

    /** Decodes one window; `sink.onSegment` is called for each finished segment, times in centiseconds
     *  relative to the window start. Returns when done, aborted (`Aborted`) or failed (`Failed`). */
    fun decode(window: PcmWindow, request: DecodeRequest, sink: SegmentSink): DecodeOutcome

    /** Thread-safe; makes a running `decode` return `Aborted` soon (polled between graph operations). */
    fun abort()
}
```

`PcmWindow` is a direct `FloatBuffer` plus a sample count (16 kHz mono). `:whisper` has no
dependency on `:core`; the adapter in `app/transcription/WhisperSpeechDecoder.kt` converts between
the two. The whole pipeline is therefore testable on the JVM with a scripted fake decoder.

## Ports for JVM-testable logic

```kotlin
fun interface RecordingCheck { suspend fun isRecording(): Boolean }   // implemented by RecordingProbe

interface JobCanceller {                                               // implemented by TranscriptionController
    fun isRunningFor(folderName: String): Boolean
    suspend fun cancelAndAwait(timeoutMs: Long): Boolean               // true when the job has stopped
}
```

`TranscriptionJobRunner` receives a `RecordingCheck`, `DeleteGuard` a `JobCanceller`, and `ModelController` a
`RecordingCheck`; their tests use fakes and run on the JVM.
