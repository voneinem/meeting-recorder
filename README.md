# Meeting Recorder

An Android app for recording meetings, taking photos on the audio timeline, and transcribing them on
the device. Meeting data never leaves the device: the only network use is downloading a speech
recognition model when you choose to, and the app has no telemetry and no cloud features.

## What it does

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

### Transcription (Phase 2)

- Transcribes a meeting on the phone, in the background and with the screen off, with a speech
  recognition model that you install once: download it (after a confirmation that names the file, its
  size and the source) or import it from a file. Nothing is downloaded without your tap.
- Language: automatic (detected once per meeting), German or English. A model that is not suited to the
  language is flagged before you start.
- Shows the progress and the remaining time in the app and in a notification, and can be cancelled. A
  recording always has priority: starting one stops a running transcription, which can be resumed later.
- Survives interruptions: the work is checkpointed in the meeting folder, a killed or stopped run can be
  resumed, and a transcript is only ever replaced when its successor is complete.
- Writes `transcript.json` and `transcript.md` into the meeting folder, with the photos placed in the text
  where they were taken. In the app, tapping a sentence plays the audio from there.

Not in Phase 2: speaker detection, summaries, translation, live transcription.

## Look and feel

The visual style (a green accent on soft neutral surfaces, 8 dp corners, thin lines, the system font, a light
and a dark appearance that follow the phone) is inspired by the website of the Fossify project. The app is
independent: it uses no Fossify names or logos and does not claim to be a Fossify app. The colors are fixed and
do not follow the wallpaper; they are defined in `app/src/main/kotlin/app/meetingrecorder/ui/theme/Palette.kt`.

## Privacy

- The `INTERNET` permission is used only for the explicit model download from `huggingface.co`; nothing
  else is ever sent. The network security configuration allows only `huggingface.co` and `hf.co` and
  forbids cleartext, and the build fails if that is not the case. No analytics, no ads, no Google Play
  Services, no Firebase.
- Permissions are requested only when a feature needs them, each with an explanation:
  microphone (recording), camera (photos), notifications (the recording indicator and transcription
  progress). Install-time permissions without a prompt: keep awake and background work (a transcription or
  a download continues with the screen off), and internet (the model download only).
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
├── meeting.json   title, start, duration, sources, source switches, photos, dropouts, transcription
├── transcript.json   (after a transcription) the segments with their positions, model and language
├── transcript.md     (after a transcription) the readable transcript with the photos in the text
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

Requirements: JDK 21 and the Android SDK (platform 37). The speech engine (whisper.cpp) is a git
submodule, built from source with the NDK `29.0.14206865` and CMake `3.31.6`. Clone with
`git clone --recurse-submodules`, or run `git submodule update --init --recursive` afterwards, then:

```bash
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "ndk;29.0.14206865" "cmake;3.31.6"
```

The Gradle wrapper pins Gradle and checks its checksum; all dependencies are verified against
`gradle/verification-metadata.xml`.

```bash
./gradlew :core:test :app:check :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Supported ABIs are `arm64-v8a` (phones) and `x86_64` (the emulator, for functional tests only); there is
no 32-bit ARM build, so transcription is not available on such a device.

`:app:check` also runs lint (missing translations and hard-coded text are errors), the
hard-coded-string scan, and four build gates: no proprietary or Google dependencies, the merged manifest must contain
exactly the allowed permissions (and `INTERNET` only with the restricted network configuration), no
prebuilt binaries in the sources, and the engine submodule must be at the commit pinned in
`gradle/whisper.pin`. More commands and the manual test
scenarios are in [`specs/001-meeting-recording-mvp/quickstart.md`](specs/001-meeting-recording-mvp/quickstart.md).

## Project layout

- `core/` pure Kotlin (no Android): the meeting format, timeline math, WAV handling and recovery
  planning. Fully unit-tested.
- `app/` the Android app: user interface, recording service, camera, storage access, playback, models
  and transcription jobs.
- `whisper/` the native speech engine: the whisper.cpp submodule, its CMake build, a small JNI bridge and
  a Kotlin API. It depends on nothing else of the project.
- `specs/` the specification, plan, contracts and tasks (Spec Kit).

## Third-party notices

- [whisper.cpp](https://github.com/ggml-org/whisper.cpp) and ggml, MIT License, built from source as a
  git submodule pinned in `gradle/whisper.pin`.
- The speech recognition models (OpenAI Whisper weights converted to the ggml format, from
  `huggingface.co/ggerganov/whisper.cpp`) and the voice-activity model (Silero VAD, from
  `huggingface.co/ggml-org/whisper-vad`), MIT License. They are downloaded by the user, never bundled.

## License

SPDX-License-Identifier: GPL-3.0-or-later. See [LICENSE](LICENSE).
