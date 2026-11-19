# Feature Specification: F-Droid Release

**Feature Branch**: `004-fdroid-release` (no git branch is created; work continues on `main`)

**Created**: 2026-11-19

**Status**: Draft

**Input**: User description: "Phase 4: publish Meeting Recorder on F-Droid. Prepare the repository and the fdroiddata submission: (1) author and copyright: Volker von Einem (GitHub voneinem, vve-f-droid@gmx.net) as author in README (done), the in-app About screen (EN and DE), the F-Droid recipe (AuthorName, AuthorEmail), and SPDX/copyright headers if sensible; (2) release readiness: versionCode/versionName policy, git tag v0.1.0 that F-Droid can build, release signing is left to F-Droid (no keystore in the repo), reproducible build check; (3) fastlane metadata complete: title, short and full description (EN, DE), changelogs/<versionCode>.txt, icon, phone screenshots, feature graphic optional; (4) the fdroiddata recipe app.meetingrecorder.yml based on specs/002-offline-transcription/fdroid-build-notes.md (submodules, ndk r29, rm: list, AutoUpdateMode, UpdateCheckMode Tags, Binaries optional), tested with `fdroid build` and `fdroid lint` locally; (5) the open inclusion question R-16 (model download from huggingface.co, anti-feature check such as NonFreeNet or none) answered before submission, with the import-only fallback decided; (6) the merge request to gitlab.com/fdroid/fdroiddata and a checklist for the review feedback. Out of scope: new app features, Play Store, own F-Droid repository."

## Overview

Meeting Recorder is feature-complete for Phases 1 to 3 (recording, photos, on-device transcription, the
Fossify-inspired look). This phase makes it publishable on F-Droid, the free-software app store: the
repository gets everything F-Droid needs to find, build, describe and attribute the app, the build recipe
is written and proven locally, the one open policy question about the model download is answered, and the
submission to F-Droid's `fdroiddata` repository is prepared and handed over for the owner to send.

The phase changes no recording, transcription, file-format or permission behavior. The only change a user
of the app can see is the author and copyright line on the About screen.

**What stays outside this repository**: the F-Droid recipe lives in F-Droid's own `fdroiddata` repository.
This repository keeps a reviewed copy of it as a contract, so the recipe and the project cannot drift apart
unnoticed.

**Steps only the owner can do** (they need the owner's GitLab account and decisions): creating the GitLab
account, forking `fdroiddata`, opening the merge request and answering the F-Droid maintainers' review.
This phase prepares each of them as far as possible and lists them explicitly.

## Clarifications

### Session 2026-11-19

- Q: Should we ask the F-Droid maintainers about the model download before sending the merge request, or send the merge request first and raise the question there? → A: Send the merge request directly and put the question and the decision record in its description (no separate inclusion issue first).
- Q: Should the author and license line be added as a header to every source file, or only to the files that already have one? → A: No new headers (no project source file has one today); attribution stays in README, About screen, recipe and `LICENSE`.
- Q: Which processor types should the F-Droid release APK contain: both `arm64-v8a` and `x86_64` as built today, or `arm64-v8a` only? → A: One APK with both ABIs, as built today (about 39 MB); no build change and one version code.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A reviewer can build the app from source with the recipe (Priority: P1)

An F-Droid maintainer (or the owner, simulating one) takes the recipe, checks out the tagged release and
builds the app on a clean machine with F-Droid's own tooling. The build needs no network access beyond
fetching the declared sources and dependencies, contains no prebuilt binary, passes the recipe linter and
produces an installable app whose version matches the tag.

**Why this priority**: Without a recipe that builds, nothing else matters; F-Droid publishes only what it
builds itself from source. This is the minimum for a submission.

**Independent Test**: In a clean checkout of the tag (with its submodule) run F-Droid's recipe linter and
its local build for `app.meetingrecorder`. Both finish without errors, and the resulting app installs and
starts on a device or emulator.

**Acceptance Scenarios**:

1. **Given** the recipe and the tag `v0.1.0`, **When** the recipe linter runs, **Then** it reports no
   errors and no warnings that the maintainers treat as blocking.
2. **Given** a clean machine that has only F-Droid's build tooling and the Android toolchain the recipe
   names, **When** the local F-Droid build runs for the tag, **Then** it finishes successfully and the
   built app reports the version recorded in the tag.
3. **Given** the build of that tag, **When** F-Droid's scanner inspects the sources and the result,
   **Then** it finds no prebuilt binary, no proprietary dependency and no tracker.
4. **Given** the same tag built twice in two different directories, **When** the native libraries are
   compared, **Then** they are byte-identical (the reproducibility check already established in Phase 2
   still holds for the release tag).

---

### User Story 2 - The store page is complete and honest (Priority: P1)

A person browsing F-Droid finds Meeting Recorder with a title, a one-line summary and a full description
in English and German, a launcher icon, phone screenshots, and a changelog for the current version. The
text says plainly what the app does, that everything stays on the phone, that the only network use is the
user-started model download, that meetings are stored unencrypted, and that people who are recorded must
consent.

**Why this priority**: The store page is what a person reads before installing, and F-Droid reviewers
check that the description matches the app's behavior. Missing or misleading text blocks or delays the
merge.

**Independent Test**: Read the store metadata folder as F-Droid would render it: every listed element
exists for both languages, respects F-Droid's length limits, and each claim in the text can be traced to a
behavior or a documented decision of the app.

**Acceptance Scenarios**:

1. **Given** the store metadata, **When** it is checked, **Then** a title, a short description and a full
   description exist in English and in German, within F-Droid's length limits.
2. **Given** the current version code, **When** the changelog folder is checked, **Then** a changelog file
   named after that version code exists and describes the release in plain language.
3. **Given** the store metadata, **When** the screenshots are checked, **Then** at least four phone
   screenshots of real screens exist (meeting list, recording, playback with transcript, model
   settings), taken from the release build in the light appearance, plus a launcher icon.
4. **Given** the full description, **When** each statement about privacy, permissions and network use is
   compared with the app and `README.md`, **Then** none contradicts them.

---

### User Story 3 - The inclusion question about the model download is answered (Priority: P1)

Before submitting, the project knows how F-Droid treats an app that downloads speech-recognition model
files from a third-party host (`huggingface.co`), and has decided what to ship. The answer comes from
F-Droid's published inclusion policy and anti-feature list and, if that is not conclusive, from a short,
precise question to the F-Droid maintainers. The decision is recorded, together with the fallback
(import-only) in case the download is not acceptable.

**Why this priority**: This is the one open risk that can make F-Droid reject the app or require an
anti-feature label. It must be settled before the merge request, not discovered in its review.

**Independent Test**: Open the decision record. It states the policy text it relies on, which anti-feature
label (if any) the recipe declares and why, what the user sees in the store as a consequence, and the
exact change needed to switch to the import-only fallback.

**Acceptance Scenarios**:

1. **Given** the open question R-16, **When** the decision record is read, **Then** it names the F-Droid
   policy sources consulted (with the date) and states one of: no anti-feature needed, a specific
   anti-feature declared, or fallback to import-only.
2. **Given** the decision "declare an anti-feature", **When** the recipe is read, **Then** it carries that
   label and the store description explains it in plain words.
3. **Given** the decision "import-only fallback", **When** the switch is prepared, **Then** the record
   lists the exact changes (permission, network configuration, download code, strings, store text) and the
   build gates that must still pass, and the release is not blocked by the fallback.
4. **Given** the decision, **When** the privacy statements in the store text, `README.md` and the app's
   settings are compared, **Then** all describe the same network behavior.

---

### User Story 4 - The author and copyright are shown everywhere they should be (Priority: P2)

The author, Volker von Einem, appears in the About screen of the app (English and German), in the
repository's `README.md` and in the F-Droid recipe (author name and author email). The author email used publicly is `vve-f-droid@gmx.net`.

**Why this priority**: Attribution is a stated requirement and F-Droid shows the author on the app's
page, but it does not block a build.

**Independent Test**: Open the About screen in English and in German and find the author and copyright
line; grep the repository and the recipe for the author name and email and find them in each place the
requirement lists.

**Acceptance Scenarios**:

1. **Given** the app in English, **When** the person opens Settings and looks at the About section,
   **Then** it shows the version, the license and a line "Copyright © 2026 Volker von Einem".
2. **Given** the app in German, **When** the same screen is opened, **Then** the same information appears
   in German wording, with the name unchanged.
3. **Given** the recipe, **When** it is read, **Then** the author name is "Volker von Einem" and the
   author email is `vve-f-droid@gmx.net`.
4. **Given** the repository, **When** it is searched for the author's private mail address used in git
   commits, **Then** it is not copied into any file (only the F-Droid address is published).

---

### User Story 5 - The release is tagged and updates are detected automatically (Priority: P2)

The release version is recorded in one agreed place, the tag `v0.1.0` points at the exact commit that
F-Droid builds, and the recipe finds future releases by their tags without manual editing. The release
signing key stays with F-Droid; the repository holds no keystore and no secret.

**Why this priority**: A stable, buildable tag is required by the recipe, but the tag can be created
only after the preceding stories are done, so it follows them.

**Independent Test**: Check out the tag in a clean directory: the version name and code in the build
equal the tag and the recipe's current version entry; the repository contains no signing key or password.

**Acceptance Scenarios**:

1. **Given** the version policy, **When** a new release is prepared, **Then** the policy states how the
   version code and version name are chosen and bumped, and the tag name derives from the version name.
2. **Given** the tag `v0.1.0`, **When** the recipe's update check reads the tag, **Then** it finds the
   same version name and version code as the build.
3. **Given** the repository, **When** it is scanned for signing material (keystore files, passwords,
   keys), **Then** none is found and the release build type does not require any.
4. **Given** a release build that F-Droid has signed with its own key, **When** it is installed on a
   device that has the debug build, **Then** the installer refuses the update because the signatures
   differ, and the submission package notes that an uninstall is needed first (debug builds are not a
   supported upgrade path).

---

### User Story 6 - The merge request is ready to send and the review is manageable (Priority: P2)

The owner has a ready-to-send submission: the recipe file, the title and body for the merge request, the
list of exact commands (fork, branch, add the file, lint, build, push, open the merge request), and a
checklist of the typical review comments with the prepared answer or fix for each. After the owner
sends it, replies from the maintainers can be handled from this checklist.

**Why this priority**: The merge request is the last step and depends on all other stories. Preparing it
removes the risk of a rejected or stalled first submission.

**Independent Test**: A person who has never submitted to F-Droid can follow the instructions from a
clean state to an opened merge request without any other source, and every check the instructions ask for
passes locally first.

**Acceptance Scenarios**:

1. **Given** the submission package, **When** the owner follows its steps, **Then** each command is
   given in full, in order, with the expected result, and ends at an opened merge request.
2. **Given** the merge request description, **When** it is read, **Then** it names the app, the license,
   the source repository, the tag, the build requirements (submodule, NDK, `rm:` list) and the decision
   on the model download.
3. **Given** the review checklist, **When** a maintainer asks about binaries, permissions, network use,
   anti-features, reproducibility or the license, **Then** the checklist has a prepared, factual answer
   with the place in the repository that proves it.

---

### Edge Cases

- The submodule's upstream tag or commit moves or disappears: the recipe fetches the pinned commit, and
  the pin check still fails the build if the checkout differs.
- The upstream engine adds a prebuilt binary outside the directories the recipe removes: the existing
  gate fails before the tag is made, and the recipe's `rm:` list is updated together with it.
- F-Droid's build server uses a different NDK or CMake patch version than the project pins: the recipe
  names the exact toolchain; if the server cannot provide it, the record says which version it accepts
  and whether the native libraries stay reproducible.
- The release APK is larger than a maintainer expects (about 39 MB because of the extracted native
  libraries of both architectures): the submission explains why (the engine's CPU variants must be
  extracted), and offers nothing that would change behavior. If the maintainers ask for a smaller
  package, splitting by architecture is a later release decision, not part of this phase.
- The only supported ABIs are `arm64-v8a` and `x86_64`: the store text and the recipe say so, and a
  32-bit device is told that transcription is not available (existing behavior).
- F-Droid asks to remove or replace the model download: the import-only fallback is a prepared switch,
  not a new feature, and the app still records, plays back and (with an imported model) transcribes.
- A screenshot would show personal meeting content: screenshots are taken with demo data only.
- The recipe's author email is public in F-Droid's repository: only the dedicated F-Droid address is
  used there.

## Requirements *(mandatory)*

### Functional Requirements

**Attribution**

- **FR-001**: The About section of the settings screen MUST show the copyright line "Copyright © 2026
  Volker von Einem" in English and German, next to the version and the license.
- **FR-002**: `README.md` MUST name the author, the GitHub handle and the F-Droid address, and the
  recipe MUST carry the author name "Volker von Einem" and the author email `vve-f-droid@gmx.net`.
- **FR-003**: The repository MUST NOT publish any author address other than `vve-f-droid@gmx.net`.
- **FR-004**: No license or copyright headers are added to the project's source files (none of them
  carries one today). Attribution is given by `README.md`, the About screen, the recipe and the `LICENSE`
  file. Third-party files (the engine submodule) keep their own notices untouched.

**Release readiness**

- **FR-005**: The version policy MUST be written down: where the version name and the version code
  live, how each is derived or bumped for a release, and how the tag name follows from the version name.
- **FR-006**: A tag `v0.1.0` MUST exist for the release commit, and checking it out MUST build an app
  whose version name is `0.1.0` and whose version code equals the one in the recipe.
- **FR-007**: The repository MUST NOT contain a keystore, a signing password or any signing secret, and
  the release build MUST NOT require one; signing is done by F-Droid.
- **FR-008**: The release build of the tag MUST be reproducible for the native libraries (same output
  from two different build directories), and the result of this check MUST be recorded.
- **FR-009**: The complete existing gate (unit tests, lint, assemble debug and release, and the four
  verification tasks for forbidden dependencies, manifest permissions, prebuilt binaries and the engine
  pin) MUST be green on the tag.

**Store metadata**

- **FR-010**: The store metadata MUST contain, for English and German, a title, a short description and
  a full description within F-Droid's length limits, with no promotional claims that the app does not
  fulfill and no mention of non-free services as a feature.
- **FR-011**: A changelog file named after the current version code MUST exist and be in plain language,
  within F-Droid's size limit.
- **FR-012**: The store metadata MUST contain a launcher icon (a store-sized image) and at least four
  phone screenshots in the light appearance taken from the release build with demo data only; a feature
  graphic is optional.
- **FR-013**: Every statement in the store text about privacy, permissions, network use, storage and
  consent MUST agree with `README.md`, the app's settings text and the recorded decision on the model
  download.
- **FR-014**: The release MUST be a single APK that contains both supported architectures (`arm64-v8a`
  and `x86_64`), with one version code; the build configuration is not changed for this. The store text
  MUST state the supported architectures and that transcription is unavailable on devices without them.

**The recipe**

- **FR-015**: A recipe for `app.meetingrecorder` MUST exist as a reviewed copy in this repository and
  MUST be what is submitted. It MUST declare: license, source and issue-tracker locations, author name
  and email, categories, the tag-based version detection and automatic update mode, the submodule
  fetch, the exact Android toolchain (NDK, CMake) and the removal of the engine's non-source
  directories (examples, bindings, models, samples, tests, ci).
- **FR-016**: The recipe's removal list and toolchain MUST equal the ones enforced by the project's
  gates and the Phase 2 build notes; a check MUST fail when they differ.
- **FR-017**: The recipe MUST pass F-Droid's recipe linter and a local F-Droid build of the tag, and the
  exact commands and results (date, tool versions) MUST be recorded.
- **FR-018**: The recipe MUST declare only the anti-features that the recorded decision requires.

**The inclusion question (R-16)**

- **FR-019**: Before the submission, a decision record MUST answer how F-Droid's policy treats the
  user-initiated model download from `huggingface.co`, citing the policy sources read and the date, and
  MUST state the chosen outcome: no anti-feature, a named anti-feature, or the import-only fallback.
- **FR-020**: The decision record MUST include a complete, ready switch description for the
  import-only fallback (what is removed, what stays, which gates and tests change, which text changes),
  so the fallback can be applied without new design work.
- **FR-021**: If the policy is not conclusive, the decision record MUST contain the exact question for
  the F-Droid maintainers, and the merge request description MUST state that question together with the
  chosen outcome and the prepared import-only fallback. No separate inclusion issue is opened first.

**The submission**

- **FR-022**: The submission package MUST contain the merge request title and description, the full
  command sequence from fork to opened merge request, the pre-submission checks, and a review checklist
  of typical maintainer comments with prepared answers and the file that proves each.
- **FR-023**: The package MUST list the owner-only steps explicitly, and nothing in this phase MUST
  perform an outward-facing action (creating an account, pushing to a fork, opening a merge request,
  publishing a tag to a remote) without the owner's explicit go-ahead at that moment.

**Unchanged behavior**

- **FR-024**: No recording, transcription, playback, file-format, permission, network or storage
  behavior changes in this phase. The only user-visible app change is FR-001, with its two strings.
- **FR-025**: Every new user-visible string MUST exist in English and German in the same change, and
  all existing unit tests, lint rules and build gates MUST remain green (constitution Principles II, VI,
  IX).

### Key Entities

- **Release**: a version of the app: version name, version code, the tag, the commit it points to, the
  changelog and the recipe's version entry. All five MUST agree.
- **Recipe**: the F-Droid build description of the app: identity, sources, author, categories, toolchain,
  removal list, version detection, anti-features. Lives in `fdroiddata`; a reviewed copy lives here.
- **Store metadata**: title, descriptions, changelog, icon and screenshots per language, read by F-Droid
  from the repository.
- **Decision record (R-16)**: the question, the policy sources, the outcome, the consequences and the
  prepared fallback.
- **Submission package**: merge request text, commands, pre-submission checks, review checklist, owner-only
  steps.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On a clean machine, the local F-Droid build of the tag finishes with exit status 0 and the
  recipe linter reports 0 errors; the commands, tool versions and date are recorded.
- **SC-002**: Zero prebuilt binaries, zero proprietary dependencies and zero trackers are reported for the
  tag by F-Droid's scanner and by the project's own gates.
- **SC-003**: Building the tag in two different directories yields byte-identical native libraries (all
  of them, not a sample).
- **SC-004**: 100% of the store elements in FR-010 to FR-012 exist for English and German (screenshots
  and icon are shared), and 0 statements in them contradict the app, `README.md` or the decision record.
- **SC-005**: The author name appears in 4 places (About screen in both languages, `README.md`, recipe)
  and the author email in 2 (`README.md`, recipe); no other personal address appears anywhere in the
  repository.
- **SC-006**: The R-16 decision record exists and is complete before the submission package is declared
  ready; applying the import-only fallback is described in steps that need no new design decisions.
- **SC-007**: A person who has not submitted to F-Droid before can reach an opened merge request by
  following only the submission package, in a single sitting without outside research.
- **SC-008**: The complete existing gate is green on the tag, and the number of unit tests does not
  decrease.

## Assumptions

- The app identifier `app.meetingrecorder` and the license (GPL-3.0-or-later) are final for the first
  release; renaming either would be a separate decision.
- The first release is `0.1.0` with version code 1, as already set in the build; the policy chooses how
  later releases are numbered but this phase ships exactly one release.
- The tag is created locally; pushing it to the remote is an outward-facing step that waits for the
  owner's go-ahead (FR-023).
- F-Droid's tooling for the local build and lint can be installed on the development machine (or run in
  a container); the exact method is a planning decision.
- The model catalog's download addresses, sizes and checksums from Phase 2 are final for this release and
  were verified on the test phone over mobile data (Phase 2, scenario S11; the Wi-Fi run and the
  disabled-buttons check were skipped by the owner); this phase does not change the catalog.
- Screenshots come from an emulator or the test phone with demo data; the owner's real meetings are never
  shown.
- The phone-level checks that the owner skipped in Phase 2 stay recorded as skipped; this phase does not
  reopen them, but the store text does not claim anything they would have verified.
- The owner's GitLab account, the fork, the merge request and the reply to maintainers are owner-only
  steps; this phase delivers everything up to them.

## Out of Scope

- New app features, behavior or visual changes beyond the About line.
- Publishing on Google Play or any other store.
- Running an own F-Droid repository or an own update channel.
- Changing the model catalog, the network behavior or the permissions (unless the import-only fallback is
  chosen, in which case only the prepared switch applies).
- Translations beyond English and German.
