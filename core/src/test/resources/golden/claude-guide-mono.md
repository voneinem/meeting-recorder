# 2026-11-15 09:05

Meeting recorded with Meeting Recorder on 2026-11-15T09:05:12+01:00. Duration: 01:00. Audio: mono, 48000 Hz, 16-bit PCM WAV.

## Folder contents

| Path | Description |
|------|-------------|
| `meeting.json` | Metadata of this meeting (schema version 1). |
| `audio.wav` | The complete meeting audio as one continuous file. |
| `photos/` | Photos taken during the meeting (none). |
| `CLAUDE.md` | This guide. |

## How to read the timeline

Every position is a sample-frame index into `audio.wav`, counted from the first frame (`atFrame`). The value `atMs` is derived: `atMs = atFrame * 1000 / sampleRate`. In this meeting the sample rate is 48000 Hz.

Paused time and the short gap while a microphone was switched are not part of the timeline. Wall-clock values such as `startedAt` are informational only and are never used to position content.

## Channels

The audio is mono (one channel).

Sources used:

- Source 0: Built-in microphone (builtin, initial source), delivers 1 channel(s), recorded 1.

## Photos

No photos were taken.

## Suggested follow-up tasks

No transcript has been created for this meeting yet.

Useful tasks for an AI assistant, for example Claude Code, with this folder as input:

1. **Summary**: "Summarize this meeting in 10 lines. Use the photos as visual context."
2. **Minutes**: "Write meeting minutes with topics, decisions and open questions."
3. **Action items**: "List the action items with owner and due date, if mentioned."

Write results as new Markdown files in this folder (for example `summary.md`); do not edit `meeting.json`.

## Format reference

`meeting.json` is UTF-8 JSON. Unknown fields must be ignored. Fields:

- `schemaVersion`: format version (currently 1).
- `title`: the meeting title. `startedAt`: wall-clock start, ISO-8601 with UTC offset (informational).
- `status`: `recording` (not finished), `complete`, or `recovered` (repaired after an interruption).
- `audio`: `file`, `format` (`pcm_s16le`), `sampleRate`, `channels` (1 or 2), `channelLayout`
  (`mono` or `stereo`), `durationFrames` (authoritative) and `durationMs` (derived).
- `sources`: microphones used, each with `id`, `name`, `type` (`builtin`, `usb` or `other`),
  `deliveredChannels` and `channels` (recorded). `initialSourceId`: the source the recording started with.
- `sourceSwitches`: `atFrame`, `atMs`, `fromSourceId`, `toSourceId`, `reason`, `channelsDuplicated`.
- `photos`: `file` (path inside this folder), `atFrame`, `atMs`.
- `dropouts`: `atFrame`, `atMs`, `droppedFrames` (audio missing from the file at that position).
