# Spike scripts (research R-17)

Development tools for the spike that decides the default model, the catalog, VAD, the carried prompt and the
gain stage, and that can falsify SC-003. **Nothing here is shipped, and nothing here runs in the build or the app.**
Recordings, models and results stay out of the repository (`spike/audio/`, `spike/models/`, `spike/out/` and
`build/spike-*/` are git-ignored).

| Script | What it does | Status |
|--------|--------------|--------|
| `build-cli.sh` | builds `whisper-cli` and `whisper-bench` for arm64 into `build/spike-arm64` | run on the host (2026-11-17), builds |
| `fetch-models.sh` | downloads the candidates into `spike/models/` and checks them against `models.sha256` | run for `ggml-tiny-q5_1.bin`, checksum matches |
| `wer.py` | word error rate; `python3 -I scripts/spike/wer.py REF HYP`, `--selftest` | self-test passes |
| `parse-run.py` | parses one `whisper-cli` run (real-time factor, language, CPU variant) | self-test passes |
| `run-spike.sh` | runs the cases S-1 to S-6 and the thermal run on the phone, appends to `spike/out/results.csv` | **not run yet: needs the phone** |

## Conditions (quickstart.md)

Charged or at least 80%, screen off, same room temperature, nothing else running, the same phone for all cases.

## Steps

1. Host: `scripts/spike/build-cli.sh` and `scripts/spike/fetch-models.sh`.
2. Audio under `spike/audio/` (task T008): `de-10min.wav`, `de-3min.wav` with `de-3min.ref.txt`, `en-sample.wav`,
   `silence-first.wav`, `de-3min-quiet.wav`. For the gain case also `de-3min-quiet-norm.wav`, for example
   `ffmpeg -i de-3min-quiet.wav -af dynaudnorm=g=3 de-3min-quiet-norm.wav` (state in the results which filter you used).
3. Phone (task T009): connect with `adb`, then
   ```bash
   dev=/data/local/tmp/spike
   adb shell mkdir -p $dev/models $dev/audio
   adb push build/spike-arm64/bin/whisper-cli build/spike-arm64/bin/lib*.so $dev/
   adb push spike/models/*.bin $dev/models/
   adb push spike/audio/*.wav $dev/audio/
   adb shell settings put system screen_off_timeout 1800000   # restore to 60000 afterwards
   ```
   Write the device state into `spike/out/device.txt` (`getprop ro.product.model`, `ro.build.version.release`, the SoC
   from `/proc/cpuinfo`, `cpuinfo_max_freq` of every core, `MemTotal`, battery level and state).
4. Run the cases: `scripts/spike/run-spike.sh s1` (then `s2` to `s6` and `thermal`). `BEST_THREADS` and `TOP2` (the
   two best models of S-1) are environment variables.
5. Write the results and the decisions into `specs/002-offline-transcription/spike-results.md` (task T016).

The real-time factor is `(sample + mel + encode + decode + batchd + prompt)` milliseconds from the engine's timings
block divided by the audio length, so loading the model is excluded. Peak memory is the highest `VmHWM` of the
process, polled once per second on the phone.
