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

## Transcript

A transcript of this meeting was created on the phone with the model "Small (q5_1)" in the language `de`.

1. **Files**: `transcript.json` is the machine-readable transcript. `transcript.md` is a readable rendering of it, generated from `transcript.json` and the photos in `meeting.json`; read it first. Editing `transcript.md` by hand is allowed, but it is overwritten when the meeting is transcribed again.
2. **Fields of `transcript.json`**: `segments`, each with `id`, `startFrame` and `endFrame` (authoritative: frames of `audio.wav`, the same timeline and sample rate as `meeting.json`), `startMs` and `endMs` (derived), `text` and `speaker` (always `null` for now). It also names the model (`model`) and the language (`language`). The language is one for the whole meeting; segments are not labelled per language.
3. **Photos in the text**: photos are not part of `transcript.json`. In `transcript.md` a photo is placed immediately before the first segment that starts after the photo's `atFrame`, or after the last segment when there is none. A photo taken during a segment therefore comes right after that segment; a segment that starts exactly at the photo's position comes before it.
4. **The `transcription` block of `meeting.json`**: `transcription.transcript` records the model (`modelName` "Small (q5_1)", `modelId` `small-q5_1`), the language (`language` `de`, requested as `requestedLanguage` `auto`), when it was completed and how many segments it has (4).
5. **Suggested follow-up tasks**: read `transcript.md` rather than `audio.wav`. The text was recognised automatically, so names and numbers may be wrong. Useful tasks for an AI assistant, for example Claude Code, with this folder as input:
   - **Summary**: "Summarize this meeting in 10 lines. Use the photos as visual context."
   - **Minutes**: "Write meeting minutes with topics, decisions and open questions."
   - **Action items**: "List the action items with owner and due date, if mentioned."

Write results as new Markdown files in this folder (for example `summary.md`); do not edit `meeting.json` or `transcript.json`.

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
