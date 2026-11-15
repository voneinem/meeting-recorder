# Research & Decisions: Meeting Recording MVP (Phase 1)

All unknowns from the plan's Technical Context are resolved below. Items marked **Verify on
device** are platform behaviors that cannot be fully confirmed from documentation and are covered
by explicit checks in [quickstart.md](quickstart.md).

## R-1: Module split and pure-Kotlin boundary

- **Decision**: `:core` (Kotlin JVM, no Android plugin) holds all format logic; `:app` depends on
  it. File access inside `:core` goes through a small `SeekableStore` interface (read, write at
  offset, length, truncate, sync). `:app` implements it with `FileChannel` over a
  `ParcelFileDescriptor`; tests use an in-memory implementation.
- **Rationale**: Constitution VII requires format logic without Android dependencies and full unit
  tests. The abstraction lets WAV header writing and crash repair be tested byte-exactly.
- **Alternatives**: Single module with a `format` package (rejected: nothing prevents Android
  imports creeping in; a module boundary enforces it). Kotlin Multiplatform (rejected: needless
  complexity).

## R-2: Audio capture and source selection

- **Decision**: `AudioRecord` with 48 kHz, `ENCODING_PCM_16BIT`. Channel count is chosen by a
  pure `ChannelPolicy`: 2 if the selected `AudioDeviceInfo.channelCounts` contains 2 (or the
  device reports no restriction and is external with a stereo mask), otherwise 1. Built-in
  microphone is always mono. The selected device is set with `setPreferredDevice` and then
  verified through `getRoutedDevice()` and a routing-changed listener; a mismatch is logged and
  shown. Audio source is `MediaRecorder.AudioSource.MIC`. Capture runs on a dedicated
  high-priority thread; frames go to a bounded queue (about 10 s capacity) drained by the writer
  thread.
- **Rationale**: `setPreferredDevice` is a preference, not a guarantee, so routing must be read
  back. A queue decouples capture from the latency of storage writes through SAF, which can stall
  briefly. More than two channels (spec edge case): only the first two are recorded by requesting
  a stereo mask.
- **Alternatives**: `MediaRecorder` (rejected: no PCM access, no per-channel meter, no fine
  control over crash behavior). `UNPROCESSED` source (rejected for default: not available on all
  devices and quieter; may be revisited). Oboe/native (rejected: native code, F-Droid build
  complexity, not needed at 48 kHz).
- **Verify on device**: Whether the RØDE Wireless PRO receiver reports `channelCounts` of `[1,2]`
  or `[2]` in split and merged mode, and what the merged-mode channel content is. If the device
  reports two channels in merged mode, the meeting is recorded as stereo with identical channels;
  this is acceptable for Phase 1 and noted for the speaker-detection phase.

## R-3: Crash-safe WAV (single file, header update + repair)

- **Decision**: One `audio.wav` per meeting (RIFF/WAVE, PCM, 48 kHz, 16-bit, 1 or 2 channels,
  44-byte canonical header). At start, the header is written with correct fields and data size 0.
  The writer rewrites the two size fields and calls `sync()` every 1 s of audio and on pause,
  stop and source switch. On next launch, `RecoveryPlanner` computes the true data size from the
  file length (`length - 44`, truncated to a multiple of the block size) and the WAV repair
  rewrites the header, so the file is playable even if no header update ever happened.
- **Rationale**: PCM data is append-only at a fixed position, so the file is self-recovering from
  its length. A process crash cannot lose bytes already passed to `write()` (the kernel owns
  them); the 1 s sync bounds loss on power loss or kernel crash and keeps most players happy even
  before repair. This keeps the single-timeline rule trivial: frame index equals
  `(bytes - 44) / blockSize`.
- **Maximum length**: WAV sizes are 32-bit, so the data chunk is limited to 4 GiB minus overhead,
  about 6.2 h stereo or 12.4 h mono at 48 kHz/16-bit. The service warns 15 min before the limit
  and stops gracefully with the meeting intact. This exceeds SC-002 (3 h). The behavior is
  required by FR-034 and the "Very long meetings" edge case in the spec.
- **Alternatives**: Segmented files merged on stop (rejected: the merge of up to 2 GB at stop
  delays finishing, creates a window where audio exists only in fragments, complicates the folder
  contract and the timeline). RF64/`WAVE_FORMAT_EXTENSIBLE` (rejected for Phase 1: poorer tool
  support, and the hard limit is already beyond typical meetings). FLAC/Opus (rejected:
  constitution names WAV; compressed streaming writers are harder to make crash-safe).
- **Verify on device**: `FileDescriptor.sync()` behavior on the SAF-backed `ExternalStorageProvider`
  (FUSE) path on several devices; cost per call at 1 s intervals.

## R-4: Timeline and photo positions

- **Decision**: The timeline is the count of frames appended to the audio stream. The recording
  session exposes `framesAppended` (an atomic counter incremented by the capture thread when it
  enqueues a buffer). A photo position is the value of this counter read at the moment of the
  tap, not when the photo finishes saving. Paused time and the source-switch gap are not counted.
  `atMs = atFrame * 1000 / sampleRate` is derived in one place (`Timeline`, `:core`).
- **Rationale**: Deterministic, independent of wall-clock, immune to pause and capture delays;
  satisfies Constitution V and SC-005 (within 1 s). The queue holds at most a few hundred
  milliseconds in practice, so enqueued position equals file position once flushed.
- **Alternatives**: Wall-clock minus pause bookkeeping (rejected: drifts, violates V). Counting
  frames written by the writer thread (rejected: lags by queue depth; equal after flush but less
  accurate at tap time).

## R-5: Foreground service and background recording

- **Decision**: `RecordingService` declared with `android:foregroundServiceType="microphone"`,
  started from the visible UI (required for while-in-use microphone access). Manifest permissions:
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`. The
  ongoing notification shows a chronometer, a Stop action and opens the recording screen. The
  service owns the capture, writer and metadata state; the UI binds to it and only renders state.
  Pause stops `AudioRecord` capture (microphone indicator goes off) and resume starts it again.
- **Rationale**: Only a foreground service keeps microphone access with screen off or app in
  background. Pausing by stopping capture respects privacy.
- **Separate process**: The service runs in `android:process=":recording"`, so a UI crash or kill does
  not end a recording (Principle III) and the crash-recovery test can kill the recording process on
  its own. The UI talks to it through AIDL (platform feature, no dependency); see
  [contracts/recording-service.md](contracts/recording-service.md), "Transport".
- **Alternatives**: Keep capturing while paused and discard (rejected: privacy, battery). A plain
  bound service (rejected: killed in background). Same-process service (rejected: a UI crash would
  also end the recording).
- **Verify on device**: Re-opening `AudioRecord` from the service while the app is in the
  background (needed for unplug fallback) works on Android 11 through the latest release. If the
  re-open is refused, the session moves to `Suspended` (auto-pause) with a notification offering
  Resume, the same path as R-6; no audio is lost and the recording is never stopped silently.

## R-6: Phone calls and other apps taking the microphone

- **Decision**: Register `AudioManager.AudioRecordingCallback`; when the client is reported as
  silenced, or capture returns errors, the session enters `Suspended` (auto-paused), keeps all
  audio so far, notifies the user, and offers Resume that starts capture again in the same
  meeting.
- **Rationale**: Implements the spec edge case without losing data or pretending to record.
- **Alternatives**: Keep writing silence (rejected: misleading timeline and file size).

## R-7: Source switch on unplug and fixed channel layout

- **Decision**: `SourceMonitor` wraps `AudioDeviceCallback` (devices added/removed) and exposes the
  list of input devices, the suggested device (first external USB input) and removal events. On
  removal of the active device the session pauses writing, releases and re-creates `AudioRecord`
  on the built-in microphone, then resumes appending. If the meeting is stereo, `ChannelPolicy`
  duplicates the mono samples into both channels so the file layout never changes. A
  `SourceSwitch` record is added at the current frame position with `channelsDuplicated: true`.
  Reconnect during recording only raises a user notice; selection of sources while recording is
  not possible (clarified in the spec).
- **Rationale**: Relying on the framework to reroute silently is not reliable across devices;
  explicit re-open gives a bounded gap (target of at most 2 s, SC-004) and a precise log entry.
  The gap itself is not part of the audio timeline, like a pause.
- **Alternatives**: Let the framework reroute (rejected: no log position, layout may change
  unpredictably). Right channel silence on fallback (rejected in clarification).

## R-8: Storage via Storage Access Framework

- **Decision**: First launch uses `ACTION_OPEN_DOCUMENT_TREE`; the URI is persisted with
  `takePersistableUriPermission` (read and write) and stored in app preferences. Android 11 and
  later forbid picking the root of internal storage and the Download folder, so the setup screen
  asks the user to pick or create a folder such as `Documents/Meetings`. The meeting folder is
  created at recording start with `DocumentFile.createDirectory`, files are created with
  `createFile`. Audio and metadata are written through
  `ContentResolver.openFileDescriptor(uri, "rw")` and a `FileChannel`, giving seek and sync.
  Listing meetings uses `DocumentsContract.buildChildDocumentsUriUsingTree` with a single
  projection query (name, mime type, modified time, size) instead of `DocumentFile.listFiles`
  chains, because per-child queries are slow.
- **Rationale**: Scoped storage without a broad storage permission (Principle VI), user-visible
  and user-controlled location (Principle IV), and direct writes avoid a window where audio exists
  only in app-private storage.
- **Risk**: Some document providers (cloud-backed) do not support `"rw"` or sync. Phase 1 only
  accepts providers where opening for `"rw"` succeeds; the setup screen runs a write/seek/delete
  test file check and rejects the folder otherwise.
- **Alternatives**: Record to app-private storage and copy on stop (rejected: data loss on
  uninstall or data clear, and copy time). `MANAGE_EXTERNAL_STORAGE` (rejected: violates minimal
  permissions, not F-Droid-friendly policy-wise). A database index (rejected: Principle IV).

## R-9: meeting.json writes and recovery

- **Decision**: `meeting.json` is created at recording start with `status: "recording"` and
  rewritten in a single truncating write whenever photos, source switches or the meeting status
  change (never on a timer, which would only repeat the truncation risk below); the duration on
  disk is written at stop and otherwise derived from the WAV length by recovery. On launch, `RecoveryPlanner` inspects every meeting folder whose
  status is `recording` (or whose JSON is unreadable): it repairs the WAV, sets
  `durationFrames` from the file length, sets `status: "recovered"`, and regenerates `CLAUDE.md`
  if missing. Photos whose files exist but are not listed are listed with the nearest known
  position only when recoverable; otherwise they stay in the folder unlisted and are reported in
  the recovery result.
- **Rationale**: The JSON is small (a few KB), so a single write is practically atomic; recovery
  never depends on the JSON being valid for the audio itself.
- **Accepted risk**: A crash exactly during the JSON write can leave it truncated; audio remains
  intact and playable, and the meeting is still listed from folder and audio data. Photo
  positions in that rare case may be missing. Judged acceptable for Phase 1.
- **Alternatives**: Temp file plus rename (rejected: rename onto an existing document is not
  atomic or even supported by SAF providers). Encode the position in the photo file name
  (rejected for now: duplicates the source of truth; can be added if the risk materializes).

## R-10: Photos

- **Decision**: CameraX `Preview` and `ImageCapture` bound to the recording screen lifecycle. A tap
  reads the timeline position first, then calls `takePicture` with an `OutputFileOptions` built on
  an `OutputStream` for the new document in `photos/`. Files are named `photo-001.jpg`,
  `photo-002.jpg`, and so on; the photo entry is added to the metadata when the save callback
  succeeds, using the position read at tap time. Capture mode is minimize-latency, JPEG, device
  orientation in EXIF. Camera is bound only while the recording screen is visible.
- **Rationale**: One-tap flow without leaving the app; the position is fixed at tap time so a slow
  save does not shift it. CameraX is AOSP/Apache-2.0 and used by FOSS apps on F-Droid.
- **Alternatives**: System camera intent (rejected: leaves the app, interrupts the flow, returns
  a bitmap or file with extra permissions handling). Camera2 directly (rejected: needless
  complexity).
- **Verify**: The dependency scan confirms CameraX does not pull Play Services (it does not; the
  scan keeps it that way).

## R-11: Permissions and manifest hygiene

- **Decision**: Declared permissions are exactly `RECORD_AUDIO`, `CAMERA`, `POST_NOTIFICATIONS`,
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`. No `INTERNET` and no storage permissions
  (SAF). Camera feature is `required="false"`. Each runtime permission gets an in-app rationale
  screen shown before the system dialog and a graceful degraded state when denied. A Gradle
  verification task reads the merged manifest of the release variant and fails if the permission
  set differs from the allow-list. `allowBackup="false"` with data-extraction and backup rules
  excluding everything.
- **Rationale**: Principles I and VI; the allow-list check catches permissions that arrive
  transitively through a library.
- **Alternatives**: Manual review only (rejected: easy to miss merged permissions).

## R-12: F-Droid compatibility and reproducible build

- **Decision**: Gradle wrapper with pinned distribution checksum; version catalog with exact
  versions; Gradle dependency verification (`verification-metadata.xml`); repositories limited to
  Google's Maven (AndroidX artifacts, which are Apache-2.0 source-built by F-Droid-accepted
  practice) and Maven Central; `android.dependenciesInfo.includeInApk=false` and
  `includeInBundle=false` (removes the Google-signed dependency blob that breaks reproducibility
  and F-Droid scans); `vcsInfo` disabled; no timestamps in outputs; fixed JDK toolchain. A
  verification task scans the resolved runtime classpath and fails on `com.google.android.gms`,
  `com.google.firebase`, `com.google.android.play` and known proprietary coordinates. Store texts
  under `fastlane/metadata/android/{en-US,de-DE}`.
- **Rationale**: Principle II. No native code in Phase 1, so there is nothing to build from source
  yet; the rule stays recorded for later phases.
- **Verify**: Build the release variant twice on two machines and compare APKs; run an F-Droid
  build metadata dry run once the app has a public repository.

## R-13: Localization and the hard-coded string rule

- **Decision**: All user-facing strings live in `res/values/strings.xml` (English) and
  `res/values-de/strings.xml` (German). Lint is configured with `MissingTranslation`,
  `ExtraTranslation` and `HardcodedText` as errors. Because stock lint does not detect string
  literals in Compose calls, a JVM test in `:app` scans Kotlin sources for string literals passed
  to UI parameters (`Text(`, `contentDescription`, `label`, `title`, `placeholder`, notification
  builder texts) and fails on any hit; a small allow-list covers non-UI constants. Plurals and
  formatted strings use resources with positional arguments. Dates and durations use locale-aware
  formatting. Generated files (`meeting.json` keys, `CLAUDE.md`) are always English regardless of
  device language.
- **Rationale**: Principle IX.
- **Alternatives**: Detekt or a custom lint check (viable later; the test is cheaper to start
  with and has no extra dependency).

## R-14: Delivery order inside Phase 1 (Principle VIII)

Each step leaves an installable, usable app:

1. Project skeleton, `:core` format logic with tests, setup flow (folder + consent), basic
   recording with built-in microphone, finalize to a valid folder, minimal list (Story 1).
2. Crash recovery and the resilient write path (Story 6), because it shapes the writer.
3. Source selection, stereo, level meters, unplug fallback (Stories 2 and 3).
4. Photos (Story 4).
5. Playback with photo jumps, rename, delete (Story 5).
6. Localization completion, polish, F-Droid metadata.

## R-15: Playback

- **Decision**: Platform `MediaPlayer` on the audio document URI, polled position about every
  200 ms for the UI, `seekTo` with `SEEK_CLOSEST`. Photo jump seeks to `atMs` of the photo and
  shows the photo. Playback runs only while the playback screen is in the foreground (spec
  assumption).
- **Rationale**: No extra dependency, supports WAV and content URIs.
- **Alternatives**: Media3 ExoPlayer (viable, FOSS; kept as a fallback if `MediaPlayer` seeking on
  `content://` WAV proves unreliable).
