# Meeting Recorder: notes for Claude Code

Android app (Kotlin, Jetpack Compose, Material 3, minSdk 30) that records meeting audio and photos
into open meeting folders. Specification, plan, contracts and tasks are in
`specs/001-meeting-recording-mvp/`; the constitution is `.specify/memory/constitution.md`.

## Build and test

```bash
./gradlew :core:test :app:check :app:assembleDebug    # everything that must be green
./gradlew :app:verifyNoForbiddenDependencies :app:verifyManifestPermissions
```

- JDK 21 and the Android SDK are required (`JAVA_HOME`, `ANDROID_HOME`). The wrapper is pinned
  with a checksum.
- After adding or changing a dependency or plugin, regenerate
  `gradle/verification-metadata.xml` with `./gradlew --write-verification-metadata sha256 help`
  and review the diff.
- Manual scenarios S1 to S10 are in `specs/001-meeting-recording-mvp/quickstart.md`.

## Module boundaries

- `:core` is pure Kotlin and must never depend on Android (enforced by its Gradle config). Anything
  that defines the meeting folder, the timeline or the WAV file belongs here, with unit tests.
- `:app` depends on `:core`, never the other way round. File access goes through the
  `SeekableStore` interface so format logic stays testable.
- The recording service runs in its own process (`:recording`) and talks to the UI through AIDL;
  see `contracts/recording-service.md`.

## Rules that are easy to break

- **English everywhere** in code, identifiers, comments, commits, specs and file formats. Only UI
  strings are localized: `values/strings.xml` (English) and `values-de/strings.xml` (German). No
  hard-coded UI strings (a unit test and lint enforce it). `meeting.json` and the generated
  `CLAUDE.md` stay English.
- **Single timeline**: positions are sample frames (`atFrame`) counted by the WAV writer; `atMs` is
  derived. Never use wall-clock time for positions.
- **Minimal permissions**: the manifest allow-list is checked by `verifyManifestPermissions`. No
  `INTERNET`. No Google, Firebase or other proprietary dependencies (F-Droid compatible).
- **No data loss**: the audio file is crash-safe (header rewritten every second and repairable from
  the file length). Do not add anything on the recording path that can block the capture thread.
- **Every recording needs a fresh service instance.** `RecordingController` unbinds when the
  recording ends. A service that stays bound is reused and the system silences the microphone in
  the background ("App op 27 missing").
- Keep `tasks.md` honest: tick a task only when it is done and, where it says so, verified on a
  device. Device checks are noted next to the task.

## Meeting folder format

`audio.wav`, `photos/photo-NNN.jpg`, `meeting.json` (schema in `contracts/meeting.schema.json`,
`schemaVersion` 1) and a generated `CLAUDE.md`. The folder is the source of truth; the app has no
database.
