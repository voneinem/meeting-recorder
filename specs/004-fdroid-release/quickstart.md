# Quickstart: validating the F-Droid release

Scenarios Q1 to Q10. Record the result of each (date, tool and image versions, outcome) in
`build-results.md`. Contracts: [recipe](contracts/app.meetingrecorder.yml),
[store metadata](contracts/store-metadata.md), [release policy](contracts/release-policy.md).

## Prerequisites

- JDK 21, `ANDROID_HOME`, NDK `29.0.14206865` and CMake `3.31.6` (as in `CLAUDE.md`), the submodule checked
  out (`git submodule update --init --recursive`).
- Docker (images `registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie` and `debian:trixie-slim`,
  several gigabytes).
- An Android emulator (API 35 or newer) or the test phone, and `adb`.
- A scratch directory outside the repository for the `fdroiddata` clone and build output (for example
  `~/fdroid-work`); nothing from it is committed.

## Q1: The About line (US4, FR-001, SC-005)

1. `./gradlew :app:assembleDebug`, install it on the emulator.
2. Open Settings, About, in English: the version, the license and `Copyright © 2026 Volker von Einem`
   are shown.
3. Switch the phone language to German and open the same screen: the line appears with the same name.
4. `./gradlew :app:testDebugUnitTest` stays green (the hard-coded-string scan and the string-parity test).

## Q2: The gate, and the gate's failure paths (FR-009, FR-016, SC-008)

1. `./gradlew :core:test :app:check :app:assembleDebug :app:assembleRelease` and
   `./gradlew :app:verifyNoForbiddenDependencies :app:verifyManifestPermissions :app:verifyNoPrebuiltBinaries :app:verifyWhisperPin :app:verifyReleaseMetadata :whisper:assembleDebug`
   all pass; the unit-test count is not lower than before the phase (record both numbers).
2. Negative checks, each undone afterwards; `:app:verifyReleaseMetadata` must fail with a message naming
   the problem:
   - change `versionCode` in the recipe copy to `2`;
   - remove one directory from the recipe copy's `rm:` list;
   - change `ndk` in the recipe copy;
   - delete `fastlane/metadata/android/en-US/changelogs/1.txt`;
   - make `short_description.txt` 81 characters long;
   - add a file `x.jks` to the index (`git add -f`) and remove it again;
   - write the address printed by `git log -1 --format=%ae` (the git commit address) into a tracked build
     file, then revert (never into a document);
   - put the word `storePassword` into `app/build.gradle.kts`, then revert; the gate must stay green with
     that word present in the specs (the content scan covers build files only).

## Q3: Recipe syntax and lint (US1, FR-017)

In the container (`debian:trixie-slim` with the `fdroidserver` package, as `fdroiddata`'s CI):

1. Clone `fdroiddata` into the scratch directory and copy the recipe to `metadata/app.meetingrecorder.yml`.
2. `fdroid readmeta`, then `fdroid rewritemeta app.meetingrecorder` (the file must not change, or the
   change is taken back into the copy), then `fdroid lint app.meetingrecorder`: no errors, and no warning
   left unexplained in `build-results.md`.

## Q4: The build in F-Droid's image (US1, SC-001, SC-002)

1. Make a scratch copy of the recipe whose `Repo:` is the local repository path (mounted into the
   container) and whose `commit` is a local commit hash; the real recipe is not edited.
2. In `registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie`, install `openjdk-21-jdk-headless`
   and make it the default Java, then `fdroid build -v -l app.meetingrecorder` (the steps of
   `fdroiddata`'s CI job).
3. Expected: exit status 0 and an unsigned APK with the version `0.1.0` (code 1); the scanner reports no
   binary, no proprietary dependency, no tracker.
4. **Open point settled here** (research R-3): if NDK `29.0.14206865` or CMake `3.31.6` is missing, add
   the recipe fallback (`sudo:` or `prebuild:` with `sdkmanager`), repeat, and record which line was
   needed. If the build server cannot do it, record the question for the maintainers.
5. Install the APK on the emulator (it is unsigned: sign it with a throwaway debug key for the test) and
   start it: the app opens and records.

## Q5: Update detection (US5, FR-005)

`fdroid checkupdates app.meetingrecorder` finds version `0.1.0`, code 1 from the tag and leaves
`CurrentVersion` and `CurrentVersionCode` unchanged (container, with the scratch recipe and the local tag).
It must also detect a bumped version in a throwaway commit (`versionCode` 2, tag `v0.1.1`) and report it;
the throwaway commit and tag are deleted afterwards.

## Q6: The tag (US5, FR-006, FR-007)

1. After the release commit: `git tag -a v0.1.0 -m "Meeting Recorder 0.1.0"`.
2. In a clean directory: `git clone --recurse-submodules <repo> --branch v0.1.0`, build, and check that
   the version name and code of the built APK are `0.1.0` and `1`
   (`aapt2 dump badging app-release-unsigned.apk`), and run the complete gate of Q2 step 1 in the clean
   clone (the tag is green on its own, FR-009).
3. `git rev-parse v0.1.0^{commit}` is written into the recipe copy; `:app:verifyReleaseMetadata` stays green.
4. Scan: `git ls-files | grep -E '\.(jks|keystore)$|keystore.properties'` prints nothing.

## Q7: Reproducibility on the tag (US1 scenario 4, FR-008, SC-003)

Build the release APK of the tag in two different directories. Compare **every** native library
(`unzip` both APKs, `sha256sum lib/*/*.so`): all hashes are equal. Record whether the two unsigned APKs
are identical as a whole; if not, record which files differ and why (information, not a failure, unless a
native library differs).

## Q8: Store metadata and screenshots (US2, FR-010 to FR-014, SC-004)

1. `:app:verifyReleaseMetadata` passes (limits, files, changelog).
2. Read both full descriptions against `README.md`: the three points of the content rules agree, the
   architectures are named, and no sentence contradicts the app.
3. Open each screenshot: demo data only, light appearance, at least four, in the contract order.
4. `fdroid lint` (Q3) shows no metadata warning.

## Q9: The final dry run against GitHub (after the owner pushed the tag)

Owner-only precondition: the tag `v0.1.0` is on `https://github.com/voneinem/meeting-recorder`.
Then in the container: `fdroid checkupdates`, `fdroid lint` and `fdroid build -v -l app.meetingrecorder`
with the **real** recipe. Expected: the same results as Q3 to Q5. Record the date. If this step is not
run, `build-results.md` says so, and the submission package marks the dry run as pending.

## Q10: Dry-read of the submission package (US6, SC-007)

Follow `submission.md` literally from its first step, as a person who has never submitted to F-Droid, as
far as no account of the owner is needed: clone `fdroiddata` into the scratch directory, create the branch
`app.meetingrecorder`, copy the recipe, run the five `fdroid` commands, read the merge request text and
the review checklist. Every missing detail, unclear step, wrong command or missing expected result is
written down, fixed in `submission.md` and re-run. Record the findings and the date in
`build-results.md`. The scenario passes when a full pass needs no finding.
