#!/usr/bin/env bash
# Spike tool (never shipped): downloads the speech candidates and the voice-activity model into spike/models/
# (git-ignored) and verifies them against scripts/spike/models.sha256. Host side, development only: this is the
# only network use of the spike. A file that fails the check is deleted and the script exits non-zero.
#
# Usage: scripts/spike/fetch-models.sh [file-name ...]     (default: all files of models.sha256)
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
dest="$here/../../spike/models"
speech="https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1"
vad="https://huggingface.co/ggml-org/whisper-vad/resolve/9ffd54a1e1ee413ddf265af9913beaf518d1639b"
mkdir -p "$dest"

wanted=("$@")
[ ${#wanted[@]} -gt 0 ] || mapfile -t wanted < <(awk '{print $2}' "$here/models.sha256")

status=0
for file in "${wanted[@]}"; do
  expected="$(awk -v f="$file" '$2==f {print $1}' "$here/models.sha256")"
  [ -n "$expected" ] || { echo "unknown file: $file" >&2; status=1; continue; }
  base="$speech"; [ "$file" = "ggml-silero-v6.2.0.bin" ] && base="$vad"
  if [ -f "$dest/$file" ] && echo "$expected  $dest/$file" | sha256sum -c --quiet - 2>/dev/null; then
    echo "ok (already there): $file"; continue
  fi
  echo "downloading $file"
  curl -fL --retry 3 -sS -o "$dest/$file" "$base/$file"
  if echo "$expected  $dest/$file" | sha256sum -c --quiet -; then
    echo "ok: $file"
  else
    echo "CHECKSUM MISMATCH, deleted: $file" >&2; rm -f "$dest/$file"; status=1
  fi
done
exit $status
