# Quickstart: Validating Offline Transcription (Phase 2)

A run-and-validate guide. It assumes the structure of [plan.md](plan.md) exists once the tasks are
done; until then, only the toolchain and spike sections apply. Formats and rules are in
[contracts/](contracts/) and [data-model.md](data-model.md), decisions in [research.md](research.md).
Phase 1's scenarios S1 to S10 are in
[../001-meeting-recording-mvp/quickstart.md](../001-meeting-recording-mvp/quickstart.md); the
numbering here continues at S11.

## Prerequisites

- Everything of Phase 1 (JDK 21 via `JAVA_HOME`, `ANDROID_HOME`, `adb`, the phone).
- **NDK and CMake** (neither is installed on the development machine yet):

  ```bash
  export JAVA_HOME=~/Android/jdk-21 ANDROID_HOME=~/Android/Sdk
  $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "ndk;29.0.14206865" "cmake;3.31.6"
  ```

  The exact versions are pinned in `whisper/build.gradle.kts`; keep them in step with this line.
- **Emulator images** for the Android 14 and Android 15 paths of the foreground service
  (functional checks only, never speed):

  ```bash
  $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "emulator" \
      "system-images;android-34;default;x86_64" "system-images;android-35;default;x86_64"
  ```
- **The engine source**, once the foundation task has added it:

  ```bash
  git submodule update --init --recursive
  git -C whisper/src/main/cpp/whisper.cpp rev-parse HEAD   # must print d1be6fde11ac6e0407606b4e42fe72d34add8037 (v1.9.5)
  ```
- **A 10-minute German test recording** for the spike and scenarios: your own meeting (it stays
  on your machine and phone and is never committed; `spike/audio/` is ignored by git) or a
  public-domain German reading. `ffmpeg` on the host converts it to the CLI's input format:

  ```bash
  ffmpeg -i meeting.wav -ar 16000 -ac 1 -c:a pcm_s16le spike/audio/de-10min.wav
  ```
- For the instrumented tests a small model and a sample on the phone (skipped, not failed, when
  absent): `ggml-tiny-q5_1.bin` (32 MB, hash in [model-catalog.md](contracts/model-catalog.md)) and
  `whisper/src/main/cpp/whisper.cpp/samples/jfk.wav`:

  ```bash
  adb push ggml-tiny-q5_1.bin /data/local/tmp/
  adb push whisper/src/main/cpp/whisper.cpp/samples/jfk.wav /data/local/tmp/
  ```

## Spike: choose the default model and the catalog (first task)

Rationale, cases S-1 to S-7 and decision rules: [research.md R-17](research.md). Procedure:

1. Build the development CLI (never shipped) from the submodule with the app's switches:

   ```bash
   cmake -S whisper/src/main/cpp/whisper.cpp -B build/spike-arm64 \
     -DCMAKE_TOOLCHAIN_FILE=$ANDROID_HOME/ndk/29.0.14206865/build/cmake/android.toolchain.cmake \
     -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 -DCMAKE_BUILD_TYPE=Release \
     -DBUILD_SHARED_LIBS=ON -DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON -DGGML_NATIVE=OFF \
     -DGGML_OPENMP=OFF -DGGML_CCACHE=OFF -DWHISPER_BUILD_EXAMPLES=ON -DWHISPER_BUILD_TESTS=OFF \
     -DWHISPER_BUILD_SERVER=OFF
   cmake --build build/spike-arm64 -j --target whisper-cli whisper-bench
   ```
   Push `whisper-cli` and every `lib*.so` of the build tree into one directory on the phone
   (`/data/local/tmp/spike/`); the executable loads the ggml CPU variants from its own directory.
2. Download the candidate models of [model-catalog.md](contracts/model-catalog.md) on the **host** (the
   spike script verifies each against the listed SHA-256) and push them.
3. Run `scripts/spike/run-spike.sh`, which loops over models, thread counts and options for the cases
   S-1 to S-7. One case by hand looks like this:

   ```bash
   adb shell "cd /data/local/tmp/spike && LD_LIBRARY_PATH=. ./whisper-cli \
     -m ggml-small-q5_1.bin -f de-10min.wav -l de -t 4 -nfa -np -nt"
   ```
   (`-fa` enables flash attention, `--vad --vad-model ggml-silero-v6.2.0.bin` enables VAD.) The
   real-time factor is the processing time of the timings block divided by the audio length.
4. Conditions: phone at 80% charge or charging, screen off during the long runs (raise
   `screen_off_timeout` for the session and restore it to 60000 afterwards; the user unlocks the
   phone), nothing else running, same room temperature. Record the device state in the results.
5. Write `specs/002-offline-transcription/spike-results.md` and apply its outcome: catalog
   entries, descriptions, `approxMemoryMb`, VAD yes/no, prompt yes/no, gain yes/no, default model.
   **Stop and ask if the fastest multilingual candidate is above 1.0× real time** (research R-17).

The emulator's numbers are meaningless; use it only to check that the build runs.

## Automated checks (no device needed)

```bash
./gradlew :core:test                      # all pure logic: transcript, pipeline, work file, status, resampler, catalog, goldens
./gradlew :whisper:assembleDebug          # native build for arm64-v8a and x86_64 (needs NDK and CMake); works with the network off
./gradlew :app:testDebugUnitTest          # network-source scanner, string scan, mappings, recovery
./gradlew :app:lintDebug                  # MissingTranslation / ExtraTranslation / HardcodedText = errors
./gradlew :app:verifyNoForbiddenDependencies
./gradlew :app:verifyManifestPermissions  # new allow-list incl. INTERNET only with the restricted network config
./gradlew :app:verifyNoPrebuiltBinaries   # submodule free of blobs outside the F-Droid rm list
./gradlew :app:verifyWhisperPin           # submodule HEAD equals gradle/whisper.pin
./gradlew :app:assembleDebug :app:assembleRelease
```

Expected: all green. `:core` must still build without the Android plugin and without the NDK. After
adding `work-runtime`, regenerate and review `gradle/verification-metadata.xml`
(`./gradlew --write-verification-metadata sha256 help`). Dev-only, with network, when the catalog
changes: `scripts/verify-model-catalog.sh`.

## Instrumented tests (device or emulator)

Never `connectedDebugAndroidTest` on the phone with real data (Gradle uninstalls the app and wipes the
storage grant). Install both APKs and run the runner, as in Phase 1:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r app.meetingrecorder.test/androidx.test.runner.AndroidJUnitRunner
```

Phase 1's seven tests stay green. New: `WhisperContextTest` (recognizes the known jfk sentence, abort
returns quickly, close after abort is clean), `ModelInstallTest` (against a loopback server of the
debug build: progress, cancel, resume with `Range`, wrong checksum, wrong host), `TranscriptionWorkerTest`
(tiny model, generated short meeting: files appear, `audio.wav` hash unchanged, no network bytes by
`TrafficStats`, cancel within 5 s, kill and resume), `RecordingPriorityTest`. Add
`-e class <fully.qualified.Class>` to run one.

## Manual validation scenarios

Use the real phone and the default model chosen by the spike. "Folder" means the meeting folder in
the storage folder; inspect it with a file manager or `adb`.

**S11 (US1): get a model.** Settings, Transcription, Models. The list shows name, size, languages,
speed/quality note and license for each entry, nothing installed. Tap download on the smallest entry:
the confirmation names file, size and `huggingface.co` and starts nothing before "Download".
Confirm: progress and bytes advance; cancel, check the partial data is gone; start again, switch the
screen off for a minute, switch it on: progress continued and a notification showed it. Let it finish
over Wi-Fi: the model shows as installed. Repeat once and turn Wi-Fi off in the middle: a clear message,
"Resume" continues from where it stopped. *(FR-003 to FR-005, SC-010; watch that the byte range
resume works through the redirect. Then start a recording while a download runs: the download pauses and offers
"Resume download"; during the recording Download and Import are disabled with an explanation.)*

**S12 (US1): import and delete.** With airplane mode on, import the same model from a file (copied to
the phone over USB): installed, recognized as the catalog entry. Import some non-model file and the
VAD model: both rejected with an explanation. Delete one model: space freed, not listed. Start a
transcription and try to delete its model while it runs: refused. Fill the phone's storage (or
choose a model larger than the free space): the dialog states the missing amount. *(FR-006 to FR-008.)*

**S13 (US2): transcribe in the background, offline.** Airplane mode on. Pick a meeting of about
five minutes, start a transcription (preselected defaults), turn the screen off and leave it. The
notification shows percentage and remaining time. On completion a notification appears. Folder:
`transcript.json` (validate it against the schema), `transcript.md`, updated `meeting.json`
(`transcription.transcript`, no `job`), updated `CLAUDE.md`; no `.part` or work file left;
`audio.wav` unchanged (compare its hash before and after). A recording without speech finishes
as done, never as failed. "No speech was detected" is shown only when the engine returns nothing: with VAD off
the tiny model invents one or two sentences from silence or noise (owner decision 2026-11-18, research R-7), which
is a known limit and not a failure of the scenario. *(FR-012 to FR-015, FR-024 to FR-027.)*

**S14 (US2): progress and estimate.** Transcribe the 10-minute German recording. In the app and in the
notification the percentage agrees within 2 points and keeps moving between window ends (it can stand still
until the first 2-minute window is done, because no speed is known before then: owner decision 2026-11-18); from 10% on,
the estimated remaining time stays within 25% of the real remaining time (note the estimate at 10%,
30%, 60%, 90%). Real-time factor of the whole run is within the spike's number. *(FR-016, SC-003, SC-004.)*

**S15 (US3): read and jump.** Open a transcribed meeting with photos. Segments appear in time order
with their start times, photos in between per the placement rule of
[transcript-folder.md](contracts/transcript-folder.md). Tap three segments: playback starts at each
(within 1 s). Tap during playback: it jumps without stopping. Open a meeting without transcript: no
empty area, an offer to transcribe. Open a 3-hour transcript: within 3 s, smooth scrolling. With
TalkBack on, a segment is announced with its time and text. *(FR-030 to FR-032, SC-007, SC-009.)*

**S16 (US4): cancel.** Start a transcription of the 10-minute recording and cancel from the app after
a minute: stopped within 5 s, notification gone, the meeting as before (no transcript, no work file,
no `job`). Repeat cancelling from the notification. Then start another transcription and delete that
meeting from the list: the confirmation warns that the running transcription will be cancelled, the job
stops, and the folder is gone with no work file left behind. *(FR-020, SC-006, edge case "deleted while
being transcribed".)*

**S17 (US4): kill and resume.** Start, wait until about 50%, force-stop the app (Settings, App info,
Force stop; on a second run also reboot the phone). Reopen: the meeting shows "failed" (interrupted)
with "Resume" and "Start over"; no `transcript.json` or `transcript.md` exists; the work file does;
`meeting.json` is valid and says `failed/interrupted/resumable`. Nothing started by itself. Resume: it
redoes at most two minutes and ends with a complete transcript; compare with an uninterrupted
run (same structure, same language). Delete the model and resume: a clear message and "Start over"
with another model. Also check the append cost on the real storage folder (no stall). *(FR-021, FR-022, SC-005.)*

**S18 (US4): a recording interrupts a transcription.** Start a transcription, then immediately start a
recording of one minute with the built-in microphone. The recording starts as fast as usual, the
transcription stops within 5 s with a notice, the meeting shows as failed (interrupted), and the
recording has no gaps (compare with a recording made without a transcription, Phase 1's S2 method).
While recording, try to start a transcription: refused with an explanation. After stopping, resume
by hand: nothing resumed automatically. With a model download running, start the recording: the download
pauses and can be resumed after the recording ends. *(FR-017, SC-012; check that no recording audio is lost.)*

**S19 (US5): list status.** With a meeting each of: never transcribed, running, finished, interrupted:
the list shows none (nothing), the percentage updating live, done, failed. Copy a transcribed
meeting folder in from outside: it shows done. A Phase 1 meeting shows none and plays as before.
Rename a transcribed meeting: `transcript.md` and `CLAUDE.md` show the new title; the status stays
done. *(FR-035.)*

**S20 (US6): re-transcribe.** On a transcribed meeting choose "Transcribe again" with another model:
the confirmation says the transcript will be replaced; while it runs the old transcript stays readable
next to the progress. Cancel one run midway: the old transcript is unchanged. Let one run end: all
transcript files and the metadata reflect the new model and language, no leftovers. Kill one run
midway: the list still says done, the detail screen shows the interrupted banner. *(FR-033, FR-034, SC-011.)*

**S21: a long meeting.** Transcribe a recording of about 3 hours (a Phase 1 test recording or a
concatenation) with the default model, screen off, on charge. It completes or, if it is longer than the
Android 15 limit on a phone that has it, stops as interrupted and resumes. Memory stays below the
spike's `approxMemoryMb`; the phone does not throttle to a crawl (note the per-hour speed). Opening
the result takes under 3 s. *(Edge case "audio is very long".)*

**S22: no network except the download.** `./gradlew :app:verifyManifestPermissions` is green. In
airplane mode run S12 (import, delete) and S13. With the app running a transcription on Wi-Fi,
`adb shell dumpsys netstats` / the `TrafficStats` test show no traffic for the app. Revoke the
notification permission: transcription and download still run and the in-app progress is visible. The
instrumented test confirms a connection to another host fails. The settings show the permissions
paragraph (internet, keep awake, background work) and the corrected privacy line, and no screen, and no document
(README, store texts, `CLAUDE.md`), still says that the app has no network access or no `INTERNET`
permission. *(FR-011, FR-036, SC-001.)*

**S23: foreground service types.** On the API 34 emulator the download and transcription run as
`dataSync`; on the API 35 emulator transcription runs as `mediaProcessing`
(`adb shell dumpsys activity services app.meetingrecorder` shows the type). The timeout of Android
15 is exercised if a trigger exists on the emulator; otherwise the stop-reason mapping is covered by
its unit test and the gap is recorded in the results (research R-20, item 4).

**S24: Phase 1 regression.** Phase 1's S1 to S10 pass, `meeting.json` of a new recording is
byte-identical in structure to Phase 1 (no `transcription` key), and Phase 1's seven instrumented
tests are green.

## Evidence

As in Phase 1, `tasks.md` ticks a device task only after the matching scenario passed on the phone;
the date, device, build and any deviation are written next to the task. Spike numbers live in
`spike-results.md`.
