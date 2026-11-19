# Implementation Plan: F-Droid Release

**Branch**: `004-fdroid-release` (no git branch is created; work continues on `main`) | **Date**: 2026-11-19 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/004-fdroid-release/spec.md`, clarified on 2026-11-19 (merge request carries the R-16 question; no new license headers; one APK with both ABIs).

## Summary

Make Meeting Recorder publishable on F-Droid without changing how the app behaves. The work has five parts:

1. **Attribution** (US4): one new About line in the app (two strings) and the author in the recipe.
2. **Release readiness** (US5): a written version policy, the tag `v0.1.0`, no signing material, a repeated reproducibility check on the tag.
3. **Store metadata** (US2): finish the existing `fastlane/` folder (changelog, icon, four phone screenshots) and correct the texts where the spec requires it (architectures, model download).
4. **The recipe** (US1): a reviewed copy of `metadata/app.meetingrecorder.yml` under `contracts/`, proven with F-Droid's own tools in F-Droid's own build image (Debian trixie, JDK 21) before anything is sent.
5. **The decision and the submission** (US3, US6): the R-16 decision record with a ready import-only fallback, and a submission package (merge request text, exact commands, review checklist).

One new automated gate, `verifyReleaseMetadata`, keeps the recipe copy, the store metadata, the version and the author data consistent with the build, so the recipe cannot drift from the project (FR-016). Everything outward-facing (GitLab account, fork, pushing the tag, the merge request) stays with the owner (FR-023).

## Technical Context

**Language/Version**: Kotlin 2.4 (existing app), Gradle 9.9.1 with Kotlin DSL (existing); the new gate is Gradle Kotlin DSL; helper scripts are POSIX `sh`. JDK 21 (also what F-Droid's build image installs).

**Primary Dependencies**: no new app dependency and no change to `gradle/verification-metadata.xml`. Tools used only for verification, never shipped: `fdroidserver` (from the `registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie` image, the image that `fdroiddata`'s CI uses for `fdroid build`), Docker 29, an Android emulator or the test phone for screenshots.

**Storage**: files only. Store metadata under `fastlane/metadata/android/<locale>/` (the layout F-Droid reads from the source repository); the recipe copy and the decision and submission documents under `specs/004-fdroid-release/`.

**Testing**: the existing gate (`:core:test :app:check :app:assembleDebug :app:assembleRelease` and the four `verify*` tasks) plus the new `verifyReleaseMetadata`, F-Droid's `fdroid readmeta`, `rewritemeta`, `lint`, `checkupdates` and `build` in the container, a double release build for reproducibility, and `adb`/emulator for screenshots and the About line.

**Target Platform**: Android 11+ (minSdk 30), ABIs `arm64-v8a` and `x86_64`, one APK (clarification 3).

**Project Type**: mobile-app (Android) with a release/distribution feature; no new module.

**Performance Goals**: none new. The release APK stays about 39 MB (existing measurement).

**Constraints**: F-Droid limits: title 50, short description 80, full description 4000 characters, changelog 500 characters; the recipe must be FLOSS-clean (no binary, no proprietary dependency, no tracker); no signing key in the repository; no outward-facing action without the owner's go-ahead.

**Scale/Scope**: one app, one release (`0.1.0`, version code 1), two languages, four to six screenshots, one recipe.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design: unchanged, still passing.*

| # | Principle | Verdict | Evidence |
|---|-----------|---------|----------|
| I | Offline and privacy first | Pass | No network behavior changes. The model download keeps its confirmation. The store text and README must describe it identically (FR-013); `NetworkSourceScannerTest` and the network security config stay as they are. |
| II | F-Droid compatibility | Pass, and this phase is its proof | No new dependency; the existing gates run on the tag; the recipe's `rm:` list and toolchain are checked against the project by `verifyReleaseMetadata`; reproducibility is re-measured on the tag (SC-003). |
| III | No data loss | Pass | Nothing on the recording path is touched. |
| IV | Open file format | Pass | No format change. |
| V | Single timeline | Pass | Not involved. |
| VI | Minimal permissions | Pass | No permission change (FR-024). `verifyManifestPermissions` runs on the tag. The import-only fallback would remove `INTERNET`, which only reduces permissions. |
| VII | Testability | Pass | No format logic is added. The About line is covered by the existing string-parity and hard-coded-string tests. The new gate is a Gradle verification task like the four existing ones; its failure paths are exercised by the negative checks in the quickstart (Q2). |
| VIII | Incremental delivery | Pass | The phase ends with an installable, submitted release. Each user story is independently checkable (US1 recipe build, US2 metadata, US3 decision, US4 attribution, US5 tag, US6 package). |
| IX | English everywhere, localized UI | Pass | The only new UI string (`settings_copyright`) goes into `values/` and `values-de/` in the same change. All specs, scripts and documents are English; the German store text is a store locale, not a UI string. |

No violations; Complexity Tracking is empty.

## Project Structure

### Documentation (this feature)

```text
specs/004-fdroid-release/
├── plan.md                         # This file
├── research.md                     # Phase 0: decisions R-1 .. R-11
├── data-model.md                   # Phase 1: Release, Recipe, StoreMetadata, DecisionRecord, SubmissionPackage
├── quickstart.md                   # Phase 1: validation scenarios Q1 .. Q9
├── contracts/
│   ├── app.meetingrecorder.yml     # reviewed copy of the recipe (what is submitted)
│   ├── store-metadata.md           # layout, limits and content rules of fastlane/
│   └── release-policy.md           # version name, version code, tag, signing
├── checklists/requirements.md
└── tasks.md                        # Phase 2 (/speckit-tasks, not created here)
```

Documents produced during implementation, in the same folder: `r16-decision.md` (the decision record, FR-019 to FR-021), `submission.md` (merge request text, commands, review checklist, owner-only steps; FR-022, FR-023), `build-results.md` (commands, tool versions, dates and outputs of the local verification, the reproducibility check and the screenshot capture; SC-001 to SC-003).

### Source Code (repository root)

```text
app/src/main/res/values/strings.xml          # + settings_copyright
app/src/main/res/values-de/strings.xml       # + settings_copyright
app/src/main/kotlin/app/meetingrecorder/ui/settings/SettingsScreen.kt   # + one Text line in the About block
gradle/verification-tasks.gradle.kts         # + verifyReleaseMetadata, wired into `check`
fastlane/metadata/android/
├── en-US/  title.txt, short_description.txt, full_description.txt (updated), changelogs/1.txt, images/icon.png, images/phoneScreenshots/1..N.png
└── de-DE/  title.txt, short_description.txt, full_description.txt (updated), changelogs/1.txt
README.md                                    # author line exists; add release and F-Droid notes
CLAUDE.md                                    # add specs/004 to the folder list, the new gate to the build commands
scripts/fdroid/                              # run-in-container helper scripts (sh)
specs/002-offline-transcription/fdroid-build-notes.md   # R-16 marked resolved, pointer to r16-decision.md
```

**Structure Decision**: no new Gradle module and no new source set. The release work is files plus one Gradle verification task, placed with the other four verification tasks. The recipe itself lives in F-Droid's `fdroiddata` repository; this repository holds a reviewed copy under `specs/004-fdroid-release/contracts/` as the single place that is checked against the build. `scripts/fdroid/` holds the container helpers so the local verification can be repeated by anyone with Docker.

## Phases

### Phase 0: Research

Resolved in [research.md](research.md): the anti-feature question (R-1), recipe format (R-2), the F-Droid build environment (R-3), version policy and automatic update detection (R-4), store metadata limits and layout (R-5), signing and reproducibility (R-6), local verification method (R-7), the new gate (R-8), screenshot and icon production (R-9, R-10), the About line (R-11). One fact cannot be settled from documents and is settled by an experiment in the implementation phase (Q4 in the quickstart): whether F-Droid's build image provides NDK `29.0.14206865` and CMake `3.31.6`. The plan prepares a fallback recipe line for that case.

### Phase 1: Design

[data-model.md](data-model.md), the three contracts and [quickstart.md](quickstart.md). The recipe copy is a complete draft; its `commit:` value is filled when the tag exists, and that fill-in is the only edit that follows the tag (a separate documentation commit, because the recipe copy is not part of what F-Droid builds).

### Implementation order (input for `/speckit-tasks`)

1. Attribution in the app: the string pair, the About line, a check of both languages (US4).
2. Store metadata: texts, changelog, icon, screenshots (US2); `verifyReleaseMetadata` for metadata, author data and signing material (FR-007, FR-010 to FR-014).
3. The decision record and the fallback description (US3).
4. The recipe copy and the recipe part of the gate; local `fdroid` verification against a local repository path (US1).
5. README and `CLAUDE.md` updates first (so the tag contains them), then the full existing gate, the release commit and the local tag `v0.1.0`; the complete gate again in a clean clone of the tag; double build for reproducibility on the tag; fill the commit into the recipe copy (US5, SC-003, FR-009).
6. The submission package and its dry-read (US6, SC-007); the final dry run against GitHub waits for the owner to push the tag.
7. Evidence table and honesty: `sc-results.md`, `fdroid-build-notes.md`, `tasks.md` ticks only where verified.

Owner-only steps (kept explicit, FR-023): push the tag to GitHub, create the GitLab account, fork `fdroiddata`, push the branch, open the merge request, answer the review.

## Complexity Tracking

No constitution violations to justify.
