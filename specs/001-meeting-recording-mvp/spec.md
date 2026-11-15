# Feature Specification: Meeting Recording MVP (Phase 1)

**Feature Branch**: `001-meeting-recording-mvp` (no branch created; work continues on `main`)

**Created**: 2026-11-15

**Status**: Draft

**Input**: User description: "Phase 1 (MVP) of the Meeting Recorder app: record audio and photos during a meeting and store them as a meeting folder. Microphone source selection (built-in or external USB-C microphone, specifically the RØDE Wireless PRO receiver), stereo recording for two-channel sources, level meter / elapsed time / pause / resume / stop, background and screen-off recording, fallback to the built-in microphone when the external one is unplugged, one-tap photos positioned on the recording timeline, user-chosen storage folder, meeting folder with audio, photos/, meeting.json and CLAUDE.md, meeting list with rename / playback with photo jumps / delete, and a legal consent notice before the first recording. Out of scope: transcription, speaker detection, speaker names, any cloud features."

## Clarifications

### Session 2026-11-15

- Q: When a two-channel recording falls back to the built-in microphone, what goes into the left and right channels from then on? → A: The built-in microphone's mono signal is copied into both channels; the switch log marks that the channels are no longer per-speaker from that point.
- Q: Should the user be able to switch the microphone source by hand while a recording is running? → A: No. The source is chosen before recording starts; the only mid-recording change is the automatic fallback to the built-in microphone.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Record a meeting and keep it safe (Priority: P1)

The user opens the app, starts a new meeting (optionally typing a title), records the conversation with the phone's built-in microphone, and stops. The meeting is saved as a folder in the storage folder the user chose on first launch. On the very first launch, the user is asked to pick that storage folder and to acknowledge a notice that all participants must consent to being recorded.

**Why this priority**: Capturing audio reliably and saving it in an open folder is the core value of the app. Everything else builds on it, and this slice alone is already a usable voice recorder for meetings.

**Independent Test**: Install the app on a clean device, pick a storage folder, acknowledge the notice, record for one minute with the built-in microphone, stop, and verify that a meeting folder with a playable audio file, `meeting.json` and `CLAUDE.md` exists in the chosen folder.

**Acceptance Scenarios**:

1. **Given** the app is launched for the first time, **When** the user wants to start a recording, **Then** the app first asks the user to choose a storage folder and to acknowledge the recording-consent notice, and does not start recording until both are done.
2. **Given** a storage folder and an acknowledged notice, **When** the user starts a meeting without entering a title, **Then** the meeting is titled with the current date and time.
3. **Given** the user enters a title, **When** the recording starts, **Then** the meeting uses that title.
4. **Given** a recording is running, **When** the user stops it, **Then** a meeting folder appears in the storage folder containing the audio file, a `photos/` subfolder, `meeting.json` and `CLAUDE.md`, and the meeting is shown in the meeting list.
5. **Given** a recording is running, **When** the user turns the screen off or switches to another app, **Then** the recording continues without gaps and a visible indication shows that recording is active.
6. **Given** a recording is running, **When** the user pauses and later resumes, **Then** the paused time is not part of the audio and the elapsed time shows only recorded time.
7. **Given** the recording consent notice was acknowledged before, **When** the user starts later recordings, **Then** the notice is not shown again before every recording.

---

### User Story 2 - Use an external microphone with two speakers (Priority: P2)

The user plugs the RØDE Wireless PRO receiver into the phone's USB-C port. The app shows the available microphone sources, marks the active one, and suggests the external microphone automatically. If the receiver delivers two channels (for example split mode: speaker A on the left, speaker B on the right), the meeting is recorded in stereo and this is noted in the meeting data; otherwise it is recorded in mono. While recording, the user sees a level meter for each channel, the elapsed time, and controls for pause, resume and stop.

**Why this priority**: The external microphone with per-speaker channels is the main reason for building the app and enables later speaker separation. It depends on the basic recording flow from Story 1.

**Independent Test**: Connect the RØDE Wireless PRO receiver in split mode, start a meeting, speak into each transmitter alternately, and verify that two level meters react independently and that the saved meeting is marked as stereo with speaker A on the left channel and speaker B on the right.

**Acceptance Scenarios**:

1. **Given** the app is open and no external microphone is connected, **When** the user views the source selection, **Then** only the built-in microphone is listed, and it is active.
2. **Given** the app is open, **When** the user plugs in the external microphone, **Then** the app lists it as available and suggests it as the source to use.
3. **Given** several sources are available, **When** the user selects a source before recording, **Then** that source is used and is shown as active.
4. **Given** the selected source delivers two channels, **When** the recording runs, **Then** it is recorded in stereo, two level meters are shown, and the meeting data states that the recording is stereo.
5. **Given** the selected source delivers one channel, **When** the recording runs, **Then** it is recorded in mono, one level meter is shown, and the meeting data states that the recording is mono.
6. **Given** a recording is running, **When** sound reaches the microphone, **Then** the level meter(s), elapsed time and recording state update continuously while the screen is on.

---

### User Story 3 - Survive microphone loss without losing audio (Priority: P2)

During a recording with the external microphone, the receiver is unplugged (accidentally or because the cable slipped). The recording does not stop: it continues with the built-in microphone. The switch is logged with its position on the recording timeline and the user is told about it.

**Why this priority**: Losing a meeting because of a loose cable is the worst failure the app can have; this directly implements the "no data loss" principle for the main hardware scenario.

**Independent Test**: Start recording with the external microphone, unplug it after 30 seconds, keep talking for another 30 seconds, stop, and verify that the audio is continuous, contains both parts, and that the meeting data lists one source switch at roughly 30 seconds.

**Acceptance Scenarios**:

1. **Given** a recording uses the external microphone, **When** it is unplugged, **Then** recording continues with the built-in microphone and the user sees a clear notice about the switch.
2. **Given** a source switch happened, **When** the meeting is saved, **Then** the meeting data contains an entry with the time position of the switch, the previous source and the new source.
3. **Given** a stereo recording switched to the built-in (mono) microphone, **When** the meeting is saved, **Then** the audio file keeps its stereo layout for the whole recording, and the switch entry makes clear that the remaining audio comes from a mono source.
4. **Given** a recording uses the external microphone and the screen is off or the app is in the background, **When** it is unplugged and the built-in microphone cannot be opened, **Then** the recording pauses without losing any audio recorded so far, and a notification offers to resume with the built-in microphone.
5. **Given** a recording uses the external microphone, **When** it is unplugged and plugged in again, **Then** the recording keeps using the built-in microphone and the user is informed that the external microphone is available again, without an automatic second switch.

---

### User Story 4 - Take photos during the meeting (Priority: P2)

While recording, the user taps a photo button to capture a whiteboard, slide or document. The recording is not interrupted. Each photo is stored in the meeting's `photos/` subfolder and is tied to the position on the recording timeline at which it was taken.

**Why this priority**: Visual context (whiteboards, slides) is a key part of meeting minutes. It builds on Story 1 but is independent of the external microphone.

**Independent Test**: Record for one minute, take three photos at different moments, stop, and verify that the meeting contains three photos, each with a timeline position matching when it was taken (within one second).

**Acceptance Scenarios**:

1. **Given** a recording is running, **When** the user taps the photo button, **Then** a photo is captured with a single tap and the recording continues without a gap.
2. **Given** a photo was taken, **When** the meeting is saved, **Then** the photo file is in the `photos/` subfolder and the meeting data lists it with its position on the recording timeline.
3. **Given** the recording was paused for some time before a photo was taken, **When** the photo position is stored, **Then** it refers to the recorded audio timeline (excluding paused time), not to wall-clock time.
4. **Given** the user denied camera access, **When** the user wants to take a photo, **Then** the app explains why the permission is needed and offers to grant it; all other recording features keep working.

---

### User Story 5 - Browse, play back and manage meetings (Priority: P3)

The user opens the meeting list, sees all meetings in the storage folder, plays one back, jumps to the moments when photos were taken, renames meetings and deletes meetings after confirmation.

**Why this priority**: Needed to make recordings useful inside the app, but the folder in the storage location is already usable without it, so it ranks after capture.

**Independent Test**: With at least two saved meetings (one with photos), open the list, rename one, play the other and jump to a photo's time position, then delete one after confirming, and verify the list and the storage folder reflect each change.

**Acceptance Scenarios**:

1. **Given** meetings exist in the storage folder, **When** the user opens the list, **Then** all meetings are shown, newest first, with title, start date and duration.
2. **Given** a meeting folder is added to the storage folder from outside the app, **When** the user opens the list, **Then** it appears in the list (the folder is the source of truth).
3. **Given** a meeting is selected, **When** the user plays it back, **Then** playback supports play, pause and seeking, and shows the current position.
4. **Given** a meeting with photos is played back, **When** the user selects a photo, **Then** playback jumps to that photo's timeline position and the photo can be viewed.
5. **Given** a meeting is selected, **When** the user renames it, **Then** the new title is stored in the meeting data and shown in the list.
6. **Given** a meeting is selected, **When** the user chooses delete, **Then** the app asks for confirmation, and only after confirmation removes the entire meeting folder; cancelling keeps everything unchanged.

---

### User Story 6 - Recover a recording after a crash (Priority: P2)

If the app crashes, is killed by the system, or the phone restarts during a recording, everything recorded up to that moment is kept. On the next start, the interrupted meeting appears in the list, marked as recovered, and its audio is playable.

**Why this priority**: Implements the project's "no data loss" principle for unexpected failures; without it, the app cannot be trusted for one-time meetings.

**Independent Test**: Start a recording, take a photo, force-stop the app after one minute, reopen the app, and verify the meeting is listed as recovered with about one minute of playable audio and the photo.

**Acceptance Scenarios**:

1. **Given** a recording was interrupted unexpectedly, **When** the user opens the app again, **Then** the meeting is listed, marked as recovered, with all audio recorded up to the interruption playable.
2. **Given** a recovered meeting, **When** its folder is inspected, **Then** `meeting.json` is valid and reflects the actual recorded duration and any photos taken before the interruption.

---

### Edge Cases

- **Storage folder unavailable**: If the chosen storage folder was removed or access was revoked, the app tells the user and asks them to choose a folder again; no recording starts into an unknown location.
- **Low storage space**: If storage is nearly full before or during recording, the app warns the user; if space runs out, the recording stops cleanly and everything recorded so far remains playable.
- **Microphone permission denied**: The app explains why the permission is needed and offers to grant it; no recording starts without it.
- **Microphone taken by another app or a phone call**: Audio recorded so far is kept, the user is clearly informed that recording was interrupted, and the user can resume recording into the same meeting.
- **External source with more than two channels**: Only the first two channels are recorded, as stereo; the meeting data notes this.
- **No microphone available at start**: The user is told that no source is available and recording cannot begin.
- **Very long meetings**: A recording of up to 6 hours in stereo (12 hours in mono) must stay stable and keep all audio. At the maximum length of the audio file format the recording stops cleanly with everything recorded so far saved and playable (see FR-034).
- **Photo taken while paused**: Allowed; its position is the point on the timeline where the recording was paused.
- **Stop with zero seconds recorded**: An empty meeting is discarded or clearly flagged and not left as a broken folder.
- **Duplicate titles**: Two meetings with the same title are allowed and never overwrite each other.
- **Unreadable or incomplete meeting folder**: A folder with missing or invalid metadata is still listed (with what can be determined) rather than hidden or crashing the list.

## Requirements *(mandatory)*

### Functional Requirements

**Recording flow**

- **FR-001**: Users MUST be able to start a new meeting with an optional title; if no title is given, the title MUST default to the current date and time.
- **FR-002**: On first launch, the app MUST ask the user to choose a storage folder, and MUST NOT start a recording until one is chosen and accessible. The folder-selection screen MUST state that meetings are stored unencrypted in the chosen folder and that any sync or cloud service on that folder will copy them off the device.
- **FR-003**: Before the first recording, the app MUST show a notice that all participants must consent to being recorded, and the user MUST acknowledge it before recording starts. The acknowledgement MUST be remembered.
- **FR-004**: While recording, the app MUST show the elapsed recorded time, a level meter for each recorded channel, and the controls pause, resume and stop.
- **FR-005**: Paused time MUST NOT be part of the recorded audio or of the elapsed time.
- **FR-006**: Recording MUST continue when the screen is off or the user switches to another app, and the app MUST show a persistent indication that recording is active, from which the user can return to the app.
- **FR-007**: Users MUST be able to stop a recording, after which the meeting is finalized and listed.

**Microphone sources**

- **FR-008**: The app MUST list the available microphone sources (the built-in microphone and any connected external USB-C microphone) and clearly show which one is active.
- **FR-009**: When an external microphone is connected, the app MUST suggest it as the source automatically; the user MUST be able to override the choice before recording starts.
- **FR-010**: The RØDE Wireless PRO receiver MUST be supported as an external source, in both split (two-channel) and mixed (single-channel) modes.
- **FR-011**: If the active source delivers two channels, the app MUST record stereo; otherwise it MUST record mono. The channel layout MUST be stated in the meeting data.
- **FR-012**: If the external microphone is disconnected during recording, the recording MUST continue with the built-in microphone, with a gap of no more than 2 seconds (SC-004), and the user MUST be notified.
- **FR-013**: Each source switch MUST be logged in the meeting data with its timeline position (FR-020), the previous source and the new source.
- **FR-014**: The channel layout of one meeting's audio MUST stay constant for the whole recording; when the new source has fewer channels, its signal MUST be copied into all channels, and the switch log entry MUST state that from this position on the channels no longer correspond to individual speakers.
- **FR-015**: Users MUST NOT be able to change the source manually while a recording is running; the source is chosen before the recording starts. Reconnecting an external microphone during a recording MUST NOT switch the source automatically; the user MUST be informed that it is available and that it will be used from the next recording.

**Photos**

- **FR-016**: While recording (including while paused), users MUST be able to take a photo with a single tap without interrupting the recording.
- **FR-017**: Each photo MUST be stored in the meeting's `photos/` subfolder and recorded in the meeting data with its timeline position (FR-020).

**Storage and format**

- **FR-018**: Each meeting MUST be stored as its own folder in the chosen storage folder, containing the audio file, a `photos/` subfolder, `meeting.json` and `CLAUDE.md`.
- **FR-019**: `meeting.json` MUST contain at least: title, start date and time, duration, source(s) used, channel layout, source switches, and photos with their timeline positions. All keys MUST be in English and the format MUST be versioned.
- **FR-020**: All time positions in the meeting data (photos, source switches) MUST refer to the recorded audio timeline, not to wall-clock time.
- **FR-021**: `CLAUDE.md` MUST describe the folder structure and the `meeting.json` format in a way that lets a person or an AI assistant process the meeting without the app, and MUST suggest possible follow-up tasks (summary, minutes, action items).
- **FR-022**: The audio, photos and metadata MUST use documented, standard, open formats; the meeting folder MUST be the single source of truth for the meeting.
- **FR-023**: The meeting folder and its metadata MUST be created when recording starts and kept up to date during the recording, so that an interrupted recording leaves a valid folder.

**Resilience**

- **FR-024**: Audio recorded up to any interruption (app crash, system kill, restart, low storage, microphone loss) MUST remain playable.
- **FR-025**: After an unexpected interruption, the app MUST detect the unfinished meeting on next start, repair its metadata, list it, and mark it as recovered.
- **FR-034**: The app MUST warn the user 15 minutes before the maximum recording length is reached. At the limit it MUST finalize the meeting with all audio recorded so far. The maximum MUST be at least 6 hours for a stereo recording.

**Meeting list and playback**

- **FR-026**: The app MUST show all meetings found in the storage folder, newest first, with title, start date and duration, including meetings added from outside the app.
- **FR-027**: Users MUST be able to rename a meeting; the new title MUST be stored in the meeting data.
- **FR-028**: Users MUST be able to play back a meeting with play, pause, seek and a visible position.
- **FR-029**: During playback, users MUST be able to see the meeting's photos and jump to each photo's timeline position.
- **FR-030**: Users MUST be able to delete a meeting only after explicit confirmation; deletion removes the entire meeting folder.

**Permissions and language**

- **FR-031**: The app MUST request only the permissions required by a feature (microphone for recording, camera for photos, notifications for the recording indicator where the system requires it), and MUST explain each permission to the user before requesting it.
- **FR-032**: The app MUST work fully without network access and MUST NOT transmit any data.
- **FR-033**: All user-facing text MUST be available in English (default) and German, following the device language, with no untranslated or hard-coded text.

### Key Entities

- **Meeting**: One recorded session, stored as one folder. Has a title, a start date and time, a duration, a channel layout (mono or stereo), a recovered flag, and a format version.
- **Audio Recording**: The continuous audio of a meeting, covering only recorded (non-paused) time. Defines the meeting's timeline.
- **Source**: A microphone used during a meeting (built-in or external), with a name, a type and the number of channels it delivers.
- **Source Switch**: A logged change of source during a meeting, with timeline position, previous source and new source.
- **Photo**: An image taken during a meeting, with a file in `photos/` and a timeline position.
- **Storage Folder**: The user-chosen location that contains all meeting folders.
- **Meeting Folder Guide (`CLAUDE.md`)**: A human- and AI-readable description of one meeting folder's structure, format and suggested follow-up tasks.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A returning user can start recording within 10 seconds of opening the app, with no more than 3 taps.
- **SC-002**: A continuous recording of at least 3 hours with the screen off and the app in the background completes with no audible gaps and no lost audio.
- **SC-003**: After a forced app termination during recording, 100% of the audio recorded up to within 2 seconds before the termination is playable once the app is reopened.
- **SC-004**: When the external microphone is unplugged during recording, recording continues with a gap of no more than 2 seconds, and the user sees a notice within 2 seconds.
- **SC-005**: Photos are saved with a timeline position accurate to within 1 second of when the user tapped, and a photo is captured within 3 seconds of the tap.
- **SC-006**: In a two-channel recording, each channel's level meter reacts independently, and a test signal on one channel does not appear on the other.
- **SC-007**: From a saved meeting folder alone, without the app, a person or AI assistant can correctly state the title, start time, duration, channel layout, source switches and photo positions in 100% of test cases.
- **SC-008**: Jumping to a photo during playback moves the playback position to the photo's position within 1 second.
- **SC-009**: The app completes all Phase 1 functions in airplane mode and sends no data over the network.
- **SC-010**: 90% of first-time users can complete first-launch setup (storage folder and notice) and record a short test meeting without help.

## Assumptions

- Target users are individuals recording their own meetings with an Android phone that has a USB-C port; they can obtain consent from participants themselves, and the app only reminds them.
- The RØDE Wireless PRO receiver is the only external microphone verified for Phase 1; other USB-C audio input devices appear as external sources on a best-effort basis.
- The built-in microphone is always available and records mono.
- The legal notice is shown once (until acknowledged) and is not a legal guarantee; it is also reachable again from the app's information or settings area.
- Renaming a meeting changes only the title in the meeting data; the folder name stays unchanged so that references to it remain stable.
- Folder names are derived from the meeting start date and time so that they are unique, sortable and filesystem-safe, regardless of the title.
- Photos use the phone's camera inside the app, in a simple one-tap flow with no editing; image editing, annotation and gallery import are out of scope.
- Playback runs inside the app; playing in the background or with the screen off is not required in Phase 1.
- A recording interrupted by another app or phone call can be resumed into the same meeting by the user; automatic resumption is not required.
- Phase 1 supports one recording at a time; there is no editing or trimming of audio and no export or sharing function beyond the user accessing the meeting folder directly.
- Transcription, speaker detection, speaker names and any cloud features are explicitly out of scope for Phase 1.
- Constitution constraints apply: offline-only operation, open file formats, a single audio timeline, minimal permissions, and English file formats with localized UI (English and German).
