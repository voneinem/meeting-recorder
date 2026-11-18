# Meeting Recorder: notes for Claude Code

Android app (Kotlin, Jetpack Compose, Material 3, minSdk 30) that records meeting audio and photos
into open meeting folders and transcribes them on the device. Specification, plan, contracts and tasks
are in `specs/001-meeting-recording-mvp/` (Phase 1) and `specs/002-offline-transcription/` (Phase 2); the
constitution is `.specify/memory/constitution.md`.

## Build and test

```bash
./gradlew :core:test :app:check :app:assembleDebug    # everything that must be green
./gradlew :app:verifyNoForbiddenDependencies :app:verifyManifestPermissions
./gradlew :app:verifyNoPrebuiltBinaries :app:verifyWhisperPin :whisper:assembleDebug
```

- JDK 21 and the Android SDK are required (`JAVA_HOME`, `ANDROID_HOME`). The native engine
  (`:whisper`, Phase 2) also needs NDK `29.0.14206865` and CMake `3.31.6`. The wrapper is pinned
  with a checksum.
- After adding or changing a dependency or plugin, regenerate
  `gradle/verification-metadata.xml` with `./gradlew --write-verification-metadata sha256 help`
  and review the diff.
- Build with `JAVA_HOME` pointing at a full JDK 21 (a JRE without `javac` fails the toolchain check).
- Manual scenarios S1 to S10 are in `specs/001-meeting-recording-mvp/quickstart.md`.
- Instrumented tests (`app/src/androidTest`) need a device. Do not run `connectedDebugAndroidTest`
  on a phone with real app data (Gradle uninstalls the app afterwards); install the two APKs and use
  `adb shell am instrument`, see the quickstart. The tests use the debug-only `TestDocumentsProvider`.

## Module boundaries

- `:core` is pure Kotlin and must never depend on Android (enforced by its Gradle config). Anything
  that defines the meeting folder, the timeline or the WAV file belongs here, with unit tests.
- `:app` depends on `:core`, never the other way round. File access goes through the
  `SeekableStore` interface so format logic stays testable.
- `:whisper` is the native speech engine: the whisper.cpp git submodule (pinned in `gradle/whisper.pin`,
  checked by `verifyWhisperPin`), `CMakeLists.txt`, `whisper_jni.cpp` and a small Kotlin API. It depends on
  nothing of the project. Bumping the engine means changing the submodule and the pin together and
  re-running the spike's accuracy check. `verifyNoPrebuiltBinaries` fails when the sources contain binaries
  (the F-Droid `rm:` list of the engine's example, binding, model, sample, test and ci directories is skipped
  and must never be built).
- The recording service runs in its own process (`:recording`) and talks to the UI through AIDL;
  see `contracts/recording-service.md`.

## Rules that are easy to break

- **English everywhere** in code, identifiers, comments, commits, specs and file formats. Only UI
  strings are localized: `values/strings.xml` (English) and `values-de/strings.xml` (German). No
  hard-coded UI strings (a unit test and lint enforce it). `meeting.json` and the generated
  `CLAUDE.md` stay English.
- **Single timeline**: positions are sample frames (`atFrame`) counted by the WAV writer; `atMs` is
  derived. Never use wall-clock time for positions.
- **Minimal permissions**: the manifest allow-list is checked by `verifyManifestPermissions`. `INTERNET`
  is allowed only for the explicit, user-initiated model download, is used only in
  `app/src/main/kotlin/app/meetingrecorder/models/download/`, and the network security config
  restricts it to `huggingface.co` and `hf.co`. No Google, Firebase or other proprietary dependencies
  (F-Droid compatible).
- **Network**: only `app/src/main/kotlin/app/meetingrecorder/models/download/` may use `java.net`, sockets or
  an HTTP client (`NetworkSourceScannerTest` enforces it); `:core` parses URLs by hand. Downloads go to the
  pinned Hugging Face addresses only, over `https`, with redirects checked hop by hop.
- **Jobs never restart by themselves.** WorkManager persists work, so every transcription and model-install
  job carries a launch token (`LaunchToken`, `JobRegistry.claim`); a worker that was not registered by this
  process start refuses to run and touches nothing. Resuming is always the user's decision.
- **Recording has priority over transcription**: `RecordingController.start` calls `onBeforeStart` first
  (stops a transcription, which keeps its work file, and pauses a download); a transcription never starts
  during a recording (`RecordingProbe`).
- **Commit protocol**: `transcript.json` and `transcript.md` are staged as `.part` files and replaced only
  when complete; `transcript.work.jsonl` is the commit record and is deleted **last**; every write to
  `meeting.json` goes through `MeetingMetadataStore` (one read-modify-write under a per-meeting lock) except
  the recording path itself. `audio.wav` and `photos/` are never written by a transcription.
- **No data loss**: the audio file is crash-safe (header rewritten every second and repairable from
  the file length). Do not add anything on the recording path that can block the capture thread.
- **Every recording needs a fresh service instance.** `RecordingController` unbinds when the
  recording ends. A service that stays bound is reused and the system silences the microphone in
  the background ("App op 27 missing").
- Instrumented tests run with `adb shell am instrument`, never `connectedDebugAndroidTest` on a phone with
  real data. Spike recordings, models and results (`spike/audio/`, `spike/models/`, `spike/out/`) are never
  committed.
- Keep `tasks.md` honest: tick a task only when it is done and, where it says so, verified on a
  device. Device checks are noted next to the task.

## Meeting folder format

`audio.wav`, `photos/photo-NNN.jpg`, `meeting.json` (schema in `contracts/meeting.schema.json`,
`schemaVersion` 1) and a generated `CLAUDE.md`. The folder is the source of truth; the app has no
database.
