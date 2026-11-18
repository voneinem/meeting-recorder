# Quickstart: Validating the Meeting Recording MVP

A run-and-validate guide. It assumes the project skeleton described in [plan.md](plan.md) exists.
Entity and format details are in [data-model.md](data-model.md) and [contracts/](contracts/).

## Prerequisites

- JDK 17, Android SDK (platform matching `targetSdk` from `gradle/libs.versions.toml`), `adb`.
- An Android 11+ phone with USB-C and developer mode; for Stories 2 and 3 the RØDE Wireless PRO
  receiver and two transmitters. The emulator cannot validate USB microphones or crash timing
  reliably; use it only for UI and `:core` checks.

## Automated checks (no device needed)

```bash
./gradlew :core:test                    # all format logic: JSON, timeline, WAV, folder, guide, recovery
./gradlew :app:testDebugUnitTest        # state machine, channel selection, hard-coded string scan
./gradlew :app:lintDebug                # MissingTranslation / ExtraTranslation / HardcodedText = errors
./gradlew :app:verifyNoForbiddenDependencies   # fails on GMS, Firebase, Play, proprietary SDKs
./gradlew :app:verifyManifestPermissions       # merged manifest equals the permission allow-list
./gradlew :app:assembleDebug
```

Expected: all green. `:core` must build without the Android Gradle plugin.

## Instrumented tests (device or emulator)

Three tests need a device: `SafMeetingStorageTest`, `RecordingServiceTest` and `CrashRecoveryTest`.
They use a document tree served by a debug-only provider (`app/src/debug/`), so they never touch
your real storage folder, and they grant the microphone permission themselves. Keep the screen
unlocked while they run.

**Do not use `./gradlew connectedDebugAndroidTest` on a phone whose app data you want to keep:** Gradle
uninstalls the app afterwards, which also deletes the storage-folder choice. Install and run by hand:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r app.meetingrecorder.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (7 tests)`. Add `-e class app.meetingrecorder.recording.CrashRecoveryTest` to run one class.

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Manual validation scenarios

Each scenario maps to a user story in [spec.md](spec.md).

### S1: First launch and basic recording (Story 1, SC-001, SC-010)

1. Start the app fresh. Expect the setup flow: choose a storage folder (for example create
   `Documents/Meetings`) with a visible note that meetings are stored unencrypted and that a sync or
   cloud service on the folder copies them off the phone, then the recording-consent notice.
   Recording cannot start before both.
2. Start a meeting without a title; record 60 s with the built-in microphone; switch the screen
   off for 20 s; open another app; return; pause 10 s; resume; stop.
3. Expect in the storage folder: `YYYY-MM-DD_HH-mm-ss/` with `audio.wav`, `photos/`, `meeting.json`,
   `CLAUDE.md`. `meeting.json` validates against `contracts/meeting.schema.json`, `status` is
   `complete`, `durationMs` is about 50 000 (pause excluded), `channelLayout` is `mono`, and the
   title is the start date and time.
4. Open the WAV in any audio player; expect continuous audio.
5. Start a second meeting: the consent notice does not appear again.
6. With a stopwatch, measure from opening the app to a running recording on a returning launch:
   at most 10 seconds and 3 taps (SC-001).

### S2: External stereo microphone (Story 2, SC-006)

1. Put the RØDE receiver in split mode and plug it in with the app open. Expect it listed and
   suggested; select it; the level meter shows two channels.
2. Speak into transmitter A only, then B only. Expect only the matching meter to move.
3. Stop. Expect `channelLayout: stereo`, `sources` containing the RØDE with 2 channels. Verify in
   an audio editor that a one-sided test signal stays on one channel.
4. Repeat in merged mode; note the reported channel count (see research R-2) and record it in the
   test notes.

### S3: Unplug during recording (Story 3, SC-004)

1. Record with the RØDE receiver in split mode; after 30 s unplug it; keep talking 30 s; stop.
2. Expect: recording kept running, a visible notice within 2 s, `sourceSwitches` has one entry at
   about 30 000 ms with `channelsDuplicated: true`, the file is still stereo and continuous, and
   the second half is audible in both channels.
3. Plug the receiver in again during a recording: expect only a notice, no source change.
4. Repeat steps 1 and 2 with the screen off and the app in the background. Expect the same result; if
   the built-in microphone cannot be opened, expect the recording to pause (Suspended) with a
   notification offering Resume, and all earlier audio intact. Record the Android version and device.

### S4: Photos (Story 4, SC-005)

1. Record; tap the photo button at about 10 s, 25 s and (after a 15 s pause) right after resuming.
2. Expect three files in `photos/`, three entries with `atMs` within 1 s of the true audio
   position (the third excludes the paused time).
3. Deny the camera permission in a fresh install: expect an explanation screen and everything else
   working.

### S5: Meeting list and playback (Story 5, SC-008)

1. Open the list: newest first with title, date, duration. Copy a meeting folder in from a computer:
   it appears.
2. Play a meeting with photos; select a photo: playback jumps to its position within 1 s.
3. Rename: `meeting.json` title changes, folder name stays. Delete: confirmation first; cancel keeps
   everything; confirm removes the whole folder.

### S6: Crash recovery (Story 6, SC-003)

1. Record 60 s, take a photo, then kill the app:
   `adb shell am force-stop app.meetingrecorder`
2. Reopen the app. Expect the meeting listed as recovered, about 58 to 60 s of audio playable,
   the photo present, `meeting.json` valid with `status: recovered`.
3. Repeat with the phone's process killed while the screen is off.

### S7: Long recording and limits (SC-002)

1. Record 3 h in stereo with the screen off and battery optimization at default; expect no gaps and
   a correct duration.
2. Verify the 4 GiB limit handling with a unit test on `:core` (simulated sizes), not by
   recording 6 h.

### S8: Offline and privacy (SC-009)

1. Enable airplane mode; run S1, S4 and S5 fully.
2. Inspect the merged manifest: no `INTERNET` permission.
   `aapt dump permissions app-debug.apk` must list exactly the allow-listed permissions.
   *Phase 2 note: from Phase 2 on `INTERNET` is present by design for the explicit model download, and this
   step checks the Phase 2 allow-list instead (see `specs/002-offline-transcription/research.md` R-11).*

### S9: Localization (FR-033)

1. Switch the device language between English and German; every screen, notification and
   permission explanation follows. `meeting.json` keys and `CLAUDE.md` stay English.

### S10: Folder readable without the app (SC-007)

1. Copy one finished meeting folder (with a source switch and at least two photos) to a computer.
2. Give only that folder to Claude Code (or a person) and ask for: title, start time, duration,
   channel layout, source switches with positions, and photo positions as `mm:ss`.
3. Expect all answers to match `meeting.json`; note any question that needed help beyond
   `CLAUDE.md` and fix the guide generator.
