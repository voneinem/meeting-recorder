# Spike results (research R-17, tasks T008 to T016)

Measured on the project's reference phone on 2026-11-17 and 2026-11-18. The raw data (`spike/out/results.csv`,
`spike/out/texts/`, `spike/out/device.txt`), the test audio and the models are git-ignored and stay local. The
numbers below are copied from `results.csv`.

**Status of the decisions:** the measurements are complete and the project owner decided at T017 on 2026-11-18:
default `small-q8_0`, catalog of four models, **no VAD**, no carried prompt, no gain stage. The last section shows the
spike's recommendation next to the outcome.

## Read this first: what is and is not proven

- The audio is **read speech** (FLEURS German, see below), not a real meeting. Accuracy on meetings with
  several speakers, overlap and noise will be worse than the numbers here. The ranking of the models is the
  useful result, the absolute word error rates are not.
- The test text has 319 reference words. One word is 0.3 points of error rate and every figure is a single run,
  so differences of about 3 points or less are noise. The accuracy columns are read with that in mind.
- Real-time factors vary from run to run: the same configuration (small-q8_0, 2 threads, flash attention on)
  measured 0.478, 0.515 and 0.557 on three runs.
- **A mistake that invalidated the first runs:** the first complete set of runs used `whisper-cli -nt`
  (no-timestamps decoding). The app always decodes with timestamps, so every number from `-nt` was
  discarded: they are archived (`results-run1-nt-INVALID.csv`) and S-1, S-2, S-3, S-4, S-6 and the thermal case
  were repeated in the app's real mode (timestamps on, default context, `-sns`). The only figures taken from
  the `-nt` run are the S-5 language probabilities, because language detection happens before decoding and
  `-nt` does not change it. `run-spike.sh` now matches the app. The JNI bridge was not at fault: through the app's
  engine path the 3-minute German test keeps 316 of 319 words.

## Device state (T009)

| Item | Value |
|------|-------|
| Phone | Gigaset E940-2849-01, Android 14 (SDK 34) |
| SoC | MediaTek MT6877V/ZA (Dimensity 900), 8 cores: 6 at 2.0 GHz, **2 at 2.4 GHz** (`cpu6`, `cpu7`) |
| Memory | `MemTotal` 7 806 396 kB |
| CPU features | `asimdhp`, `asimddp` (dot product), `fphp`; no `i8mm`, no SVE |
| Power | **USB-powered** the whole time; battery 54% at the start, 17% at the end (the plan asked for 80% or more, the owner chose to run on USB power at the lower level) |
| Temperature | battery sensor 27.5 °C at the start, 36.1 °C at the end; no cooling, screen kept on, `screen_off_timeout` restored to 60000 afterwards |
| Connection | USB (not wireless debugging) |

## Build id (T016)

| Item | Value |
|------|-------|
| Engine | whisper.cpp **v1.9.5**, commit `d1be6fde11ac6e0407606b4e42fe72d34add8037` (the submodule, pinned in `gradle/whisper.pin`) |
| Toolchain | NDK 29.0.14206865, CMake 3.31.6, Ninja, Android platform 30, arm64-v8a, `CMAKE_BUILD_TYPE=Release` |
| Switches | `BUILD_SHARED_LIBS=ON`, `GGML_BACKEND_DL=ON`, `GGML_CPU_ALL_VARIANTS=ON`, `GGML_NATIVE=OFF`, `GGML_OPENMP=OFF`, `GGML_CCACHE=OFF`, examples on, tests and server off (`scripts/spike/build-cli.sh`) |
| ggml CPU variant selected on the phone | **`ggml-cpu-android_armv8.2_2`** (named in every run's log and in the `variant` column) |
| Decode settings | 2 threads unless stated, flash attention on, timestamps on, default context, `-sns` (suppress non-speech tokens), `-l de` (`-l auto` for S-5), greedy decoding as in the app |
| Tool | `whisper-cli` and `whisper-bench` (development tools, never shipped) run through `adb shell` from `/data/local/tmp/spike` |

## Test audio (T008)

The owner delegated the choice of audio and chose public read speech. It was built with
`scripts/spike/make-fleurs-audio.py` from the **FLEURS German dev split** (google/fleurs, CC-BY 4.0): one recording per
sentence id, joined with 0.6 s of silence, converted to 16 kHz mono 16 bit.

| File | Content |
|------|---------|
| `de-10min.wav` | 601.86 s, the long sequence (reference `de-10min.ref.txt`) |
| `de-3min.wav` | 186.24 s, the start of the same sequence (reference `de-3min.ref.txt`, 319 words) |
| `silence-first.wav` | 30 s of digital silence, then `de-3min.wav` (216.24 s) |
| `de-3min-quiet.wav` | `de-3min.wav` at -20 dB (`-af volume=-20dB`) |
| `de-3min-quiet-norm.wav` | the quiet file with `-af dynaudnorm=g=3` (the filter named in the spike README) |
| `en-sample.wav` | `jfk.wav` of the engine's samples (11.0 s) |

## S-1: speed per model, thread count, flash attention (T010)

Real-time factor (RTF) = processing time / audio time on `de-10min.wav` (601.86 s). Peak memory is the process's peak
resident set.

| Model | Threads | Flash attention | RTF | Peak memory |
|-------|--------:|-----------------|----:|------------:|
| tiny-q5_1 | 2 | on | **0.166** | 274 MB |
| base-q5_1 | 2 | on | 0.314 | 341 MB |
| base-q8_0 | 2 | on | 0.181 | 361 MB |
| small-q5_1 | 2 | on | **1.055** | 607 MB |
| small-q8_0 | 2 | on | **0.478 / 0.515 / 0.557** (three runs) | 678 MB |
| large-v3-turbo-q8_0 | 2 | on | **1.844** | 1236 MB |
| medium-q5_0, large-v3-turbo-q5_0 | 2 | on | 2.787 and 4.257 (3-minute run, see S-6) | 1224 MB, 904 MB |

Thread sweep (`small-q8_0`, flash attention on):

| Threads | 2 | 3 | 4 | 6 | 8 |
|---------|--:|--:|--:|--:|--:|
| RTF | **0.557** | 0.722 | 0.645 | 0.741 | 0.806 |

Flash attention (`small-q8_0`, 2 threads): **on** 0.478 to 0.557 and 678 MB, **off** 0.618 and 814 MB. On is about
15% faster and uses 136 MB less (one run for "off").

Findings:

- **Two threads are best**, which matches the two 2.4 GHz cores. More threads are slower, because the six slower cores
  hold the fast ones back.
- **q8_0 is faster than q5 on this CPU** (base: 0.181 against 0.314), as research R-8 expected (q8_0 maps onto the
  dot-product instructions). It is not smaller on disk.
- `small-q5_1` is at 1.055: slower than real time, so it fails the 1.0 limit of the catalog rule.
- `large-v3-turbo` and `medium` are far too slow for this phone.

## S-2: does VAD pay off? (T011)

Silero VAD (`ggml-silero-v6.2.0.bin`, 885 098 bytes) with `--vad`, the two best candidates of S-1 that are accurate
enough to matter (`small-q8_0`, `base-q5_1`), 2 threads.

| Model | Audio | RTF no VAD | RTF with VAD | Change |
|-------|-------|-----------:|-------------:|-------:|
| small-q8_0 | de-10min | 0.549 | 0.482 | -12% |
| small-q8_0 | silence-first (216 s) | 0.636 | 0.383 | -40% |
| base-q5_1 | de-10min | 0.356 | 0.264 | -26% |
| base-q5_1 | silence-first (216 s) | 0.463 | 0.232 | -50% |

| Model | Segments (de-3min) no VAD | with VAD | Word error rate no VAD | with VAD |
|-------|--------------------------:|---------:|-----------------------:|---------:|
| small-q8_0 | 17 | 30 | 10.00% | 11.25% |
| base-q5_1 | 17 | 29 | 15.00% | 19.06% |

The three criteria of research R-7:

- **(a) RTF not worse by more than 5%: pass.** VAD is 12% to 26% faster on speech and up to 50% faster with silence.
- **(b) segment starts within 0.5 s of the no-VAD run on 95% of segments: fails as written.** 35% of the no-VAD
  starts have a VAD start within 0.5 s (20% the other way round). The number measures granularity, not accuracy: without
  VAD the engine returns 17 long segments, with VAD 29 to 30 shorter ones that begin inside the same sentences. The
  criterion compares two segmentations that are never going to line up. A redefinition
  such as "a VAD segment starts inside a segment of the no-VAD run (0.5 s tolerance)" was measured afterwards: it
  holds for **24 of 30** (small-q8_0, 80%) and **22 of 29** (base-q5_1, 76%) VAD segments. The rest start in a pause or
  at an edge of a no-VAD segment, so the redefined criterion is not clearly met either. Whether to relax (b) is the
  owner's decision.
- **(c) fewer hallucinated segments in the silent parts: pass.** In the first 30 s of silence the run without VAD
  produced **15 segments (small-q8_0) and 16 (base-q5_1)** of invented text, for small-q8_0 mostly the same sentence
  ("Das ist ein ganz besonderes Thema."); with VAD there are **0**.

Cost: VAD raised the word error rate on this read speech by 1.3 points (small, 4 words) and 4.1 points (base,
13 words). Single run, so partly noise, but the direction is not in VAD's favour. The silent stretches of
`de-10min.wav` were not measured separately (see "Deviations").

## S-3: does a gain stage help? (T012)

`de-3min-quiet.wav` (-20 dB), word error rate against the reference. The gain stage is `dynaudnorm=g=3`, applied on the host.

| Model | Quiet, unprocessed | Quiet, normalized | Original level (S-6) |
|-------|-------------------:|------------------:|---------------------:|
| small-q8_0 | 10.00% | 9.69% | 10.00% |
| base-q5_1 | 15.94% | 20.94% | 15.00% |

The engine decodes -20 dB audio as well as the original, and normalization does not help (it hurt `base-q5_1` by 5
points). **Decision: no gain stage.**

## S-4: does a carried prompt help? (T013)

`de-10min.wav` as six consecutive 120 s excerpts plus a 1.9 s tail, with and without the last 200 characters of the
previous excerpt's text as `--prompt`. Word error rate of the concatenated text against `de-10min.ref.txt`.

| Model | Without prompt | With prompt | Repeated sentences (both) |
|-------|---------------:|------------:|--------------------------:|
| small-q8_0 | 13.59% | 18.34% | 0 |
| base-q5_1 | 19.63% | 17.15% | 0 |

Mixed result: worse for `small-q8_0`, better for `base-q5_1`, no repeats either way. The rule says to adopt the
prompt only if it reduces errors, and it does not do so consistently. **Decision: no carried prompt.**
**Limit of this result:** the test sentences are unrelated to each other, so there is no context to carry. A prompt
may help on a real meeting that keeps one topic. A rerun on the owner's own meeting audio is the proper test; it
would not block anything, because the pipeline keeps the prompt as an option and the default is off.

## S-5: is language detection right? (T014)

`-l auto`, 2 threads. Detected language and its probability. These figures come from the archived `-nt` run (see
above); detection runs before decoding and does not depend on it.

| Model | de-3min | en-sample | silence-first (30 s silence, then speech) |
|-------|---------|-----------|-------------------------------------------|
| tiny-q5_1 | de 0.987 | en 0.982 | **en 0.575** |
| base-q5_1 | de 0.990 | en 0.948 | **en 0.409** |
| base-q8_0 | de 0.989 | en 0.959 | **en 0.374** |
| small-q5_1 | de 0.995 | en 0.963 | **en 0.376** |
| small-q8_0 | de 0.992 | en 0.954 | **en 0.364** |

German and English are detected with 0.95 or more. The CLI detects on the first 30 s only, and on silence every
model answers `en` with 0.36 to 0.57, which is wrong for this German file. The app avoids this: `LanguageProbe`
samples three 30 s windows at 10%, 40% and 70% of the recording and replaces windows below -60 dBFS RMS, so a
leading silence is never the only evidence. This is a reason to keep the three-window probe, and to show the
detected language with its probability.

## S-6: accuracy (T015)

Word error rate on `de-3min.wav` against `de-3min.ref.txt` (319 words), 2 threads, flash attention on.

| Model | WER | RTF (186 s) | Peak memory |
|-------|----:|------------:|------------:|
| tiny-q5_1 | 25.62% | 0.179 | 235 MB |
| base-q5_1 | 15.00% | 0.345 | 302 MB |
| base-q8_0 | 20.31% | 0.185 | 323 MB |
| small-q5_1 | 18.44% | 1.009 | 569 MB |
| **small-q8_0** | **10.00%** | 0.501 | 640 MB |
| medium-q5_0 | 4.69% | 2.787 | 1224 MB |
| large-v3-turbo-q5_0 | 4.38% | 4.257 | 904 MB |
| large-v3-turbo-q8_0 | 9.69% | 1.585 | 1190 MB |

`small-q8_0` is the most accurate model that is fast enough. `base-q8_0` is less accurate than `base-q5_1` here
(5 points, which is 16 words: more than noise). The three most accurate models run at 1.6 to 4.3 times real time and
cannot be offered.

## Thermal behaviour: per-minute real-time factor

Ten consecutive one-minute clips of `de-10min.wav`, each its own `whisper-cli` run (so each includes model loading),
no cooling, USB powered, 2 threads. A 1.9 s tail clip is not shown (its RTF is dominated by loading).

| Minute | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | Mean |
|--------|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|-----:|
| small-q8_0 | 0.476 | 0.549 | 0.599 | **0.877** | 0.689 | 0.598 | 0.532 | 0.571 | 0.519 | 0.688 | **0.61** |
| base-q5_1 | 0.323 | 0.352 | 0.353 | 0.396 | 0.415 | 0.353 | 0.337 | 0.334 | 0.365 | 0.329 | 0.36 |

No upward trend, i.e. no thermal throttling in ten minutes. `small-q8_0` has single minutes up to 0.88, so its
sustained factor is about 0.6 with spikes close to 0.9. That is why the default rule is 0.7 and not 1.0. Over the whole
spike the battery sensor rose from 27.5 °C to 36.1 °C. Ten minutes says nothing about a 2 hour meeting on a warm
phone; the long device scenarios of tasks.md cover that.

## S-7: debug against release native code (T032)

Measured on the phone on 2026-11-18 through the engine's own API (`WhisperContextTest.logsTheDecodeTimeOfTheSample`):
`jfk.wav` (11 s) with `tiny-q5_1`, 2 threads, flash attention on, seven decodes, median of the six after the first.
The instrumented test package was built once against the debug and once against the release variant of `:whisper`.

| Build | Decode times of the run (ms) | Median |
|-------|------------------------------|-------:|
| debug, run 1 | 1601, 1520, 1523, 1523, 1519, 1522, 1522 | 1522 ms |
| debug, earlier run | 1548, 1519, 1519, 1517, 1520, 1520, 1514 | 1519 ms |
| release, run 1 | 1545, 1483, 1568, 1517, 1512, 1517, 1515 | 1517 ms |
| release, run 2 | 1539, 1508, 1519, 1514, 1514, 1496, 1483 | 1514 ms |

The difference is below 0.6%, far inside the 5% limit. The cause is visible in the build: the native library is built with
`CMAKE_BUILD_TYPE=Release` in both variants and the `libggml-cpu-android_armv8.2_2.so` of the two test packages is
**byte-identical** (same SHA-256 `ba9146c5...0d4e`). The real-time factor is 0.14 (1.5 s for 11 s), faster than the spike's
10-minute runs of the same model because of the short, clean clip.

CPU variant, read from `/proc/self/maps` inside the instrumented test on the phone: **`libggml-cpu-android_armv8.2_2.so`**,
the same variant the spike's command-line tool chose. The engine does not log the choice (it drops info and debug messages
on purpose). The tests also pass on the x86_64 emulator (T032 asks for both).

## Second pass "S-B": the real worker on the phone (T136, scenario S14)

`TranscriptionSpeedTest` (instrumented, run with `adb shell am instrument -e allowDevice true`) runs the 10-minute German test
audio (601.9 s, converted to 48 kHz stereo like a recording) through the real stack: `TranscriptionController`, WorkManager,
`TranscriptionWorker`, foreground service, engine, commit. Default model `small-q8_0`, language `de`, 2 threads (the
`ThreadPolicy` result on this phone), flash attention on, VAD off, screen kept on, USB power, battery 56%. Two runs:

| Run | Total time | Real-time factor | Segments | Spike S-1 (CLI, same model) |
|-----|-----------:|-----------------:|---------:|----------------------------:|
| 1 | 217.2 s | **0.361** | 62 | 0.48 to 0.56 |
| 2 | 210.6 s | **0.350** | 62 | 0.48 to 0.56 |

The whole run, with pipeline, window planning, checkpoint writes, foreground service and commit, is about 30% **faster** than the
command-line measurements of the spike. Plausible causes (not separated): the app's fixed 2 threads and window handling
against the CLI's own batching, and a cooler phone. SC-003 holds with a wide margin.

Progress, measured every 250 ms:

| Check | Result |
|-------|--------|
| Percentage in the notification against the app (3793 to 4016 readings) | worst disagreement **0.00 points** (limit 2) |
| Remaining-time estimate against the real remaining time | run 1: 12.9% at 10%, 16.6% at 30%, 0.5% at 60%, 6.3% at 90%; run 2: **9.0%, 7.8%, 3.6%, 10.4%** (limit 25%) |
| Percentage moves at least once per second (scenario S14 wording) | **No.** Longest standstill 40 s. Run 2 stalls longer than 5 s: at 0 s for 40 s, then 59 s+21 s, 100 s+25 s, 146 s+20 s, 187 s+17 s |

The standstills follow the windows: a window of 120 s of audio takes about 40 s to decode, the estimator anchors at window ends,
extrapolates between them at the measured rate with a cap on how far it may run ahead, and the first window has no rate yet, so the
percentage stays at 0% for the first 40 s and the remaining time shows nothing. The remaining time itself counts down every
second and the notification is refreshed every 2 s, so FR-016 ("refreshed at least every 5 seconds") holds for the refresh but
a user sees a percentage that stands still for up to 40 s. Options for the owner: accept it, raise the extrapolation cap to a
whole window (the rate is known after the first window), and/or decode the first window in smaller pieces so that an anchor
arrives after about 10 s. T136 stays open until this is decided.

## Deviations from the plan

1. **Audio:** FLEURS read speech instead of a recording of the owner's meeting or a LibriVox reading (the owner chose
   "public read speech"). Exact reference texts, but cleaner than a meeting.
2. **Thread sweep on `small-q8_0`** instead of `small-q5_1` (T010 asks for `small-q5_1`), because `small-q8_0` became the
   candidate for the default. `small-q5_1` ran at 2 threads only.
3. **S-1:** `medium-q5_0` and `large-v3-turbo-q5_0` were measured on the 3-minute file only (S-6): at 2.8 and 4.3 times
   real time they were not worth ten minutes each. `small-q8_0` was run three times at the best settings.
4. **S-2 silent stretches of `de-10min.wav`** (found with `ffmpeg -af silencedetect`) were not counted; only the 30 s
   of leading silence of `silence-first.wav`. The 0.6 s pauses of the test file are the only other silence in it. The
   repeat count that research R-5's rule would collapse was not computed either; for `small-q8_0` the hallucinated
   segments are one sentence repeated.
5. **S-2 criterion (b)** is reported as written and fails; the alternative definition was measured once on the
   3-minute file only (80% and 76%).
6. **S-5** figures are from the `-nt` run (explained above).
7. **S-1 per-minute RTF** was measured as ten separate one-minute clips (thermal case) instead of reading the per-minute
   times of one ten-minute run, so model loading is included in every minute.
8. **Power and battery:** USB power at 17% to 54% instead of 80% or more; wireless debugging replaced by USB.
9. **S-4 audio** is unrelated sentences, which is unfair to a carried prompt (see S-4).
10. The first complete set of runs was invalid and was repeated (see the start of this file).

## Decisions (research R-17)

| Question | Decision | Basis |
|----------|----------|-------|
| **Default model** | **`small-q8_0`** (264 MB). Largest candidate with sustained RTF at most 0.7: mean 0.61 in the thermal run, 0.48 to 0.56 in S-1. | S-1, thermal |
| **Catalog** (RTF at most 1.0, German accuracy worth offering) | `tiny-q5_1` (fastest, 0.17, WER 26%), `base-q5_1` (0.31, WER 15%), `base-q8_0` (0.18, WER 20%), `small-q8_0` (0.5, WER 10%, recommended). Not offered: `small-q5_1` (1.06, fails the limit), `medium-q5_0`, `large-v3-turbo-*` (1.6 to 4.3 times real time). Whether to offer both base models is open: `base-q8_0` is faster and larger, `base-q5_1` more accurate here; the owner may drop one. | S-1, S-6 |
| **SC-003 holds** | The fastest multilingual model (`tiny-q5_1`) runs at **0.17** times real time, far below 1.0. SC-003 is not falsified, and the default also meets it (0.5 to 0.6). No change to the spec. | S-1 |
| **VAD** | **Owner decision (T017): no, VAD ships off and is not in the catalog.** The spike's recommendation was: **yes, as a close call.** Criterion (b) fails as written (35%) and is only 76% to 80% under a looser definition. It makes decoding 12% to 26% faster and removes the invented text in silence (15 to 16 segments to 0), at a cost of 1.3 to 4.1 points of word error rate on this read speech. The alternative is no VAD plus the repetition guard and the silent-window skip, which keeps the accuracy but leaves hallucinated segments in silent parts and gives up the speed. If adopted: catalog entry `vad-silero-v6.2.0` (885 098 bytes), downloaded together with the first speech model. **The owner decides at T017.** | S-2 |
| **Carried prompt** | No. Default off; the option stays in the pipeline. Rerun on the owner's own meeting audio is the proper test. | S-4 |
| **Gain stage** | No. | S-3 |
| **Thread count rule** | 2 threads on this phone: the 2 cores near the maximum frequency. `ThreadPolicy` counts cores within 80% of the highest frequency; here 2.0/2.4 GHz is 83%, so it would count all 8 cores and pick 6 (RTF 0.74 against 0.56). The rule must be stricter (cores within about 95% of the highest frequency, minimum 2); this is a code change to be made after T017. | S-1 |
| **Flash attention** | On by default (`LoadOptions.flashAttention` is currently `false` and must become `true`): faster and 136 MB smaller. | S-1 |
| **`approxMemoryMb`** | Measured peak plus about 10% for the app's window buffers, rounded up to 50 MB: `tiny-q5_1` 300, `base-q5_1` 350, `base-q8_0` 400, `small-q8_0` 750. | S-1, S-6 |
