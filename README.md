# Meeting Recorder

An Android app for recording meetings, taking photos on the audio timeline, and (in later phases)
transcribing them. Everything stays on the device: the app has no network access, no telemetry and
no cloud features.

## What it does (Phase 1)

- Records meeting audio from the built-in microphone or an external USB microphone. A source that
  delivers two channels is recorded in stereo, everything else in mono. Recording continues with
  the screen off and with the app in the background.
- If the external microphone is unplugged during a recording, the recording continues on the
  built-in microphone and the switch is logged with its position on the timeline.
- Take photos with one tap during a recording (whiteboard, slides). Every photo is placed on the
  audio timeline.
- Browse, play back, rename and delete meetings. Selecting a photo during playback jumps to the
  moment it was taken.
- Survives crashes, system kills and full storage. After a crash or kill, what was recorded is
  repaired and marked as recovered on the next start; when storage runs full, the recording is
  saved up to that point.

Not in Phase 1: transcription, speaker detection, anything that needs a network.

## Privacy

- No `INTERNET` permission, no analytics, no ads, no Google Play Services, no Firebase.
- Permissions are requested only when a feature needs them, each with an explanation:
  microphone (recording), camera (photos), notifications (the recording indicator).
- Meetings are stored **unencrypted** in a folder you choose. If that folder is synchronized by a
  sync or cloud app, the meetings are copied off the phone by that app, not by this one.
- Recording people requires their consent. The app asks you to confirm this before the first
  recording.

## The meeting folder

Each meeting is an open, self-describing folder that you can read without the app:

```text
2026-11-16_20-45-42/
├── audio.wav      one continuous PCM file, 48 kHz, 16 bit, mono or stereo
├── photos/        photo-001.jpg, photo-002.jpg, ...
├── meeting.json   title, start, duration, sources, source switches, photos, dropouts
└── CLAUDE.md      explains the folder and the format, and suggests follow-up work
```

All positions in `meeting.json` are sample positions on the audio timeline (`atFrame`), with
milliseconds (`atMs`) derived from them. Give a meeting folder to a person or to an AI assistant
such as Claude Code: `CLAUDE.md` in the folder is enough to answer questions about it or to write a
summary, minutes or action items. The format is specified in
[`specs/001-meeting-recording-mvp/contracts/meeting-folder.md`](specs/001-meeting-recording-mvp/contracts/meeting-folder.md)
and as a JSON Schema in
[`contracts/meeting.schema.json`](specs/001-meeting-recording-mvp/contracts/meeting.schema.json).

## Building

Requirements: JDK 21 and the Android SDK (platform 37). The Gradle wrapper pins Gradle and checks
its checksum; all dependencies are verified against `gradle/verification-metadata.xml`.

```bash
./gradlew :core:test :app:check :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`:app:check` also runs lint (missing translations and hard-coded text are errors), the
hard-coded-string scan, and two build gates: no proprietary or Google dependencies, and the
merged manifest must contain exactly the allowed permissions. More commands and the manual test
scenarios are in [`specs/001-meeting-recording-mvp/quickstart.md`](specs/001-meeting-recording-mvp/quickstart.md).

## Project layout

- `core/` pure Kotlin (no Android): the meeting format, timeline math, WAV handling and recovery
  planning. Fully unit-tested.
- `app/` the Android app: user interface, recording service, camera, storage access, playback.
- `specs/` the specification, plan, contracts and tasks (Spec Kit).

## License

SPDX-License-Identifier: GPL-3.0-or-later. See [LICENSE](LICENSE).
