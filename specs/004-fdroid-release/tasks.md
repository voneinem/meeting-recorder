---

description: "Task list for the F-Droid Release (Phase 4)"
---

# Tasks: F-Droid Release (Phase 4)

**Input**: Design documents from `/specs/004-fdroid-release/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ (`app.meetingrecorder.yml`, `store-metadata.md`, `release-policy.md`), quickstart.md

**Tests**: The spec asks for no new unit tests: this phase adds no logic of the app, and the one new UI line is covered by the existing string-parity and hard-coded-string tests. Verification is the new Gradle gate `verifyReleaseMetadata` (Phase 2, extended in later phases), its negative checks (quickstart Q2) and the scenarios Q1 to Q9 of quickstart.md.

**Organization**: Tasks are grouped by user story (US1 to US6, as numbered in spec.md). The gate is built once in Phase 2 and extended story by story. The stories are independent except where a task says "depends on": US1 needs only a scratch commit to build, US5 (the tag) comes after the stories whose files it freezes, and US6 comes last because the package quotes every result.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1 to US6, as numbered in spec.md
- Paths follow plan.md. `PKG` means `app/meetingrecorder`, so `app/src/main/kotlin/PKG/ui/settings/` is `app/src/main/kotlin/app/meetingrecorder/ui/settings/`. `RES` means `app/src/main/res/`. `FASTLANE` means `fastlane/metadata/android/`. `SPEC` means `specs/004-fdroid-release/`. `SCRATCH` means `~/fdroid-work`, a directory outside the repository that is never committed.
- **(device)** marks a task that needs an emulator or the phone, **(owner)** one that only the project owner can do or must approve (an outward-facing step, or a commit: commit only when the owner asks, FR-023). Do not tick a device or owner task before it happened; write the result, date and any skipped part next to it.
- **Rules that apply to every task**: no change to recording, transcription, playback, file-format, permission, network or storage behavior (FR-024); no new dependency (`gradle/verification-metadata.xml` changes only by the build-time entries that a clean checkout needs, `T048`, reviewed in the diff); no outward-facing action (account, push of a branch or tag, merge request) without the owner's explicit go-ahead at that moment (FR-023); English in code, comments, scripts and documents (the German store texts and the German UI string are the only German); the only public author address is `vve-f-droid@gmx.net` and the address used in git commits is never written into a file, neither in code nor in specs, scripts or documents (FR-003); refer to it as "the git commit address".
- Use `export JAVA_HOME=~/Android/jdk-21 ANDROID_HOME=~/Android/Sdk` for every Gradle call.
- Record every verification (command, tool and image versions, date, result) in `SPEC/build-results.md` (created in `T002`).

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: The tools and the baseline everything else is measured against.

- [X] T001 Pull the F-Droid images and create the scratch area: `docker pull registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie` and `docker pull debian:trixie-slim`, create `~/fdroid-work` (outside the repository, never committed), record the image digests and sizes; do not touch other running containers (research R-7; a `gotify` container runs on this machine)
- [X] T002 [P] Create `SPEC/build-results.md` with the required structure of data-model.md "Build record" (one section per verification: lint, readmeta, checkupdates, build, reproducibility, the existing gate, screenshot capture, open items), and record the **baseline** there: run `./gradlew :core:test :app:check :app:assembleDebug :app:assembleRelease` and the four verify tasks on the untouched tree and write down the number of unit tests of `:core` and `:app` (SC-008 compares against it)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The gate that every later story extends. Nothing else depends on the recipe or the store files existing yet, so the first version checks only what already exists.

**⚠️ CRITICAL**: US1 to US5 each add checks to this task; create it first.

- [X] T003 Add the task `verifyReleaseMetadata` (group `verification`, description "Fails when the recipe copy, the store metadata, the version or the author data disagree with the build.") to `gradle/verification-tasks.gradle.kts` next to the four existing tasks, wire it into `check` (`dependsOn(... , "verifyReleaseMetadata")`), plain file reading and regular expressions only (no YAML library, no new dependency), a helper per check collecting messages and one `GradleException` listing all findings; reuse the existing `skippedEngineDirectories` constant and read `versionName`, `versionCode` and `ndkVersion` from `app/build.gradle.kts` and `whisper/build.gradle.kts`. First version implements only research R-8 checks 3 and 4: **(3)** `git ls-files` contains no `*.jks`, `*.keystore` or `keystore.properties` anywhere; none of the **build files** (`build.gradle.kts` of `app`, `core` and `whisper`, the root `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`; never `gradle/verification-tasks.gradle.kts` itself, never `specs/`, `.specify/`, `.claude/` or the engine submodule) matches `storePassword|keyPassword|keyAlias`; and `app/build.gradle.kts` has no `signingConfigs` block and no `signingConfig =` line (a content scan of all tracked files would match the words in this specification and in the gate's own source); **(4)** no tracked file contains an address that is **not written in the gate**: the gate computes the forbidden addresses at run time as the distinct author addresses of `git log --format=%ae` minus `vve-f-droid@gmx.net` and minus any address ending in `noreply.github.com`, scans the tracked text files for them (excluding the engine submodule), and logs a warning instead of failing when `git` or the history is not available (a shallow clone still has one commit); the gate source and all specs therefore never contain the address (FR-003, FR-007)
- [X] T004 Run `./gradlew :app:verifyReleaseMetadata` (green) and the two negative checks of quickstart Q2 that apply now: `git add -f` a file `x.jks` then remove it again, and write the address printed by `git log -1 --format=%ae` (the git commit address, typed from the command output, never copied into a document) into a tracked build file then revert; additionally put the word `storePassword` into `app/build.gradle.kts` then revert, and confirm that the gate stays green with that word present in `SPEC/research.md`; each failure message must name the problem; record in `SPEC/build-results.md` without writing the address there (depends on `T003`)

**Checkpoint**: the gate exists, passes and fails where it should; later phases extend it.

---

## Phase 3: User Story 1 - A reviewer can build the app from source with the recipe (Priority: P1) 🎯 MVP

**Goal**: The recipe is reviewed, checked against the build, passes F-Droid's linter and builds in F-Droid's own image.

**Independent Test**: In the container, `fdroid readmeta`, `rewritemeta`, `lint`, `checkupdates` and `fdroid build -l app.meetingrecorder` finish without errors for the scratch recipe; the built APK installs and starts; the gate's recipe checks are green. Reproducibility on the tag is `T039` (US5) because it needs the tag.

- [X] T005 [P] [US1] Create the container helpers in `scripts/fdroid/`: `common.sh` (image names, the scratch path, a function that creates the scratch source repository: `git clone --recurse-submodules --local <repo>` into `SCRATCH/src`, applies the current uncommitted changes (`git diff HEAD` plus untracked files) and commits them there, so F-Droid can clone a real commit without a commit in this repository), `lint.sh` (runs `fdroid readmeta`, `fdroid rewritemeta app.meetingrecorder`, `fdroid lint app.meetingrecorder` in `debian:trixie-slim` with the `fdroidserver` package, as `fdroiddata`'s CI does), `build.sh` (runs `fdroid build -v -l app.meetingrecorder` in `buildserver-trixie`, installing `openjdk-21-jdk-headless` and selecting it with `update-alternatives`, the steps of `fdroiddata`'s CI job), `checkupdates.sh`; each script takes the recipe path as argument, prints the tool versions first, and never edits the real recipe (POSIX `sh`, `set -eu`, a usage line)
- [X] T006 [US1] Review `SPEC/contracts/app.meetingrecorder.yml` field by field against `specs/002-offline-transcription/fdroid-build-notes.md` and the current build (version `0.1.0`, code 1, `subdir: app`, `submodules: true`, the six `rm:` directories, `ndk: r29`, `Categories`, `UpdateCheckData` regex against the lines in `app/build.gradle.kts`); fix any difference in the copy; the copy keeps the placeholder `TAG_COMMIT_PLACEHOLDER` until `T038`
- [X] T007 [US1] Extend `verifyReleaseMetadata` with research R-8 check 1: the recipe copy `specs/004-fdroid-release/contracts/app.meetingrecorder.yml` must contain `versionName` and `versionCode` equal to `app/build.gradle.kts`, `CurrentVersion` and `CurrentVersionCode` equal to them, an `rm:` list that equals `skippedEngineDirectories` (each prefixed with `whisper/src/main/cpp/whisper.cpp/`, no more, no fewer), `ndk: r29` consistent with `ndkVersion = "29.0.14206865"` (the `r29` major equals `29`), and, when the recipe contains an `sdkmanager` line (the fallback of `T011`), the NDK and CMake versions in it equal `ndkVersion` of `whisper/build.gradle.kts` and its `cmake { version = "3.31.6" }` (FR-016 names the toolchain), `submodules: true`, `License: GPL-3.0-or-later`, `AuthorName: Volker von Einem`, `AuthorEmail: vve-f-droid@gmx.net`, and a `commit:` that is either the placeholder or 40 hex digits; the failure messages name the field (depends on `T003`, `T006`)
- [X] T008 [US1] Negative checks for check 1 (quickstart Q2): change the recipe copy's `versionCode` to `2`, remove one `rm:` line, change `ndk`; each must fail `:app:verifyReleaseMetadata` with a message that names it; undo each; record (depends on `T007`)
- [X] T009 [US1] Run quickstart Q3 with `scripts/fdroid/lint.sh` on a scratch copy of the recipe in `SCRATCH` (clone `fdroiddata` there, copy the recipe to `metadata/app.meetingrecorder.yml`): `fdroid readmeta` passes, `fdroid rewritemeta` leaves no diff (or the rewritten form is taken back into the copy), `fdroid lint` reports no error and no unexplained warning; the `Categories` value is accepted (if not, correct it in the copy); record the output and tool versions (depends on `T005`, `T006`)
- [X] T010 [US1] (device) Run quickstart Q4 with `scripts/fdroid/build.sh`: scratch recipe whose `Repo:` is the local scratch source repository and whose `commit` is the scratch commit; expected exit status 0, an unsigned release APK of version `0.1.0` / code 1, a scanner report with no binary, no proprietary dependency, no tracker (SC-001, SC-002); install it on the emulator `mr-api35` after signing with a throwaway debug key and check that the app opens and starts a recording; record the full command lines and the result (depends on `T005`, `T006`)
- [X] T011 [US1] Settle research R-3, the open point of Q4: if the build in `T010` fails or warns because NDK `29.0.14206865` or CMake `3.31.6` is missing or `subdir: app` does not find the root wrapper, change the recipe copy accordingly (`sudo:` or `prebuild:` with `sdkmanager --install "ndk;29.0.14206865" "cmake;3.31.6"`, or the `subdir`/`gradle` form that works), repeat `T009` and `T010`, extend `T007` if a new field becomes part of the contract, and record in `SPEC/build-results.md` exactly which lines were needed; if the build server cannot provide a needed tool at all, write the question for the maintainers into `SPEC/build-results.md` section "Open items" as a pending item (`T039` carries it into `SPEC/submission.md`, which does not exist yet) (depends on `T010`)
- [X] T012 [US1] Run quickstart Q5 with `scripts/fdroid/checkupdates.sh`: `fdroid checkupdates app.meetingrecorder` finds version `0.1.0` / code 1 and leaves `CurrentVersion` and `CurrentVersionCode` unchanged; then make a throwaway commit in the **scratch** source repository only (`versionCode = 2`, `versionName = "0.1.1"`, tag `v0.1.1`), confirm that `checkupdates` reports it, and delete the throwaway commit and tag; record the result (depends on `T005`, `T006`)

- [X] T048 [US1] Fix the finding of `T010`: in F-Droid's image (and in any fresh Gradle home) `gradle help` fails with "Dependency verification failed for configuration 'classpath'", 5 artifacts (`bc-jdk18on-bom-1.80.2.module`, `guava-parent-33.4.0-jre.pom`, `junit-bom-5.10.2.module`, `junit-bom-5.11.0-M2.module`, `kotlinx-coroutines-bom-1.8.0.pom`) that a warm Gradle cache hides because they were verified earlier; regenerate `gradle/verification-metadata.xml` in a **fresh `GRADLE_USER_HOME`** with `./gradlew --write-verification-metadata sha256 :core:test :app:check :app:assembleRelease :whisper:assembleDebug` (CLAUDE.md procedure), review the whole diff (only `Generated by Gradle` build-time artifacts of existing dependencies: parent poms, BOMs, `.module` files; no new library), prove it with a second fresh home (`./gradlew help` and `:app:assembleRelease` succeed), and record the cause, the diff size and the proof in `SPEC/build-results.md`; run `T010` again afterwards (depends on `T010`)

**Checkpoint**: the recipe is proven in F-Droid's own image against a scratch commit; the final dry run against the tag and GitHub follows in US5 and US6.

---

## Phase 4: User Story 2 - The store page is complete and honest (Priority: P1)

**Goal**: Both locales have complete store text, a changelog, an icon and at least four demo screenshots, and every statement is true.

**Independent Test**: `:app:verifyReleaseMetadata` passes its store checks; quickstart Q8 (descriptions agree with README, four screenshots in contract order, lint shows no metadata warning).

- [X] T013 [P] [US2] Update `FASTLANE/en-US/full_description.txt` per `contracts/store-metadata.md`: keep the existing statements on privacy, the model download only on tap, unencrypted storage and consent, add the supported architectures (`arm64-v8a` and `x86_64`) and that transcription is unavailable on devices without them (FR-014), and describe the model download in plain words (the source is `huggingface.co`, the file is named and sized before anything starts); **constraints quoted from data-model.md: `full_description.txt` at most 4000 characters; `short_description.txt` at most 80 characters, one line; `title.txt` at most 50 characters**; no claim the app does not fulfill
- [X] T014 [P] [US2] Update `FASTLANE/de-DE/full_description.txt` as the German translation of the same facts (the same statements, not different ones); same limits as `T013`
- [X] T015 [P] [US2] Create `FASTLANE/en-US/changelogs/1.txt` and `FASTLANE/de-DE/changelogs/1.txt`: a plain description of the first release (recording with photos on the timeline, transcription on the phone, open meeting folders); **constraint: at most 500 characters**; the file name is the version code `1` without padding
- [X] T016 [P] [US2] Create `FASTLANE/en-US/images/icon.png` (512 x 512 pixels, PNG, square) rendered from the adaptive launcher icon's own layers (`RES/drawable/ic_launcher_foreground.xml` over the `ic_launcher_background` color of `RES/values/`), so store and launcher match (research R-10); choose a render route that adds no dependency to the project (for example render the installed launcher icon on the emulator, or convert the vector to SVG with a script kept in `SCRATCH`), verify the size with `file` and look at the image
- [X] T017 [US2] (device) Prepare the demo data for the screenshots (research R-9): start the emulator `mr-api35`, set English and the light appearance (`adb shell cmd uimode night no`), build the release APK, sign it with a throwaway debug key and install it, choose a demo storage folder, record a short demo meeting from a synthetic or public-domain speech sample (check the origin and the license of the file under `spike/audio/` before using it; otherwise generate one) with two demo photos of a whiteboard-like picture, and transcribe it with the smallest model (import the model from `spike/models/` into the emulator, never download for this); **no real meeting, person or private content appears** (depends on `T010`)
- [X] T018 [US2] (device) Capture `FASTLANE/en-US/images/phoneScreenshots/1.png` to `4.png` with `adb exec-out screencap -p`, in the contract order: meeting list, running recording, playback with transcript and photos, model settings; optionally `5.png` for settings; check each image for personal data (status bar clock and notifications included) and delete the emulator demo data afterwards; record device, API level and date in `SPEC/build-results.md` (depends on `T017`)
- [X] T019 [US2] Extend `verifyReleaseMetadata` with research R-8 check 2: for `en-US` and `de-DE` the files `title.txt`, `short_description.txt`, `full_description.txt` and `changelogs/<versionCode>.txt` exist; **title at most 50, short description at most 80 characters and one line, full description at most 4000 characters, changelog at most 500 characters**; `en-US/images/icon.png` exists and is 512 x 512 (read the PNG header, no library); `en-US/images/phoneScreenshots/` holds at least four `N.png` files numbered from 1 without gaps; the full descriptions of both locales contain the architecture names `arm64-v8a` and `x86_64`; failure messages name the file (depends on `T003`)
- [X] T020 [US2] Negative checks for check 2 (quickstart Q2): delete `changelogs/1.txt`, make `short_description.txt` 81 characters long, remove a screenshot so three remain; each fails with a message that names it; undo each; record (depends on `T019`)
- [X] T021 [US2] Run quickstart Q8: `:app:verifyReleaseMetadata` green; read both full descriptions against `README.md` and the settings texts of `RES/values/strings.xml` for the three facts (nothing leaves the phone, download only on tap, unencrypted storage, consent) and the architecture sentence; open every screenshot; run `scripts/fdroid/lint.sh` again so metadata warnings would show; record the comparison (depends on `T013` to `T019`)

**Checkpoint**: the store page is complete in both languages with true content and demo screenshots.

---

## Phase 5: User Story 3 - The inclusion question about the model download is answered (Priority: P1)

**Goal**: The R-16 decision is recorded with its sources, the recipe and store text agree with it, and the import-only fallback is ready to apply.

**Independent Test**: Open `SPEC/r16-decision.md`: it names the policy sources with the date, states the outcome, the question for the maintainers and the complete fallback switch; the recipe has exactly the anti-features the record says.

- [X] T022 [US3] Create `SPEC/r16-decision.md` with the sections of data-model.md "Decision record (R-16)": the question; the sources read (Inclusion Policy, Anti-Features, Build Metadata Reference, with the URLs and the date 2026-11-19, the quoted sentences of research R-1); the facts about the app, each with the file that proves it (`app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/network_security_config.xml`, `core` catalog invariants test, `NetworkSourceScannerTest`, `app/src/main/kotlin/PKG/models/download/`); the outcome `no-anti-feature` with its reasoning from research R-1 and the residual risk stated plainly; the consequence for the recipe (no `AntiFeatures` line) and the store text; the exact question for the maintainers ("Is a user-initiated download of MIT-licensed speech-model data from a pinned third-party host (huggingface.co, pinned commit and SHA-256, never bundled, import from a file possible) acceptable without an anti-feature?")
- [X] T023 [US3] Add the section "Import-only fallback" to `SPEC/r16-decision.md` as a complete switch list built from the real code, not from memory: find every place with `grep -rn` (the `INTERNET` permission and its comment in `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/network_security_config.xml` and its manifest reference, the `models/download/` package and its tests, the catalog download entries and `scripts/verify-model-catalog.sh`, the download parts of `ModelController` and `ModelSettingsScreen`, the strings that mention the download in `RES/values/` and `RES/values-de/`, the rules in `CLAUDE.md` and `README.md`, the store texts, `verifyManifestPermissions` and `NetworkSourceScannerTest` expectations); state for each: removed, kept or changed; state what stays (import, delete, checksum check of imports, the model screen); list the build gates and tests that change and the order of the work; state that the release is not blocked by the fallback (depends on `T022`)
- [X] T024 [US3] Make the rest agree with the decision: confirm `SPEC/contracts/app.meetingrecorder.yml` has no `AntiFeatures` line and `verifyReleaseMetadata` check 1 (`T007`) fails if one is added without the record changing (add that rule: an `AntiFeatures` line fails the gate unless `SPEC/r16-decision.md` contains the matching `anti-feature: <name>` outcome); update `specs/002-offline-transcription/fdroid-build-notes.md` section "Open question (research R-16)" to say the question is answered in `SPEC/r16-decision.md` and that the answer is final only after the maintainers' reply (depends on `T022`, `T007`)
- [X] T025 [US3] Compare the privacy and network statements across the store texts (`FASTLANE/*/full_description.txt`), `README.md` section "Privacy", and the settings strings (`settings_privacy`, `settings_permissions_internet` in `RES/values/` and `RES/values-de/`): all three describe the same network behavior and none contradicts `SPEC/r16-decision.md`; fix any difference at its source; record the comparison in `SPEC/build-results.md` (US3 scenario 4, FR-013) (depends on `T013`, `T014`, `T022`)

**Checkpoint**: the R-16 decision and its fallback are on paper before anything is submitted.

---

## Phase 6: User Story 4 - The author and copyright are shown everywhere they should be (Priority: P2)

**Goal**: The About screen shows the copyright line in both languages; the author appears in README and recipe; no other address is published.

**Independent Test**: Quickstart Q1 on the emulator in English and German; the gate's author checks pass.

- [X] T026 [P] [US4] Add `settings_copyright` with the text `Copyright © 2026 Volker von Einem` to `RES/values/strings.xml` and `RES/values-de/strings.xml` directly after `settings_license` in both files, in the same change (constitution Principle IX; the text is identical in both languages, the key must exist in both for lint)
- [X] T027 [US4] Add one `Text(text = stringResource(R.string.settings_copyright))` line to the About block of `app/src/main/kotlin/PKG/ui/settings/SettingsScreen.kt` between the license line and the privacy line; use the existing text style, no new component, no color, no permission (FR-001, FR-024) (depends on `T026`)
- [X] T028 [US4] Run `./gradlew :app:testDebugUnitTest :app:lintDebug` (hard-coded-string scan, string parity, `ThemeSourceRulesTest` stay green), then (device) quickstart Q1 on the emulator: the line shows in English and in German next to version and license; record the result (depends on `T027`)
- [X] T029 [US4] Extend `verifyReleaseMetadata` with research R-8 check 5 and the author data: `settings_copyright` exists in `RES/values/strings.xml` and `RES/values-de/strings.xml` and contains `Volker von Einem`; `README.md` contains `Volker von Einem` and `vve-f-droid@gmx.net`; the address `vve-f-droid@gmx.net` appears in no tracked file other than `README.md`, the recipe copy and the documents under `specs/` (SC-005, FR-002, FR-003); failure messages name the file (depends on `T026`, `T003`)

**Checkpoint**: attribution is visible in the app and the repository and enforced by the gate.

---

## Phase 7: User Story 5 - The release is tagged and updates are detected automatically (Priority: P2)

**Goal**: A tag `v0.1.0` that F-Droid can build, the recipe holding its commit, no signing material, and a reproducibility result on the tag.

**Independent Test**: A clean checkout of the tag builds an app with version `0.1.0` / code 1; all native libraries of two builds in different directories are identical; the gate is green on the tag.

- [X] T030 [US5] Confirm `SPEC/contracts/release-policy.md` matches the build: `app/build.gradle.kts` has `versionName = "0.1.0"` and `versionCode = 1` only once each and `release` has no signing configuration; if the repository `CLAUDE.md` or README mention a different numbering, correct them to the policy (depends on `T029`)
- [X] T031 [P] [US5] Update `README.md`: a short "Release and F-Droid" paragraph (version policy pointer, the tag, F-Droid signs the app, uninstall a debug build first, the recipe copy in `specs/004-fdroid-release/contracts/`), keeping the existing privacy, permission and license statements unchanged unless `T025` found a difference; no claim the phase did not verify
- [X] T032 [P] [US5] Update the repository `CLAUDE.md`: add `specs/004-fdroid-release/` to the list of spec folders in the first paragraph, add `:app:verifyReleaseMetadata` to the verification commands under "Build and test", add one rule under "Rules that are easy to break" ("Release metadata: the recipe copy, store metadata, version and author data are checked by `verifyReleaseMetadata`; the recipe lives in `fdroiddata`, the copy in `specs/004-fdroid-release/contracts/` is what is submitted; the version lives only in `app/build.gradle.kts`; no signing material in the repository; commit and push of tags only on the owner's request"), and keep every existing rule unchanged
- [X] T033 [US5] Run the complete gate on the final tree (quickstart Q2 step 1): `./gradlew :core:test :app:check :app:assembleDebug :app:assembleRelease` and `./gradlew :app:verifyNoForbiddenDependencies :app:verifyManifestPermissions :app:verifyNoPrebuiltBinaries :app:verifyWhisperPin :app:verifyReleaseMetadata :whisper:assembleDebug`; all green, and the unit-test counts are not lower than the baseline of `T002` (FR-009, SC-008); record both numbers (depends on `T013` to `T032`; the README and `CLAUDE.md` of `T031` and `T032` are updated first so that the tagged commit already contains them)
- [X] T034 [US5] (owner) Ask the owner for the go-ahead to commit, then create the **release commit** containing the app change (`T026`, `T027`), the store metadata (`T013` to `T018`), the gate (`T003` and extensions), the helper scripts, the specs and the documentation changes made so far, with a commit message in English that describes change and reason and ends with the attribution lines of the session; do not push (FR-023) (depends on `T033`) *(Done 2026-11-19 with the owner's explicit go-ahead: release commit `cf6f938` with the F-Droid address as author and committer (the owner's git config unchanged); nothing pushed.)*
- [X] T035 [US5] (owner) With the owner's go-ahead, create the annotated tag on the release commit: `git tag -a v0.1.0 -m "Meeting Recorder 0.1.0"`; do not push the tag (FR-006, FR-023) (depends on `T034`) *(Done 2026-11-19 with the owner's go-ahead: annotated tag `v0.1.0` on `cf6f938`, tagger the F-Droid address; not pushed.)*
- [X] T036 [US5] Run quickstart Q6: in a clean directory under `SCRATCH`, `git clone --recurse-submodules <repo> --branch v0.1.0`, `./gradlew :app:assembleRelease`, read the version of the APK with `aapt2 dump badging` (`0.1.0`, code 1), run the **complete gate of `T033`** in the clean clone (the tag must be green on its own, FR-009: `:core:test :app:check :app:assembleRelease` and the verify tasks, including `:app:verifyReleaseMetadata`), and confirm that `git ls-files | grep -E '\.(jks|keystore)$|keystore.properties'` prints nothing; record (depends on `T035`) *(Done 2026-11-19: the complete gate is green in a clean clone of the tag, version `0.1.0` / code 1, no signing material; `build-results.md`.)*
- [X] T037 [US5] Run quickstart Q7: build the release APK of the tag in two different directories under `SCRATCH` (the method of Phase 2 T173 and `specs/002-offline-transcription/spike-results.md` "Reproducibility"), unzip both APKs and compare `sha256sum` of **every** `lib/*/*.so` (all must be identical, SC-003); record also whether the two unsigned APKs are identical as a whole and, if not, which entries differ and why (information only unless a native library differs); a difference in a native library is a defect to fix before the tag is used, not a note: delete the **unpushed** local tag (and the release commit if it must change), fix, re-run `T033` and `T034`, re-create the tag with `T035` and repeat `T036` and `T037` (safe because nothing was pushed, FR-023) (depends on `T035`) *(Done 2026-11-19: all 24 native libraries identical in two directories, and the complete unsigned APKs are byte-identical (and identical to the F-Droid image's build); `build-results.md`.)*
- [X] T038 [US5] Write `git rev-parse v0.1.0^{commit}` into `commit:` of `SPEC/contracts/app.meetingrecorder.yml` (replacing `TAG_COMMIT_PLACEHOLDER`), re-run `:app:verifyReleaseMetadata` (check 1 now sees 40 hex digits), then repeat `T009`, `T010` and `T012` with the scratch recipe pointing at a scratch clone of the tag and this hash; record; this edit is a separate documentation commit after the tag because the recipe copy is not part of what F-Droid builds (release-policy step 4); commit only with the owner's go-ahead (depends on `T035`, `T036`) *(The commit hash is written into the recipe copy and lint, checkupdates and the build were repeated against a scratch clone of the tag, all green (2026-11-19). The documentation commit that holds this edit, the build record, `submission.md` and `sc-results.md` was made on 2026-11-19 with the owner's go-ahead.)*

**Checkpoint**: the tagged release builds, is reproducible in its native code, and the recipe copy names its commit.

---

## Phase 8: User Story 6 - The merge request is ready to send and the review is manageable (Priority: P2)

**Goal**: A package the owner can follow from a clean state to an opened merge request, and prepared answers for the review.

**Independent Test**: Quickstart-level read-through: every command is complete and in order, every check in it was run in this phase with its result recorded, every review topic has an answer and a proof file.

- [X] T039 [US6] Create `SPEC/submission.md` with the merge request title and description (app name, `app.meetingrecorder`, GPL-3.0-or-later, source and tag, the build requirements: submodule at the pinned commit of `gradle/whisper.pin`, NDK `29.0.14206865`, CMake `3.31.6`, the six-directory `rm:` list and why (prebuilt test files of the engine that nothing builds), the 16 KB page alignment and extracted native libraries, the about-39-MB size with both ABIs and why, the unsigned release build, the model-download decision of `SPEC/r16-decision.md` with the exact question and the prepared fallback, and an honest list of what was not verified on a device (from `specs/002-offline-transcription/sc-results.md`)); carry over every pending question from `SPEC/build-results.md` "Open items" (for example the toolchain question of `T011`); English; no private address (depends on `T022`, `T038`)
- [X] T040 [US6] Add the command sequence to `SPEC/submission.md`, complete and in order, with the expected result of each step: register on GitLab (owner), fork `https://gitlab.com/fdroid/fdroiddata`, clone the fork, create a branch named `app.meetingrecorder` (never commit to `master`), copy the recipe from `SPEC/contracts/app.meetingrecorder.yml` to `metadata/app.meetingrecorder.yml`, run `fdroid readmeta`, `fdroid rewritemeta app.meetingrecorder`, `fdroid checkupdates app.meetingrecorder`, `fdroid lint app.meetingrecorder`, `fdroid build -v -l app.meetingrecorder` (or the container helpers of `scripts/fdroid/`), commit with a message of the form "New app: Meeting Recorder", push the branch to the fork, check the fork's CI pipeline, open the merge request with the description above and the template filled in, keep squash enabled; mark each step **(owner)** where it uses the owner's account (depends on `T039`)
- [X] T041 [US6] Add the review checklist to `SPEC/submission.md` as a table (maintainer topic, prepared factual answer, file that proves it) covering at least: prebuilt binaries and the `rm:` list (`gradle/verification-tasks.gradle.kts`, `SPEC/build-results.md`), the engine submodule and its pin (`gradle/whisper.pin`, `.gitmodules`), permissions and why each (`app/src/main/AndroidManifest.xml`, `verifyManifestPermissions`), network use and the model download (`SPEC/r16-decision.md`, `network_security_config.xml`, `NetworkSourceScannerTest`), anti-features (none, with the reasoning), trackers and proprietary dependencies (`verifyNoForbiddenDependencies`), license and third-party notices (`LICENSE`, `README.md`), reproducibility (`SPEC/build-results.md`), APK size and ABIs, NDK and CMake versions, signing by F-Droid and the uninstall note for debug builds, the description's claims against the app, the author fields, the version scheme and update detection (`SPEC/contracts/release-policy.md`); plus a short "if the maintainers say X" list: asked for `NonFreeNet` or `NonFreeAssets` (accept, update recipe and store text), asked to remove the download (apply the fallback of `T023`), asked for a smaller package (a later per-ABI decision, not this phase) (depends on `T039`)
- [X] T042 [US6] Add the section "Owner-only steps" to `SPEC/submission.md` (FR-023): push the branch and the tag `v0.1.0` to `https://github.com/voneinem/meeting-recorder`, create the GitLab account, fork, push the fork branch, open the merge request, answer the maintainers' review; mark the final dry run (`T044`) as pending until it ran (depends on `T040`)
- [X] T043 [US6] Dry-read the submission package (SC-007): follow `SPEC/submission.md` literally from its first step, as a person who has never submitted to F-Droid, as far as it needs no account of the owner (clone `fdroiddata` into `SCRATCH`, create the branch `app.meetingrecorder`, copy the recipe, run `fdroid readmeta`, `rewritemeta`, `checkupdates`, `lint` and `build -v -l`, read the merge request text and the review checklist); write down every missing detail, unclear step, wrong command or missing expected result, fix `SPEC/submission.md`, repeat the part that failed, and record the findings and the date in `SPEC/build-results.md` (depends on `T040`, `T041`, `T042`) *(Done 2026-11-19 by the assistant, not by a first-time submitter; it found the private-repository blocker, `submission.md` was corrected.)*
- [ ] T044 [US6] (owner) After the owner pushed the tag `v0.1.0` to GitHub: run quickstart Q9 with the **real** recipe copy (`fdroid checkupdates`, `fdroid lint`, `fdroid build -v -l app.meetingrecorder` against `https://github.com/voneinem/meeting-recorder.git`) using the container helpers; expected results as `T009`, `T010`, `T012`; record the date; if the owner skips this step, write "not run" in `SPEC/build-results.md` and keep it marked pending in `SPEC/submission.md` (depends on `T038`, `T042`, `T043`) *(**PENDING, not run, 2026-11-19**: needs the owner to make the GitHub repository public and push the tag; with the real recipe `fdroid checkupdates` currently fails with an authentication error because the repository is private (`submission.md` 1.0 and section 5). Lint with the real recipe passed.)*

**Checkpoint**: the package is ready to hand over; the maintainers' replies can be handled from the checklist.

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: The evidence table, the final checks and the honesty review. The README and `CLAUDE.md` updates are in Phase 7 (`T031`, `T032`) because the tagged commit must contain them.

- [X] T045 Create `SPEC/sc-results.md` (the pattern of `specs/003-fossify-style/sc-results.md`): one row for each of SC-001 to SC-008 with the measured result, the task or record that holds the evidence and the status (met, partly met, not measured with the reason); an SC that depends on the pending dry run (`T044`) or on a skipped device step is not shown as met (depends on `T036`, `T037`, `T038`)
- [X] T046 Re-run the complete gate on the final tree (quickstart Q2 step 1) after all edits, confirm `SPEC/checklists/requirements.md` still passes (all items checked, no `[NEEDS CLARIFICATION]`), confirm `git status` shows no file from `SCRATCH`, no keystore and no screenshot with personal data, and confirm the unit-test count is not below the baseline of `T002` (depends on `T031`, `T032`, `T045`)
- [X] T047 Keep this file honest: tick a task only when it is done and, where it says so, verified on a device or approved by the owner; for each unticked task that is owner-only or pending, write the reason and the date next to it; list in `SPEC/build-results.md` section "Open items" everything that remains for the owner (push, account, fork, merge request, dry run, the maintainers' answer on R-16) (depends on `T046`)

---

## Dependencies & Execution Order

### Phase dependencies

- **Phase 1** first. **Phase 2** (the gate) before every story, because each story extends it.
- **US1 (Phase 3)**, **US2 (Phase 4)**, **US3 (Phase 5)** and **US4 (Phase 6)** are independent of each other after Phase 2 and can run in any order or in parallel; `T010` needs a built release APK (the same build `T017` uses), `T021` needs `T013` to `T019`, `T025` needs `T013`, `T014` and `T022`.
- **US5 (Phase 7)** needs US1, US2, US3 and US4 done, because the release commit freezes their files.
- **US6 (Phase 8)** needs US3 (the decision) and US5 (the tag and its recipe hash).
- **Polish (Phase 9)** last.

### Within the phases

- The gate extensions (`T007`, `T019`, `T029`) each depend on `T003` only; their negative checks (`T004`, `T008`, `T020`) follow them.
- Recipe changes in `T011` can force repeating `T009` and `T010`.
- Owner steps (`T034`, `T035`, `T042`, `T044`) cannot be done by the assistant alone and never run without the owner's go-ahead.

### Parallel opportunities

- `T001` and `T002`.
- In US1: `T005` with `T006`.
- In US2: `T013`, `T014`, `T015`, `T016` (four different files).
- US1 to US4 as whole phases; within US4 `T026` is a single quick change and `T027` follows it.
- `T031` and `T032` (README and `CLAUDE.md`, before the release commit).

### Parallel example: US2

```text
T013 en-US full_description.txt   T014 de-DE full_description.txt
T015 changelogs (both locales)    T016 images/icon.png
then T017 -> T018 (device), T019 -> T020, T021
```

## Implementation Strategy

**MVP = US1 (the recipe builds in F-Droid's image) with the Phase 2 gate.** It proves the one thing that can fail F-Droid's review outright, and it settles the open NDK and CMake question early, which could change the recipe. Then US2 to US4 in any order, because each is small and independent; US5 only when all four are done so the tag freezes a finished state; US6 last so the package quotes real results. Stop and validate after each checkpoint; nothing outward-facing happens before the owner says so.

## Notes

- 48 tasks: Setup 2, Foundational 2, US1 9, US2 9, US3 4, US4 4, US5 9, US6 6, Polish 3.
- Owner or device steps are marked in the task text; unticked until they happened.
- Commit only when the owner asks. The commit and tag of `T034` and `T035` are such requests and wait for the owner.
