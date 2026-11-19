# Build record: F-Droid Release (Phase 4)

Commands, tool and image versions, dates and results of every verification of this phase (data-model.md
"Build record"). The scratch area is `~/fdroid-work` (outside the repository, never committed).

## Setup and baseline (T001, T002)

**Images (2026-11-19)**

| Image | Digest | Size |
|-------|--------|------|
| `registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie` | `sha256:1ea8bef1820bff06c2974e31b4fc4c79c364cd24231b86e71fa4b926797e0dac` | 1.56 GB |
| `debian:trixie-slim` | `sha256:a72b4433de1cf7d6bae0c4cee74d0547f58601a643b3375f070c51600d63b6b2` | 78.9 MB |

Facts found in the build image (read with `docker run`): Debian 13 (trixie); `ANDROID_HOME=/opt/android-sdk`
with an **empty** `ndk` directory, no `cmake` directory, empty `build-tools` and `platforms`; `fdroid` is not
on the `PATH` (`fdroidserver` is cloned at a pinned commit by the CI job); `/etc/profile.d/bsenv.sh` sets
`ANDROID_HOME`, `home_vagrant=/home/vagrant` and `fdroidserver=$home_vagrant/fdroidserver`. No other
container on the machine was touched.

**Baseline of the untouched tree (2026-11-19)**: `./gradlew :core:test :app:check :app:assembleDebug
:app:assembleRelease :app:verifyNoForbiddenDependencies :app:verifyManifestPermissions
:app:verifyNoPrebuiltBinaries :app:verifyWhisperPin :whisper:assembleDebug` finished with
`BUILD SUCCESSFUL in 39s` (exit 0). Unit tests, summed over the XML reports of all variants:
**`:core` 353 tests, `:app` 259 tests, 0 failures**. SC-008 and the later gate runs compare against these
numbers.

## Gate `verifyReleaseMetadata`, first version (T003, T004)

Added to `gradle/verification-tasks.gradle.kts` and wired into `check` (2026-11-19). First version: no
keystore file tracked, no signing word (`storePassword`, `keyPassword`, `keyAlias`, `signingConfig`) in a build
file, and the author's private commit address (derived from `git log`, not written in the gate) in no
tracked or untracked-but-not-ignored text file. Green on the tree; the specs may contain the word
`storePassword` without failing the gate (the scan covers build files only).

Negative checks (each failed with a message naming the problem, then reverted; the gate was green again):

| Check | Result |
|-------|--------|
| `git add -f x.jks` | `Signing material is tracked: x.jks ...`, exit 1 |
| the git commit address appended to `gradle.properties` | `gradle.properties contains the git commit address of the author; only the F-Droid address may be published (FR-003)`, exit 1 |
| `storePassword` added to `app/build.gradle.kts` | `app/build.gradle.kts contains 'storePassword': the release build must not need signing configuration`, exit 1 |

## Recipe: lint, build, update detection (T009 to T012, T048), 2026-11-19

Tooling: `scripts/fdroid/` (prepare, lint, checkupdates, build). The recipe under test is the reviewed copy in
`contracts/`; the scratch copy differs only in `Repo:` (the scratch source repository, a commit of the working
tree) and `commit:` (its hash). `fdroidserver` is the git revision that `fdroiddata`'s CI pins
(`a35fdfddd9c66823987a410566a6101186e39c84`, reports 2.4.2) on top of the Debian package `2.4.2-1` in
`debian:trixie-slim`; the Debian package alone is stricter about other apps' metadata and fails `readmeta` on an
unrelated app (invalid Bitcoin address), so it is not used for the check.

| Step | Command | Result |
|------|---------|--------|
| readmeta, rewritemeta, lint (Q3) | `scripts/fdroid/lint.sh` | exit 0; no error and no warning. `rewritemeta` rewrote the draft (dropped comments, put `UpdateCheckData` on its own line); the rewritten form is now the reviewed copy, the comments moved to `contracts/recipe-notes.md`. A second run reports no rewrite. With the store metadata present in the source clone, lint still reports nothing. |
| checkupdates (Q5), first run | `scripts/fdroid/checkupdates.sh` | exit 0; found version `0.1.0`, code 1 from tag `v0.1.0` in `app/build.gradle.kts`; **added `AutoName: Meeting Recorder`**. `fdroiddata`'s CI runs `checkupdates --auto` and fails when it changes the file, so the line was added to the reviewed copy (research R-2). |
| checkupdates, with `AutoName` | same | exit 0, recipe unchanged. |
| update detection (throwaway) | a commit with `versionCode = 2`, `versionName = "0.1.1"` and tag `v0.1.1` in the **scratch** repository only | `...updating to version 2`; `CurrentVersion: 0.1.1`, `CurrentVersionCode: 2`. The throwaway commit and tag are discarded with the scratch repository (`prepare.sh` recreates it). |
| build (Q4), first run | `scripts/fdroid/build.sh` | **failed** in 24 s: "Dependency verification failed for configuration 'classpath'", 5 artifacts (`bc-jdk18on-bom-1.80.2.module`, `guava-parent-33.4.0-jre.pom`, `junit-bom-5.10.2.module`, `junit-bom-5.11.0-M2.module`, `kotlinx-coroutines-bom-1.8.0.pom`). Reproduced with any fresh Gradle home (`GRADLE_USER_HOME` empty): a warm Gradle cache had hidden missing entries in `gradle/verification-metadata.xml`. Not specific to F-Droid; any fresh clone failed. |
| fix (T048) | `./gradlew --write-verification-metadata sha256 :core:test :app:check :app:assembleRelease :whisper:assembleDebug` in a fresh `GRADLE_USER_HOME` | `gradle/verification-metadata.xml`: **60 lines added, none removed**: parent poms, BOMs and `.module` files of existing dependencies (`oss-parent` 75, `jackson-parent` 2.21, `guava-parent` 33.3.1-android and 33.4.0-jre, `groovy-bom` 4.0.29 pom, `bc-jdk18on-bom` 1.80.2 module, `kotlinx-coroutines-bom` 1.8.0, `kotlinx-serialization-bom` 1.7.3, `junit-bom` 5.10.2, 5.11.0-M2 and 6.0.3, `jackson-base` and `jackson-dataformats-text` 3.1.8). No library was added. Reviewed in the diff. |
| proof of the fix | a second fresh `GRADLE_USER_HOME`: `./gradlew --no-daemon help :core:test :app:assembleRelease :app:verifyReleaseMetadata` | `BUILD SUCCESSFUL in 1m 36s` with verification enforced. |
| build (Q4), second run | `scripts/fdroid/build.sh` in `buildserver-trixie` | **exit 0**: `Successfully built app.meetingrecorder:1`, `BUILD SUCCESSFUL in 11m 54s`, 90 tasks executed; unsigned `app.meetingrecorder_1.apk`, 39,370,114 bytes. |

Details of the successful build (from `~/fdroid-work/build2.log`):

- JDK `openjdk 21.0.13.1` (installed in the image as the CI does); Gradle `9.9.1` through F-Droid's `gradlew-fdroid`,
  which removes the project's `gradle-wrapper.jar` ("Removing gradle-wrapper.jar") and checks the distribution
  against F-Droid's transparency log.
- **NDK (research R-3, settled)**: `ndk: r29` is enough. fdroidserver warned "Android NDK version 'r29' could not
  be found" and then downloaded `android-ndk-r29-linux.zip` and verified it against F-Droid's checksum log.
  **CMake `3.31.6`**: installed by the Android Gradle Plugin during the build ("Install CMake 3.31.6 ... complete",
  license accepted). **No `sudo:` or `prebuild:` fallback was needed**, so the recipe has none.
- The recipe's `rm:` took effect ("Removing whisper/src/main/cpp/whisper.cpp/bindings", `.../examples`, ...); the
  source scanner ("Scanning source for common problems", signature data `suss`) reported nothing; the build
  continued to `assembleRelease`. The engine submodule was fetched from GitHub at the pinned commit
  `d1be6fde11ac6e0407606b4e42fe72d34add8037`.
- AGP prints the warning CXX5106 ("NDK was located by using ndk.dir property") because fdroidserver writes
  `ndk.dir` into `local.properties`; harmless and not ours.
- The APK declares `native-code: arm64-v8a armeabi-v7a x86 x86_64` (`aapt2 dump badging`): the whisper libraries
  are only `arm64-v8a` and `x86_64`; the other two names come from other libraries (CameraX), as the notes of
  Phase 2 say.

Install and start (T010, device: emulator `mr-api35`, Android 15, x86_64): the APK was signed with a throwaway
key, installed and started; the first-run screen appeared, a recording ran, two photos were taken, the meeting was
saved, a model was imported from a file and a meeting was transcribed (see "Screenshots"). A debug build that was
still installed on the emulator **refused the update because the signatures differ** (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`),
the behavior the release policy describes (uninstall a debug build first).

## Store metadata, author line and screenshots (T013 to T021, T026 to T029), 2026-11-19

- Texts: both full descriptions gained the model-download sentences (once, from `huggingface.co`, file, size and
  source named first, nothing before the tap, or import from a file) and a "Supported devices" section
  (`arm64-v8a`, `x86_64`; no transcription elsewhere). The German text called the button „Herunterladen“, but the app
  shows „Laden“: fixed (found by the comparison of T025). Changelogs: 382 bytes (en) and 464 bytes (de), limit 500.
  Lengths in characters: titles 17, short descriptions 77 and 69, full descriptions about 2500 and 2800.
- Comparison of the network, storage and consent statements (T021, T025): the store texts, `README.md` "Privacy" and the
  settings and dialog strings (`settings_privacy`, `settings_permissions_internet`, `models_intro`,
  `models_network_note`, `model_download_message`) say the same: the only network use is the model download after
  the tap, from the named source; meeting data is never sent; meetings are stored unencrypted; consent is required.
- Icon: `en-US/images/icon.png`, 512 x 512, drawn from the launcher icon's geometry (`ic_launcher_foreground.xml`
  over `#106D20`) with Pillow (ImageMagick's SVG renderer dropped the stroked path); looked at and matches the launcher.
- About line (T026 to T028): `settings_copyright` = `Copyright © 2026 Volker von Einem` in both string files. Lint
  rejected the planned "(C)" (`TypographyOther`), so the text uses the © sign; the spec, the tasks and the README were
  aligned. `:app:testDebugUnitTest :app:lintDebug` green. On the F-Droid-built APK the About block shows version,
  license and the copyright line in English and in German.
- Screenshots (T017, T018): taken on the emulator `mr-api35` (1080 x 2400, Android 15, light appearance, English)
  with the **F-Droid-built APK**: `1.png` meeting list (three demo meetings), `2.png` running recording with the
  level meter and a photo, `3.png` playback with photos and transcript, `4.png` model settings with an imported
  model. Demo data only: the recordings are silence except the demo meeting, whose audio is the public-domain
  `jfk.wav` (11 s, from the engine's samples) placed in a 34.8 s recording, the photos are the emulator's synthetic
  camera scene, the model was imported from a file (no download). The transcript read: "And so my fellow Americans
  asked not what your country can do for you, ask what you / can do for your country." with both photos placed
  (00:14 and 00:29). The emulator's demo data and the app were removed afterwards.
- Gate: `verifyReleaseMetadata` now also checks the store files and the author data. Negative checks (each failed with
  a message that names the problem, then undone): missing changelog; 81-character short description; three
  screenshots; 256 x 256 icon; the architecture name removed from a description; `settings_copyright` removed
  from `values-de`; the published author address added to a build file. Green again afterwards.

## Complete gate on the final tree before the release commit (T033), 2026-11-19

`./gradlew :core:test :app:check :app:assembleDebug :app:assembleRelease :app:verifyNoForbiddenDependencies
:app:verifyManifestPermissions :app:verifyNoPrebuiltBinaries :app:verifyWhisperPin :app:verifyReleaseMetadata
:whisper:assembleDebug` finished with `BUILD SUCCESSFUL`, exit 0. Unit tests, summed over the XML reports:
**`:core` 353, `:app` 259, 0 failures**, equal to the baseline (no test added, none removed; this phase adds no app
logic). The README and `CLAUDE.md` updates (T031, T032) are part of this tree.

## Release tag and the clean-clone checks (T034 to T038), 2026-11-19

- Release commit `cf6f93887c9f01bdea13158f7690d57c3d2c99ad` (author and committer `vve-f-droid@gmx.net`, chosen by the
  owner for this commit; the owner's git config is unchanged) and the annotated tag `v0.1.0` (tagger the same address). Both
  are local; nothing was pushed.
- **Clean clone of the tag** (`git clone --recurse-submodules --branch v0.1.0`, the engine submodule fetched from GitHub
  at `d1be6fde11ac6e0407606b4e42fe72d34add8037`): the complete gate (`:core:test :app:check :app:assembleDebug
  :app:assembleRelease` and the five verify tasks, `:whisper:assembleDebug`) is green, 190 tasks executed; `:core` 353
  and `:app` 259 tests, 0 failures. `aapt2 dump badging`: `versionCode='1' versionName='0.1.0'`, 39,370,114 bytes. No
  `*.jks`, `*.keystore` or `keystore.properties` is tracked.
- **Reproducibility (SC-003)**: the tag built in two different directories
  (`~/fdroid-work/tag-a` and `~/fdroid-work/other/second-checkout/repro`): **all 24 native libraries identical**
  (`sha256sum` of every `lib/*/*.so`), and the **two complete unsigned APKs are byte-identical**. The APK built by
  F-Droid's build image from the scratch commit before the tag has the same SHA-256 and the same 24 libraries:
  `159773be74c11a7fcb738928db4eae2c3865891c61d2deb1568f95184381ebde`.
- **Recipe with the tag's commit**: `commit: cf6f93887c9f01bdea13158f7690d57c3d2c99ad` is written into the reviewed copy
  (the gate accepts it); lint, `checkupdates` (recipe unchanged) and `fdroid build -v -l` were repeated with the
  scratch recipe pointing at a scratch clone of the tag: lint exit 0, checkupdates exit 0 with no change, build exit 0
  (`Successfully built app.meetingrecorder:1 from cf6f93887c9f01bdea13158f7690d57c3d2c99ad`, `BUILD SUCCESSFUL in 2m 36s`),
  same APK hash. The edit of the recipe copy is a working-tree change after the tag; it is committed only with the
  owner's go-ahead (release policy step 4).

## Dry-read of the submission package (T043), 2026-11-19

`submission.md` was followed literally from the first step as far as no account is needed (clone of `fdroiddata`,
branch `app.meetingrecorder`, copy of the recipe, one commit, the helper commands with the **real** recipe).
Findings: (1) **blocker**: with the real recipe `fdroid checkupdates` fails, "Authentication failed for
https://github.com/voneinem/meeting-recorder.git": the repository is private (HTTP 404 on the public page, GitHub
visibility `PRIVATE`); section 1.0 and step 0 were added to the package. (2) `scripts/fdroid/lint.sh` with the real
recipe path works (exit 0). (3) `checkupdates.sh` and `build.sh` with the real recipe need the repository to be public
and the tag pushed (steps 0 and 1): they are the pending final dry run (T044). (4) The "new file" check of step 5 is a
real-fork property: in the scratch clone the file already existed, which is an artifact of the scratch tooling. No
other missing step or wrong command was found; the package was corrected for (1).

## Final dry run against GitHub with the real recipe (T044), 2026-11-19

After the owner approved it, `main` and the tag `v0.1.0` were pushed and the repository was made public (HTTP 200 on the
public page; anonymous `git ls-remote` shows `refs/heads/main` at `205432c` and `refs/tags/v0.1.0^{}` at `cf6f938`).
With the **real** recipe copy (`Repo: https://github.com/voneinem/meeting-recorder.git`, commit
`cf6f93887c9f01bdea13158f7690d57c3d2c99ad`) and F-Droid's tooling:

| Step | Result |
|------|--------|
| `scripts/fdroid/lint.sh <recipe>` | exit 0, no rewrite, no warning |
| `scripts/fdroid/checkupdates.sh <recipe>` | exit 0, recipe unchanged; the submodule was fetched from GitHub at `d1be6fde11ac6e0407606b4e42fe72d34add8037`; tag `v0.1.0` resolved to `cf6f93887c9f01bdea13158f7690d57c3d2c99ad` |
| `scripts/fdroid/build.sh <recipe>` (`buildserver-trixie`) | exit 0, `Successfully built app.meetingrecorder:1 from cf6f93887c9f01bdea13158f7690d57c3d2c99ad`, `BUILD SUCCESSFUL in 2m 31s`; APK 39,370,114 bytes, SHA-256 `159773be74c11a7fcb738928db4eae2c3865891c61d2deb1568f95184381ebde`, identical to all earlier builds |

## Open items

Everything that remains is for the owner, in this order (details and options in `submission.md`):

1. ~~Decide whether the repository becomes public.~~ **Done 2026-11-19**: made public by the assistant on the owner's explicit go-ahead (the history, including the earlier commits' author address, is public).
2. ~~Decide the three points of `submission.md` 1.1 to 1.3~~ **Decided 2026-11-19**: send now and state the age plainly, F-Droid signs, one APK with both ABIs. (Context:: Quality point 6 (the project is four days old; F-Droid asks
   for three months of active development unless an exception applies: about 2027-02-15 by age), reproducible builds
   with your own signature or F-Droid's signature (one-way at first publication; the app is technically ready), and the
   APK size / ABI split (one 39.4 MB APK).)
3. ~~Approve a documentation commit.~~ **Done 2026-11-19**: `205432c`; the tag stays on the release commit `cf6f938`. This record of the dry run is a later documentation change.
4. ~~Push `main` and the tag, final dry run (T044).~~ **Done 2026-11-19**, see above.
5. **Write the two marked passages** of the merge request text (why you wrote the app; the project's age) and the
   reproducible-builds reason.
6. **GitLab account, fork of `fdroiddata`, push of the branch `app.meetingrecorder`, the merge request, and the
   answers to the maintainers** (`submission.md` sections 3 and 4).
7. **The maintainers' answer on R-16** (the model download without an anti-feature): the decision is final only after it.
8. If you want the emulator or the phone checked again with the released F-Droid build, the device checks skipped in
   Phase 2 (`specs/002-offline-transcription/sc-results.md`) are unchanged and still skipped.
