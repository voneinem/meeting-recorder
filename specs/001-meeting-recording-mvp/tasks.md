---

description: "Task list for Meeting Recording MVP (Phase 1)"
---

# Tasks: Meeting Recording MVP (Phase 1)

**Input**: Design documents from `/specs/001-meeting-recording-mvp/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Constitution Principle VII requires format logic in `:core` to be fully unit-tested, so
`:core` test tasks are included and come before their implementation. UI is verified through the
manual scenarios in [quickstart.md](quickstart.md) plus a few instrumented tests on a device.

**Organization**: Tasks are grouped by user story. Story phases follow priority, then the delivery
order from research R-14 (P1 first, then the resilient write path, then sources, photos, and
finally playback management). Every checkpoint leaves an installable, usable app (Principle VIII).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1 to US6, as numbered in spec.md
- Paths follow plan.md: `core/` (pure Kotlin JVM) and `app/` (Android). `PKG` below means
  `app/meetingrecorder`, so `core/src/main/kotlin/PKG/core/` is
  `core/src/main/kotlin/app/meetingrecorder/core/`.
- **Language rule (Principle IX)**: code, comments, commit messages and generated files are English.
  Every task that adds a user-facing string adds it to **both** `app/src/main/res/values/strings.xml`
  (English) and `app/src/main/res/values-de/strings.xml` (German) in the same change.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project skeleton, build configuration and F-Droid hygiene (Principles I, II, VI)

- [X] T001 Create the root Gradle build: `settings.gradle.kts` (includes `:core` and `:app`, repositories restricted to Google Maven and Maven Central, version catalog enabled), root `build.gradle.kts`, and `gradle.properties` (`android.useAndroidX=true`, `android.nonTransitiveRClass=true`, `org.gradle.caching=false`)
- [X] T002 Create `gradle/libs.versions.toml` with exact pinned versions and aliases for: Kotlin, Kotlin serialization plugin, kotlinx-serialization-json, kotlinx-coroutines, Android Gradle Plugin, Compose BOM, Material 3, Activity Compose, Lifecycle (runtime, viewmodel-compose, service), Navigation Compose, DocumentFile, CameraX (core, camera2, lifecycle, view), JUnit 5, kotlin-test, and one test-only FOSS JSON Schema validator (Apache-2.0 or MIT licensed; never on the app's runtime classpath). No Google Play Services, Firebase or proprietary coordinates (Principle II)
- [X] T003 [P] Pin the Gradle wrapper in `gradle/wrapper/gradle-wrapper.properties` with `distributionSha256Sum`, and add `gradlew` / `gradlew.bat`
- [X] T004 [P] Create `core/build.gradle.kts` as a Kotlin JVM module (JVM toolchain 17, kotlinx.serialization plugin, JUnit 5, kotlin-test and the JSON Schema validator for tests only; the test resources include the contracts folder `../specs/001-meeting-recording-mvp/contracts` so the tests read the one and only copy of `meeting.schema.json`). It MUST NOT apply any Android plugin and MUST depend only on the Kotlin stdlib and kotlinx-serialization-json (Principle VII)
- [X] T005 [P] Create `app/build.gradle.kts`: Android application, `applicationId "app.meetingrecorder"`, `minSdk 30`, `targetSdk` and `compileSdk` set to the latest stable SDK (write the API number into `gradle/libs.versions.toml`), Compose + Material 3, dependency on `:core`, `android.dependenciesInfo.includeInApk=false` and `includeInBundle=false`, no VCS info, `lint` options (`warningsAsErrors`), `buildFeatures { aidl = true }`, unit tests enabled
- [X] T006 [P] Create `app/src/main/AndroidManifest.xml`: permissions exactly `RECORD_AUDIO`, `CAMERA`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` (no `INTERNET`, no storage permissions); `<uses-feature android:name="android.hardware.camera" android:required="false"/>`; `android:allowBackup="false"` with `dataExtractionRules` and `fullBackupContent`; `MainActivity` (the `RecordingService` declaration is added by the task that creates the service)
- [X] T007 [P] Create `app/src/main/res/xml/data_extraction_rules.xml` and `app/src/main/res/xml/backup_rules.xml` that exclude all domains from cloud backup and device transfer
- [X] T008 [P] Create `.gitignore` for Android/Gradle (`build/`, `.gradle/`, `local.properties`, `*.iml`, `.idea/`, signing keys)
- [X] T009 [P] Add a `LICENSE` file with the full GPL-3.0-or-later text (decided by the project owner) and an SPDX note (`GPL-3.0-or-later`) in `README.md`; use the unmodified official license text

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The pure-Kotlin format core and the Android storage/navigation base that every story
needs. Format logic is test-first.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Format core in `:core` (pure Kotlin, Principle VII)

- [X] T010 [P] Create the model in `core/src/main/kotlin/PKG/core/model/MeetingMetadata.kt` with `@Serializable` types `MeetingMetadata`, `AudioInfo`, `Source`, `SourceSwitch`, `Photo` and enums `MeetingStatus` (`recording`/`complete`/`recovered`), `ChannelLayout` (`mono`/`stereo`), `SourceType` (`builtin`/`usb`/`other`), `SwitchReason` (`device-removed`). Field names and constraints exactly as in data-model.md: `schemaVersion` "Currently `1`. Readers MUST reject unknown major versions and ignore unknown fields."; `title` "1..200 chars after trimming"; `startedAt` ISO-8601 with UTC offset, informational; `audio.file` always `audio.wav`; `audio.format` `pcm_s16le`; `audio.channels` "`1` or `2`; constant for the whole file"; `audio.durationFrames` "`>= 0`" and authoritative; `audio.durationMs` derived; `Source.id` "Index within the meeting, starting at 0"; `Source.deliveredChannels` "Number of channels the device delivers (`>= 1`)"; `Source.channels` "Number of channels recorded from this source (`1` or `2`; at most `deliveredChannels`)"; `SourceSwitch.atFrame` "`0 <= atFrame <= durationFrames`"; `SourceSwitch.toSourceId` "differs from `fromSourceId`"; `Photo.file` `photos/photo-NNN.jpg`; `Dropout` with `atFrame` ("Position on the audio timeline at which audio is missing. `0 <= atFrame <= durationFrames`"), `atMs` derived and `droppedFrames` "Number of frames that were captured but not stored (`> 0`)", and `MeetingMetadata.dropouts` ordered by `atFrame`
- [X] T011 [P] Write `core/src/test/kotlin/PKG/core/time/TimelineTest.kt`: frames to ms (floor) and ms to frames at 48000 Hz and 44100 Hz, zero, large values beyond 6 h without overflow, display formatting `mm:ss` and `h:mm:ss`, position validation against `durationFrames`
- [X] T012 [P] Implement `core/src/main/kotlin/PKG/core/time/Timeline.kt`: the single conversion between sample frames and milliseconds (`atMs = atFrame * 1000 / sampleRate`, floor, using `Long` arithmetic), display formatting, and `isValidPosition(atFrame, durationFrames)` (Principle V)
- [X] T013 [P] Write `core/src/test/kotlin/PKG/core/json/MeetingJsonTest.kt` with golden files in `core/src/test/resources/golden/`: `meeting-mono-complete.json`, `meeting-stereo-switch-photos.json`. Cases: round trip equals golden bytes, every golden file and every encoded sample validates against `meeting.schema.json` and a deliberately broken sample is rejected by it (the schema and the Kotlin model must not drift apart), unknown fields ignored, unknown `schemaVersion` rejected, each validation rule from data-model.md reported as a violation (channels vs layout mismatch, `atFrame` out of range, unsorted lists, wrong derived `atMs`, unknown source id, switch to same source, bad photo file name, dropout with `droppedFrames` of 0 or position out of range, source `channels` above `deliveredChannels` or above 2), unreadable JSON handled
- [X] T014 Implement `core/src/main/kotlin/PKG/core/json/MeetingJson.kt` (encode/decode with `kotlinx.serialization`, pretty-printed, stable key order, `ignoreUnknownKeys`, `schemaVersion` check) and `core/src/main/kotlin/PKG/core/json/MeetingValidator.kt` returning a list of violations per the validation rules in data-model.md (depends on T010, T012)
- [X] T015 [P] Write `core/src/test/kotlin/PKG/core/wav/WavHeaderTest.kt` and the test fixture `core/src/test/kotlin/PKG/core/wav/InMemorySeekableStore.kt`: canonical 44-byte header bytes for mono and stereo 48 kHz/16-bit, size fields after `updateSizes`, parse round trip, repair of a file whose header says size 0 but has data, repair truncates a trailing partial frame (`length - 44` rounded down to a multiple of the block size), repair of a file shorter than 44 bytes reported as unrecoverable
- [X] T016 [P] Implement `core/src/main/kotlin/PKG/core/wav/SeekableStore.kt` (interface: `length()`, `readAt(offset, buffer)`, `writeAt(offset, buffer)`, `truncate(length)`, `sync()`) and `core/src/main/kotlin/PKG/core/wav/WavHeader.kt` (`build`, `parse`, `updateSizes(store, dataBytes)`, `repair(store): RepairResult`) (research R-1, R-3)
- [X] T017 [P] Write `core/src/test/kotlin/PKG/core/folder/MeetingFolderLayoutTest.kt`: folder name `YYYY-MM-DD_HH-mm-ss` from a start time, collision suffix `-2`, `-3`, photo names `photo-001.jpg` increasing, constants for `meeting.json`, `CLAUDE.md`, `audio.wav`, `photos`, recognition of a directory as a meeting (contains `meeting.json` or `audio.wav`)
- [X] T018 [P] Implement `core/src/main/kotlin/PKG/core/folder/MeetingFolderLayout.kt` per contracts/meeting-folder.md
- [X] T019 [P] Write `core/src/test/kotlin/PKG/core/guide/ClaudeGuideTest.kt` with golden files `core/src/test/resources/golden/claude-guide-mono.md` and `claude-guide-stereo-switch-photos.md`: output is English, contains the seven sections from contracts/meeting-folder.md with the real values (title, start, duration, channel layout, photo list as `mm:ss`, switch positions and the "no longer per-speaker" statement), lists dropouts (position and lost duration) when present, mentions that transcription is not part of the folder, and states that only the first two channels were recorded when a source's `deliveredChannels` exceeds its `channels`
- [X] T020 Implement `core/src/main/kotlin/PKG/core/guide/ClaudeGuide.kt` generating `CLAUDE.md` content from a `MeetingMetadata` (depends on T010, T012, T018)
- [X] T021 [P] Write `core/src/test/kotlin/PKG/core/model/MeetingEditorTest.kt`: create new meeting (default title `YYYY-MM-DD HH:mm` from local start when blank, trimmed otherwise, title longer than 200 chars rejected), add photo (next number, position within range), add source switch, add dropout, rename (only `title` changes), finish (`complete`, durations set, derived `atMs` recomputed), mark recovered
- [X] T022 Implement `core/src/main/kotlin/PKG/core/model/MeetingEditor.kt`: pure functions producing new `MeetingMetadata` values for create, addPhoto, addSourceSwitch, addDropout, rename, setDuration, finish and markRecovered (depends on T010, T012)
- [X] T023 [P] Write `core/src/test/kotlin/PKG/core/level/LevelMeterTest.kt`: peak and RMS in dBFS per channel from interleaved PCM16 buffers, silence gives the floor value, full-scale square wave gives 0 dBFS, mono and stereo, one-sided signal leaves the other channel at the floor
- [X] T024 [P] Implement `core/src/main/kotlin/PKG/core/level/LevelMeter.kt` (`measure(buffer, frames, channels): List<ChannelLevel>`)

### Android base in `:app`

- [X] T025 [P] Create `app/src/main/res/values/strings.xml` and `app/src/main/res/values-de/strings.xml` with `app_name` and shared action labels (OK, Cancel, Delete, Rename, Back, Resume, Stop), both files in the same change
- [X] T026 [P] Create the Material 3 theme in `app/src/main/kotlin/PKG/ui/theme/Theme.kt`, `Color.kt`, `Type.kt` (light and dark, dynamic color on Android 12+)
- [X] T027 [P] Implement `app/src/main/kotlin/PKG/AppPreferences.kt` storing `storageTreeUri` and `consentAcknowledged` in app-private preferences (data-model.md, "App preferences")
- [X] T028 Implement `app/src/main/kotlin/PKG/storage/ParcelSeekableStore.kt`: `SeekableStore` over a `ParcelFileDescriptor` opened with mode `"rw"`, using a `FileChannel` for positioned reads/writes and `FileDescriptor.sync()` for `sync()` (depends on T016)
- [X] T029 Implement `app/src/main/kotlin/PKG/storage/SafMeetingStorage.kt` on the persisted tree URI: probe a folder (create, write, seek, sync, delete a test file; reject providers that fail, research R-8), create meeting folder and `photos/` via `DocumentFile`, open a `SeekableStore` for a file, write a text file in one truncating write, read a text file, list child directories with a single `DocumentsContract.buildChildDocumentsUriUsingTree` projection query (name, mime type, modified time, size), delete a folder recursively, report free space (depends on T018, T028)
- [X] T030 Implement `app/src/main/kotlin/PKG/MeetingRecorderApp.kt`, `MainActivity.kt` and `ui/AppNavigation.kt` (Compose NavHost with the routes `setup` and `consent` only; later story tasks add `record`, `list` and `playback/{folder}` where their screens are created; start route decided by `AppPreferences`: no tree URI means `setup`, consent not acknowledged means `consent`) (depends on T026, T027)
- [X] T031 Generate Gradle dependency verification with `./gradlew --write-verification-metadata sha256 :core:test :app:assembleDebug` into `gradle/verification-metadata.xml` (depends on T001 to T006 and T030, so an app exists to build) and commit it
- [X] T032 [P] Write `app/src/test/kotlin/PKG/HardcodedStringsTest.kt`: scans `app/src/main/kotlin` for string literals passed to `Text(`, `contentDescription =`, `label =`, `title =`, `placeholder =` and notification builder text calls and fails with file and line for each hit; small documented allow-list for non-UI constants (research R-13)
- [X] T033 [P] Create `app/lint.xml` making `MissingTranslation`, `ExtraTranslation` and `HardcodedText` errors, and reference it from `app/build.gradle.kts` (research R-13)
- [X] T034 Create `gradle/verification-tasks.gradle.kts` (applied from `app/build.gradle.kts`) defining `verifyNoForbiddenDependencies` (fails when the resolved runtime classpath of the release variant contains `com.google.android.gms`, `com.google.firebase`, `com.google.android.play` or other known proprietary coordinates) and `verifyManifestPermissions` (fails when the merged release manifest's permissions differ from the allow-list in T006); wire both into `check` (research R-11, R-12)
- [ ] T035 [P] Write `app/src/androidTest/kotlin/PKG/storage/SafMeetingStorageTest.kt`: against a test document tree, create folder, write and read text, seek-and-patch bytes in a file, list, delete (needs a device or emulator)

**Checkpoint**: `./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:verifyNoForbiddenDependencies :app:verifyManifestPermissions` is green; format logic is complete and tested.

---

## Phase 3: User Story 1 - Record a meeting and keep it safe (Priority: P1) 🎯 MVP

**Goal**: First-launch setup (storage folder, consent notice), start with optional title, record
with the built-in microphone in the background and with the screen off, pause/resume, stop, and
see the saved meeting in a basic list.

**Independent Test**: Quickstart scenario S1: record 60 s with the built-in microphone, stop, and
verify a meeting folder with a playable `audio.wav`, `photos/`, `meeting.json` (`status: complete`,
mono, pause excluded from duration) and `CLAUDE.md`, listed in the app.

### Implementation for User Story 1

- [X] T036 [US1] Add US1 strings (setup, consent notice text, permission explanations, record screen, notification, list, error notices) to both `values/strings.xml` and `values-de/strings.xml`
- [X] T037 [P] [US1] Implement `app/src/main/kotlin/PKG/ui/setup/SetupScreen.kt` and `SetupViewModel.kt`: explains why a folder is needed, launches `ACTION_OPEN_DOCUMENT_TREE`, calls `takePersistableUriPermission` (read and write), runs the folder probe from T029, shows a plain notice that meetings are stored unencrypted in the chosen folder and that any sync or cloud service on it copies them off the device (no provider detection), shows an error and asks again on failure, stores the URI in `AppPreferences` (FR-002; hint to pick or create `Documents/Meetings`)
- [X] T038 [US1] Implement `app/src/main/kotlin/PKG/storage/StorageGuard.kt`: checks that the persisted tree permission is still granted and that the folder passes the probe from T029; run it on app launch (in the start-route logic of `ui/AppNavigation.kt`) and before every recording start (in `RecordViewModel`); on failure route to `setup` with an explanation instead of starting (FR-002, spec edge case "Storage folder unavailable"; depends on T029, T037)
- [X] T039 [P] [US1] Implement `app/src/main/kotlin/PKG/ui/setup/ConsentScreen.kt`: shows the notice that all participants must consent to being recorded and requires an explicit acknowledgement, stored in `AppPreferences`; not shown again afterwards (FR-003)
- [X] T040 [P] [US1] Implement `app/src/main/kotlin/PKG/permissions/PermissionRationale.kt` and a Compose helper: before the system dialog show a screen explaining why `RECORD_AUDIO` and `POST_NOTIFICATIONS` are needed; denied state offers retry and a link to app settings; recording does not start without `RECORD_AUDIO` (FR-031)
- [X] T041 [P] [US1] Write `app/src/test/kotlin/PKG/recording/RecordingStateMachineTest.kt` for the states in data-model.md (Idle, Recording, Paused, Suspended, Finalizing): allowed and disallowed transitions per contracts/recording-service.md, disallowed commands ignored and reported
- [X] T042 [US1] Implement `app/src/main/kotlin/PKG/recording/RecordingStateMachine.kt` (pure Kotlin, no Android imports) and the `RecordingState`, `RecordingEvent` types from contracts/recording-service.md (depends on T041)
- [X] T043 [P] [US1] Implement `app/src/main/kotlin/PKG/recording/AudioCapture.kt`: `AudioRecord` at 48000 Hz, `ENCODING_PCM_16BIT`, `MediaRecorder.AudioSource.MIC`, built-in microphone mono, dedicated high-priority read thread, `start()`/`stop()`/`release()`, buffers delivered through a callback, error callback on read failure
- [X] T044 [P] [US1] Implement `app/src/main/kotlin/PKG/recording/WavWriter.kt`: bounded queue (about 10 s of audio) drained by a writer thread into the meeting's `audio.wav` through a `SeekableStore`; writes the header at start (data size 0), appends PCM, and every 1 s of audio calls `WavHeader.updateSizes` and `sync()`; also on pause and stop; when the queue is full it never blocks the capture thread: the newest buffer that does not fit is dropped, counted in `framesAppended` terms only for stored audio, and reported as `AudioDropped(atFrame, frames)` so the session can add a dropout to `meeting.json` and show a notice; exposes `framesAppended` (atomic) counted at enqueue (research R-3, R-4)
- [X] T045 [US1] Implement `app/src/main/kotlin/PKG/recording/MeetingFileWriter.kt`: creates the meeting folder and `photos/`, writes the initial `meeting.json` (`status: recording`) with, until `SourceMonitor` exists (US2), a fixed built-in source entry (`name` "Built-in microphone", `type` builtin, `deliveredChannels` 1, `channels` 1) built with `MeetingEditor`, rewrites `meeting.json` in one truncating write on request, and finalizes (final header, `durationFrames`, `status: complete`, `CLAUDE.md` from `ClaudeGuide`); discards the whole folder when zero frames were recorded (FR-018, FR-019, FR-023; depends on T022, T020, T029)
- [X] T046 [US1] Implement `app/src/main/kotlin/PKG/recording/RecordingSession.kt`: orchestrates `AudioCapture`, `WavWriter`, `LevelMeter` (levels about 20 Hz) and `MeetingFileWriter` under the state machine; `start(title, deviceId, storageTreeUri)`, `pause()` (stops capture, syncs), `resume()`, `stop()`; paused time is never counted; exposes `RecordingState` as a `StateFlow` (depends on T042, T043, T044, T045, T024)
- [X] T047 [US1] Define the IPC contract in `app/src/main/aidl/app/meetingrecorder/`: `IRecordingService.aidl` (commands from contracts/recording-service.md: `start`, `pause`, `resume`, `stop`, `markPhotoPosition`, `addPhoto`, `registerCallback`, `unregisterCallback`), `IRecordingCallback.aidl` (state snapshots and one-shot events) and `Parcelable` types for `RecordingState`, `ChannelLevel`, `SourceInfo` and `RecordingEvent`; `start` carries the title, the audio device id (`null` for the built-in microphone) and the storage tree URI so the service never reads app preferences (depends on T042)
- [X] T048 [US1] Implement `app/src/main/kotlin/PKG/recording/RecordingNotification.kt` (channel, ongoing notification with chronometer, Stop action, content intent to the record screen) and `RecordingService.kt` (foreground service of type microphone in its own process, owns one `RecordingSession`, started from the visible UI) plus `RecordingController.kt` (UI-side client that binds to the service through the AIDL interface and exposes the commands and the state flow of contracts/recording-service.md); also add `<service android:name=".recording.RecordingService" android:process=":recording" android:foregroundServiceType="microphone" android:exported="false"/>` to `app/src/main/AndroidManifest.xml` (FR-006; depends on T046 and the IPC task before it)
- [X] T049 [P] [US1] Implement `app/src/main/kotlin/PKG/ui/components/LevelMeterBar.kt`: Compose bar for one channel level in dBFS, with a content description from string resources
- [X] T050 [US1] Implement `app/src/main/kotlin/PKG/ui/record/RecordScreen.kt` and `RecordViewModel.kt`: optional title field (default shown as the date and time), Start, elapsed time, level meter, Pause/Resume, Stop with a state-dependent layout; keeps the screen state in sync with the bound controller; when no microphone source is available it shows an error (string in both languages) and does not start; adds the `record` route to `ui/AppNavigation.kt` and makes it the start destination once setup, consent and the storage check pass (FR-001, FR-004, FR-005, FR-007; depends on T048, T049, T040)
- [X] T051 [P] [US1] Implement `app/src/main/kotlin/PKG/storage/MeetingRepository.kt`: lists meetings via `SafMeetingStorage`, parses each `meeting.json` with `MeetingJson`, newest first by `startedAt`; a folder with missing or invalid metadata is still returned with what can be determined (folder name as title, duration from the WAV length) (FR-026; depends on T029, T014)
- [X] T052 [US1] Implement the basic list in `app/src/main/kotlin/PKG/ui/list/MeetingListScreen.kt` and `MeetingListViewModel.kt`: title, start date, duration; empty state; opened after stopping a recording; adds the `list` route to `ui/AppNavigation.kt` (depends on T051)
- [X] T053 [US1] Wire navigation in `ui/AppNavigation.kt`: `setup` to `consent` to `record`, `record` to `list`, back handling during a recording keeps the service running (depends on T037, T039, T050, T052)
- [X] T054 [US1] Add the settings/about screen `app/src/main/kotlin/PKG/ui/settings/SettingsScreen.kt` (re-read the consent notice, storage folder with change option, app version, license, "no data leaves the device" statement), with strings in both language files and a navigation entry from the list
- [ ] T055 [US1] Write `app/src/androidTest/kotlin/PKG/recording/RecordingServiceTest.kt`: with `RECORD_AUDIO` granted, record about 5 s through `RecordingController`, pause 2 s, resume, stop; assert the meeting folder contents, valid `meeting.json` (via `MeetingValidator`), a parseable WAV whose frame count matches `durationFrames`, and that the pause is excluded
- [X] T056 [US1] Run quickstart scenario S1 on a device and record the result in the PR description. **Result 2026-11-15: passed** on Android 14 (E940-2849-01); two meetings pulled from the phone and verified: valid `meeting.json`, WAV header sizes equal the file length, `durationFrames` equals the audio frames, `durationMs` derived correctly, audio not silent, `CLAUDE.md` present

**Checkpoint**: User Story 1 works end to end: an installable app that records and saves meetings.

---

## Phase 4: User Story 6 - Recover a recording after a crash (Priority: P2)

**Goal**: Everything recorded before an app crash, system kill or restart is kept; the interrupted
meeting is repaired, marked as recovered and playable on next launch. Also covers interruptions
(calls, other apps), low storage and the WAV size limit.

**Independent Test**: Quickstart scenario S6: record 60 s, force-stop the app, reopen: the meeting
is listed as recovered with about 58 to 60 s of audio and a valid `meeting.json`.

### Implementation for User Story 6

- [X] T057 [P] [US6] Write `core/src/test/kotlin/PKG/core/recovery/RecoveryPlannerTest.kt`: folder with `status: recording` and WAV data gives actions repair header, set `durationFrames` from file length, mark `recovered`, regenerate `CLAUDE.md` if missing; `status: complete` gives no actions; unreadable `meeting.json` gives a reconstructed meeting (folder name as title, start from folder name, mono/stereo from the WAV header); audio file missing or shorter than 44 bytes reported as unrecoverable but the folder kept; photo files in `photos/` not listed in the metadata reported
- [X] T058 [US6] Implement `core/src/main/kotlin/PKG/core/recovery/RecoveryPlanner.kt`: pure function from a folder snapshot (file names, sizes, parsed or failed `meeting.json`, WAV header bytes) to a list of recovery actions (depends on T057, T016, T022)
- [X] T059 [P] [US6] Write `core/src/test/kotlin/PKG/core/wav/RecordingLimitsTest.kt` and implement `core/src/main/kotlin/PKG/core/wav/RecordingLimits.kt`: maximum frames for 1 and 2 channels at a given sample rate under the 4 GiB WAV data limit (minus a safety margin of 1 MiB), remaining seconds, and the 15-minute warning threshold (FR-034, research R-3)
- [X] T060 [US6] Add US6 strings (recovered label, suspension notices with Resume/Stop, low-storage and storage-full notices, limit-approaching and limit-reached notices) to both `values/strings.xml` and `values-de/strings.xml`
- [X] T061 [US6] Implement `app/src/main/kotlin/PKG/recording/RecoveryRunner.kt`: on app start (before the list loads, not while a recording is active) inspects all meeting folders through `SafMeetingStorage`, applies the actions from `RecoveryPlanner` (repair WAV through `WavHeader.repair`, rewrite `meeting.json` with `status: recovered`, regenerate `CLAUDE.md`), never modifies unknown files; call it from `MeetingRecorderApp.kt` in the main process only (check the process name; the `:recording` process must not run it) (FR-025; depends on T058, T029)
- [X] T062 [US6] Extend `RecordingSession.kt` and `MeetingFileWriter.kt`: rewrite `meeting.json` only on events (photo added, source switch, status change, stop), never on a timer; the duration on disk is written at stop and otherwise derived from the WAV length by recovery (research R-9)
- [ ] T063 [US6] Implement `app/src/main/kotlin/PKG/recording/SuspensionMonitor.kt` using `AudioManager.AudioRecordingCallback` (client silenced) plus capture read errors; in `RecordingSession.kt` move to `Suspended` (auto-pause keeping all audio), emit `Suspended(reason)`, and allow `resume()` into the same meeting (research R-6; depends on T046)
- [ ] T064 [US6] In `RecordingSession.kt` and `RecordingService.kt` handle storage and limits: check free space before start and periodically, emit `StorageLow`; on write failure for lack of space finalize with data so far and emit `StorageFull`; use `RecordingLimits` to emit `LimitApproaching` 15 min before and finalize gracefully with `LimitReached` (FR-034, contracts/recording-service.md; depends on T059, T046)
- [ ] T065 [US6] Show recovered meetings in `ui/list/MeetingListScreen.kt` with a "recovered" badge, and show the suspension, storage and limit notices with Resume/Stop actions on `ui/record/RecordScreen.kt` and in the notification (depends on T060, T063, T064)
- [ ] T066 [US6] Write `app/src/androidTest/kotlin/PKG/recording/CrashRecoveryTest.kt`: start a recording through the service, kill the `app.meetingrecorder:recording` process with `android.os.Process.killProcess` (pid found through `ActivityManager.runningAppProcesses`) while the test process keeps running, run `RecoveryRunner`, assert `status: recovered`, valid header, and audio length within 2 s of what was written (SC-003)
- [X] T067 [US6] Run quickstart scenario S6 on a device, also with the screen off, and record the result in the PR description (2026-11-16, Gigaset E940, Android 14, force-stop at about 55 s with one photo: repaired to `recovered`, 54.35 s, photo kept, badge shown. Repeated with the screen off: audio kept growing, 42.55 s recovered. Not covered: the call/other-app and storage/limit paths, see T063-T065)

**Checkpoint**: Stories 1 and 6 work: no recording is lost after crashes, kills, interruptions or full storage.

---

## Phase 5: User Story 2 - Use an external microphone with two speakers (Priority: P2)

**Goal**: List available sources with the active one marked, suggest the external microphone when
plugged in, record stereo when the source delivers two channels, show a level meter per channel.

**Independent Test**: Quickstart scenario S2 with the RØDE Wireless PRO receiver in split mode:
two independent meters, saved meeting is stereo with the receiver listed as source.

### Implementation for User Story 2

- [X] T068 [P] [US2] Write `core/src/test/kotlin/PKG/core/channels/ChannelPolicyTest.kt` and implement `core/src/main/kotlin/PKG/core/channels/ChannelPolicy.kt`: choose 2 channels when the device's reported channel counts contain 2, otherwise 1; built-in microphone always 1; a device reporting more than two channels is recorded as stereo using the first two, and the result carries both the recorded and the delivered channel count; empty channel-count list treated per research R-2 (external with stereo support gives 2) (FR-011)
- [X] T069 [US2] Add US2 strings (source list, active marker, suggestion, stereo/mono labels, meter channel names) to both `values/strings.xml` and `values-de/strings.xml`
- [X] T070 [US2] Implement `app/src/main/kotlin/PKG/recording/SourceMonitor.kt`: wraps `AudioManager.registerAudioDeviceCallback` for input devices, maps `AudioDeviceInfo` to the `Source` type (`builtin`, `usb`, `other`, name from the product name, channel counts), exposes `availableSources`, `suggestedSource` (first external USB input) and removal/added events as flows (FR-008, FR-009, FR-010; depends on T068)
- [X] T071 [US2] Extend `AudioCapture.kt` to take a preferred `AudioDeviceInfo` and a channel count: `setPreferredDevice`, stereo channel mask when 2, and read back `getRoutedDevice()` plus a routing-changed listener, logging and reporting a mismatch (research R-2; depends on T070)
- [X] T072 [US2] Extend `RecordingSession.kt`, `MeetingFileWriter.kt` and the WAV writer wiring so the meeting is created with the chosen source's channel count, `sources` (including `deliveredChannels`) and `initialSourceId` filled from the selected source, and `LevelMeter` returns one level per channel (FR-011, FR-019; depends on T071)
- [X] T073 [P] [US2] Implement `app/src/main/kotlin/PKG/ui/record/SourcePicker.kt`: shows the available sources with the active one marked, pre-selects the suggested external source when it is plugged in, lets the user override before recording starts, hidden or disabled during a recording (spec clarification: no manual switching while recording)
- [X] T074 [US2] Update `ui/record/RecordScreen.kt` and `RecordViewModel.kt` to show the source picker and one `LevelMeterBar` per recorded channel (labelled left/right for stereo) (depends on T073, T072)
- [ ] T075 [US2] Run quickstart scenario S2 with the RØDE Wireless PRO in split and merged mode and write the reported `channelCounts` into `specs/001-meeting-recording-mvp/research.md` under R-2 "Verify on device"

**Checkpoint**: Stories 1, 6 and 2 work: stereo recording from the external microphone with per-channel meters.

---

## Phase 6: User Story 3 - Survive microphone loss without losing audio (Priority: P2)

**Goal**: When the external microphone is unplugged during recording, continue on the built-in
microphone with a fixed channel layout, log the switch at its timeline position and tell the user.

**Independent Test**: Quickstart scenario S3: unplug after 30 s; audio continuous, one switch entry at
about 30 000 ms with `channelsDuplicated: true`, notice shown within 2 s.

### Implementation for User Story 3

- [X] T076 [US3] Extend `core/src/test/kotlin/PKG/core/channels/ChannelPolicyTest.kt` and `core/src/main/kotlin/PKG/core/channels/ChannelPolicy.kt` with `duplicateMonoToStereo(input: ShortArray, frames): ShortArray` (each mono frame copied into left and right) and `needsDuplication(fileChannels, sourceChannels)`; tests cover empty input, odd sizes and exact sample values (FR-014; depends on T068)
- [X] T077 [US3] Add US3 strings (switch notice with from/to source, external-available-again notice) to both `values/strings.xml` and `values-de/strings.xml`
- [X] T078 [US3] Implement the fallback in `RecordingSession.kt`: on a removal event for the active external source, stop and release capture, re-create `AudioCapture` on the built-in microphone, apply `duplicateMonoToStereo` when the file is stereo, continue appending to the same WAV; add a `SourceSwitch` through `MeetingEditor.addSourceSwitch` at the current `framesAppended` with `reason: device-removed` and `channelsDuplicated` per `needsDuplication`; add the built-in source to `sources` if missing; rewrite `meeting.json` and sync; emit `SourceSwitched`; if Android refuses to open the built-in microphone (for example in the background), move to `Suspended` (auto-pause) keeping all audio, notify with a Resume action that retries on the built-in microphone, and never stop silently. The gap is not counted in the timeline (FR-012, FR-013, FR-014, research R-7; depends on T076, T070, T072)
- [X] T079 [US3] Handle reconnects in `RecordingSession.kt`: a re-added external device during a recording emits `ExternalAvailableAgain` and never switches the source (FR-015; depends on T070)
- [X] T080 [US3] Show the notices in `ui/record/RecordScreen.kt` as a banner/snackbar (switch with source names, external available again, stays visible until dismissed or 10 s) and update the notification text during a fallback (depends on T077, T078, T079)
- [X] T081 [US3] Write `app/src/test/kotlin/PKG/recording/SourceFallbackTest.kt` (JVM test instead of androidTest: it runs the real `SourceSelector`, `ChannelPolicy`, `WavWriter` and `MeetingEditor` against an in-memory file; the session glue itself is only checked on a device) using a fake `AudioCapture` and `SourceMonitor`: stereo meeting, removal event at a known frame, assert one `SourceSwitch` with `atFrame` equal to the frame count at removal, file stays stereo, post-switch samples present in both channels, reconnect does not switch
- [ ] T082 [US3] Run quickstart scenario S3 on a device with the RØDE receiver, measure the gap and the notice latency against SC-004, repeat with the screen off and the app in the background (quickstart S3 step 4), record results in the PR description

**Checkpoint**: Stories 1, 6, 2 and 3 work: the main hardware failure no longer loses a meeting.

---

## Phase 7: User Story 4 - Take photos during the meeting (Priority: P2)

**Goal**: One-tap photos on the recording screen, stored in `photos/` and tied to the audio timeline.

**Independent Test**: Quickstart scenario S4: three photos at known moments, one right after a pause;
three files and three entries with `atMs` within 1 s of the true position.

### Implementation for User Story 4

- [X] T083 [US4] Add US4 strings (photo button description, camera permission explanation, denied state, save-failed notice) to both `values/strings.xml` and `values-de/strings.xml`
- [X] T084 [US4] Extend `RecordingController.kt` and `RecordingSession.kt` with `markPhotoPosition()` (returns `framesAppended` at call time) and `addPhoto(file, atFrame)` (uses `MeetingEditor.addPhoto`, rewrites `meeting.json`) per contracts/recording-service.md; allowed while Recording, Paused and Suspended (FR-016, FR-017)
- [X] T085 [P] [US4] Implement `app/src/main/kotlin/PKG/camera/CameraController.kt`: CameraX `Preview` and `ImageCapture` (minimize-latency, JPEG, orientation in EXIF) bound to the lifecycle of the record screen only; unbind when the screen is hidden
- [X] T086 [P] [US4] Implement `app/src/main/kotlin/PKG/camera/PhotoSaver.kt`: creates `photos/photo-NNN.jpg` through `SafMeetingStorage` using the next number from `MeetingFolderLayout`, captures with `OutputFileOptions` over the document's `OutputStream`, and reports success with the file name or failure (depends on T029, T018)
- [X] T087 [US4] Implement the photo flow in `ui/record/RecordScreen.kt` and `RecordViewModel.kt`: camera preview, a one-tap photo button; on tap first call `markPhotoPosition()`, then capture and save, then `addPhoto` with the saved name and the position taken at tap time; button feedback within 3 s; failure notice without interrupting recording (depends on T084, T085, T086, T083)
- [X] T088 [US4] Add the `CAMERA` rationale flow to `permissions/PermissionRationale.kt` and the denied state on the record screen: explanation, retry, link to settings; all other recording features keep working (FR-031; depends on T040, T087)
- [X] T089 [US4] Write `app/src/test/kotlin/PKG/camera/PhotoTakerTest.kt` (JVM test instead of androidTest: the flow logic in `PhotoTaker.kt` is plain Kotlin behind interfaces) with a fake capture backend: positions taken at tap time stay unchanged when the save callback is delayed, a photo taken after a pause reports the pause-point frame, photo numbering is `photo-001`, `photo-002`, and so on
- [X] T090 [US4] Run quickstart scenario S4 on a device and record the result in the PR description (2026-11-16, Gigaset E940, Android 14: 3 photos at 10.55 s, 25.5 s and 31.46 s of 39.56 s, the last right after a 19 s pause; JPEGs valid with EXIF; no PR yet)

**Checkpoint**: Stories 1, 6, 2, 3 and 4 work: audio plus photos on one timeline.

---

## Phase 8: User Story 5 - Browse, play back and manage meetings (Priority: P3)

**Goal**: Play back meetings with jumps to photo positions, rename, and delete with confirmation, on
top of the list from User Story 1.

**Independent Test**: Quickstart scenario S5: rename one meeting, play another and jump to a photo,
delete one after confirming; list and storage folder reflect each change.

### Implementation for User Story 5

- [X] T091 [US5] Add US5 strings (playback controls, photo jump, rename dialog, delete confirmation, error states) to both `values/strings.xml` and `values-de/strings.xml`
- [X] T092 [P] [US5] Implement `app/src/main/kotlin/PKG/playback/MeetingPlayer.kt`: platform `MediaPlayer` on the meeting's `audio.wav` document URI, play/pause/`seekTo(ms, SEEK_CLOSEST)`, position polled about every 200 ms as a flow, released when the playback screen leaves the foreground (FR-028, research R-15)
- [X] T093 [US5] Implement `app/src/main/kotlin/PKG/ui/playback/PlaybackScreen.kt` and `PlaybackViewModel.kt`: play, pause, seek bar and visible position (`Timeline` formatting), a photo strip from `meeting.json`, tapping a photo seeks to `atMs` of that photo (within 1 s, SC-008) and shows the photo; large photo viewer (FR-029; depends on T092, T051)
- [X] T094 [US5] Implement rename in `ui/list/MeetingListScreen.kt` and `MeetingListViewModel.kt`: dialog with the current title, `MeetingEditor.rename` (title 1..200 characters after trimming), one truncating write of `meeting.json`, folder name unchanged, `CLAUDE.md` regenerated (FR-027)
- [X] T095 [US5] Implement delete in `ui/list/MeetingListScreen.kt` and `MeetingListViewModel.kt`: a confirmation dialog naming the meeting; only after confirmation delete the whole folder through `SafMeetingStorage`; cancel changes nothing (FR-030)
- [X] T096 [US5] Make the list robust and complete in `MeetingRepository.kt` and `MeetingListScreen.kt`: pick up folders added from outside the app on every resume, show unreadable folders with what can be determined, keep unknown files untouched, show title, start date and duration (FR-026; depends on T094, T095)
- [X] T097 [US5] Add the `playback/{folder}` route in `ui/AppNavigation.kt` and open it from list items (depends on T093, T096)
- [ ] T098 [US5] Run quickstart scenario S5 on a device and record the result in the PR description

**Checkpoint**: All six user stories are independently functional; Phase 1 feature-complete.

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Release readiness, documentation and verification across stories

- [ ] T099 [P] Create F-Droid store texts in `fastlane/metadata/android/en-US/` and `fastlane/metadata/android/de-DE/` (`title.txt`, `short_description.txt`, `full_description.txt`)
- [ ] T100 [P] Write the repository `CLAUDE.md` (English): build and test commands from quickstart.md, module boundaries (`:core` stays pure Kotlin), the constitution's key rules, and the folder format reference link to `specs/001-meeting-recording-mvp/contracts/meeting-folder.md`
- [ ] T101 [P] Write `README.md` (English): what the app does, privacy statement, how to build, how to read a meeting folder without the app, license
- [ ] T102 [P] Accessibility pass across `app/src/main/kotlin/PKG/ui/`: content descriptions via string resources, touch target sizes, contrast in light and dark, TalkBack order of the record screen controls
- [ ] T103 Verify localization end to end: `./gradlew :app:lintRelease` with no `MissingTranslation`/`ExtraTranslation`, `HardcodedStringsTest` green, switch the device between English and German and walk all screens (quickstart S9); confirm `meeting.json` keys and `CLAUDE.md` stay English
- [ ] T104 Verify privacy and permissions (quickstart S8): airplane mode full run, `aapt dump permissions` equals the allow-list, no `INTERNET`, `verifyNoForbiddenDependencies` green
- [ ] T105 Verify reproducibility (research R-12): build the release variant twice on two clean checkouts or machines and compare the APKs; fix any non-determinism (timestamps, paths)
- [ ] T106 Run quickstart scenario S7 (3 h stereo, screen off) and the limit unit tests; record battery and storage observations
- [ ] T107 Run the full automated check set from quickstart.md and the manual scenarios S1 to S10; fix failures; record the outcome in the PR description
- [ ] T108 Clean up leftovers: search for `TODO`, unused dependencies in `gradle/libs.versions.toml`, unused strings in both language files

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies.
- **Foundational (Phase 2)**: Depends on Setup. BLOCKS all user stories. T031 (dependency checksums) runs after T030.
- **US1 (Phase 3)**: Depends on Foundational. This is the MVP.
- **US6 (Phase 4)**: Depends on US1 (extends the session, writer and list).
- **US2 (Phase 5)**: Depends on US1; independent of US6.
- **US3 (Phase 6)**: Depends on US2 (needs the source monitor and the stereo path).
- **US4 (Phase 7)**: Depends on US1; independent of US2, US3 and US6.
- **US5 (Phase 8)**: Depends on US1 (list and repository); independent of the others.
- **Polish (Phase 9)**: Depends on all stories that are to ship.

### User Story Dependencies

- **US1 (P1)**: Starts after Foundational. No story dependencies.
- **US6 (P2)**: Builds on the US1 writer and session. Recovery logic in `:core` (T057 to T059) can start right after Foundational.
- **US2 (P2)**: Builds on US1 capture and session. T068 (`ChannelPolicy`) can start after Foundational.
- **US3 (P2)**: Needs US2 (T070, T072). T076 can start once T068 exists.
- **US4 (P2)**: Needs only US1. Camera tasks T085 and T086 can start after Foundational.
- **US5 (P3)**: Needs US1 list and storage. T092 can start after Foundational.

### Within Each Story

- `:core` tests before their implementation (Principle VII).
- Strings task first within a story, because UI tasks reference the strings.
- Models, then services/session, then UI, then wiring, then device validation.

### Parallel Opportunities

- Setup: T003 to T009 in parallel after T001 and T002.
- Foundational `:core`: the test/impl pairs T011/T012, T013/T014, T015/T016, T017/T018, T019/T020, T021/T022 and T023/T024 touch different files and can run in parallel across pairs; Android base tasks T025 to T027, T032, T033, T035 in parallel.
- US1: T037, T039, T040, T041, T043, T044, T049, T051 in parallel.
- After US1: US6, US2, US4 and US5 can proceed in parallel by different people; US3 follows US2.
- Polish: all tasks marked [P] in parallel.

---

## Parallel Example: Foundational `:core`

```bash
# Launch the independent core tests together:
Task: "Write TimelineTest in core/src/test/kotlin/app/meetingrecorder/core/time/TimelineTest.kt"
Task: "Write WavHeaderTest and InMemorySeekableStore in core/src/test/kotlin/app/meetingrecorder/core/wav/"
Task: "Write MeetingFolderLayoutTest in core/src/test/kotlin/app/meetingrecorder/core/folder/MeetingFolderLayoutTest.kt"
Task: "Write LevelMeterTest in core/src/test/kotlin/app/meetingrecorder/core/level/LevelMeterTest.kt"
```

## Parallel Example: User Story 1

```bash
Task: "Implement SetupScreen and SetupViewModel in app/src/main/kotlin/app/meetingrecorder/ui/setup/"
Task: "Implement ConsentScreen in app/src/main/kotlin/app/meetingrecorder/ui/setup/ConsentScreen.kt"
Task: "Implement AudioCapture in app/src/main/kotlin/app/meetingrecorder/recording/AudioCapture.kt"
Task: "Implement WavWriter in app/src/main/kotlin/app/meetingrecorder/recording/WavWriter.kt"
Task: "Implement MeetingRepository in app/src/main/kotlin/app/meetingrecorder/storage/MeetingRepository.kt"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1 Setup, then Phase 2 Foundational (tested format core).
2. Phase 3 User Story 1.
3. **STOP and VALIDATE**: quickstart S1, plus the automated gate (`:core:test`, lint, dependency and permission checks).
4. Install and use it as a voice recorder for meetings.

### Incremental Delivery

1. Setup + Foundational: format core ready.
2. US1: usable recorder (MVP), including the settings/about screen.
3. US6: crash-proof (the trust story; do this before sharing the app widely).
4. US2, then US3: external stereo microphone and unplug fallback (the main hardware scenario).
5. US4: photos.
6. US5: playback, rename, delete.
7. Polish: store texts, accessibility, reproducibility check.

### Notes

- The 4 GiB WAV limit handling (T059, T064) implements FR-034 and the "Very long meetings" edge case.
- Any task that adds or upgrades a dependency or plugin re-runs
  `./gradlew --write-verification-metadata sha256` and commits `gradle/verification-metadata.xml`.
- Commit after each task or logical group, in English.
- Never mark a story done before its quickstart scenario passes on a device.
