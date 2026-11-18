#!/usr/bin/env bash
# Spike runner (never shipped): runs the cases S-1 to S-6 of research R-17 on the phone through `adb shell` and
# appends one CSV row per run to spike/out/results.csv (git-ignored; spike audio may be confidential).
#
# Usage: scripts/spike/run-spike.sh s1threads|s1models|s1|s2|s3|s4|s5|s6|thermal
# Needs: the phone prepared as in scripts/spike/README.md (everything under /data/local/tmp/spike), the host
# audio under spike/audio/ and python3. Environment: ADB (default adb), DEV (default /data/local/tmp/spike),
# BEST_THREADS (default 4), TOP2 (the two best models of S-1, default "ggml-small-q5_1.bin ggml-base-q5_1.bin").
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$here/../.."
adb="${ADB:-adb}"
dev="${DEV:-/data/local/tmp/spike}"
best_threads="${BEST_THREADS:-4}"
top2="${TOP2:-ggml-small-q5_1.bin ggml-base-q5_1.bin}"
audio_dir="$root/spike/audio"
out_dir="$root/spike/out"
csv="$out_dir/results.csv"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$out_dir"
[ -f "$csv" ] || echo "case,model,threads,flash_attn,vad,prompt,gain,audio_s,rtf,peak_rss_mb,variant,wer,detected_language,language_probability" > "$csv"

audio_seconds() { python3 -I -c 'import os,sys; print((os.path.getsize(sys.argv[1]) - 44) / 32000)' "$1"; }

# run_one CASE MODEL THREADS FLASH(on|off) VAD(yes|no) PROMPT_TEXT GAIN_LABEL AUDIO_FILE [REFERENCE_FILE] [LANGUAGE]
run_one() {
  local case_id="$1" model="$2" threads="$3" fa="$4" vad="$5" prompt="$6" gain="$7" audio="$8" ref="${9:-}" lang="${10:-de}"
  # Same decoder settings as the app (whisper_jni.cpp): timestamps ON (the CLI's `-nt` switches the engine to a
  # different decoding mode without timestamp tokens that the app never uses), the default text context, and
  # non-speech tokens suppressed. Segment timestamps are stripped before the text is scored.
  local flags="-t $threads -l $lang -sns"
  # EXTRA_FLAGS come last, so they override the defaults above (for example a larger `-mc` for the prompt case).
  flags="$flags ${EXTRA_FLAGS:-}"
  [ "$fa" = off ] && flags="$flags -nfa"
  [ "$vad" = yes ] && flags="$flags --vad --vad-model models/ggml-silero-v6.2.0.bin"
  [ -n "$prompt" ] && flags="$flags --prompt \"$(printf '%s' "$prompt" | tr -d '"\\$`')\""
  # On the phone: start the CLI, poll VmHWM of its process once per second, print the peak when it ends.
  "$adb" shell "cd $dev && LD_LIBRARY_PATH=$dev sh -c './whisper-cli -m models/$model -f audio/$audio $flags > out.txt 2> err.txt & pid=\$!; peak=0; while kill -0 \$pid 2>/dev/null; do v=\$(grep VmHWM /proc/\$pid/status 2>/dev/null | tr -s \" \" | cut -d\" \" -f2); [ -n \"\$v\" ] && [ \"\$v\" -gt \"\$peak\" ] && peak=\$v; sleep 1; done; wait \$pid; echo PEAK_KB=\$peak'" > "$work/peak.txt"
  "$adb" pull -q "$dev/out.txt" "$work/out.txt"
  "$adb" pull -q "$dev/err.txt" "$work/err.txt"
  sed -E 's/^\[[^]]*\] *//' "$work/out.txt" > "$work/plain.txt"
  local seconds parsed rtf language probability variant peak_mb wer=""
  seconds="$(audio_seconds "$audio_dir/$audio")"
  parsed="$(python3 -I "$here/parse-run.py" "$work/err.txt" "$seconds")"
  rtf="$(sed -n 's/.*rtf=\([^ ]*\).*/\1/p' <<<"$parsed")"
  language="$(sed -n 's/.*language=\([^ ]*\).*/\1/p' <<<"$parsed")"
  probability="$(sed -n 's/.*probability=\([^ ]*\).*/\1/p' <<<"$parsed")"
  variant="$(sed -n 's/.*variant=\([^ ]*\).*/\1/p' <<<"$parsed")"
  peak_mb="$(awk -F= '/PEAK_KB/ {printf "%d", $2/1024}' "$work/peak.txt")"
  [ -n "$ref" ] && wer="$(python3 -I "$here/wer.py" "$audio_dir/$ref" "$work/plain.txt" | awk '{print $2}')"
  mkdir -p "$out_dir/texts"
  cp "$work/out.txt" "$out_dir/texts/${case_id}__${model%.bin}__${audio%.wav}__t$threads-fa$fa-vad$vad-p$([ -n "$prompt" ] && echo y || echo n)-$gain.txt"
  echo "$case_id,$model,$threads,$fa,$vad,$([ -n "$prompt" ] && echo yes || echo no),$gain,$seconds,$rtf,$peak_mb,$variant,$wer,$language,$probability" >> "$csv"
  echo "$case_id $model t=$threads fa=$fa vad=$vad gain=$gain rtf=$rtf peak=${peak_mb}MB wer=${wer:-n/a} lang=${language:-?}"
}

all_models() { awk '$2 != "ggml-silero-v6.2.0.bin" {print $2}' "$here/models.sha256"; }

case "${1:-}" in
  s1threads) # SWEEP_MODEL (default small-q5_1) at 2 3 4 6 8 threads: decides BEST_THREADS for everything else
    for t in 2 3 4 6 8; do run_one S-1 "${SWEEP_MODEL:-ggml-small-q5_1.bin}" "$t" on no "" none de-10min.wav; done ;;
  s1models) # every candidate at BEST_THREADS, then flash attention on and off for small-q5_1
    for m in ${MODELS:-$(all_models)}; do run_one S-1 "$m" "$best_threads" on no "" none de-10min.wav; done
    for fa in on off; do run_one S-1 "${SWEEP_MODEL:-ggml-small-q5_1.bin}" "$best_threads" "$fa" no "" none de-10min.wav; done ;;
  s1) "$0" s1threads; "$0" s1models ;;
  s2) # does VAD pay off?
    for m in $top2; do
      for vad in no yes; do
        run_one S-2 "$m" "$best_threads" on "$vad" "" none de-10min.wav
        run_one S-2 "$m" "$best_threads" on "$vad" "" none silence-first.wav
      done
    done ;;
  s2text) # VAD criteria (b) and (c) of research R-7 need the text: word error rate and timestamped segments
    for m in $top2; do
      for vad in no yes; do
        run_one S-2wer "$m" "$best_threads" on "$vad" "" none de-3min.wav de-3min.ref.txt
        run_one S-2ts "$m" "$best_threads" on "$vad" "" none de-3min.wav
        run_one S-2ts "$m" "$best_threads" on "$vad" "" none silence-first.wav
      done
    done ;;
  s3) # does a gain stage help? Prepare de-3min-quiet-norm.wav on the host first (see the README)
    for m in $top2; do
      run_one S-3 "$m" "$best_threads" on no "" none de-3min-quiet.wav de-3min.ref.txt
      [ -f "$audio_dir/de-3min-quiet-norm.wav" ] && run_one S-3 "$m" "$best_threads" on no "" normalized de-3min-quiet-norm.wav de-3min.ref.txt
    done ;;
  s4) # does a carried prompt help? 120 s excerpts, with and without the last 200 characters of the previous text
    command -v ffmpeg >/dev/null || { echo "ffmpeg is required for s4" >&2; exit 2; }
    ffmpeg -loglevel error -y -i "$audio_dir/de-10min.wav" -f segment -segment_time 120 -c copy "$work/excerpt-%02d.wav"
    for m in $top2; do
      for mode in plain prompted; do
        previous=""
        for excerpt in "$work"/excerpt-*.wav; do
          name="excerpt-$mode-$(basename "$excerpt")"
          cp "$excerpt" "$audio_dir/$name"
          "$adb" push -q "$audio_dir/$name" "$dev/audio/$name"
          prompt=""; [ "$mode" = prompted ] && prompt="$previous"
          run_one S-4 "$m" "$best_threads" on no "$prompt" none "$name"
          previous="$(tail -c 200 "$work/plain.txt" | tr '\n' ' ')"
          rm -f "$audio_dir/$name"
        done
      done
    done ;;
  s5) # is language detection right? (-l auto)
    for m in ${MODELS:-$(all_models)}; do
      for a in de-3min.wav en-sample.wav silence-first.wav; do run_one S-5 "$m" "$best_threads" on no "" none "$a" "" auto; done
    done ;;
  s6) # accuracy: word error rate of every candidate
    for m in $(all_models); do run_one S-6 "$m" "$best_threads" on no "" none de-3min.wav de-3min.ref.txt; done ;;
  thermal) # ten consecutive one-minute clips without a cooling pause: the real-time factor of each clip
    command -v ffmpeg >/dev/null || { echo "ffmpeg is required for thermal" >&2; exit 2; }
    ffmpeg -loglevel error -y -i "$audio_dir/de-10min.wav" -f segment -segment_time 60 -c copy "$work/minute-%02d.wav"
    for m in $top2; do
      for clip in "$work"/minute-*.wav; do
        name="thermal-$(basename "$clip")"
        cp "$clip" "$audio_dir/$name"
        "$adb" push -q "$audio_dir/$name" "$dev/audio/$name"
        run_one "thermal-$(basename "$clip" .wav)" "$m" "$best_threads" on no "" none "$name"
        rm -f "$audio_dir/$name"
      done
    done ;;
  *) sed -n '2,10p' "$0" >&2; exit 2 ;;
esac
