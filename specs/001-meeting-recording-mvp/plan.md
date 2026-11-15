# Implementation Plan: Meeting Recording MVP (Phase 1)

**Branch**: `001-meeting-recording-mvp` (no branch created; work continues on `main`) | **Date**: 2026-11-15 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-meeting-recording-mvp/spec.md`

## Summary

Phase 1 delivers an offline Android app that records meeting audio (built-in or external USB-C
microphone, mono or stereo), takes photos on the audio timeline, stores each meeting as an open,
self-describing folder in a user-chosen storage folder, and lets the user browse, play back, rename
and delete meetings.

Technical approach:

- **Two Gradle modules.** A pure-Kotlin JVM module `:core` holds all format logic (meeting
  metadata model and JSON codec, timeline math, WAV header build/parse/repair, folder naming,
  `CLAUDE.md` generation, recovery planning, level metering). The Android module `:app` is a thin
  layer for UI, recording service, camera, storage access and playback.
- **Recording** runs in a microphone foreground service. A capture thread reads 48 kHz / 16-bit PCM
  from `AudioRecord`, a separate writer thread appends it to one `audio.wav` through a buffered
  queue. The frame counter of this stream is the single timeline.
- **Crash safety** comes from a WAV layout that is recoverable from the file length alone: the
  header is rewritten and the file synced every second, and a repair step on next launch derives the
  true size from the file length.
- **Storage** uses the Storage Access Framework tree picked once by the user, with a persisted
  permission. The recording is written directly into the meeting folder, which is created at start.
- **Source handling** uses `AudioDeviceInfo` with an explicit preferred device and an
  `AudioDeviceCallback`. On unplug the service re-opens capture on the built-in microphone and
  keeps the channel layout fixed by duplicating mono into both channels.

See [research.md](research.md) for decisions and rejected alternatives,
[data-model.md](data-model.md) for entities and states, [contracts/](contracts/) for the folder
format, JSON schema and service control contract, and [quickstart.md](quickstart.md) for
validation.

## Technical Context

**Language/Version**: Kotlin (latest stable at scaffolding time), JVM toolchain 17 for `:core`

**Primary Dependencies**: AndroidX (all AOSP / Apache-2.0): Compose BOM + Material 3, Activity
Compose, Lifecycle, Navigation Compose, DocumentFile, CameraX (core, camera2, lifecycle, view);
kotlinx.serialization (JSON), kotlinx.coroutines. No Google Play Services, Firebase or other
proprietary SDK. Playback uses the platform `MediaPlayer` (no extra dependency).

**Storage**: Files only. The meeting folder is the source of truth. User-chosen tree via SAF
(persisted URI permission, `androidx.documentfile` plus `DocumentsContract` for fast listing).
App preferences (storage tree URI, consent acknowledged) in `SharedPreferences`/DataStore
(derived or app-local state only). No database.

**Testing**: JUnit 5 + kotlin.test in `:core` (JVM, no emulator, full coverage of format logic;
meeting JSON is also validated against `contracts/meeting.schema.json` with a test-only FOSS JSON
Schema validator);
JVM unit tests in `:app` for non-UI logic (channel selection, state machine, hard-coded string
scan); a small number of instrumented tests for service, SAF and camera on a device. Manual
hardware validation with the RØDE Wireless PRO receiver ([quickstart.md](quickstart.md)).

**Target Platform**: Android, `minSdk 30` (Android 11), `targetSdk` = latest stable SDK at
scaffolding time (pinned in the version catalog), phones with USB-C.

**Project Type**: mobile-app (Android) plus a pure-Kotlin library module.

**Performance Goals**: Level meters refresh at about 20 Hz; photo captured and saved within 3 s of
the tap (SC-005); recording start from app open within 10 s (SC-001); no audible gaps for at least
3 h (SC-002).

**Constraints**: Offline only, no `INTERNET` permission in Phase 1; no `allowBackup`; no prebuilt
binaries; reproducible-build settings (see research.md R-12); WAV limit of 4 GiB gives a hard
maximum recording of about 6.2 h in stereo and 12.4 h in mono (research.md R-3); UI strings only
in `values/` and `values-de/`.

**Scale/Scope**: Single user, hundreds of meetings, one recording at a time, 5 screens (setup/
consent, record, meeting list, playback, settings/about), about 35 localized strings groups.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Gate | Status (pre-research) | Status (post-design) |
|-----------|------|-----------------------|----------------------|
| I. Offline & Privacy First | No network permission or code, no telemetry, no cloud, no backup of meeting data | PASS | PASS: no `INTERNET` permission at all in Phase 1; `allowBackup=false` and empty data-extraction rules; manifest permission allow-list check (R-11) |
| II. F-Droid Compatibility | FOSS-only dependencies, no GMS/Firebase, no prebuilt binaries, reproducible build | PASS | PASS: AndroidX only; dependency-scan task fails the build on `com.google.android.gms`/`firebase`; Gradle dependency verification; `dependenciesInfo` disabled (R-12) |
| III. No Data Loss | Survives crash, screen-off, app switch, unplug; audio always playable | PASS (approach) | PASS: foreground service, direct write into the meeting folder, 1 s header update + sync, repair on launch, explicit fallback re-open on unplug (R-2, R-3, R-5, R-7) |
| IV. Open File Format | Self-describing folder, WAV/JPEG/JSON/Markdown, folder is source of truth, documented and versioned | PASS | PASS: [contracts/meeting-folder.md](contracts/meeting-folder.md), [contracts/meeting.schema.json](contracts/meeting.schema.json), `schemaVersion`; no private database |
| V. Single Timeline | All times are sample positions | PASS | PASS: `atFrame` is authoritative in all records; `atMs` is derived; one conversion function in `:core` (R-4) |
| VI. Minimal Permissions | Only needed permissions, each explained | PASS | PASS: `RECORD_AUDIO`, `CAMERA`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` only; rationale screen per runtime permission (R-11) |
| VII. Testability | Format logic pure Kotlin, fully unit-tested | PASS | PASS: `:core` module has no Android dependency (enforced by its Gradle config); WAV logic works on an abstract seekable channel (R-1) |
| VIII. Incremental Delivery | Phase ends in usable app | PASS | PASS: delivery order in research.md R-14 keeps an installable app at each story |
| IX. English Everywhere, Localized UI | English code/formats, strings in `values/` and `values-de/` | PASS | PASS: `MissingTranslation`/`ExtraTranslation` lint errors plus hard-coded string test (R-13) |

No violations. Complexity Tracking is empty.

**Open spec follow-up (not a violation):** the 4 GiB WAV limit introduces user-visible behavior
(warning before and graceful stop at the limit) that the spec does not state. Recommended as a
small spec addition before `/speckit-tasks`; see research.md R-3.

## Project Structure

### Documentation (this feature)

```text
specs/001-meeting-recording-mvp/
├── plan.md                           # This file
├── research.md                       # Phase 0: decisions, rationale, alternatives
├── data-model.md                     # Phase 1: entities, validation, state machine
├── quickstart.md                     # Phase 1: build, test and validation guide
├── contracts/
│   ├── meeting-folder.md             # Meeting folder layout and naming contract
│   ├── meeting.schema.json           # JSON Schema of meeting.json (schemaVersion 1)
│   └── recording-service.md          # Internal control/state contract UI <-> service
├── checklists/
│   └── requirements.md               # Spec quality checklist
└── tasks.md                          # Phase 2 output (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
settings.gradle.kts
build.gradle.kts
gradle/
├── libs.versions.toml                # version catalog (all versions pinned here)
├── verification-metadata.xml         # Gradle dependency verification (checksums)
└── wrapper/                          # pinned Gradle wrapper

core/                                 # pure Kotlin JVM module, NO Android dependency
├── build.gradle.kts
└── src/
    ├── main/kotlin/app/meetingrecorder/core/
    │   ├── model/                    # MeetingMetadata, AudioInfo, Source, SourceSwitch, Dropout, Photo, Status, MeetingEditor
    │   ├── json/                     # MeetingJson codec, schemaVersion handling, validation
    │   ├── time/                     # Timeline: frames <-> ms, formatting, position validation
    │   ├── wav/                      # WavHeader build/parse/repair on a SeekableStore abstraction, RecordingLimits
    │   ├── folder/                   # MeetingFolderLayout: names, collision suffix, path rules
    │   ├── guide/                    # ClaudeGuide: generates the English CLAUDE.md for a meeting
    │   ├── level/                    # LevelMeter: peak/RMS per channel from PCM16 buffers
    │   ├── recovery/                 # RecoveryPlanner: decides repair actions for unfinished meetings
    │   └── channels/                 # ChannelPolicy: stereo/mono choice, mono->stereo duplication
    └── test/kotlin/app/meetingrecorder/core/   # mirrors main; golden files for JSON, WAV, CLAUDE.md

app/                                  # Android application module
├── build.gradle.kts
└── src/
    ├── main/
    │   ├── AndroidManifest.xml
    │   ├── aidl/app/meetingrecorder/ # IRecordingService, IRecordingCallback, parcelable state/event types
    │   ├── kotlin/app/meetingrecorder/
    │   │   ├── MainActivity.kt
    │   │   ├── MeetingRecorderApp.kt
    │   │   ├── ui/                   # Compose: setup (folder + consent), record, list, playback, settings
    │   │   │   └── theme/
    │   │   ├── recording/            # RecordingService (own process), RecordingController, RecordingSession,
    │   │   │                         #   RecordingStateMachine, AudioCapture, WavWriter, MeetingFileWriter,
    │   │   │                         #   SourceMonitor (AudioDeviceCallback), SuspensionMonitor, RecoveryRunner,
    │   │   │                         #   RecordingNotification
    │   │   ├── camera/               # CameraX ImageCapture binding, photo saving
    │   │   ├── storage/              # SafMeetingStorage (DocumentFile/DocumentsContract), ParcelSeekableStore, StorageGuard,
    │   │   │                         #   MeetingRepository, tree permission
    │   │   ├── playback/             # MediaPlayer wrapper, photo jump support
    │   │   └── permissions/          # Permission rationale flow
    │   └── res/
    │       ├── values/strings.xml    # English (default)
    │       ├── values-de/strings.xml # German
    │       └── xml/                  # data_extraction_rules.xml, backup rules (exclude all)
    ├── test/kotlin/                  # JVM tests: state machine, source selection, string-scan test
    └── androidTest/kotlin/           # Instrumented: service lifecycle, SAF write, camera

fastlane/metadata/android/{en-US,de-DE}/   # F-Droid store texts (English + German)
```

**Structure Decision**: Two Gradle modules. `:core` exists to satisfy Constitution Principle VII:
it contains everything that defines the meeting folder and timeline, depends only on Kotlin stdlib
and kotlinx.serialization, and is tested on the JVM. `:app` depends on `:core`, never the reverse.
The file-access boundary is an interface in `:core` (`SeekableStore`) implemented in `:app` on top
of a `ParcelFileDescriptor`, so WAV header and recovery logic is tested with in-memory stores.
The application ID `app.meetingrecorder` is a placeholder that can be changed before the first
release.

## Complexity Tracking

No constitution violations; nothing to justify.
