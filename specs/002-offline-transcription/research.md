# Research & Decisions: Offline Transcription (Phase 2)

All unknowns of the plan's Technical Context are resolved below. Facts marked *(checked)* were read
from the real sources on 2026-11-16 (whisper.cpp v1.9.5 checked out and read, Hugging Face API,
Google Maven, `sdkmanager`); items marked **Verify on device** cannot be confirmed from
documentation and have an explicit check in [quickstart.md](quickstart.md).

## Where this plan departs from the input

The input description was followed except in these places, each with its reason below. The user can
overrule any of them; the first two change file formats or algorithms, the others are mechanics.

| # | Input said | Plan does | Why | Details |
|---|-----------|-----------|-----|---------|
| 1 | `transcript.json` segments with `startMs`, `endMs` | Segments carry `startFrame`/`endFrame` (authoritative) **and** `startMs`/`endMs` (derived) | Constitution V and spec FR-024/FR-028: the timeline is sample frames, milliseconds are derived; same pattern as `atFrame`/`atMs` in `meeting.json` | R-5, [schema](contracts/transcript.schema.json) |
| 2 | "Process long recordings in chunks with overlap" | Windows of 120 s whose overlap is **adaptive**: the next window starts where the last, possibly cut, segment began | Fixed overlap needs a rule to merge two decodings of the same audio, and the two decodings segment it differently, so text is duplicated or lost at the seam | R-5 |
| 3 | CPU: "NEON" | ggml's runtime CPU variants (armv8.0 up to armv9.2), picked by the CPU, not one fixed `-march` | A single armv8.2 build crashes (illegal instruction) on cores without dot-product, e.g. Cortex-A53/A73 phones | R-2 |
| 4 | OkHttp "or" HttpURLConnection | `HttpURLConnection`, manual redirects with a host allow-list | No new dependency; redirects can be policed hop by hop | R-10 |
| 5 | WorkManager | WorkManager, plus a **launch-token guard** so that it never restarts a job by itself, plus `WAKE_LOCK` | WorkManager re-runs persisted work after a kill, which FR-017/FR-022 forbid; a foreground service does not keep the CPU awake | R-9, R-11 |
| 6 | "Evaluate whisper.cpp's built-in VAD" | Spike decides; if adopted, its ~0.9 MB voice model is an auxiliary catalog download, **not bundled** | The constitution forbids bundled ML models | R-7 |

## R-1: Engine and how it is vendored

- **Decision** *(checked)*: whisper.cpp **v1.9.5** (commit `d1be6fde11ac6e0407606b4e42fe72d34add8037`,
  2026-10-06, MIT; ggml is inside the same repository, no second submodule) as a git submodule at
  `whisper/src/main/cpp/whisper.cpp`, pinned to the tag's commit. The pin (tag + commit) is also
  recorded in `gradle/whisper.pin`; the Gradle task `verifyWhisperPin` fails when the submodule's
  `HEAD` differs from it, so a drive-by submodule bump cannot slip in.
- **Rationale**: The user's choice. It is MIT, C/C++ only, builds with the NDK's CMake toolchain, has
  German support through the multilingual models, runs on the CPU without extra runtimes, and
  exposes what the app needs: segment callback, abort callback, language detection, optional VAD.
- **Alternatives**: a prebuilt whisper AAR (rejected: constitution II, no binaries); Vosk/Kaldi
  (rejected: lower accuracy on meeting speech, German models are weak); sherpa-onnx or Moonshine
  (rejected for now: a different engine and model family than the user chose, ONNX runtime is a
  bigger native dependency); Android `SpeechRecognizer` (rejected: cloud or Google components,
  constitution I and II).
- **Tag and license check**: the tag was resolved with `git ls-remote --tags`; the latest tags at the
  time were `v1.9.4` and `v1.9.5`. Upgrading later means changing the pin deliberately and re-running
  the spike's accuracy check.

## R-2: Native build (CMake + NDK), ABIs, CPU variants

- **Toolchain**: NDK `29.0.14206865` and CMake `3.31.6`, both installed with `sdkmanager`
  *(checked: available; neither is installed on the development machine yet)*, pinned in
  `whisper/build.gradle.kts` (`ndkVersion`, `externalNativeBuild.cmake.version`). NDK r28 or newer
  produces 16 KB page-aligned libraries by default; the linker flag `-Wl,-z,max-page-size=16384`
  is added explicitly so an older NDK would not silently regress (Android 15+ devices with 16 KB
  pages).
- **ABIs**: `arm64-v8a` and `x86_64` only (`ndk.abiFilters`). 32-bit ARM devices are not supported;
  this is stated in the README and the F-Droid texts. x86_64 exists for the emulator (functional
  tests; its speed is meaningless).
- **Always optimized**: the module's `CMakeLists.txt` forces `CMAKE_BUILD_TYPE=Release` before
  `project()` (`set(... CACHE STRING "" FORCE)`) and `externalNativeBuild.cmake.arguments` passes
  `-DCMAKE_BUILD_TYPE=Release` too, so the debug app variant still links the optimized native code.
  `-O3`, `-ffunction-sections -fdata-sections`, `-Wl,--gc-sections`. Debug symbols stay in the
  `.so` for debug builds only (stripped in release by AGP). **Verify**: `readelf -p .comment` and a
  speed test of the debug APK against the release APK in the spike.
- **CPU variants (arm64)** *(checked in `ggml/src/CMakeLists.txt`)*: `GGML_BACKEND_DL=ON`,
  `GGML_CPU_ALL_VARIANTS=ON`, `BUILD_SHARED_LIBS=ON`, `GGML_NATIVE=OFF`. ggml then builds the Android
  CPU backend as separate libraries (`android_armv8.0_1`, `armv8.2_1` dot product, `armv8.2_2`
  + FP16 vector arithmetic, `armv8.6_1` + int8 matrix multiply, `armv9.0_1`, `armv9.2_1/2`) and
  `ggml_backend_load_all_from_path(nativeLibraryDir)` loads the best one the running CPU supports.
  The JNI library calls it once in `nativeInit`. Consequences: the libraries must be extracted to
  disk (`packaging.jniLibs.useLegacyPackaging = true`, so `nativeLibraryDir` contains them); APK size
  grows by a few megabytes. This is ggml's supported path and replaces a hand-made two-library
  scheme. The upstream Android example builds a second variant by calling one function twice on the
  same `ggml` target, which applies both sets of flags to one target; it is not copied.
  **Verify on device**: the selected variant is logged (name only) and appears in the spike table.
- **x86_64**: one statically linked baseline CPU backend (`GGML_NATIVE=OFF`, no `ALL_VARIANTS`).
- **C++ runtime**: `ANDROID_STL=c++_static`. Every interface between our libraries is a C API (ggml and
  whisper export C functions, the JNI layer is plain JNI), so each library can carry its own copy of the
  C++ runtime, and no `libc++_shared.so` from the NDK has to be packaged.
- **Switches** *(checked in the option lists)*: `GGML_OPENMP=OFF` (a dynamic `libomp.so` from the NDK
  would be one more binary to vet; ggml's own thread pool is used), `GGML_CCACHE=OFF` (hermetic
  build), `GGML_CPU_KLEIDIAI=OFF` (it downloads sources at configure time, which constitution II
  forbids), `WHISPER_BUILD_TESTS/EXAMPLES/SERVER=OFF`, `WHISPER_CURL=OFF`, `WHISPER_SDL2=OFF`, no
  GPU backends (Vulkan is possible later, rejected for now: more native surface, driver variance).
  No `FetchContent` anywhere; the configure step must work with the network off. **Verify**: the
  build in the quickstart runs once with networking disabled.
- **Reproducibility**: `-ffile-prefix-map=<source dir>=.`, `-Wl,--build-id=none`, no `__DATE__`
  use; the NDK and CMake versions are exact pins. Phase 1's reproducible-build check (T105) is
  still open; native code makes it harder, so the check is repeated for the native libraries in
  the polish phase.

## R-3: Module split and the JNI bridge

- **Decision**: a new Android library module `:whisper` (namespace `app.meetingrecorder.whisper`)
  holds the submodule, `CMakeLists.txt`, `whisper_jni.cpp` (a few hundred lines of C++17) and a small
  Kotlin API. It depends on nothing of the project (neither `:core` nor `:app`). `:app` depends on
  `:core` and `:whisper`; `:core` stays pure Kotlin and never sees native code.
- **Kotlin API** (the "thin bridge" of the input):

  ```kotlin
  class WhisperContext : AutoCloseable {
      companion object {
          fun load(modelFile: File, nativeLibraryDir: String, options: LoadOptions = LoadOptions()): WhisperContext
          val engineVersion: String                                  // "1.9.5"
      }
      fun detectLanguage(samples: FloatBuffer, count: Int, threads: Int): List<LanguageProbability>
      fun decode(samples: FloatBuffer, count: Int, params: DecodeParams, onSegment: (RawSegment) -> Unit): DecodeStatus
      fun abort()                      // thread-safe, flips an atomic flag read by the abort callback
      override fun close()             // whisper_free; call only after decode has returned
  }
  data class DecodeParams(val language: String, val threads: Int, val initialPrompt: String?,
                          val suppressNonSpeech: Boolean, val vadModel: File?)
  data class RawSegment(val startCs: Long, val endCs: Long, val text: String)
  ```

  `samples` must be a **direct** `FloatBuffer` (no copy across JNI). One `decode` per context at a
  time. Native errors become a `WhisperException(code)`.
- **Native side**: `whisper_init_from_file_with_params`; for `decode`, `whisper_full` with the
  `new_segment_callback` (calls back into Kotlin on the decoding thread) and an `abort_callback`
  polled by ggml between graph operations; for `detectLanguage`, `whisper_pcm_to_mel` followed by
  `whisper_lang_auto_detect`. `no_context` and `suppress_nst` are set from `DecodeParams`; timestamps
  on; translation off; the engine's log is routed to `logcat` at warning level and above, and **no
  transcript text is ever logged**.
- **Testing**: no JNI logic worth testing on the JVM, which is why it stays thin. The pipeline is
  tested against `SpeechDecoder` fakes in `:core`; `WhisperContextTest` (instrumented, skipped when
  the model is absent) runs a real tiny model on a public-domain English sample and checks that the
  known sentence is recognized, which also proves loading, abort and `close`.
- **Alternative**: put the bridge in `:app` (rejected: the native build and its toolchain
  requirements would then apply to every `:app` task and the F-Droid `rm` list could not be tied to
  one module).

## R-4: Audio preprocessing: WAV to 16 kHz mono float, streaming

- **Decision**: all in `:core` (`core.audio`), pure Kotlin, on the existing `SeekableStore`:
  1. `WavPcmReader` reads `audio.wav` through the header parser of Phase 1 in blocks of 1 s of frames
     (16-bit little endian, 1 or 2 channels, any sample rate in the header). The length comes from
     the file length, as in the recovery rule, so a crash-truncated file is handled.
  2. `Downmix`: stereo to mono as `(L + R) / 2` in float. Whether a gain stage helps quiet
     recordings was a spike question (R-17, S-3): **no gain stage** (T017); -20 dB audio decodes as well as the original and normalization did not help.
  3. `PolyphaseResampler`: rational-ratio resampling to 16 kHz with a Kaiser-windowed sinc
     low-pass (cut-off just under 8 kHz, stop-band attenuation of at least 60 dB), state carried
     across blocks, linear phase with the group delay removed so that output sample `n`
     corresponds to input frame `n * rate / 16000` within one output sample (62.5 µs). Rates that
     divide evenly (48 kHz, 3:1) take a fast path.
  4. `PcmWindowSource.read(fromFrame, toFrame)` returns the window as a direct `FloatBuffer`
     (16 kHz, mono). Windows start at arbitrary frames (R-5), so the resampler is re-primed with a
     short lead-in before `fromFrame` and the lead-in output is discarded.
- **Memory**: a window is at most 120 s × 16 000 × 4 B = 7.7 MB plus one 1 s input block; the file is
  never read whole. A 6 h stereo meeting (4 GiB) is read once, front to back.
- **Cost estimate**: 3:1 decimation with a 48-tap filter is about 17 G multiply-adds for 6 h, which
  is some tens of seconds on the JVM/ART (to be measured), negligible against decoding. If the spike shows
  otherwise, the filter moves into the JNI library behind the same interface.
- **Tests** (all JVM): impulse position preserved to ±1 output sample; a 1 kHz sine keeps its
  amplitude within 0.5 dB and a 9 kHz sine is attenuated by at least 60 dB; block boundaries produce
  no discontinuity (output of 1-sample-at-a-time equals output of one big block, bit for bit);
  mono and stereo; a header with 44.1 kHz; a file whose last block is partial.
- **Alternatives**: `MediaCodec`/`MediaExtractor` (rejected: they decode compressed formats and add
  platform variance for something that is a plain PCM file); resampling in native code with the
  engine (whisper.cpp has no resampler); linear interpolation (rejected: aliasing above 8 kHz
  lowers recognition accuracy).

## R-5: Windowed decoding with adaptive overlap and checkpoints

- **Problem**: `whisper_full` already slides over audio in 30 s steps internally, but it needs the
  whole signal in memory (6 h of float samples is 1.4 GB), so the app feeds it windows. The input
  asks for chunks with overlap and for timestamps mapped back to the original timeline.
- **Why not fixed overlap**: with fixed overlap two decodings see the same seconds and segment them
  differently; deciding which one "owns" a segment (by start or midpoint) leaves duplicated words or
  gaps at some seams, and a text-level merge is fragile. Adaptive overlap has no seam to merge.
- **Decision (window rule)** *(this is what `WindowPlanner` implements and tests)*: constants
  `WINDOW = 120 s`, `TAIL_GUARD = 2 s`, `MIN_ADVANCE = 5 s`.

  ```text
  cursor = checkpoint.nextFrame            (0 for a new job)
  while cursor < durationFrames:
      end   = min(cursor + WINDOW, durationFrames)
      segs  = decode(window[cursor, end])             # times relative to cursor, 10 ms resolution
      if segs.isEmpty():                accepted = []         ; next = end
      elif end == durationFrames:       accepted = segs       ; next = end
      elif segs.last.end <= end - TAIL_GUARD:
                                        accepted = segs       ; next = end   # speech ended, silence follows
      else:                             accepted = segs minus last ; next = cursor + segs.last.start
      if next - cursor < MIN_ADVANCE:   accepted = segs ; next = max(end of segs.last, cursor + MIN_ADVANCE)  # progress guarantee
      append window line (accepted, next) to the work file ; cursor = next
  ```

  A segment that touches the end of the window may be cut by it, so it is not accepted; the next
  window starts at its beginning and decodes it whole. That re-decoded span (typically a few
  seconds) is the whole "overlap". No two accepted segments ever cover the same audio.
- **Checkpoint**: one `window` line per call, after the segments are accepted. The most work lost
  to a kill is the call in progress, at most `WINDOW` = 2 minutes of audio, which is exactly the
  bound of FR-022. (Segments could be flushed earlier, from the segment callback, to shrink the
  loss to about one segment; deferred as an optimization because 2 minutes meets the requirement.)
- **Timeline**: a segment's frames are `cursor + cs * rate / 100` in integer arithmetic, clamped
  to `[cursor, durationFrames]`. Whisper times have 10 ms resolution, so at 48 kHz they are
  multiples of 480 frames. Nothing else about positions is ever derived from wall-clock time.
- **Short remainder**: if less than 1.1 s of audio is left, the window is padded with silence to
  1.1 s (the engine refuses shorter input) and the segments are clamped to the real length.
- **Context across windows**: inside a call the engine conditions on earlier text; across calls it
  does not. The default is no carried prompt (deterministic, no risk of text being echoed). Whether
  carrying the last 200 characters improves consistency was spike item S-4: **decided no** (T017): word error rose from
  13.6% to 18.3% for `small-q8_0` and fell from 19.6% to 17.2% for `base-q5_1`, no repeats either way; the test
  sentences were unrelated, so a rerun on real meeting audio is the proper test.
- **Hallucination guard** (`RepetitionGuard`, pure): more than three consecutive segments with
  identical text collapse to the first. Known failure of Whisper on silence/noise; the spike
  measures how often it triggers (S-2).
- **Tests** (JVM, scripted `SpeechDecoder`): a fake that returns segments from a known script
  regardless of window boundaries must yield the identical transcript for any `WINDOW` between 20 s
  and 600 s and for any kill point (resume equals uninterrupted); progress guarantee when one segment
  spans the whole window; a window with no speech; the end of audio; the 1.1 s padding; a decoder
  that throws; abort in the middle.

## R-6: Language: detect once per meeting

- **Decision** (clarification of 2026-11-16): `auto` runs language detection **once** and the
  whole transcript is decoded in that language. Probes: three windows of 30 s at 10%, 40% and 70% of
  the audio (moved to the nearest position where the RMS is above −60 dBFS; a probe on digital
  silence is skipped, up to nine positions are tried). The language probabilities of the probes are
  summed and the highest one **among the languages the model supports** wins; `confidence` is its
  mean probability. English-only models skip detection and use `en`. A fixed German or English
  choice skips detection. If no probe finds signal, the device language is used when the model
  supports it, otherwise English. The result is written once to the work file (`language` line) so a
  resumed job decodes in the same language.
- **Why not the engine's own detection per call**: with `language = auto` each `whisper_full`
  call would run detection again (an extra encoder pass per window, a few percent of the total on
  larger models) and could flip languages between windows, which the clarification excludes.
- **Spike S-5** checks detection on the German recording and on an English one, and on a meeting that
  starts with silence.

## R-7: Voice activity detection

- **What exists** *(checked)*: whisper.cpp 1.9.5 has VAD in `whisper_full`
  (`params.vad`, `vad_model_path`, `vad_params`: threshold, minimum speech/silence durations,
  padding, maximum speech length). It needs a separate Silero model file in ggml format,
  `ggml-silero-v6.2.0.bin` or `v5.1.2` (885 098 bytes each, MIT, repository `ggml-org/whisper-vad`).
  When VAD is on, segment times are mapped back to the original timeline; the header notes that
  `whisper_full_get_token_data().t0/t1` stay in VAD time, which is irrelevant here because only
  segment times are used.
- **Decision**: the pipeline and the catalog schema support VAD from the start (`DecodeParams.vadModel`,
  catalog `kind = VAD`, header field `vad` in the work file and `processing.vad` in the transcript),
  but whether it ships **on** is decided by the spike (S-2) with these criteria, all three
  required: (a) on the 10-minute German recording the real-time factor does not get worse by more
  than 5%; (b) the segment start times differ from a run without VAD by at most 0.5 s on 95% of
  segments; (c) fewer hallucinated segments in the silent parts than without it. If it is not
  adopted, the fallbacks are `suppress_nst`, the engine's no-speech threshold and the repetition guard.
- **If adopted**, the VAD model is downloaded together with the first speech model (one
  confirmation showing the combined size), is hash-verified like any model and is never bundled, as
  constitution I and the technology constraints require. Entry candidate: `vad-silero-v6.2.0`
  (matches the test model that ships in whisper.cpp 1.9.5).
- **Decision (spike S-2, project owner, T017, 2026-11-18): VAD ships off and is not in the catalog.** Measured on the
  reference phone ([spike-results.md](spike-results.md)): (a) pass, 12% to 26% faster; (b) fails as written (35% of segment
  starts within 0.5 s; 76% to 80% under a looser definition); (c) pass, 15 to 16 invented segments in 30 s of silence
  became 0; but the word error rate rose by 1.3 to 4.1 points on read speech. The owner chose accuracy and a smaller
  download. The pipeline and the catalog schema still support VAD (`DecodeParams.vadModel`, `kind = VAD`), so it can be
  added later; the fallbacks stay: `suppress_nst`, the engine's no-speech threshold, the repetition guard and the
  language probe's silent-window skip. A real-meeting test (S-2 on the owner's own audio) could reopen the question.
- **Rejected for now**: a home-made energy-based silence skipper (cheap, but it cannot tell speech from
  noise, which is where the hallucinations come from).

## R-8: Models and quantization

- **Source** *(checked)*: the official `ggerganov/whisper.cpp` repository on Hugging Face (license
  tag MIT), file URLs pinned to the revision `5359861c739e955e79d9a303bcbc70fb988958b1`.
  The API lists size and SHA-256 per file; the real byte sizes and hashes of the candidates are in
  [contracts/model-catalog.md](contracts/model-catalog.md). A `HEAD` request returns the same
  hash as `x-linked-etag`, which the downloader compares before storing any data.
- **Candidates for the spike**: `tiny-q5_1` (32 MB, only to establish the floor), `base-q5_1` (60 MB),
  `base-q8_0` (82 MB), `small-q5_1` (190 MB), `small-q8_0` (264 MB), `medium-q5_0` (539 MB),
  `large-v3-turbo-q5_0` (574 MB), `large-v3-turbo-q8_0` (874 MB). The non-quantized files (148 MB to
  3 GB) are not offered: too large for a phone and slower to load.
- **Quantization is not automatically faster**: q5 weights need unpacking per use, q8_0 weights
  map directly onto the dot-product instructions. Both of `small` and `base` are measured so the
  catalog can prefer whichever is faster on the reference device.
- **English-only (`*.en`) models** are not in the catalog (German is the primary language); an
  imported `.en` file is recognised by its vocabulary size and marked English-only (FR-014).
- **Selection rule** (R-17): the **default** is the largest candidate whose sustained real-time factor
  on the reference phone is at most 0.7; the **catalog** contains every candidate at or below 1.0
  that also has a German accuracy worth offering. SC-003 asks that the fastest offered model is at
  most 1.0.
- **Descriptions** shown to the user ("fast, rough", "balanced", "slow, accurate") are written from
  the measurements, in English and German, not from folklore.
- **Memory**: whisper.cpp reads the model into memory (no `mmap`); a model needs about its file size
  plus working buffers. `approxMemoryMb` is measured in the spike (peak PSS) and drives the FR-014-style
  warning on devices with little RAM.

## R-9: Background execution

- **Decision**: WorkManager **2.12.0** *(checked: the latest stable release on Google Maven)* `CoroutineWorker`,
  unique work, expedited with `RUN_AS_NON_EXPEDITED_WORK_REQUEST`, promoted to a foreground
  service with `setForeground`; type `mediaProcessing` on Android 15+, `dataSync` before. Full rules
  in [contracts/transcription-service.md](contracts/transcription-service.md).
- **What the AAR adds** *(checked: its manifest and POM)*: permissions `WAKE_LOCK`,
  `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`; services
  `SystemJobService` and `SystemForegroundService`; receivers (reschedule is disabled by default);
  an `InitializationProvider` entry; dependencies Room, startup, tracing, jspecify,
  `concurrent-futures` and the empty `listenablefuture` stub; no native code. All are AndroidX /
  Apache-2.0. The Room database holds only job bookkeeping, never meeting content, so Principle IV
  (folder is the source of truth) is intact; deleting it loses nothing.
- **Friction and how it is handled**:
  1. WorkManager re-runs persisted work after a process death or reboot. FR-017/FR-022 require that
     resume is always the user's decision, and Android 12+ forbids starting the foreground service
     from the background anyway. **Launch-token guard**: a job only runs if its request was
     registered by the current process; otherwise it fails at once without touching the folder
     (contract). Recovery also cancels the unique work names at app start.
  2. `RECEIVE_BOOT_COMPLETED` and `ACCESS_NETWORK_STATE` are removed from the merged manifest
     (`tools:node="remove"`; Phase 1 already removes the latter). No network constraint is used, so
     WorkManager never needs the network state. **Verify**: a smoke test starts a job on a build with
     both removed.
  3. Android 15 gives `dataSync` and `mediaProcessing` foreground services a budget of 6 hours per day
     and calls `onTimeout`. A 6-hour meeting at a real-time factor near 1 can reach it. The worker maps
     the stop to `failed/interrupted/resumable` and the notification offers "Resume". **Verify**:
     the stop-reason mapping is a unit test; the real timeout is checked on an API 35+ emulator if the
     platform offers a way to trigger it, otherwise it stays an unverified risk (R-20).
- **Keeping the CPU awake**: a foreground service keeps the process alive but does **not** keep the
  CPU from suspending when the screen is off; a held wake lock does. The worker holds a
  `PARTIAL_WAKE_LOCK` for the job (12 h timeout, released in `finally`). This needs the normal
  permission `WAKE_LOCK` (granted at install, no prompt, no data access). Phase 1 did not need it
  because audio capture keeps the device awake.
- **Thread priority**: decode threads keep the default priority. `THREAD_PRIORITY_BACKGROUND`
  would move them into the system's background control group with a small CPU share, which would
  wreck the real-time factor; recording is protected instead by stopping transcription when a
  recording starts (FR-017) and by the capture thread's audio priority. Thread count is the number
  of high-performance cores (cores whose maximum frequency is within 80% of the highest, read from
  `/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq` as the upstream Android example does),
  clamped to 2..6; the spike sweeps 2 to 8. **Verify on device** that nothing is throttled in the
  background cgroup (`/proc/<tid>/cgroup`).
- **Contingency**: the facades (`TranscriptionController`, `ModelController`) hide the mechanism. If
  the friction above proves worse than expected, a plain `Service` with `START_NOT_STICKY` (which by
  construction never restarts itself) can replace WorkManager without touching anything else;
  it would also drop `RECEIVE_BOOT_COMPLETED`, Room and the startup provider from the dependency list.
- **Alternatives considered**: `JobScheduler` user-initiated data transfer jobs (Android 14+:
  the platform's intended mechanism for user-started long transfers; rejected for now because it is
  not available on Android 11 to 13, our `minSdk` is 30, and WorkManager does not cover it);
  an expedited-only worker without a foreground service (rejected: too short a budget).

## R-10: Model download and import

- **Decision**: `HttpURLConnection`, GET with `Range`/`If-Range`, automatic redirects **off**, each
  hop checked against the host rule, SHA-256 computed while streaming, protocol in
  [contracts/model-catalog.md](contracts/model-catalog.md).
- **Why `HttpURLConnection`**: no dependency to vet (constitution II says every new dependency must
  be checked), no growth of `verification-metadata.xml`, and it does everything this one use
  needs: HTTPS, byte ranges, per-hop redirect handling. OkHttp would add HTTP/2 and nicer APIs at
  the price of two libraries (OkHttp and Okio, both Apache-2.0) in the one process that may touch
  the network. The single use site is small enough to review completely.
- **Hugging Face specifics** *(checked)*: the file URL answers `302` to a signed, expiring URL on
  `*.cdn.hf.co` and returns `x-repo-commit`, `x-linked-size` and `x-linked-etag` (= SHA-256). The
  signed URL expires, therefore every (re)start begins at the pinned URL.
  **Verify on device (S11)**: `Range` resume through the redirect, and the behavior when the signed
  URL expires mid-download.
- **Import**: `ACTION_OPEN_DOCUMENT`; the header is validated before any bytes are copied (magic and
  hyper-parameters; the voice-activity model shares the magic, so the values matter); copy with
  hashing; recognised as a catalog entry when the hash matches. Details in the contract.
- **Not done**: metered-network detection (it would need `ACCESS_NETWORK_STATE`; the spec decided
  that the confirmation shows the size and the user decides).

## R-11: Network guard and permissions

- **Manifest after Phase 2** (the allow-list of `verifyManifestPermissions`):

  | Permission | Why | New |
  |------------|-----|-----|
  | `RECORD_AUDIO`, `CAMERA`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | Phase 1 | no |
  | `INTERNET` | the explicit model download, and nothing else (constitution I and VI) | yes |
  | `WAKE_LOCK` | keep the CPU running while a transcription or download runs with the screen off (FR-004, FR-015) | yes |
  | `FOREGROUND_SERVICE_DATA_SYNC` | foreground service type for the download, and for transcription before Android 15 | yes |
  | `FOREGROUND_SERVICE_MEDIA_PROCESSING` | foreground service type for transcription on Android 15+ | yes |
  | `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (app-scoped, from androidx.core) | existing | no |
  | `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED` | merged in by WorkManager, **removed** | no |

  `WAKE_LOCK` and the two foreground-service permissions are install-time ("normal") permissions:
  no prompt, no access to data. Only `INTERNET` matters for trust, and it is contained below. The
  spec's FR-036 was corrected to list them.
- **Containment of `INTERNET`**, three layers that must all hold (details in the contract):
  1. only `app.meetingrecorder.models.download` may use network classes; a source-scanning unit test
     enforces it;
  2. `network_security_config.xml` (cleartext off; system trust only for `huggingface.co` and
     `hf.co`, no trust anchors for any other host) plus `usesCleartextTraffic="false"`;
  3. the build: `verifyManifestPermissions` allows `INTERNET` only when the merged manifest
     references that configuration and the configuration has exactly those domains.
  A runtime check in the instrumented tests compares the app's `TrafficStats` before and after a
  transcription (SC-001). **Verify on device** that a connection to another host fails with the
  config in place (the behavior of a `base-config` without trust anchors is documented but is
  tested rather than assumed).
- **New Gradle gates** (in `gradle/verification-tasks.gradle.kts`, run by `check`):
  `verifyManifestPermissions` (new allow-list and the network rule), `verifyNoPrebuiltBinaries` (R-16),
  `verifyWhisperPin` (R-1).

## R-12: Durability on SAF: commit protocol, atomic replace, `meeting.json`

- **Constraints of the storage layer** *(read from `SafMeetingStorage`)*: files are replaced by a
  truncating write (`"wt"`), which is not atomic; a rename onto an existing name is refused by the
  common provider, so replacement is delete-then-rename; appends use a file descriptor like Phase 1's
  WAV writer.
- **Decision**: the commit protocol and work-file format of
  [contracts/transcript-folder.md](contracts/transcript-folder.md). The key property: the work file is
  the commit record and is deleted last, so every crash point either keeps the old transcript or
  can be repaired forward by repeating the commit.
- **Provider capability**: the folder probe additionally reads each test file's `COLUMN_FLAGS`;
  when the provider lacks `FLAG_SUPPORTS_RENAME` or `FLAG_SUPPORTS_DELETE` the replacement strategy
  falls back from staged to direct (truncating) writes. This is still safe for transcripts because a
  torn final file is rewritten by the repeated commit while the work file exists. (Not an
  alternative for the audio: transcription never writes it.)
- **`meeting.json` hardening**: Phase 2 raises the number of writes to `meeting.json` (start, end,
  failure), and a damaged `meeting.json` would cost the photo positions, which exist nowhere else.
  So all writes to it, including Phase 1's rename and recovery, go through
  `MeetingMetadataStore.update(folder) { … }`: one read-modify-write under a per-meeting in-process
  mutex (re-reading inside the lock, so a rename during a transcription cannot undo the job's
  update) and a staged replacement. The recording process writes only the meeting it is recording
  and never a meeting that can be transcribed (FR-018), so no cross-process lock is needed.
- **Append cost**: one small append per window (about every 2 minutes of audio) is negligible even on slow
  providers; **verify** on the real storage folder (S17).
- **Disk full** while writing: surfaces as `storage-full`; the previous files are untouched; with the
  work file unwritable the job stops and keeps what it has.

## R-13: Phase 1 compatibility and the status shown in the list

- **`MeetingMetadata`** gains `transcription: MeetingTranscription? = null`; `MeetingJson` is set to
  `explicitNulls = false` so Phase 1 files (with no such key) re-encode byte-identically. A golden
  regression test covers the two existing golden files; a new golden has a `transcription` block.
  `schemaVersion` stays 1: the block is optional and the schema already allows extra properties.
- **Older app versions** ignore the block and drop it when they rewrite `meeting.json` (rename
  uses `ignoreUnknownKeys`); the transcript files stay. Because the resolved status is derived from the
  folder (table in the contract), such a folder still shows "done".
- **`MeetingRepository.rename`** already regenerates `CLAUDE.md`; it now also regenerates
  `transcript.md` from `transcript.json` so the title in the Markdown does not go stale.
- **The list** (`MeetingEntry`) gets a `TranscriptionStatus`. It is computed from the names
  already returned by the folder listing (`transcript.json`, `transcript.work.jsonl`) plus the
  `transcription` block of `meeting.json` that is read anyway, so the list costs no extra I/O.
  Validity of `transcript.json` (decodability) is checked when a meeting is opened.
- **Guide**: `ClaudeGuide` gets the transcript section; its two golden files are updated and two new
  ones added (with and without transcript).

## R-14: UI

- **Settings** gains a "Transcription" section: *Models* (list, details, download, import,
  delete), *Default language* (Automatic, German, English), *Default model*. Strings follow the
  Phase 1 pattern, German in the same change.
- **Start dialog** (from the list's overflow menu and from the detail screen): model choice (installed
  models with size), language choice (preselected per data-model), inline notices (language not
  suited to the model, little memory for this model, "this replaces the existing transcript" with a
  confirm for re-transcription, FR-033). No model installed: the dialog is replaced by a message
  with a button to the model settings (FR-013).
- **Detail screen** (the Phase 1 playback screen): a transcript section under the player: status
  line with the applicable action ("Transcribe", "Transcribe again", "Resume", "Start over",
  "Cancel", "Dismiss"), the progress while a job runs for this meeting, and a `LazyColumn` built from
  `TranscriptTimeline.interleave` (segment rows: time and text; photo rows: inline thumbnail from the
  existing `PhotoLoader`, tap opens it as in Phase 1). Items have stable keys. Tapping a segment
  calls the existing player's seek and play with `startMs` (it is already the one conversion).
  Rows are announced to TalkBack as a button with the time and the text. Not included (out of scope
  or not asked): following the playing segment, editing text, search.
- **List**: a status chip per meeting (none shows nothing, with the start action in the overflow menu),
  the percentage while running, "failed" with retry/resume.
- **Large transcripts**: `transcript.json` for 3 h is a few hundred KB; it is decoded off the main
  thread; the list virtualizes; photos are decoded lazily (SC-009).

## R-15: Progress and remaining time

Specified in [contracts/transcription-service.md](contracts/transcription-service.md): anchors from
segment and window ends, interpolation at the measured rate, never decreasing, bounded lead, moving
average of the real-time factor, one estimator feeding the notification and the UI. It is a pure class
with a table-driven unit test (steady rate, a stall, a slowdown, first window, resume with elapsed
time carried in the work file). SC-003 (±25% from 10%) is measured in S14.

## R-16: F-Droid and binaries

- **Finding** *(checked)*: the whisper.cpp v1.9.5 tree contains prebuilt binaries that F-Droid's
  scanner would flag: `models/for-tests-*.bin` (a dozen test models), `samples/jfk.wav`, and three
  `gradle-wrapper.jar` files under `examples/` and `bindings/`. None is used by our build.
- **Decision**: the F-Droid build recipe removes `examples`, `bindings`, `models`, `samples`,
  `tests` and `ci` of the submodule before building (`rm:`), and the project enforces the same
  locally: `verifyNoPrebuiltBinaries` scans the submodule for `.so .a .jar .aar .dll .exe .dylib
  .class .apk .bin .onnx .mlmodelc` outside an explicit list of directories that mirrors the `rm:`
  list, and fails otherwise; `CMakeLists.txt` never adds those directories (tests, examples and
  server are switched off). A new upstream version that adds a binary elsewhere fails the check
  before it reaches F-Droid.
- **New dependencies**: `androidx.work:work-runtime:2.12.0` and its transitive libraries (R-9), all
  from Google Maven; `gradle/verification-metadata.xml` is regenerated and the diff reviewed.
  No `-ktx` artifact is needed in this version **(check when adding)**. `verifyNoForbiddenDependencies`
  passes unchanged (`com.google.guava:listenablefuture` is the empty stub).
- **Native toolchain**: NDK and CMake come from the SDK like the rest of Phase 1's toolchain; the
  F-Droid recipe names the NDK version (`ndk:`). The submodule is fetched by git at the pinned commit
  (`submodules: true`).
- **Licenses**: whisper.cpp and ggml MIT; Whisper weights as converted by `ggml` MIT; Silero VAD MIT.
  Each is shown with its model in the app; the app's own license file gains the third-party notices.
- **To review when the F-Droid metadata is written**: how F-Droid's policy treats an app that downloads
  model files from a third-party host (the files are free assets; no non-free network service is
  promoted). Not decided here.

## R-17: The spike (first task)

- **Purpose**: decide the default model and the curated list, measure memory, confirm the engine
  build and thread settings, and decide VAD, carried prompt and downmix gain, **before** the rest of
  the work depends on the answers. It can also falsify SC-003, in which case the spec's number
  changes before any UI exists.
- **Method**: build `whisper-cli` (a development tool, never shipped, built from the submodule with
  the app's CMake switches) for arm64 with the same ggml variant mechanism, push it with the models to
  `/data/local/tmp`, and run each case through `adb shell`. The script is `scripts/spike/run-spike.sh`
  (versioned; its output is a CSV). Running outside the app measures the engine; the **second pass**
  (S-B) repeats the headline numbers through the real worker once US2 exists, to include the pipeline
  and the foreground service. Reference device: the Gigaset E940 (Android 14); the x86_64 emulator
  is useless for speed.
- **Test audio**: a 10-minute German recording. Not committed anywhere (`spike/audio/` is in
  `.gitignore`): either a recording of the user's own meeting (stays local; it may contain
  confidential content) or a public-domain German reading (for example from LibriVox, which also
  gives a reference text for accuracy). A 3-minute excerpt with a known reference text is used for the
  accuracy column.
- **Cases** (each reports wall time, real-time factor = processing time ÷ audio time, peak memory
  (PSS), and, for the 10-minute run, the real-time factor per minute to expose thermal throttling):

  | ID | Question | Runs |
  |----|----------|------|
  | S-1 | Speed per model and per quantization; best thread count | every candidate at its best thread count; `small-q5_1` at 2, 3, 4, 6, 8 threads; flash attention on/off |
  | S-2 | Does VAD pay off? | the two best candidates with VAD off/on; count of segments in known silent parts; start-time differences |
  | S-3 | Does a gain stage help? | the two best candidates with and without peak normalisation on a quiet recording |
  | S-4 | Does a carried prompt help? | windows of 120 s with and without the previous text as prompt; repetition count |
  | S-5 | Is language detection right? | German, English, silence-first recording |
  | S-6 | Accuracy | word error rate on the 3-minute reference excerpt per model |
  | S-7 | Optimization level | the debug APK's native code against the release one (R-2) |

- **Decision rules**: default = largest model with sustained real-time factor ≤ 0.7 (headroom for heat
  and for estimate error); catalog = candidates ≤ 1.0 with an acceptable word error rate; adopt VAD by the
  criteria in R-7; adopt the prompt only if it reduces errors without adding repeats. **If even the
  fastest multilingual candidate exceeds 1.0**, stop and decide with the user: relax SC-003,
  offer `tiny`, or accept "slower than real time" with an honest estimate.
- **Deliverable**: `spike-results.md` in this directory (table, device state, build id), and the
  catalog entries, descriptions and `approxMemoryMb` that follow from it.
- **Conditions**: phone charging or at least 80% battery, screen off during the long runs, room
  temperature, nothing else running; the user has to unlock the phone and enable wireless debugging
  (see the project notes). The spike writes nothing into any meeting folder.

## R-18: Tests

- **`:core` (JVM, full coverage of format logic, constitution VII)**: transcript model, JSON codec,
  validator, Markdown generator (golden files, including the photo-placement table), interleave,
  work-file codec (torn tail at every byte offset of a sample file), status resolver (every row of
  the table), progress estimator, window planner and pipeline with fake decoders, resampler and
  reader, catalog invariants, model header parser (real headers of the candidates are 48 bytes
  captured into test resources, plus malformed ones), `MeetingJson` round trip with and without the
  block, `ClaudeGuide` goldens, schema validation of the examples and generated files against
  `transcript.schema.json` and the merged `meeting.schema.json` with the same test-only validator as
  Phase 1.
- **`:app` (JVM)**: the network-source scanner, the hard-coded-string test (extended to new files),
  the stop-reason and failure-reason mapping, start-dialog preselection, status badge mapping,
  `TranscriptionRecovery` against an in-memory storage fake.
- **Instrumented** (device or emulator, run with `adb shell am instrument` as in Phase 1, never
  `connectedDebugAndroidTest` on the user's phone): `WhisperContextTest`, `ModelInstallTest` (against a
  local test server that is `127.0.0.1` only in the debug build, which the network config permits
  only there), `TranscriptionWorkerTest` (tiny model, short generated meeting: result files, `audio.wav`
  hash unchanged, no network bytes, cancel latency, kill and resume), `RecordingPriorityTest`.
- **Debug-only test server**: the debug source set replaces `network_security_config.xml` with a
  version that additionally permits cleartext to `127.0.0.1`, and adds a loopback `HttpServer`
  helper; neither exists in release builds, which the network rule of `verifyManifestPermissions`
  confirms on the merged release manifest.

## R-19: Delivery order

Each step ends with an installable app (constitution VIII); later steps are additive.

1. **Spike S-1 to S-7** on the CLI build (needs NDK, CMake, the phone). Result: `spike-results.md`.
2. **Foundation**: build gates (permissions, network rule, binaries, pin), submodule, `:whisper`
   builds and loads on the phone and the emulator, `WhisperContextTest` green. In parallel
   (no device needed): all pure `:core` work.
3. **US1** models: catalog, download, import, delete, settings UI.
4. **US2** transcription: pipeline, worker, notifications, commit, guide, start dialog.
5. **US5** list status (small, and needed to see US2 working across meetings).
6. **US3** transcript view with tap-to-seek and photos.
7. **US4** cancel, kill, resume, recovery, recording priority.
8. **US6** re-transcription.
9. **Polish**: German strings and review, accessibility pass, README and F-Droid texts, spike S-B
   second pass, scenarios S11 to S24 of the quickstart, Phase 1 regression (S1 to S10).

## R-20: Risks and open items

| # | Risk | Mitigation / what happens |
|---|------|---------------------------|
| 1 | Real-time factor on the E940 is worse than 1.0 even for the smallest useful model (SC-003) | Spike first; escalation path in R-17 |
| 2 | Memory: medium and turbo need roughly 1 to 2 GB | `approxMemoryMb`, pre-start warning, `out-of-memory` failure reason, resume after a low-memory kill |
| 3 | WorkManager restarts or delays jobs against the spec | Launch-token guard, expedited start, contingency `Service` (R-9) |
| 4 | Android 15 foreground-service time budget (6 h) | Resume path. **Verified on the API 35 emulator (2026-11-18):** `device_config put activity_manager media_processing_fgs_timeout_duration 20000` triggers the real timeout; `TranscriptionWorkerTest.aForegroundServiceTimeoutInterruptsTheJobAndKeepsItsWorkFile` shows the job ends `failed / interrupted / resumable` with its work file kept, and that a start right afterwards runs. The test found that WorkManager reschedules a stopped job, which made `KEEP` drop the user's next start; both controllers now use `REPLACE`. The `dataSync` timeout and Android 16 are not exercised. |
| 5 | SAF providers differ (no rename, slow appends) | Capability probe and direct-write fallback (R-12) |
| 6 | A native crash kills the app process | Work file and recovery; recording is in another process and unaffected |
| 7 | Hallucination and repeated text on silence/noise | VAD decision (R-7), `suppress_nst`, repetition guard, spike S-2 |
| 8 | Reproducible build with native code (Phase 1's T105 is still open) | Pins, path maps, check repeated in the polish phase |
| 9 | Hugging Face changes a URL or a CDN host | URLs pinned to a commit and hash-verified; import is the always-working path; host allow-list covers `hf.co` subdomains |
| 10 | Very long jobs drain the battery or throttle the phone | Estimate shown; wake lock bounded; hint to keep the phone charging is a cheap follow-up (not in the spec) |
| 11 | Android 16+ changes foreground-service rules again | Types are chosen in one function; the `Service` contingency |
| 12 | `network_security_config` behavior for "no trust anchors" is assumed from documentation | Tested on a device (R-11) before it is relied on |
