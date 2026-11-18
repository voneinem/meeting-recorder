# Contract: model catalog, download and import

The only network use of the whole app (Principle I). Everything here is a rule the code and the
build checks enforce; the numbers of the catalog itself come from the spike
([research.md R-17](../research.md)) and from the Hugging Face API at the pinned revision.

## Catalog entry

The catalog is Kotlin data in `:core` (`ModelCatalog`), compiled into the app. No catalog is ever
fetched from the network, so there is nothing to spoof.

| Field | Type | Rule |
|-------|------|------|
| `id` | string | Stable, lower-case, e.g. `small-q5_1`. Used in file names and `transcript.json`. |
| `kind` | `speech` \| `vad` | `vad` entries are auxiliary (voice-activity model), downloaded together with a speech model, never listed on their own. |
| `displayName` | string resource key | Localized name shown to the user. |
| `descriptionKey` | string resource key | Localized speed/quality note, written from spike results. |
| `languages` | list of codes | `["multilingual"]` or `["en"]`. Drives the FR-014 warning. |
| `sizeBytes` | long | Exact size of the file; used for the progress, the free-space check and as an integrity check. |
| `sha256` | 64 hex | Of the whole file. |
| `url` | https URL | `https://huggingface.co/ggerganov/whisper.cpp/resolve/<commit>/<file>`; **pinned to a commit**, never `main`. |
| `sourceName` | string | Shown before the download: "Hugging Face (huggingface.co)". |
| `license` | SPDX id | `MIT` for all entries. Shown to the user (FR-001, FR-009). |
| `approxMemoryMb` | int | Measured in the spike; used to warn on devices with little RAM. |
| `recommendedFor` | optional tag | Marks the spike's default. |

Invariants checked by a unit test: `sha256` has 64 hex digits, `sizeBytes > 0`, `url` is `https`,
the host is `huggingface.co`, the URL contains a 40-hex commit, ids are unique, each `license` is on
the F-Droid-acceptable list, every `descriptionKey`/`displayName` exists in `values/` and
`values-de/`. A developer script (`scripts/verify-model-catalog.sh`, needs network, never part of the
app or the build) compares every entry with the Hugging Face API response (`size`, `lfs.sha256`) and
with the `x-linked-etag` header of the file.

Candidate entries measured by the spike (values read from the Hugging Face API on 2026-11-16,
repository revision `5359861c739e955e79d9a303bcbc70fb988958b1`):

| Candidate id | File | Bytes | SHA-256 |
|--------------|------|------:|---------|
| `tiny-q5_1` | `ggml-tiny-q5_1.bin` | 32152673 | `818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7` |
| `base-q5_1` | `ggml-base-q5_1.bin` | 59707625 | `422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898` |
| `base-q8_0` | `ggml-base-q8_0.bin` | 81768585 | `c577b9a86e7e048a0b7eada054f4dd79a56bbfa911fbdacf900ac5b567cbb7d9` |
| `small-q5_1` | `ggml-small-q5_1.bin` | 190085487 | `ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb` |
| `small-q8_0` | `ggml-small-q8_0.bin` | 264464607 | `49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f` |
| `medium-q5_0` | `ggml-medium-q5_0.bin` | 539212467 | `19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f` |
| `large-v3-turbo-q5_0` | `ggml-large-v3-turbo-q5_0.bin` | 574041195 | `394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2` |
| `large-v3-turbo-q8_0` | `ggml-large-v3-turbo-q8_0.bin` | 874188075 | `317eb69c11673c9de1e1f0d459b253999804ec71ac4c23c17ecf5fbe24e259a1` |
| `vad-silero-v6.2.0` (`vad`) | `ggml-silero-v6.2.0.bin` (repo `ggml-org/whisper-vad`, revision `9ffd54a1e1ee413ddf265af9913beaf518d1639b`) | 885098 | `2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987` |

**Final catalog (task T017, 2026-11-18; measurements in [spike-results.md](../spike-results.md)):** `tiny-q5_1`,
`base-q5_1`, `base-q8_0` and `small-q8_0` (the default, `recommended`). Not shipped: `small-q5_1` (1.06 times real time,
above the 1.0 limit), `medium-q5_0` and `large-v3-turbo-*` (1.6 to 4.3 times real time on the reference phone). The VAD
entry stays out of the catalog (VAD ships off). `approxMemoryMb` is the measured peak plus a margin: 300, 350, 400 and 750.

## On-device layout

Models live in app-private storage, outside every meeting folder (FR-038), and are not part of any
backup (the app already sets `allowBackup=false`).

```text
<filesDir>/models/
├── <id>.bin            # installed model file
├── <id>.json           # sidecar: id, displayName, origin, sha256, sizeBytes, languages, installedAt
├── <id>.part           # download or import in progress
└── <id>.part.json      # { url, expectedSha256, expectedSize, etag, bytesDone }
```

A model is **installed** iff `<id>.bin` and a valid `<id>.json` exist and the file length equals
`sizeBytes`. A `.bin` without a sidecar is re-hashed once and, if it matches a catalog entry, its
sidecar is rebuilt; otherwise it is deleted. Imported models get `id = imported-<first 12 hex of
sha256>` and `origin = "imported"`.

## Download protocol

1. **Confirmation (FR-003).** The dialog shows `displayName`, `sizeBytes`, `sourceName` and the
   license, and that the file is downloaded from the internet. Nothing starts before "Download".
2. **Free space (FR-007).** Required: `sizeBytes - bytesAlreadyDownloaded + 2%` (at least 16 MiB).
   Measured with `StatFs` on `<filesDir>/models`. If short, the dialog states the missing amount and
   the download does not start.
3. **Connection.** `HttpURLConnection`, `GET`, automatic redirects **off**. Each hop (the original
   URL and every `Location`) is accepted only if the scheme is `https` and the host is
   `huggingface.co` or ends in `.hf.co`; at most 5 hops; anything else aborts the download with
   "unexpected download address". Observed redirect on 2026-11-16: `huggingface.co` → `*.cdn.hf.co`
   with a signed, expiring URL, which is why a resume always starts again at the original URL.
4. **Early check.** The final response must carry the expected length (`Content-Length`, or the total
   in `Content-Range` when resuming). The first hop's `x-linked-etag`, when present, must equal the
   catalog `sha256`; a mismatch aborts before any bytes are stored ("the file on the server changed").
5. **Resume.** If `<id>.part` exists with a matching `<id>.part.json` (same url, size, sha256), send
   `Range: bytes=<bytesDone>-` and `If-Range: <etag>`. A `200` answer means the server ignored the
   range: truncate and start over. A `206` answer must have a `Content-Range` that starts at
   `bytesDone`.
6. **Stream** into `<id>.part` while feeding a SHA-256 digest (on resume the existing bytes are read
   through the digest first). Write `bytesDone` into `<id>.part.json` at most every 4 MiB or 2 s.
   Progress is `bytesDone / sizeBytes`, reported at least once per second.
7. **Verify.** When `bytesDone == sizeBytes`: digest must equal `sha256`; mismatch deletes the
   `.part` files and fails with "checksum mismatch" (FR-005, SC-010). Then rename `.part` to `.bin`
   (atomic move in the same directory) and write the sidecar (staged, then renamed). Only after both
   exist is the model installed.
8. **Cancel (FR-004)** closes the connection and deletes `<id>.part` and `<id>.part.json`. An
   interruption (network lost, process killed, timeout) keeps both so that "Resume download" works;
   the model is *not installed* in either case.
9. A speech model whose catalog entry lists a VAD companion downloads it in the same job, first,
   because it is small; the confirmation shows the combined size.

No credentials, cookies or identifiers are sent. The request carries a generic `User-Agent`
(`MeetingRecorder`) and no app version or device data. Redirect hosts and the pinned URL are the only
network destinations.

## Import protocol (FR-006)

1. The user picks one file (`ACTION_OPEN_DOCUMENT`, `*/*`). No storage permission is needed.
2. **Header check before copying.** Read the first 48 bytes: `uint32` magic `0x67676d6c` ("ggml")
   followed by 11 little-endian `int32` values `n_vocab, n_audio_ctx, n_audio_state, n_audio_head,
   n_audio_layer, n_text_ctx, n_text_state, n_text_head, n_text_layer, n_mels, ftype`. Accepted as
   a speech model only if `n_audio_ctx == 1500`, `n_text_ctx == 448`, `n_mels` is 80 or 128 and
   `n_vocab` is `51864` (English-only) or `51865`/`51866` (multilingual). The voice-activity model
   has the same magic but different values, so it is rejected here. Anything else: "this file is not
   a speech recognition model".
3. Free-space check with the file size from the provider (FR-007).
4. Copy into `<id>.part` while hashing; show progress; cancellable.
5. If the hash equals a catalog entry, the model is recognized as that entry (`origin` stays
   `imported`, name and languages come from the catalog). Otherwise it is listed as
   "Imported: <file name>", languages from `n_vocab` (`en` or `multilingual`).
6. An import of a file that is already installed (same hash) is a no-op with a message.

## Deleting (FR-008)

Refused only while a transcription is using the model (`TranscriptionRegistry` knows the model id of
the running job). A resumable work file in some meeting that references the model is *not* a reason
to refuse: that meeting then offers only "start over" (US4 scenario 7). Deleting removes `.bin`,
`.json` and any `.part` files and clears the default-model setting if it pointed here. No transcript
is touched (FR-038).

## Network rules, enforced three times

1. **Code**: only the package `app.meetingrecorder.models.download` may use `java.net`, sockets or
   any HTTP client. A unit test scans all sources and fails otherwise (same approach as the
   hard-coded-string scanner of Phase 1).
2. **Platform**: `network_security_config.xml` forbids cleartext and trusts system certificates only
   for `huggingface.co` and `hf.co` (including subdomains); other hosts get no trust anchors.
   `usesCleartextTraffic="false"`. An instrumented test checks that a connection to another host fails.
3. **Build**: `verifyManifestPermissions` allows `INTERNET` only together with that configuration
   and with exactly the allow-list of [plan.md](../plan.md).

A fourth, runtime check lives in the instrumented tests: `TrafficStats` bytes of the app's uid do not
grow while a transcription runs (SC-001).
