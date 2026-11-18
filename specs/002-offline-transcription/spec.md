# Feature Specification: Offline Transcription (Phase 2)

**Feature Branch**: `002-offline-transcription` (no branch created; work continues on `main`)

**Created**: 2026-11-16

**Status**: Draft

**Input**: User description: "Phase 2 of the Meeting Recorder app: offline transcription of recorded meetings. From a meeting in the meeting list (or its detail screen) the user can start a transcription that runs fully on the device, without any network access. Speech recognition models are managed in a settings screen: downloaded on explicit user action (showing name, size, language suitability, speed/quality trade-off) or imported from a local file, with progress, cancel, checksum verification and deletion; no model is bundled. Before starting, the user selects the model and the spoken language (automatic detection, German, English; default from settings). Transcription runs in the background, continues with the screen off, shows progress (percentage and estimated remaining time) in the app and in a notification, and can be cancelled; after an app kill it can be restarted or resumed without corrupted partial results. The result is written into the meeting folder: transcript.json (segments with start and end on the audio timeline, text, model and language, an empty speaker field), transcript.md (timestamps, photos embedded as Markdown images at their timestamps), updated meeting.json (transcription metadata) and an updated CLAUDE.md. The detail screen shows the transcript, tapping a segment plays the audio from there, photos appear inline. A meeting can be re-transcribed with another model or language after confirmation. The meeting list shows the transcription status per meeting (none, in progress, done, failed). Out of scope: speaker detection, speaker names, editing the transcript text, cloud transcription, live transcription during recording."

## Clarifications

### Session 2026-11-16

- Q: What should happen when the user starts a recording while a transcription is running? → A: The recording always starts immediately; the running transcription is stopped with its progress saved and is shown as failed (interrupted), so the user can resume it by hand later (no automatic pause or restart).
- Q: With automatic language detection, is the language detected once for the whole meeting or per segment? → A: Once per meeting; one language is stored for the whole transcript, and a mixed-language meeting can be re-transcribed with a fixed language.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Get a speech recognition model onto the device (Priority: P1)

The user opens the settings screen and finds a section for speech recognition models. It lists the models the app knows about, each with its name, download size, the languages it is suited for, a short description of its speed and quality trade-off, and its license. No model is installed at first. The user picks a model and starts the download; before it starts, the app states what will be downloaded, how large it is and from where. The download shows progress and can be cancelled. When it finishes, the app verifies the file against its published checksum before the model becomes usable. Alternatively, the user imports a model file that is already on the device (for example copied over USB). Installed models show their size and can be deleted to free space.

**Why this priority**: Without a model there is no transcription, and no model is bundled with the app. This is the first thing every user has to do, and it is the only part of the app that is allowed to use the network (and only on explicit user action).

**Independent Test**: With no model installed, open the settings, download the smallest model, watch progress, cancel one download and restart it, let one finish, and verify that the model is listed as installed. Then import a model from a local file, and delete both. Verify with the network switched off that importing and deleting still work.

**Acceptance Scenarios**:

1. **Given** the app was freshly installed, **When** the user opens the model section of the settings, **Then** the available models are listed with name, size, language suitability, speed/quality note and license, and none is marked as installed.
2. **Given** a model is listed, **When** the user taps download, **Then** a confirmation states the model name, the download size and the source before anything is downloaded, and nothing is downloaded unless the user confirms.
3. **Given** a download is running, **When** the user looks at the model list, **Then** the percentage and the amount downloaded are shown and update continuously, and a cancel action is available.
4. **Given** a download is running, **When** the user cancels it, **Then** the download stops, the partial data is removed or clearly marked as resumable, and the model is not usable.
5. **Given** a download has finished, **When** the checksum of the file does not match the published one, **Then** the file is discarded, the model is not usable, and the user sees an error that explains the failure and offers to retry.
6. **Given** a download has finished and the checksum matches, **When** the user views the list, **Then** the model is marked as installed and can be selected for transcription.
7. **Given** a model file is stored on the device, **When** the user imports it from a local file, **Then** it is checked, copied in and listed as installed without any network access; a file that is not a usable model is rejected with an explanation.
8. **Given** an installed model, **When** the user deletes it after confirming, **Then** its storage is freed and it disappears from the installed list.
9. **Given** a model is currently used by a running transcription, **When** the user tries to delete it, **Then** the app refuses and explains that the model is in use.
10. **Given** the device has less free space than a download or import needs, **When** the user starts it, **Then** the app tells the user how much space is missing and does not start.

---

### User Story 2 - Transcribe a meeting in the background (Priority: P1)

From the meeting list or from a meeting's detail screen, the user starts a transcription. The app asks which installed model to use and which language was spoken (automatic detection, German or English), preselected from the settings. After the user confirms, transcription runs on the device. The user can leave the app, turn the screen off and do other things. The app and a notification show the percentage done and the estimated remaining time. When it finishes, the meeting folder contains the transcript files and the user is notified.

**Why this priority**: This is the core value of Phase 2: turning a recorded meeting into text without any data leaving the device. Combined with Story 1 it is already a complete, usable transcription feature.

**Independent Test**: With a model installed and airplane mode on, record or pick a meeting of about five minutes with speech, start a transcription with the screen then turned off, wait for the completion notification, and verify that the meeting folder contains `transcript.json` and `transcript.md` with plausible text, and that `meeting.json` and `CLAUDE.md` describe the result.

**Acceptance Scenarios**:

1. **Given** a meeting without a transcript and at least one installed model, **When** the user chooses to transcribe it from the list or the detail screen, **Then** a dialog offers the installed models and the languages (automatic, German, English), preselected from the settings, and transcription starts after confirmation.
2. **Given** no model is installed, **When** the user tries to start a transcription, **Then** the app explains that a model is needed and leads the user to the model section of the settings instead of failing.
3. **Given** a transcription is running, **When** the user opens the app, **Then** the percentage done and the estimated remaining time are shown for that meeting and update at least every few seconds.
4. **Given** a transcription is running, **When** the user turns the screen off or switches to another app, **Then** it continues, and a notification shows the progress and offers to cancel it.
5. **Given** a transcription finishes, **When** the user returns to the app, **Then** the meeting shows as transcribed and a notification (if the user was away) said so.
6. **Given** a transcription finished, **When** the meeting folder is opened without the app, **Then** it contains `transcript.json`, `transcript.md`, an updated `meeting.json` and an updated `CLAUDE.md`, and the files are consistent with each other (see the requirements for their content).
7. **Given** a transcription is running, **When** the user starts a recording, **Then** the recording starts immediately as usual, the transcription is stopped with its progress saved, and the user is told that it was interrupted and can be resumed later.
8. **Given** a transcription is running, **When** the user tries to start a second transcription, **Then** the app declines and explains that only one transcription runs at a time.
9. **Given** a meeting whose audio contains no speech, **When** it is transcribed, **Then** the transcription completes with an empty segment list and the transcript says that no speech was detected; it is not reported as a failure. *(Owner decision 2026-11-18 after scenario S13 on the phone: this holds for audio the engine itself recognises as empty. With the voice-activity detector off (research R-7), real silence, quiet room noise and noise can still produce one or two invented sentences with the small models, so for such audio this is a best effort, not a guarantee.)*

---

### User Story 3 - Read the transcript and jump into the audio (Priority: P2)

On the meeting detail screen, the user sees the transcript as a list of segments with their timestamps. Photos taken during the meeting appear inline between the segments, at the positions where they were taken. Tapping a segment starts audio playback from that segment's start. Tapping a photo works as in Phase 1 (the photo opens, and playback can jump to its position).

**Why this priority**: The transcript only becomes useful in the app when it can be read next to the audio and the photos. It builds on Story 2 but is a separate screen with its own behavior.

**Independent Test**: Open a transcribed meeting that has photos, verify the order of segments and photos against the timestamps in the folder, tap three different segments and confirm that playback starts at each one's start time.

**Acceptance Scenarios**:

1. **Given** a transcribed meeting, **When** the user opens its detail screen, **Then** the transcript is shown as segments in time order, each with its start time.
2. **Given** a transcribed meeting with photos, **When** the user scrolls the transcript, **Then** each photo appears between the segments around its timeline position, and photos with equal positions keep their original order.
3. **Given** a transcript is shown, **When** the user taps a segment, **Then** audio playback starts at the start of that segment (at most one second off) and the playback position is visible.
4. **Given** playback is running, **When** the user taps another segment, **Then** playback jumps there without stopping first.
5. **Given** a meeting without a transcript, **When** the user opens its detail screen, **Then** no empty transcript area is shown; instead the screen offers to start a transcription (or shows its current state).
6. **Given** a very long transcript (hours of speech), **When** the user scrolls, **Then** scrolling stays smooth and the screen stays responsive.

---

### User Story 4 - Cancel, survive interruptions, resume (Priority: P2)

The user can cancel a running transcription from the app or from the notification at any time. If the app is killed by the system or the user, or the device restarts, no half-written transcript is left behind and the meeting is not harmed. Afterwards the meeting shows that the transcription was interrupted, and the user can resume it (continuing where it stopped, losing at most a small amount of work) or start it again from the beginning.

**Why this priority**: Transcribing a long meeting takes a long time on a phone, so interruptions will happen. Predictable cancel and resume behavior is what makes the feature trustworthy, in line with the project's "no data loss" principle.

**Independent Test**: Start transcribing a long meeting, force-stop the app halfway, reopen it, verify that the meeting is shown as interrupted/failed with a resume option and that no transcript files with partial content exist under their final names; resume and verify that the result is complete and identical in structure to an uninterrupted run. Separately, cancel a run and verify that it stops quickly and leaves the meeting as it was.

**Acceptance Scenarios**:

1. **Given** a transcription is running, **When** the user cancels it (in the app or from the notification), **Then** processing stops within a few seconds, the notification disappears, and the meeting returns to the state it had before (no transcript, or the previous transcript unchanged).
2. **Given** a transcription is running, **When** the app is force-stopped or the device restarts, **Then** no `transcript.json` or `transcript.md` with partial content exists afterwards, and `meeting.json` is still valid.
3. **Given** a transcription was interrupted, **When** the user opens the app, **Then** the meeting is shown as failed (interrupted) and offers "resume" and "start over".
4. **Given** an interrupted transcription, **When** the user chooses resume with the same model and language, **Then** processing continues from the last saved progress, repeating at most two minutes of audio, and the final result is complete.
5. **Given** an interrupted transcription, **When** the user chooses start over (optionally with a different model or language), **Then** processing starts from the beginning and discards the partial progress.
6. **Given** a transcription fails for another reason (for example the model file is damaged or storage is full), **When** it stops, **Then** the meeting is shown as failed with a short, understandable reason, any previous transcript stays intact, and the user can retry.
7. **Given** a model that an interrupted transcription used was deleted since, **When** the user chooses resume, **Then** the app explains that the model is gone and offers to start over with another model.
8. **Given** a transcription was stopped because the user started a recording, **When** the recording has ended, **Then** the meeting shows as failed (interrupted) with "resume" and "start over", and nothing resumes automatically.
9. **Given** a recording is in progress, **When** the user tries to start, resume or re-transcribe any transcription, **Then** the app declines and explains that transcription is not possible during a recording.

---

### User Story 5 - See the transcription status in the meeting list (Priority: P2)

Every meeting in the list shows its transcription status: none, in progress (with the percentage), done or failed. The status is read from the meeting folders, so it is also correct for meetings added from outside the app.

**Why this priority**: It lets the user see at a glance what has been transcribed and what needs attention, and it makes background work visible. It is small but touches every meeting.

**Independent Test**: With four meetings (never transcribed, running, finished, failed), open the list and verify each shows the correct status; copy a transcribed meeting folder in from outside and verify that it shows as done.

**Acceptance Scenarios**:

1. **Given** a meeting that was never transcribed, **When** the list is shown, **Then** it shows the status "none" (or no badge), together with a way to start a transcription.
2. **Given** a transcription is running, **When** the list is shown, **Then** that meeting shows "in progress" with its current percentage, updating live.
3. **Given** a finished transcription, **When** the list is shown, **Then** the meeting shows "done".
4. **Given** a failed or interrupted transcription, **When** the list is shown, **Then** the meeting shows "failed" and offers retry or resume.
5. **Given** a meeting folder with a transcript was added from outside the app, **When** the list is shown, **Then** its status is derived from the folder and shown correctly.
6. **Given** Phase 1 meetings that have no transcription information at all, **When** the list is shown, **Then** they show "none" and keep working exactly as before.

---

### User Story 6 - Re-transcribe with another model or language (Priority: P3)

If the result is poor (wrong language, a model that was too small), the user can transcribe the meeting again from its detail screen or from the list, choosing a different model or language. Because this replaces the existing transcript, the app asks for confirmation first. The old transcript stays untouched until the new one is complete.

**Why this priority**: It is a convenience and a quality safety net. The core feature works without it, and it reuses the flow from Story 2.

**Independent Test**: Transcribe a meeting with a small model, re-transcribe it with another model after confirming, cancel one such run midway and verify the old transcript is still intact, then let one finish and verify that the files now reflect the new model and language.

**Acceptance Scenarios**:

1. **Given** a meeting with a transcript, **When** the user chooses to transcribe again, **Then** the app asks for model and language and warns that the existing transcript will be replaced, and starts only after the user confirms.
2. **Given** a re-transcription is running, **When** the user opens the meeting, **Then** the old transcript is still shown and readable, together with the progress of the new run.
3. **Given** a re-transcription is cancelled or fails, **When** the user opens the meeting, **Then** the old transcript is unchanged and still shown as done.
4. **Given** a re-transcription finishes, **When** the user opens the meeting, **Then** the new transcript replaces the old one in all transcript files and in the transcription metadata, and no leftovers of the old transcript remain.

---

### Edge Cases

- **Recording started during a transcription**: the recording wins. The transcription stops with its progress saved, the user is notified, and the meeting shows as failed (interrupted) until the user resumes it after the recording has ended.
- **Meeting is still being recorded, or was recovered after a crash**: A meeting that is still recording cannot be transcribed. A recovered meeting can be transcribed like any other.
- **Audio is very long** (up to 6 hours stereo, the Phase 1 maximum): transcription must stay stable, show a realistic remaining-time estimate and survive interruptions through the resume mechanism.
- **Stereo recordings**: both channels contribute to the single transcript; segments do not yet say which channel (speaker) they came from.
- **Language does not suit the model** (for example an English-only model with German selected): the app warns before starting and does not offer unsuitable combinations as the default; the user may still proceed only if the combination is technically possible.
- **Automatic language detection**: the language is detected once for the whole meeting, and the detected language (not just "auto") is recorded in the transcript. A meeting that mixes languages is transcribed in the one language that was detected or chosen; the user can re-transcribe it with a fixed language.
- **Storage almost full**: the transcription stops cleanly with a clear error and never leaves a damaged transcript; the existing audio, photos and metadata are never put at risk.
- **Device restarts, battery saver, thermal throttling**: transcription may become slow or be interrupted; progress and remaining-time estimates adapt, and resume applies.
- **Notification permission denied**: transcription still runs and its progress stays visible in the app; the app explains that the notification could not be shown.
- **A meeting is deleted while it is being transcribed**: the app asks for confirmation, cancels the transcription and removes the folder.
- **A meeting is renamed while it is being transcribed**: allowed; the transcript is unaffected.
- **Folder changed from outside** (for example the user edited `transcript.md`): the app does not read `transcript.md` back; the transcript shown in the app comes from `transcript.json`, and `transcript.md` is regenerated whenever the transcript is replaced.
- **`transcript.json` missing or unreadable while `meeting.json` says "done"**: when the meeting is opened it is shown as failed with the option to transcribe again, never as an empty transcript. The meeting list only checks that the file exists, so it can still show "done" for such a meeting until it is opened.
- **Model import of the wrong kind of file**: rejected before it takes space, with an explanation.
- **Network lost during a download**: the download stops with an understandable message; the user can retry, and data already downloaded is reused where possible.
- **Photos with a position beyond the last segment, or in a meeting without any segment**: they appear after the last segment (or alone) in the transcript.

## Requirements *(mandatory)*

### Functional Requirements

#### Models and settings

- **FR-001**: The app MUST provide a settings section for speech recognition models, listing every model the app offers and every installed model. For each offered model it MUST show the name, the download size, the languages it is suitable for, a short speed/quality trade-off description and its license.
- **FR-002**: No model MUST be bundled with the app. A model MUST be obtained only by an explicit user action: either a download or an import from a local file.
- **FR-003**: Before a download starts, the app MUST show the model name, the download size and the source (host) of the download, and MUST start only after the user confirms. Nothing MUST be downloaded in the background or without such a confirmation.
- **FR-004**: A download MUST show progress (percentage and amount), MUST be cancellable, and MUST continue while the screen is off or the app is in the background, with a notification that shows progress and offers cancel.
- **FR-005**: After a download, the file MUST be verified against a published checksum before the model becomes usable. A file that fails verification MUST be discarded and the user informed.
- **FR-006**: Users MUST be able to import a model from a local file without network access. The app MUST check that the file is a usable model and reject anything else with an explanation. An imported model MUST be listed with a name and size like a downloaded one.
- **FR-007**: Before a download or import, the app MUST check that enough free storage is available and, if not, tell the user how much is missing.
- **FR-008**: Users MUST be able to delete an installed model after confirmation, to free its storage. A model used by a running transcription MUST NOT be deletable.
- **FR-009**: Every model the app offers for download MUST have a license that is compatible with free-software distribution (F-Droid), and the license MUST be visible to the user.
- **FR-010**: The settings MUST let the user choose a default spoken language (automatic detection, German or English) and a default model. These defaults MUST preselect the choices in the start dialog (FR-012).
- **FR-011**: The network MUST be used only for the explicit download of models described in FR-003. Transcription, import, deletion, playback and all other functions MUST work with no network access and MUST NOT transmit any data.

#### Starting and running a transcription

- **FR-012**: From the meeting list and from the meeting detail screen, users MUST be able to start a transcription. Before it starts, the user MUST choose an installed model and the spoken language (automatic detection, German, English), preselected from the settings.
- **FR-013**: If no model is installed, the app MUST lead the user to the model settings instead of starting.
- **FR-014**: The app MUST warn when the chosen model is not suitable for the chosen language.
- **FR-015**: Transcription MUST run entirely on the device and MUST continue when the screen is off or the user switches to another app.
- **FR-016**: While transcribing, the app MUST show the percentage done and the estimated remaining time for the meeting, and the same information MUST appear in a notification that offers a cancel action. Progress MUST be refreshed at least every 5 seconds.
- **FR-017**: Only one transcription MUST run at a time; when the user tries to start a second one, the app MUST decline and explain why. A recording MUST always be able to start, even while a transcription is running: starting a recording MUST stop the running transcription within 5 seconds, keep its saved progress (FR-022), tell the user that it was interrupted, and leave it resumable by hand after the recording ends. While a recording is in progress, the app MUST NOT start, resume or restart any transcription and MUST explain why. A transcription MUST NOT be resumed automatically. While a recording is in progress the app likewise MUST NOT start a model download or import; starting a recording pauses a running download (it stays resumable) and cancels a running import. The recording path MUST NOT be slowed or put at risk by transcription.
- **FR-018**: A meeting that is still being recorded MUST NOT be transcribable. Recovered meetings MUST be transcribable.
- **FR-019**: When a transcription finishes, fails or is cancelled, the app MUST tell the user (in the app, and by notification when the app is not in the foreground).

#### Cancel, interruption and resume

- **FR-020**: Users MUST be able to cancel a running transcription from the app and from the notification. Cancelling MUST stop processing within 5 seconds and MUST leave the meeting in the state it had before the transcription started.
- **FR-021**: Transcript files under their final names (`transcript.json`, `transcript.md`) MUST only ever contain a complete transcript. Partial progress MUST be kept in the meeting folder, clearly marked as work in progress, and MUST be removed when the transcription completes or is discarded. A reader of the folder MUST never see a half-written transcript.
- **FR-022**: After the app is killed, the device restarts, the process dies or a recording was started (FR-017) during a transcription, the app MUST detect the unfinished transcription on the next start, show the meeting as failed (interrupted), and offer both "resume" and "start over". Resume MUST continue from saved progress, repeating at most two minutes of audio.
- **FR-023**: When a transcription fails (damaged model, storage full, unreadable audio), the app MUST show a short, understandable reason and offer retry. A failure MUST NOT damage the audio, the photos, an earlier transcript or `meeting.json`.

#### Result in the meeting folder

- **FR-024**: A finished transcription MUST write `transcript.json` into the meeting folder. It MUST contain a format version, the model used, the language requested and the single language actually used or detected for the whole meeting (as a standard language code), the sample rate of the audio timeline and a list of segments. Each segment MUST contain its start and end position on the audio timeline (as sample positions, consistent with `meeting.json`, plus the derived milliseconds), its text, and a speaker field that is present and empty. All keys MUST be in English.
- **FR-025**: A finished transcription MUST write `transcript.md`, a human-readable version of the transcript with the meeting title, a line stating model, language and date, and one entry per segment with its timestamp. Photos MUST be embedded as Markdown images (relative paths into `photos/`) at the position of their timeline position, in timeline order; photos at equal positions keep their order, and photos after the last segment appear at the end.
- **FR-026**: `meeting.json` MUST be updated with transcription metadata: the model, language, date of completion and processing duration of the committed transcript, and, while a run is in progress or after it failed, the run's state and, for a failure, its reason. The status shown to the user (none, in progress, done, failed) MUST be derivable from this metadata together with the folder content (FR-029); it is not stored as a separate field, because a stored status could disagree with the files. Meetings without this information MUST remain valid and be treated as "none"; the change MUST be backward compatible, so Phase 1 meetings and older versions of the app are not broken.
- **FR-027**: `CLAUDE.md` of the meeting folder MUST be updated to describe the transcript files and the transcription metadata so that a person or an AI assistant can use them without the app. The rest of its content MUST be preserved.
- **FR-028**: All time positions in the transcript (segment boundaries) MUST refer to the audio timeline, not to wall-clock time, and the conversion to display time MUST use the same single conversion as the rest of the app.
- **FR-029**: The transcript formats MUST be documented and versioned, and MUST use only open standard formats (JSON, Markdown). The meeting folder MUST remain the single source of truth: the status shown in the app MUST be derived from the folder.

#### Showing the transcript

- **FR-030**: The meeting detail screen MUST show the transcript of a transcribed meeting as a list of segments in time order with their start times, with the meeting's photos shown inline at their timeline positions.
- **FR-031**: Tapping a segment MUST start audio playback at the segment's start, or move playback there if it is already running.
- **FR-032**: For a meeting without a transcript, the detail screen MUST show no empty transcript area but offer to start a transcription or show its current state.

#### Re-transcription and status

- **FR-033**: Users MUST be able to re-transcribe a meeting with a different model or language. The app MUST ask for confirmation before it starts, stating that the existing transcript will be replaced.
- **FR-034**: During a re-transcription the old transcript MUST remain complete, readable and unchanged until the new one is complete. Only then MUST all transcript files and the metadata be replaced together. If the re-transcription is cancelled or fails, the old transcript MUST remain.
- **FR-035**: The meeting list MUST show for each meeting its transcription status (none, in progress with percentage, done, failed), derived from the meeting folders, including for meetings added from outside the app. The list derives the status from the names and metadata it already reads; whether a transcript file is readable is checked when the meeting is opened.

#### Cross-cutting

- **FR-036**: The only new permission that needs the user's attention is network access for the explicit model download (FR-003, FR-011); the app MUST explain why it is needed where the download is offered, and MUST keep working without it. The feature additionally needs install-time permissions that show no prompt and expose no data: permission to keep the device awake while a transcription or download runs with the screen off (FR-004, FR-015), and the permissions for the two kinds of background work (download and audio processing). Each MUST be justified in the plan and explained to the user in the settings, in a short permissions paragraph that also replaces the Phase 1 statement that the app has no network access. The notification permission already used for the recording indicator is reused.
- **FR-037**: All new user-facing text (including errors, notifications and the status labels) MUST be available in English (default) and German, following the device language, with no hard-coded text.
- **FR-038**: Model files MUST NOT be stored in meeting folders and MUST NOT become part of meeting data; deleting a meeting MUST NOT delete a model, and deleting a model MUST NOT affect any transcript.

### Key Entities *(include if feature involves data)*

- **Speech recognition model**: A downloadable or importable model file with a name, size, supported languages, a speed/quality description, a license, a published checksum (for downloaded models), and an installation state (not installed, downloading, installed). Lives on the device outside the meeting folders.
- **Transcription**: One run of turning a meeting's audio into text, with the chosen model, requested language and a state (none, in progress, done, failed), progress, and, once finished, the language detected or used, the completion date and the processing duration. Recorded in `meeting.json`.
- **Transcript**: The result of a finished transcription: model, languages, sample rate and an ordered list of segments, stored as `transcript.json` and rendered as `transcript.md`.
- **Transcript segment**: A stretch of speech with start and end positions on the audio timeline, its text and an empty speaker field (filled in a later phase).
- **Work-in-progress file**: The marked partial result of an unfinished transcription inside the meeting folder, used only to resume and removed when the transcription completes or is discarded.
- **Meeting** (existing): Gains transcription metadata and, once transcribed, the two transcript files.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: With the network switched off, 100% of transcription functions (start, progress, cancel, resume, read, re-transcribe, import, delete) work, and no data is sent over the network at any time except the confirmed model download.
- **SC-002**: A user with no model installed can download a model, start a transcription of a short meeting and read the result within 5 minutes of opening the settings (excluding download time), without help, in at least 9 of 10 first attempts.
- **SC-003**: With the fastest model the app offers, a meeting is transcribed in no more than its own duration on the project's reference phone (to be confirmed on the device during planning); the remaining-time estimate is within 25% of the actual remaining time from 10% progress onward.
- **SC-004**: A transcription continues with the screen off for the full length of a 1-hour meeting without being stopped by the system, and the percentage shown in the notification and in the app agree within 2 percentage points.
- **SC-005**: After a forced app termination in the middle of a transcription, 100% of test runs show no transcript file with partial content under its final name, and a resumed run produces a complete transcript while redoing at most two minutes of audio.
- **SC-006**: Cancelling a running transcription stops all processing within 5 seconds in 100% of test runs, and the meeting is left as it was before.
- **SC-007**: Tapping a segment starts playback within 1 second of that segment's start in 100% of test cases, and photos appear within one segment of their timeline position.
- **SC-008**: From a transcribed meeting folder alone, without the app, a person or AI assistant can correctly state the model, language, the text at a given time and the positions of all photos relative to the text in 100% of test cases.
- **SC-009**: A transcribed meeting's detail screen of a 3-hour meeting opens within 3 seconds, and scrolling stays smooth (no visible stutter) on the reference phone.
- **SC-010**: A download that fails or is damaged never leaves a usable but corrupt model: in 100% of test cases (cancelled, network lost, wrong checksum) the model is shown as not installed.
- **SC-011**: Re-transcribing never loses the previous transcript unless the new one completed: in 100% of cancelled, failed and killed re-transcription test runs, the old transcript is complete and unchanged afterwards.
- **SC-012**: Starting a recording while a transcription is running never takes longer than starting one otherwise (SC-001 of Phase 1 still holds), the transcription stops within 5 seconds, and in 100% of test runs the recording has no gaps or lost audio compared with a recording made without a transcription.

## Assumptions

- **Scope of this phase**: Speaker detection, speaker names, editing of the transcript text, cloud transcription and live transcription during recording are out of scope. The `speaker` field is written empty so that a later phase can fill it without changing the format.
- **Model catalog**: The app ships with a fixed list of known models (name, size, languages, license, source, checksum). Arbitrary download addresses are not supported; new models arrive with app updates, or by importing a file. Model sources and checksums are public and verifiable.
- **Imported models**: A model imported from a file is checked for being a usable model; if its checksum matches a known catalog entry it is recognized as that model, otherwise it is listed as an imported model under its file name.
- **Languages**: Phase 2 offers automatic detection, German and English, with one language per meeting. More languages can follow later without a format change, because the language is stored as a standard language code. Per-segment language is not part of this phase.
- **Default choices**: If no default model is set, the start dialog preselects the model used last, or the only installed model. The default language setting starts as "automatic".
- **One job at a time**: Transcription never runs together with a recording or with another transcription, to protect the recording (no data loss) and the device's capacity. A recording always takes precedence (FR-017). A queue of several transcriptions is not part of this phase.
- **Stereo audio**: Both channels are transcribed together into one text; per-channel results belong to the later speaker phase.
- **Network use**: The model download is the single exception to the app's no-network rule. This is the justification, in line with the constitution, for the network permission that Phase 1 deliberately did not have; the permission check in the build must be updated to allow exactly the permissions listed in FR-036, and nothing else. Metered-network detection is not included: the confirmation states the size and the user decides.
- **Storage of models**: Models are large (hundreds of megabytes to over a gigabyte), are stored on the device outside the meeting folders and are not exported with meetings.
- **Notifications**: The notification permission requested for the recording indicator is reused. Without it, transcription still runs and progress is visible in the app.
- **Performance target**: The SC-003 speed target is an expectation to confirm on the reference device (Gigaset E940, Android 14) when the model catalog is chosen in the plan; the catalog may have to exclude models that are too slow to be useful on that class of device.
- **Phase 1 compatibility**: Phase 1 meeting folders stay valid and unchanged until they are transcribed; transcription is additive and not needed for any Phase 1 function (Incremental Delivery).
