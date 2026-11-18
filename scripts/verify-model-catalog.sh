#!/usr/bin/env bash
# Development only. Needs the network. Never run by the build or by the app (Principle I).
#
# Compares every entry of the compiled-in model catalog with what Hugging Face says about the pinned file:
#   * `size` and `lfs.sha256` from the model API (https://huggingface.co/api/models/<repo>?blobs=true), and
#   * `x-linked-size` and `x-linked-etag` of a HEAD request on the pinned URL.
# Exits non-zero on any difference. With an empty catalog it says so and exits 0.
#
# Usage: scripts/verify-model-catalog.sh [path/to/ModelCatalog.kt]
set -euo pipefail

catalog="${1:-core/src/main/kotlin/app/meetingrecorder/core/models/ModelCatalog.kt}"
[ -f "$catalog" ] || { echo "catalog not found: $catalog" >&2; exit 2; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 2; }
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 2; }

# One line per entry: id|sizeBytes|sha256|url   (named arguments of ModelCatalogEntry(...) blocks)
entries="$(python3 -I - "$catalog" <<'PY'
import re, sys
text = open(sys.argv[1], encoding="utf-8").read()
for block in re.findall(r"ModelCatalogEntry\((.*?)\n\s*\)", text, flags=re.S):
    def field(name):
        m = re.search(r"\b" + name + r"\s*=\s*\"?([^\",\n]+)\"?\s*,", block)
        return m.group(1).replace("_", "") if name == "sizeBytes" and m else (m.group(1) if m else "")
    print("|".join([field("id"), field("sizeBytes"), field("sha256"), field("url")]))
PY
)"

if [ -z "$entries" ]; then
  echo "The catalog has no entries: nothing to verify."
  exit 0
fi

failures=0
fail() { echo "  MISMATCH: $*" >&2; failures=$((failures + 1)); }

while IFS='|' read -r id size sha url; do
  echo "== $id"
  # https://huggingface.co/<repo>/resolve/<commit>/<file>
  path="${url#https://huggingface.co/}"
  repo="$(echo "$path" | cut -d/ -f1-2)"
  file="$(echo "$path" | cut -d/ -f5-)"
  api="$(curl -fsS "https://huggingface.co/api/models/${repo}?blobs=true")" || { fail "$id: the model API request failed"; continue; }
  api_fields="$(printf '%s' "$api" | python3 -I -c '
import json, sys
file = sys.argv[1]
data = json.load(sys.stdin)
for s in data.get("siblings", []):
    if s.get("rfilename") == file:
        print(s.get("size", ""), (s.get("lfs") or {}).get("sha256", ""))
        break
' "$file")"
  read -r api_size api_sha <<<"$api_fields"
  [ -n "${api_size:-}" ] || { fail "$id: $file is not in the model API response"; continue; }
  [ "$api_size" = "$size" ] || fail "$id: size is $size, the API says $api_size"
  [ "$api_sha" = "$sha" ] || fail "$id: sha256 is $sha, the API says $api_sha"

  headers="$(curl -fsSI "$url")" || { fail "$id: the HEAD request failed"; continue; }
  linked_size="$(printf '%s' "$headers" | tr -d '\r' | awk 'tolower($1)=="x-linked-size:"{print $2}' | tail -1)"
  linked_etag="$(printf '%s' "$headers" | tr -d '\r' | awk 'tolower($1)=="x-linked-etag:"{print $2}' | tail -1 | tr -d '"')"
  [ -z "$linked_size" ] || [ "$linked_size" = "$size" ] || fail "$id: size is $size, x-linked-size says $linked_size"
  [ -z "$linked_etag" ] || [ "$linked_etag" = "$sha" ] || fail "$id: sha256 is $sha, x-linked-etag says $linked_etag"
done <<<"$entries"

if [ "$failures" -ne 0 ]; then
  echo "$failures difference(s) found." >&2
  exit 1
fi
echo "All catalog entries match Hugging Face."
