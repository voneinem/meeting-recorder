# Meeting Recorder

An Android app for recording meetings, taking photos on the audio timeline, and (in later phases)
transcribing them. Everything stays on the device: the app has no network access, no telemetry and
no cloud features.

Each meeting is stored as an open, self-describing folder (WAV audio, JPEG photos, `meeting.json`,
and a generated `CLAUDE.md` that explains the folder to people and to AI assistants). The format is
documented in [`specs/001-meeting-recording-mvp/contracts/meeting-folder.md`](specs/001-meeting-recording-mvp/contracts/meeting-folder.md).

## License

SPDX-License-Identifier: GPL-3.0-or-later. See [LICENSE](LICENSE).
