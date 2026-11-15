# Data Model: Meeting Recording MVP (Phase 1)

All types below live in `:core` (`app.meetingrecorder.core.model`) and are pure Kotlin. The
persisted representation is `meeting.json` ([contracts/meeting.schema.json](contracts/meeting.schema.json));
the folder layout is in [contracts/meeting-folder.md](contracts/meeting-folder.md). Field names in
the file are English camelCase.

## Meeting

One recorded session = one folder.

| Field | Type | Rules |
|-------|------|-------|
| `schemaVersion` | int | Currently `1`. Readers MUST reject unknown major versions and ignore unknown fields. |
| `title` | string | 1..200 chars after trimming; default is the local start date and time (`YYYY-MM-DD HH:mm`). Not unique. |
| `startedAt` | string | ISO-8601 with UTC offset. Informational wall-clock only; never used for positioning. |
| `status` | enum | `recording` \| `complete` \| `recovered`. |
| `audio` | AudioInfo | See below. |
| `sources` | list of Source | At least one. Ids unique within the meeting. |
| `initialSourceId` | int | References a `sources[].id`. |
| `sourceSwitches` | list of SourceSwitch | Ordered by `atFrame`. May be empty. |
| `photos` | list of Photo | Ordered by `atFrame`. May be empty. |
| `dropouts` | list of Dropout | Ordered by `atFrame`. Audio lost because the writer buffer overflowed. Usually empty. |

Identity: the folder name (`YYYY-MM-DD_HH-mm-ss`, plus `-2`, `-3`, ... on collision). The folder
name never changes after creation, not even on rename (see spec assumptions).

## AudioInfo

| Field | Type | Rules |
|-------|------|-------|
| `file` | string | Always `audio.wav` in Phase 1. |
| `format` | string | `pcm_s16le`. |
| `sampleRate` | int | `48000` in Phase 1; readers MUST use the stored value. |
| `channels` | int | `1` or `2`; constant for the whole file. |
| `channelLayout` | enum | `mono` \| `stereo`; consistent with `channels`. |
| `durationFrames` | long | Authoritative duration; `>= 0`; equals data bytes / (2 * channels). |
| `durationMs` | long | Derived: `durationFrames * 1000 / sampleRate` (floor). |

## Source

A microphone used in the meeting.

| Field | Type | Rules |
|-------|------|-------|
| `id` | int | Index within the meeting, starting at 0. |
| `name` | string | Product name reported by the device, for example `RØDE Wireless PRO` or `Built-in microphone`. |
| `type` | enum | `builtin` \| `usb` \| `other`. |
| `deliveredChannels` | int | Number of channels the device delivers (`>= 1`). |
| `channels` | int | Number of channels recorded from this source (`1` or `2`; at most `deliveredChannels`). A device delivering more than two channels is recorded as stereo from its first two. |

## SourceSwitch

| Field | Type | Rules |
|-------|------|-------|
| `atFrame` | long | Authoritative position on the audio timeline. `0 <= atFrame <= durationFrames`. |
| `atMs` | long | Derived from `atFrame`. |
| `fromSourceId` | int | References `sources[].id`. |
| `toSourceId` | int | References `sources[].id`; differs from `fromSourceId`. |
| `reason` | enum | `device-removed` in Phase 1 (reserved for later reasons). |
| `channelsDuplicated` | boolean | `true` when the new source has fewer channels than the file and its signal is copied into all channels; from this position on the channels no longer correspond to individual speakers. |

## Dropout

Recorded when saving audio falls behind and the writer buffer overflows: the newest audio that does not
fit is dropped, capture and the timeline keep running (the dropped frames are not part of the timeline).

| Field | Type | Rules |
|-------|------|-------|
| `atFrame` | long | Position on the audio timeline at which audio is missing. `0 <= atFrame <= durationFrames`. |
| `atMs` | long | Derived from `atFrame`. |
| `droppedFrames` | long | Number of frames that were captured but not stored (`> 0`). |

## Photo

| Field | Type | Rules |
|-------|------|-------|
| `file` | string | Relative path, `photos/photo-NNN.jpg`; the file MUST exist when `status` is `complete`. |
| `atFrame` | long | Position at the moment of the tap. `0 <= atFrame <= durationFrames`. |
| `atMs` | long | Derived from `atFrame`. |

## Validation rules (enforced in `:core`)

1. `schemaVersion` known; required fields present; enums valid.
2. `channels` matches `channelLayout`; `sampleRate > 0`; for every source, `channels <= deliveredChannels` and `channels <= 2`.
3. Every `atFrame` is within `[0, durationFrames]`; lists are sorted ascending.
4. Every `atMs` equals the value derived from `atFrame` and `sampleRate`.
5. All referenced source ids exist; a switch changes the source; every dropout has `droppedFrames > 0`.
6. Photo file names match `photos/photo-NNN.jpg` with NNN strictly increasing from 001.
7. A readable-but-invalid file is reported with the list of violations; the meeting is still
   listed with what can be determined (spec edge case).

## Recording session (runtime, not persisted)

Owned by the foreground service; rendered by the UI.

| Field | Type | Notes |
|-------|------|-------|
| `state` | enum | See state machine. |
| `framesAppended` | long | Timeline position, atomic counter. |
| `levels` | per-channel peak/RMS (dBFS) | About 20 updates per second. |
| `activeSource` | Source | Currently capturing source. |
| `availableSources` | list of Source | From `AudioDeviceCallback`. |
| `suggestedSource` | Source or none | First external USB input. |
| `lastEvent` | event | `SourceSwitched`, `ExternalAvailableAgain`, `Suspended`, `StorageLow`, `LimitApproaching`, and so on, for user notices. |

## State machine

```text
            start()                    pause()                      stop()
 Idle ───────────────► Recording ───────────────► Paused ──────────────────────┐
  ▲                      │  ▲  ◄─────────────── │   │                          │
  │                      │  │      resume()      │   │ stop()                   ▼
  │          suspend     │  │ resume()           │   └──────────────────► Finalizing ──► Completed
  │       (call/other    ▼  │                    │                              │
  │        app/errors) Suspended ──────────────┘                              │
  │                      │ stop()                                               │
  │                      └──────────────────────────────────────────────────────┘
  └──────────────────────────────────────────────────────────────────────────────┘
```

- `Idle`: no active meeting; source list and suggestion are live.
- `Recording`: capture running; photos allowed; source switch on unplug stays in this state.
- `Paused`: capture stopped by the user; photos allowed (position is the pause point).
- `Suspended`: capture stopped by the system (call, another app, errors); user notified; Resume
  or Stop.
- `Finalizing`: write final header and metadata, set `status: complete`, create/refresh
  `CLAUDE.md`, then back to `Idle`.
- Process death in any recording state leaves `status: recording` on disk; recovery turns it into
  `recovered` on next launch.
- Zero frames recorded at stop: the meeting folder is deleted and the user is told (spec edge
  case).

## App preferences (not part of the meeting format)

| Key | Meaning |
|-----|---------|
| `storageTreeUri` | Persisted SAF tree of the storage folder. |
| `consentAcknowledged` | Whether the recording-consent notice was accepted. |

These are app-local conveniences; deleting them never affects any meeting folder.
