# Data Model: Offline Transcription (Phase 2)

Extends the [Phase 1 data model](../001-meeting-recording-mvp/data-model.md). Pure types live in
`:core` (package `app.meetingrecorder.core.*`); Android-only types are marked *(app)*.
Serialized forms are in [contracts/](contracts/).

## Entities

### ModelCatalogEntry (`core.models`)

Compile-time data, see [contracts/model-catalog.md](contracts/model-catalog.md): `id`, `kind`
(`SPEECH`/`VAD`), `displayNameKey`, `descriptionKey`, `languages` (`MULTILINGUAL` or `ENGLISH_ONLY`),
`sizeBytes`, `sha256`, `url`, `sourceName`, `license`, `approxMemoryMb`, `vadCompanionId?`.

### InstalledModel (`core.models`, sidecar `<id>.json`)

| Field | Type | Rule |
|-------|------|------|
| `id` | string | catalog id or `imported-<12 hex>` |
| `displayName` | string | catalog name, or `Imported: <file name>` |
| `origin` | `CATALOG` \| `IMPORTED` | |
| `sha256` | 64 hex | of `<id>.bin`; the identity used by `transcript.json` and the work file |
| `sizeBytes` | long | equals the file length, else the model is damaged |
| `languages` | `MULTILINGUAL` \| `ENGLISH_ONLY` | from the catalog or from `n_vocab` |
| `installedAt` | ISO-8601 | informational |

Installed state is derived from the files (rule in the contract); there is no registry of models.

### ModelInstallJob (app, in memory + `<id>.part.json`)

State machine of one model id:

```text
NotInstalled --download/import--> Transferring --size reached--> Verifying --hash ok--> Installed
Transferring --cancel--> NotInstalled          (partial files deleted)
Transferring --network lost / process killed / recording started--> Paused   (partial files kept)
Paused --resume--> Transferring ;  Paused --delete partial--> NotInstalled
Verifying --hash mismatch--> Failed(checksum) --> NotInstalled (files deleted)
Installed --delete (not in use)--> NotInstalled
```

Validation: one install job at a time; `bytesDone <= expectedSize`; `Paused` requires
`<id>.part` and a matching `<id>.part.json`.

### TranscriptionRequest (app) and TranscriptionJob

Request: `folderName`, `modelId`, `requestedLanguage` (`auto` or a code), `mode`
(`START`, `RESUME`, `RESTART`). The job is the run of a request. In memory it is the
`TranscriptionState`; on disk it is `meeting.json` `transcription.job` plus the work file.

```text
(none) --start--> Preparing --model loaded, language known--> Running --last window--> Committing --done--> (none, transcript committed)
Preparing|Running --cancel--> Cancelling --stopped--> (none)                      (work file deleted, previous transcript kept)
Preparing|Running --recording started--> Cancelling --stopped--> Failed(recording-started, resumable)
Preparing|Running --system stop / kill--> Failed(interrupted, resumable)
Preparing|Running|Committing --error--> Failed(reason)
Failed(resumable) --resume--> Preparing ;  Failed --start over--> Preparing (work file deleted first)
Failed --dismiss--> (none)                                                        (work file and job block removed)
```

`Failed` is what the list shows as "failed" when no transcript exists (FR-035); with a committed
transcript the list shows "done" and the detail screen shows a banner (see the status table in
[contracts/transcript-folder.md](contracts/transcript-folder.md)).

Invariants: at most one live job in the process; `progress` never decreases within a job; a job
never changes `audio.wav`, `photos/` or any file other than the ones in the contract.

### WorkFile (`core.transcription`)

Records `Header`, `LanguageChoice`, `Window`, `End`, see the contract. Pure functions:
`WorkFile.decode(bytes) -> Decoded(records, goodLength, torn)`, `encode(record)`,
`canResume(header, request, model, audioInfo)`, `toTranscript(records, …)`. Segments inside windows
are already de-duplicated by the window rule (research R-5), so `toTranscript` only numbers them.

### Transcript and TranscriptSegment (`core.transcript`)

`Transcript(schemaVersion, generatedAt, audio(sampleRate, durationFrames), model, language, engine,
processing, segments)` and `TranscriptSegment(id, startFrame, endFrame, text, speaker?)`;
`startMs`/`endMs` are computed through `Timeline`, never stored in the Kotlin type (they are written
to JSON and ignored on read). Rules (`TranscriptValidator`): see the contract. `TranscriptJson` is
its own JSON configuration (separate from `MeetingJson`): stable key order, `explicitNulls = true`
so that `"speaker": null` is always written, decoding ignores unknown keys.

`TranscriptTimeline.interleave(segments, photos): List<Item>` with `Item = Segment | Photo`: the one
placement function used by `TranscriptMarkdown` and by the transcript list in the UI.

### MeetingTranscription (`core.model`, part of `MeetingMetadata`)

`MeetingMetadata` gains `val transcription: MeetingTranscription? = null` (JSON key
`transcription`, omitted when null, see [meeting-transcription.schema.json](contracts/meeting-transcription.schema.json)):

- `TranscriptInfo(modelId, modelName, requestedLanguage, language, completedAt, processingMs, segmentCount)`
- `JobInfo(state: IN_PROGRESS|FAILED, modelId, requestedLanguage, startedAt, failureReason?,
  failureMessage?, resumable)`

`MeetingJson` is configured with `explicitNulls = false` so existing Phase 1 files round-trip
byte-identically (a golden regression test) and a null `transcription` is never written. Older app
versions ignore the block and drop it when they rewrite `meeting.json` (rename); the folder-derived
status below makes that harmless.

### TranscriptionStatus (`core.transcription`, derived, never stored)

`NONE | IN_PROGRESS(progress?) | DONE | FAILED(reason)` computed by
`StatusResolver.resolve(meeting.transcription, transcriptState, workFileState, live)`; the
table in the contract is its specification and its test table. `transcriptState` is
`Missing | Valid | Unreadable`. Shown per meeting in the list (FR-035) and on the detail screen.

### TranscriptionSettings (app, `AppPreferences`)

App-local conveniences, like Phase 1's preferences; deleting them never affects a folder.

| Key | Values | Default |
|-----|--------|---------|
| `transcription.defaultLanguage` | `auto`, `de`, `en` | `auto` |
| `transcription.defaultModelId` | installed model id or none | none |
| `transcription.lastModelId` | installed model id or none | none (set when a job starts) |

Start dialog preselection (FR-010, assumption): the default model if installed, else the last used
model if installed, else the only installed model, else none (and the dialog leads to the model
settings, FR-013).

## Derived values and unit rules

| Value | Rule |
|-------|------|
| Segment frames from Whisper time | `frame = windowStartFrame + cs * sampleRate / 100` (integer arithmetic) |
| Window start | an exact frame; the resampler's output sample `n` corresponds to input frame `n * sampleRate / 16000` |
| Progress | `framesDone / audio.durationFrames`, clamped to `[0, 1]`; `1.0` only when committing |
| Audio duration | `audio.durationFrames` from `meeting.json`; for a recovered folder without it, from the WAV length (the Phase 1 rule) |
| Free space needed for a model | `sizeBytes - bytesDone + max(2%, 16 MiB)` |
| Memory warning | shown when `ActivityManager.MemoryInfo.totalMem < 2 × approxMemoryMb MiB` |

## Folder state summary

```text
meeting folder
├── audio.wav                 read-only for transcription
├── photos/                   read-only for transcription
├── meeting.json              + optional transcription{transcript?, job?}
├── CLAUDE.md                 + transcript section
├── transcript.json           complete or absent
├── transcript.md             generated, replaceable
├── transcript.work.jsonl     only while a job exists / waits for its commit
└── *.part                    only during a commit, or leftovers (deleted on sight)
```
