# Contract: transcript files in the meeting folder

Extends [Phase 1 `meeting-folder.md`](../../001-meeting-recording-mvp/contracts/meeting-folder.md).
Everything not listed here is unchanged. The folder stays the single source of truth (Principle IV);
the app has no database for any of this.

## Files

| Path | Written by | Meaning |
|------|-----------|---------|
| `transcript.json` | commit step | The transcript. Schema: [transcript.schema.json](transcript.schema.json). **Complete or absent**, never partial. |
| `transcript.md` | commit step | Human-readable rendering, generated from `transcript.json` + `meeting.json` photos. Never read back by the app. |
| `transcript.work.jsonl` | running job | Checkpoint of an unfinished job. Present only while a job runs, is interrupted, or is waiting for its commit to finish. |
| `transcript.json.part`, `transcript.md.part` | commit step | Staging files of a commit in progress. Meaningless leftovers if found without a work file. |
| `meeting.json` | job start / end | Gains the optional `transcription` object ([meeting-transcription.schema.json](meeting-transcription.schema.json)). `schemaVersion` stays `1`. |
| `CLAUDE.md` | job end, rename | Regenerated; gains the section described below. |

`audio.wav` and `photos/` are only ever **read** by transcription. A test hashes `audio.wav` before
and after a run (FR-023, Principle III).

## `transcript.json`

See the schema and [examples/transcript.example.json](examples/transcript.example.json). Rules
beyond the schema, enforced by `TranscriptValidator` in `:core`:

- `startFrame <= endFrame <= audio.durationFrames`; segments sorted by `startFrame` (non-decreasing);
  `id` is `0..n-1` in file order.
- `startMs = Timeline.framesToMs(startFrame, sampleRate)` and the same for `endMs` (one conversion,
  Principle V). On read, the frames win if they disagree with the milliseconds.
- Whisper times have 10 ms resolution; the writer converts centiseconds to frames as
  `cs * sampleRate / 100` (exact at 48 kHz: multiples of 480).
- `text` is trimmed and never empty; segments that are empty after trimming are dropped.
- `speaker` is `null` in this phase. A reader MUST accept a string there (forward compatibility).
- `language.used` is the one language the whole transcript was decoded in (clarification of
  2026-11-16): a mixed-language meeting is not labelled per segment.

## Writing and replacing: the commit protocol

SAF cannot replace a file atomically (a rename onto an existing name fails), and a truncating write
leaves a damaged file if it is cut short. The protocol below keeps two properties: a file named
`transcript.json` is always complete, and the old transcript of a re-transcription is never lost
before the new one exists (FR-021, FR-034).

**While running** (append-only, torn tail tolerated, see [Work file](#work-file)):

1. `transcript.work.jsonl` is created with a header, then one line per decoded window, then one
   `end` line when the whole audio is done.

**Commit** (starts only when the work file has its `end` line; every step is safe to repeat):

1. Build the `Transcript` from the work file. Write `transcript.json.part`, sync, read it back and
   check it decodes to an equal `Transcript`.
2. Build `transcript.md` from it; write `transcript.md.part`, sync.
3. Replace `transcript.json`: delete the old file if present, rename the `.part` file to the final
   name. Same for `transcript.md`.
4. Update `meeting.json` (read-modify-write, see below): `transcription.transcript` = the new
   transcript, `transcription.job` removed.
5. Regenerate `CLAUDE.md`.
6. Delete `transcript.work.jsonl` **last**.

**Why this is safe**

- A crash before step 3 leaves the old transcript (if any) untouched and the work file complete.
- A crash between "delete old" and "rename" leaves a complete `.part` file and the complete work
  file. Recovery repeats the commit and the new transcript appears.
- The work file is the commit record: **a work file with an `end` line means "the commit is not
  finished yet"; recovery repeats the whole commit from step 1** (it is idempotent). A work file
  without an `end` line is a job that can be resumed or discarded by the user, never committed.
- `.part` files without a work file are deleted on sight.

**`meeting.json` writes** (all of Phase 2, and rename/recovery of Phase 1 as hardening): one
read-modify-write under a per-meeting in-process lock that re-reads the file inside the lock, and
the write itself uses the same stage-delete-rename replacement (`meeting.json.part`). A reader that
finds `meeting.json` missing or unreadable while `meeting.json.part` decodes uses the `.part` file.
Progress is never written to `meeting.json` (it changes every second); it exists only in memory and
in the work file.

## Work file

`transcript.work.jsonl`, UTF-8, one JSON object per line, every line ends with `\n`.

| `t` | Fields | When |
|-----|--------|------|
| `header` | `v` (1), `modelId`, `modelSha256`, `requestedLanguage`, `vad` (bool), `sampleRate`, `durationFrames`, `engineVersion`, `pipeline` (int, bumped when chunking changes results), `startedAt` | first line |
| `language` | `code`, `confidence`, `probes` (positions in frames) | once, after language detection; for a fixed language it records that choice |
| `window` | `fromFrame`, `nextFrame`, `elapsedMs`, `segments` (`[{startFrame, endFrame, text}]`) | after every decode call; `nextFrame` is where the next window starts |
| `end` | `segmentCount`, `elapsedMsTotal` | once, after the last window |

Reading: lines are parsed in order. The first line that is incomplete (no trailing `\n`) or does not
parse ends the read; everything after it is ignored and, on resume, the file is truncated to the
last good line. `elapsedMs` of all windows summed is the processing time (resume sessions add up).

**Resume is allowed only if** `header.modelSha256`, `requestedLanguage`, `vad`, `sampleRate`,
`durationFrames` and `pipeline` equal the new request and the model file is installed. Otherwise
only "start over" is offered (US4 scenario 7).

## `meeting.json` additions and status resolution

`transcription.transcript` describes the committed transcript, `transcription.job` the latest run
that is running or failed. The block is a cache: the **resolved status** below is computed from the
folder, so it is right for folders copied in from outside, for folders touched by an older app
version that dropped the block, and after a crash.

Inputs: `T` = `transcript.json` exists and decodes and validates; `J` = `transcription.job.state`
(or none); `W` = work file exists; `live` = the in-process registry says a job for this folder is
running now.

| `T` | `J` / `W` / `live` | Resolved status | Detail screen |
|-----|--------------------|-----------------|---------------|
| no, file missing | no job, no `W` | **none** | offers "Transcribe" |
| no | running (`live`) | **in progress** | progress, cancel |
| no | `in-progress` but not `live`, or `failed` | **failed** (interrupted / reason) | resume (if `W` usable) / start over |
| no | `W` with `end` line | **in progress** until recovery finishes the commit (seconds), then **done** | — |
| yes | no job | **done** | transcript |
| yes | running (`live`) | **in progress** | old transcript readable + progress (FR-034) |
| yes | `failed`, or `in-progress` but not `live` | **done** | transcript + banner: "re-transcription failed/interrupted: resume / start over / dismiss" |
| file exists but does not decode | any | **failed** (transcript unreadable) | offers "Transcribe again" |
| yes | `meeting.json` has no `transcription` block | **done** | transcript (block is rebuilt on next write) |

"Dismiss" removes the work file and the `job` block; the old transcript stays.

## `transcript.md`

Deterministic output of `TranscriptMarkdown.generate(meeting, transcript)`. Same input, same bytes.

```markdown
# <meeting title>

*Transcript · model <model.name> · language <language.used>[ (detected)] · <YYYY-MM-DD of generatedAt> · whisper.cpp <engine.version>*

<!-- generated-from: transcript.json sha256=<64 hex of the transcript.json bytes> -->

**[<position>]** <segment text>

![Photo <n> (<position>)](photos/photo-<NNN>.jpg)
```

- `<position>` is `Timeline.format(startMs)`: `mm:ss`, or `h:mm:ss` from one hour on. A photo uses
  the position of its `atMs`.
- Blocks are separated by one blank line; the file ends with one newline. No speaker prefix in this
  phase.
- `<n>` is the photo's 1-based index in `meeting.json` `photos` (equals the number in its file name).
- No speech: after the header comment the file contains the single line `*No speech was detected.*`.
- A segment text that contains Markdown control characters is not escaped; the text is the
  recognizer's output verbatim (trimmed). Editing `transcript.md` by hand is allowed and is
  overwritten on the next transcription.

### Photo placement rule (shared by `transcript.md` and the in-app transcript)

`TranscriptTimeline.interleave(segments, photos)` returns one ordered list. A photo with
`atFrame = p` is placed **immediately before the first segment whose `startFrame > p`**; if there is
none, after the last segment. Consequences, each covered by a unit test:

- A photo taken during a segment (`startFrame <= p < endFrame`) comes right **after** that segment.
- A segment starting exactly at `p` comes **before** the photo.
- Photos with equal `atFrame` keep their order in `meeting.json`.
- Photos before the first segment come first; with zero segments the photos are listed alone.
- Photos are never reordered among themselves; `meeting.json` order is by `atFrame` already.

### Golden example

For [examples/transcript.example.json](examples/transcript.example.json) with three photos in
`meeting.json` (`photo-001.jpg` at frame 300000, `photo-002.jpg` at 576000, `photo-003.jpg` at
912000) and the title "Weekly sync":

```markdown
# Weekly sync

*Transcript · model Small (q5_1) · language de (detected) · 2026-11-16 · whisper.cpp 1.9.5*

<!-- generated-from: transcript.json sha256=<hash> -->

**[00:00]** Guten Morgen zusammen, wir fangen mit dem Rückblick an.

**[00:04]** Die Zahlen aus dem letzten Quartal sehen gut aus.

![Photo 1 (00:06)](photos/photo-001.jpg)

**[00:12]** Hier auf dem Whiteboard sieht man die neue Planung.

![Photo 2 (00:12)](photos/photo-002.jpg)

**[00:15]** Gibt es dazu Fragen?

![Photo 3 (00:19)](photos/photo-003.jpg)
```

Reading the placement: photo 1 (6.25 s) lies inside segment 1 (4.2 to 9.8 s), so it follows that
segment. Photo 2 (12.0 s) equals the start of segment 2, which therefore comes first. Photo 3
(19.0 s) is at the end of the last segment; there is no later segment, so it comes last.

## `CLAUDE.md` addition

`ClaudeGuide.generate` appends this section (English, independent of the device language) **only
when a transcript is committed**; without one it adds a single line, "No transcript has been
created for this meeting yet." The section lists, in this order:

1. The files `transcript.json` and `transcript.md`, what each is for, and that `transcript.md`
   is generated from `transcript.json`.
2. The `transcript.json` fields: segments with `startFrame`/`endFrame` (authoritative, same
   timeline and sample rate as `meeting.json`), derived `startMs`/`endMs`, `text`, `speaker`
   (always `null` for now), plus model and language, and that the language is one for the whole
   meeting.
3. How photos relate to the text (the placement rule above) and that photos are not part of
   `transcript.json`.
4. The `transcription` block of `meeting.json` and the model and language used.
5. Suggested follow-ups using the transcript: summary, minutes, action items, decisions, with the
   hint to read `transcript.md` rather than `audio.wav` and to treat the recognizer's output as
   possibly wrong in names and numbers.

Golden files for a meeting with and without a transcript live in `core/src/test/resources/golden/`.
