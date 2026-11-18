# Implementation Plan: Offline Transcription (Phase 2)

**Branch**: `002-offline-transcription` (no branch created; work continues on `main`) | **Date**: 2026-11-16 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/002-offline-transcription/spec.md`, plus the technical direction given with `/speckit-plan` (whisper.cpp, WorkManager, spike first).

## Summary

Phase 2 adds on-device transcription of recorded meetings: the user manages speech recognition
models in the settings (explicit download with checksum, import, delete), starts a transcription
from the list or the detail screen, and gets `transcript.json`, `transcript.md`, updated
`meeting.json` and `CLAUDE.md` in the meeting folder, with a transcript view that plays the audio
from a tapped segment and shows photos inline.

Technical approach:

- **Engine**: whisper.cpp v1.9.5 as a git submodule pinned to its tag's commit, compiled from source
  with CMake and the NDK for `arm64-v8a` and `x86_64`, always optimized, using ggml's runtime CPU
  variants on arm64. A thin JNI bridge in a new `:whisper` module (load, detect language, decode
  with segment callback and abort, free). No prebuilt binaries ([R-1, R-2, R-3, R-16](research.md)).
- **Pipeline in `:core`** (pure Kotlin, JVM-tested): streaming WAV reader, stereo downmix and a
  polyphase resampler to 16 kHz mono; decoding in 120-second windows where the next window starts
  at the last, possibly cut, segment (adaptive overlap, no merge step); frame-exact mapping back to
  the audio timeline ([R-4, R-5](research.md)). Language is detected once per meeting ([R-6](research.md)).
- **Durability**: every window is appended to a work file in the meeting folder; the finished
  transcript is committed by a redoable protocol, so a file named `transcript.json` is always
  complete and a re-transcription never loses the old transcript before the new one exists
  ([contracts/transcript-folder.md](contracts/transcript-folder.md), [R-12](research.md)).
- **Background work**: WorkManager `CoroutineWorker` promoted to a foreground service
  (`mediaProcessing` on Android 15+, `dataSync` before), with a launch-token guard so that nothing
  is ever resumed automatically, a wake lock for screen-off processing, and recording always taking
  priority ([contracts/transcription-service.md](contracts/transcription-service.md), [R-9](research.md)).
- **Network** only for the explicit model download: `HttpURLConnection` with a host allow-list,
  byte-range resume, SHA-256 verification, contained by code, platform configuration and build checks
  ([contracts/model-catalog.md](contracts/model-catalog.md), [R-10, R-11](research.md)).
- **First task is a spike** that measures real-time factor and memory of each candidate model on the
  phone and decides the default model, the curated list, VAD and two pipeline options ([R-17](research.md)).

The plan deviates from the input in six places, listed with reasons at the top of
[research.md](research.md); the two that change formats or algorithms are the authoritative
`startFrame`/`endFrame` next to `startMs`/`endMs` (the constitution's single timeline) and adaptive
instead of fixed window overlap.

See [research.md](research.md) for decisions and rejected alternatives, [data-model.md](data-model.md)
for entities and state machines, [contracts/](contracts/) for the file formats and the job and
catalog contracts, and [quickstart.md](quickstart.md) for validation.

## Technical Context

**Language/Version**: Kotlin (as in Phase 1, `kotlin` in the version catalog), JVM target 17, JDK 21
for the build; C++17 for the JNI bridge (about 300 lines) and the vendored whisper.cpp/ggml
(C/C++, built by the NDK's clang). NDK `29.0.14206865`, CMake `3.31.6`, both pinned.

**Primary Dependencies**: new: `androidx.work:work-runtime` 2.12.0 (Apache-2.0, AndroidX, with Room,
startup, tracing, jspecify, concurrent-futures and the empty `listenablefuture` stub as transitive
libraries); whisper.cpp v1.9.5 (MIT, submodule, ggml included). Unchanged: Compose, Material 3,
kotlinx.serialization, coroutines, DocumentFile, CameraX. The model download uses the platform's
`HttpURLConnection`, no extra library.

**Storage**: Files only. Meeting folders stay the source of truth (new files: `transcript.json`,
`transcript.md`, transient `transcript.work.jsonl` and `*.part`). Models in app-private
`<filesDir>/models/` with sidecar JSON files, outside every meeting folder. Three new app-local
preferences (default language, default model, last model). WorkManager keeps its own bookkeeping
database, which holds no meeting data. No database for meeting state.

**Testing**: JUnit 5 + kotlin.test in `:core` (all new pipeline, format and state logic on the JVM,
scripted `SpeechDecoder` fakes, golden files, schema validation of the examples); JVM tests in
`:app` for the network-source scanner, string scan, mappings and recovery; instrumented tests on a
device for the engine, installation, the worker, resume and recording priority (run with `adb shell am
instrument`, never `connectedDebugAndroidTest` on the phone with real data). Manual scenarios S11 to
S24 in [quickstart.md](quickstart.md); Phase 1's S1 to S10 as regression.

**Target Platform**: Android, `minSdk 30`, `targetSdk` as in Phase 1 (API 37); phones with a 64-bit
ARM CPU (x86_64 for the emulator). 32-bit ARM is not supported.

**Project Type**: mobile-app (Android) with a pure-Kotlin library module and, new, a thin native
library module.

**Performance Goals**: SC-003: the fastest offered model at most 1.0× real time on the reference phone
(Gigaset E940), the default model at most 0.7×, both to be confirmed by the spike; progress shown
between window ends (not before the first window is done) with an estimate within 25% from 10% progress; cancel within 5 s;
transcript screen of 3 hours opens within 3 s.

**Constraints**: offline except the explicit download (constitution I); no `INTERNET` use outside
`app.meetingrecorder.models.download`; F-Droid: no prebuilt binaries, no build-time downloads,
reproducible build aims; recording must never be slowed or blocked by transcription; `audio.wav` and
photos are read-only for transcription; no segment text in logs; memory bounded by one 120 s window
plus the model (no mmap, model size dominates); UI strings only in `values/` and `values-de/`.

**Scale/Scope**: single user, hundreds of meetings, one job at a time, meetings up to 6 h stereo
(Phase 1 limit); about 8 new or changed screens/dialogs (model settings, model detail and confirm
dialogs, start dialog, transcript section, status chips, notices, settings entries); about 60
new string keys in two languages.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Gate | Status (pre-research) | Status (post-design) |
|-----------|------|-----------------------|----------------------|
| I. Offline & Privacy First | Network only for explicit, user-initiated model download; user told what and from where first; no telemetry; data leaves the device only by user export | PASS (approach) | PASS: `INTERNET` is used only by `models.download`; confirmation names file, size, source and license (FR-003); pinned HTTPS URLs, host allow-list per redirect hop, platform network config trusting only `huggingface.co`/`hf.co`; source-scan test, manifest check and a `TrafficStats` test (R-10, R-11, [model-catalog.md](contracts/model-catalog.md)); no transcript text in logs |
| II. F-Droid Compatibility | FOSS only; no GMS/Firebase/proprietary SDK; no `.so/.aar/.jar` blobs; native code built from source; reproducible aims; no build-time downloads | PASS (approach) | PASS: whisper.cpp built from the pinned submodule; `verifyNoPrebuiltBinaries` plus `rm:` list for the 12 test models and 3 `gradle-wrapper.jar` found in the tree; `verifyWhisperPin`; NDK/CMake exact pins; KleidiAI, OpenMP, `FetchContent`, curl switched off; WorkManager and its AndroidX transitives vetted and added to `verification-metadata.xml`; model and VAD licenses MIT (R-1, R-2, R-16) |
| III. No Data Loss | Existing recordings and their data never at risk; recording never blocked; crash/kill recoverable | PASS (approach) | PASS: `audio.wav` and photos opened read-only (hash test); starting a recording stops transcription and never waits for it (FR-017, SC-012); work file + redoable commit mean no crash point loses audio, an old transcript or `meeting.json` (stage-and-replace, locked read-modify-write); recording stays in its own process |
| IV. Open File Format | Documented, versioned, standard formats; folder is the single source of truth; changes backward compatible | PASS (approach) | PASS: [transcript.schema.json](contracts/transcript.schema.json) (versioned), [meeting-transcription.schema.json](contracts/meeting-transcription.schema.json) (additive, `schemaVersion` stays 1), [transcript-folder.md](contracts/transcript-folder.md); status derived from the folder, so older app versions that drop the block do no harm; Phase 1 files round-trip byte-identically (golden test); WorkManager's database holds only job bookkeeping |
| V. Single Timeline | Positions are sample frames; ms derived; one conversion | PASS (approach) | PASS: segments store `startFrame`/`endFrame`; `startMs`/`endMs` through `Timeline`; Whisper centiseconds converted with integer arithmetic; resampler maps output samples to input frames exactly (R-4, R-5); no wall-clock positioning |
| VI. Minimal Permissions | Only what a feature needs, justified, `INTERNET` only for the download | PASS (approach) | PASS with justification: `INTERNET` (download only, contained), `WAKE_LOCK` (CPU awake with screen off), `FOREGROUND_SERVICE_DATA_SYNC` and `FOREGROUND_SERVICE_MEDIA_PROCESSING` (background-work types), each explained to the user in the settings (permissions paragraph, FR-036). All are install-time permissions without prompt. `ACCESS_NETWORK_STATE` and `RECEIVE_BOOT_COMPLETED` merged in by WorkManager are removed. `verifyManifestPermissions` gets the new exact allow-list (R-11) |
| VII. Testability | Format logic pure Kotlin, fully unit-tested, JVM only | PASS (approach) | PASS: transcript model/JSON/Markdown, work file, status, commit planning, resampler, window pipeline, catalog, header parser, progress, guide section: all in `:core` with tests; native engine reached only through the `SpeechDecoder` interface, so the pipeline is tested with fakes; JNI kept thin (R-3, R-18) |
| VIII. Incremental Delivery | Each phase ends in an installable, usable app; later phases additive; stories independent | PASS (approach) | PASS: Phase 1 works unchanged without any model; order in R-19 keeps an installable app after each step; Phase 3 (speakers) needs no format change (`speaker` field, frames) |
| IX. English Everywhere, Localized UI | English code, formats, generated files; UI strings in `values/` and `values-de/` | PASS (approach) | PASS: `HardcodedStringsTest` and lint cover new files; transcript files and `CLAUDE.md` section are English; catalog descriptions and notification texts are string resources in both languages |

No violations. The constitution needs no amendment: Principle I and VI already allow the model
download and its `INTERNET` permission, and the technology constraint "models are not bundled" is
met because even the optional ~0.9 MB VAD model is a catalog download.

**Spec follow-ups made while planning** (consistent edits, not scope changes): FR-036 and the network
assumption in [spec.md](spec.md) now name the install-time permissions the plan needs (the spec had said
network access was the only new permission). No other spec change is needed; the spike may force one
(SC-003) and will say so in `spike-results.md`.

## Requirements Coverage

| Requirements | Where designed |
|--------------|----------------|
| FR-001 to FR-011 (models, download, import, delete, settings, network) | [model-catalog.md](contracts/model-catalog.md), R-8, R-10, R-11, R-14 |
| FR-012 to FR-019 (start, languages, background, progress, one job, recording priority, notices) | [transcription-service.md](contracts/transcription-service.md), R-6, R-9, R-14, R-15 |
| FR-020 to FR-023 (cancel, work file, interruption, failure) | [transcription-service.md](contracts/transcription-service.md), [transcript-folder.md](contracts/transcript-folder.md), R-5, R-12 |
| FR-024 to FR-029 (transcript files, metadata, guide, timeline, formats) | [transcript.schema.json](contracts/transcript.schema.json), [meeting-transcription.schema.json](contracts/meeting-transcription.schema.json), [transcript-folder.md](contracts/transcript-folder.md), [data-model.md](data-model.md), R-13 |
| FR-030 to FR-032 (transcript view, tap to play, no empty area) | R-14, [transcript-folder.md](contracts/transcript-folder.md) (placement rule) |
| FR-033 to FR-035 (re-transcription, list status) | [transcript-folder.md](contracts/transcript-folder.md) (status table, commit), R-13, R-14 |
| FR-036 to FR-038 (permissions, localization, models outside folders) | R-11, R-14, [model-catalog.md](contracts/model-catalog.md) |
| SC-001 to SC-012 | measured by the quickstart scenarios S11 to S24 and the spike; SC-003 depends on R-17 |

## Project Structure

### Documentation (this feature)

```text
specs/002-offline-transcription/
├── plan.md                              # This file
├── research.md                          # Phase 0: decisions, rationale, alternatives, risks
├── data-model.md                        # Phase 1: entities, state machines, derived values
├── quickstart.md                        # Phase 1: toolchain, build, spike, validation scenarios
├── contracts/
│   ├── transcript.schema.json           # JSON Schema of transcript.json (schemaVersion 1)
│   ├── meeting-transcription.schema.json # optional "transcription" block of meeting.json
│   ├── transcript-folder.md             # files, commit protocol, work file, status table, transcript.md format
│   ├── transcription-service.md         # facades, WorkManager job rules, launch token, decoder interface
│   ├── model-catalog.md                 # catalog entry, on-device layout, download and import protocols
│   └── examples/
│       ├── transcript.example.json      # validated against the schema
│       └── meeting-transcription.example.json
├── checklists/
│   └── requirements.md                  # Spec quality checklist
├── spike-results.md                     # written by the first task (not created by /speckit-plan)
└── tasks.md                             # Phase 2 output (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
settings.gradle.kts                      # + include(":whisper")
gradle/
├── libs.versions.toml                   # + work-runtime, android-library plugin
├── whisper.pin                          # tag + commit of the submodule (checked by verifyWhisperPin)
├── verification-metadata.xml            # regenerated for the new dependencies
└── verification-tasks.gradle.kts        # new allow-list; verifyNoPrebuiltBinaries, verifyWhisperPin, network rule
.gitmodules                              # whisper.cpp submodule

whisper/                                 # NEW Android library: native build + thin JNI bridge, no project dependencies
├── build.gradle.kts                     # ndkVersion, cmake version, abiFilters, Release forced, useLegacyPackaging
└── src/
    ├── main/
    │   ├── cpp/
    │   │   ├── CMakeLists.txt           # switches of R-2; variants on arm64
    │   │   ├── whisper_jni.cpp          # init/free/detect/decode/abort, log routing
    │   │   └── whisper.cpp/             # git submodule, pinned to v1.9.5 (d1be6fd)
    │   └── kotlin/app/meetingrecorder/whisper/   # WhisperContext, DecodeParams, RawSegment, LanguageProbability, WhisperException
    └── androidTest/kotlin/              # WhisperContextTest (model pushed by adb; skipped when absent)

core/src/main/kotlin/app/meetingrecorder/core/   # pure Kotlin, new packages (tests mirror under src/test)
├── audio/            # WavPcmReader, Downmix, PolyphaseResampler, PcmWindowSource
├── transcript/       # Transcript, TranscriptSegment, TranscriptJson, TranscriptValidator,
│                     #   TranscriptMarkdown, TranscriptTimeline (interleave / photo placement)
├── transcription/    # SpeechDecoder + request/outcome types, WindowPlanner, TranscriptionPipeline,
│                     #   LanguageProbe, RepetitionGuard, WorkFile (codec, canResume, toTranscript),
│                     #   CommitPlanner, StatusResolver, TranscriptionStatus, ProgressEstimator, StartDefaults
├── models/           # ModelCatalog, ModelCatalogEntry, InstalledModel + ModelFiles (sidecar JSON), ModelFileHeader,
│                     #   DownloadRules (space, host policy, response checks), ModelInstallMachine, Sha256Streaming
├── model/            # MeetingMetadata + MeetingTranscription (TranscriptInfo, JobInfo)          [changed]
├── json/             # MeetingJson: explicitNulls = false                                          [changed]
├── guide/            # ClaudeGuide: transcript section                                             [changed]
└── folder/           # MeetingFolderLayout: transcript file names                                  [changed]
core/src/test/resources/golden/   # + transcript json/md, guide with transcript, meeting with transcription block

app/
├── build.gradle.kts                     # + :whisper, work-runtime
└── src/
    ├── main/
    │   ├── AndroidManifest.xml          # permissions of R-11, FGS types override, networkSecurityConfig, removals
    │   ├── res/xml/network_security_config.xml
    │   ├── kotlin/app/meetingrecorder/
    │   │   ├── MeetingRecorderApp.kt    # launch token, controllers, TranscriptionRecovery after RecoveryRunner   [changed]
    │   │   ├── AppPreferences.kt        # + default language, default model, last model                           [changed]
    │   │   ├── work/                    # LaunchToken, JobRegistry (launch-token guard), Notifications (channels)
    │   │   ├── transcription/           # TranscriptionController, TranscriptionWorker (thin), TranscriptionJobRunner (Android-free
    │   │   │                            #   orchestration), TranscriptionRegistry, TranscriptionRecovery, TranscriptCommitter,
    │   │   │                            #   TranscriptionIo (ports on the folder), WhisperSpeechDecoder (adapter), StopReasonMapper,
    │   │   │                            #   TranscriptionNotification, ThreadPolicy (core count), DeleteGuard
    │   │   ├── models/                  # ModelRepository (files, sidecars), ModelStateStore, ModelController, ModelInstallWorker,
    │   │   │                            #   ModelImporter (SAF copy, no network)
    │   │   │   └── download/            #   ModelDownloader  <- the only package that may touch the network
    │   │   ├── storage/                 # SafMeetingStorage (+ staged replace, append, flags probe), FolderFiles (port), MeetingMetadataStore (locked
    │   │   │                            #   read-modify-write), MeetingRepository (+ status, transcript load, rename regenerates md) [changed]
    │   │   ├── recording/               # RecordingController: onBeforeStart hook (stopForRecording, pauseForRecording), RecordingProbe (new)                           [changed]
    │   │   └── ui/
    │   │       ├── models/              # ModelSettingsScreen, ModelViewModel, dialogs
    │   │       ├── transcription/       # StartTranscriptionDialog, TranscriptSection, TranscriptItems, StatusChip
    │   │       ├── list/                # MeetingListScreen: chip, menu                                            [changed]
    │   │       ├── playback/            # PlaybackScreen: transcript section, seek on tap                          [changed]
    │   │       └── settings/            # SettingsScreen: transcription section                                    [changed]
    │   └── res/values/strings.xml, values-de/strings.xml                                                           [changed]
    ├── debug/res/xml/network_security_config.xml   # + cleartext to 127.0.0.1 for the loopback test server
    ├── test/kotlin/                     # network-source scanner, mappings, recovery, preselection
    └── androidTest/kotlin/              # ModelInstallTest, TranscriptionWorkerTest, RecordingPriorityTest

scripts/spike/                           # dev-only: build-cli.sh, run-spike.sh (CSV), README (never part of the app)
scripts/verify-model-catalog.sh          # dev-only, needs network: catalog vs Hugging Face
fastlane/metadata/android/{en-US,de-DE}/ # texts updated for transcription and the download     [changed]
README.md, CLAUDE.md                     # toolchain, submodule, new rules                          [changed]
```

**Structure Decision**: One new Gradle module, `:whisper`, because the native toolchain (NDK, CMake,
the submodule, the F-Droid `rm:` list, the 16 KB alignment and variant-packaging settings) must apply
to exactly one place; `:core` stays pure Kotlin and holds everything that defines the transcript and
the pipeline, with the engine behind the `SpeechDecoder` interface; `:app` holds Android glue only
(worker, notifications, storage, UI). `:whisper` depends on nothing of the project, `:app` depends on
`:core` and `:whisper`, never the reverse.

## Complexity Tracking

No constitution violations. Additions that cost something, and why the simpler option was rejected:

| Addition | Why needed | Simpler alternative rejected because |
|----------|-----------|--------------------------------------|
| Third module `:whisper` | Isolates native build, submodule and F-Droid file removal; keeps `:core` pure (constitution VII) | Native code in `:app` would force NDK/CMake on every `:app` task and blur the `rm:` boundary |
| WorkManager and its AndroidX dependencies | The input's choice; gives foreground-service handling, unique work and a cancel intent | A plain `Service` is simpler and never restarts itself, kept as the documented contingency (R-9) because the input chose WorkManager |
| Launch-token guard | WorkManager would otherwise resume killed jobs by itself, against FR-017/FR-022 | Trusting WorkManager's retry policy: cannot express "never restart" |
| Stage-and-replace plus work-file commit record | SAF has no atomic replace, and FR-021/FR-034 demand never-partial and never-lose | Direct truncating writes: a crash leaves a damaged `transcript.json` or loses the old one |
| ggml CPU variants as shared libraries (`useLegacyPackaging`) | One APK must run on cores with and without dot-product, and be fast where possible | A fixed `-march`: crashes on older cores; baseline only: gives up the dot-product speed-up on newer cores (how much is a spike measurement, S-1) |
| Locked read-modify-write for `meeting.json` | More writers than in Phase 1 (job start/end, rename, recovery); photo positions live only there | Writing as in Phase 1: a rename during a job could undo the job's update |
