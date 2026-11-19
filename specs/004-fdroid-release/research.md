# Research: F-Droid Release

Date: 2026-11-19. Sources read on that date: F-Droid documentation (Inclusion Policy, Anti-Features,
Build Metadata Reference, All About Descriptions, Graphics and Screenshots), the `fdroiddata`
`CONTRIBUTING.md` and `.gitlab-ci.yml`, and the `fdroidserver` buildserver `Dockerfile`. Statements marked
*(not verified)* are open until the experiment named next to them has run.

## R-1: Does the model download need an anti-feature? (spec R-16, FR-018, FR-019)

- **Finding**: the Inclusion Policy restricts one kind of runtime download: apps "must not download
  additional executable binary files" without explicit, opt-in user consent. It says nothing about
  non-executable data such as model weights. The Anti-Features page defines the candidates narrowly:
  `NonFreeNet` is "applied to apps that promote or depend entirely on a proprietary network service";
  `NonFreeAssets` is for non-free media shipped with the app; `NonFreeDep` is for apps that need
  non-free software to run. None of the pages mentions downloaded models or data.
- **Facts about the app** (all in the repository): the files are MIT-licensed (Whisper weights as
  converted by `ggml`, Silero VAD); they are not bundled in the APK; the download starts only after a
  confirmation that names file, size and source; the source is a pinned Hugging Face commit with a
  pinned SHA-256; redirects are checked hop by hop and only `huggingface.co` and `hf.co` are allowed; no
  meeting data is ever sent; the app works without the download (import from a file, or recording only).
- **Decision (preliminary, final form in `r16-decision.md`)**: declare **no anti-feature**. Reasoning:
  `NonFreeNet` does not fit because the app neither promotes nor depends entirely on a service (file
  hosting, replaceable by import); `NonFreeAssets` does not fit because nothing non-free ships in the app
  and the weights are MIT; the policy's download rule concerns executables, and a ggml model file is data
  read by the engine, never executed. State this in the merge request together with the question "is a
  user-initiated download of MIT-licensed model data from a pinned third-party host acceptable without
  an anti-feature?" (clarification 1).
- **Residual risk, stated honestly**: whether weights count as "free" when the training data is not open
  is a debated question in the open-source community; F-Droid has no written rule for it. The maintainers
  may ask for `NonFreeAssets` or `NonFreeNet`. Both are labels, not rejections ("without necessarily
  disqualifying applications"), and the store description already explains the download in plain words.
  If they refuse the download altogether, the fallback applies.
- **Fallback (import-only), to be written out in `r16-decision.md`**: remove `INTERNET` and the network
  security config from the manifest and the allow-list of `verifyManifestPermissions`; remove the
  `models/download/` package and its tests and the catalog's download entries (the catalog invariants test
  keeps working with an empty download list); keep the import path, the model screen (import and delete),
  the checksum verification of imports; change the settings, README and store texts that mention the
  download; update `NetworkSourceScannerTest` (no allowed package) and the repository `CLAUDE.md` rules.
  The release is not blocked by this: it is a prepared switch.
- **Alternatives considered**: declaring `NonFreeNet` pre-emptively (rejected: it would label the app
  with a warning the policy does not require and which describes a proprietary service the app does not
  depend on); asking in an inclusion issue first (rejected by the owner, clarification 1); shipping
  import-only from the start (rejected: the download is the convenience that makes transcription usable
  and is explicitly allowed by the constitution, Principle I).

## R-2: Recipe format and fields (FR-015, FR-017)

- **Decision**: a YAML file `metadata/app.meetingrecorder.yml` with: `Categories` (a list),
  `License: GPL-3.0-or-later`, `AuthorName: Volker von Einem`, `AuthorEmail: vve-f-droid@gmx.net`,
  `SourceCode`, `IssueTracker`, `RepoType: git`, `Repo`, a `Builds` entry (`versionName`, `versionCode`,
  full `commit` hash, `subdir: app`, `submodules: true`, `gradle: yes`, `rm:` list, `ndk`), and
  `AutoUpdateMode: Version`, `UpdateCheckMode: Tags` with an explicit `UpdateCheckData`, `CurrentVersion`
  and `CurrentVersionCode`.
- **Rationale**: the reference documents these exact fields. `commit` must be a full hash, not a tag
  name. `AutoUpdateMode: Version` together with `UpdateCheckMode: Tags` uses the checked tag directly.
  The title, summary and description come from `fastlane/` in the source repository; the reference says
  not to set them in the recipe ("the localized metadata in the app's source code should be used").
  `AutoName` is the one exception: `fdroid checkupdates` writes it from the app label, and `fdroiddata`'s CI
  fails when `checkupdates --auto` changes the file, so the recipe carries `AutoName: Meeting Recorder`
  (found by the local run, `build-results.md`).
- **`UpdateCheckData`** (explicit, so detection does not depend on default heuristics):
  `app/build.gradle.kts|versionCode\s*=\s*(\d+)|app/build.gradle.kts|versionName\s*=\s*"([^"]+)"`. It
  matches `versionCode = 1` and `versionName = "0.1.0"` in `app/build.gradle.kts`, the single place of
  the version (R-4). Checked by `fdroid checkupdates` in Q5.
- **`rm:`**: the six directories of the engine tree (`examples`, `bindings`, `models`, `samples`,
  `tests`, `ci` under `whisper/src/main/cpp/whisper.cpp`), exactly the list that `verifyNoPrebuiltBinaries`
  skips (fdroid-build-notes.md). `rm:` paths are relative to the repository root.
- **Open detail**: with `subdir: app` in a multi-module Gradle project, F-Droid must run the root
  wrapper. Many multi-module recipes use `subdir: app`; confirmed or corrected by the local build (Q4).
- **Category**: `Multimedia` (voice recorders); `fdroid lint` checks the name against the known list (Q3).
- **Alternatives**: `Binaries:` for reproducible verification against an upstream-signed APK (rejected for
  this release: it needs an owner signing key, which the spec excludes, FR-007); omitting `UpdateCheckData`
  (rejected: relying on defaults makes `checkupdates` less predictable).

## R-3: F-Droid's build environment (FR-015, FR-017, SC-001)

- **Finding**: `fdroiddata`'s CI builds in `registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie`
  (Debian 13), installs `openjdk-21-jdk-headless` per app and makes it the default Java, sets
  `ANDROID_HOME=/opt/android-sdk`, and removes the preinstalled NDK before each app
  (`rm -rf $ANDROID_HOME/ndk`), which implies the NDK is obtained per recipe. Lint and `checkupdates`
  run in `debian:trixie-slim` with the `fdroidserver` package.
- **Consequences**: JDK 21 matches the project (`CLAUDE.md`). The Gradle wrapper is checked by F-Droid's
  scanner against known wrapper hashes; the project's wrapper is the standard one with a pinned
  distribution checksum.
- **Not verified (experiment Q4)**: whether `ndk: r29` resolves to NDK `29.0.14206865` and whether CMake
  `3.31.6` (pinned in `whisper/build.gradle.kts`) can be installed in the build. The documents do not list
  the available NDKs. The experiment runs the real build in the real image. If the NDK or CMake is
  missing, the recipe gets a `sudo:` or `prebuild:` step that installs `ndk;29.0.14206865` and
  `cmake;3.31.6` with `sdkmanager`; if the build server forbids network access for that step, the fallback
  is to ask the maintainers for the NDK (named in the merge request). The outcome and the final recipe
  lines are recorded in `build-results.md`.
- **Alternatives**: building on the host with the project's own toolchain only (rejected: it proves the
  project builds, not that F-Droid's environment builds it).

## R-4: Version policy and update detection (FR-005, FR-006)

- **Decision** (written down in `contracts/release-policy.md`): the version lives only in
  `app/build.gradle.kts` (`versionName`, `versionCode`). `versionName` follows semantic versioning
  (`MAJOR.MINOR.PATCH`). `versionCode` is a plain integer that increases by 1 for every release
  (`0.1.0` is code 1). The tag is `v<versionName>`. The recipe's `CurrentVersion` and
  `CurrentVersionCode` equal the latest release.
- **Rationale**: one place, no derived arithmetic to get wrong, and it matches what is already in the
  build, so this phase needs no build change. A single universal APK (clarification 3) needs one code per
  release; per-ABI codes (`VercodeOperation`) are not needed.
- **Alternatives**: a code derived from the name (`major*10000+minor*100+patch`) (rejected: it would
  change the existing code 1 for no benefit and makes a hotfix numbering rule necessary); a date-based code
  (rejected: unrelated to the release contents).

## R-5: Store metadata (FR-010 to FR-014)

- **Finding**: F-Droid reads `fastlane/metadata/android/<locale>/` from the repository. Limits: title 50
  characters, `short_description.txt` 80, `full_description.txt` 4000, `changelogs/<versionCode>.txt` 500
  (the file is named by the version code without padding). Images: `images/icon.png` (typically 512 x 512),
  `images/featureGraphic.png` (typically 1024 x 500, optional), `images/phoneScreenshots/1.png`, `2.png`,
  and so on, PNG or JPEG. A directory other than the listed ones is ignored.
- **Current state** (measured): titles 17 characters; short descriptions 77 (en-US) and 69 (de-DE);
  full descriptions about 2050 and 2300 characters. All are within the limits. Missing: changelogs, icon,
  screenshots. The texts must gain the supported architectures and the unavailability of transcription on
  devices without them (FR-014); they already state the download and the unencrypted storage.
- **Decision**: `verifyReleaseMetadata` enforces the limits, the presence of every file for both locales
  (images are shared: they live under `en-US/images/`, the fallback locale, and `de-DE` reuses them), the
  changelog file named after the version code, and that the full descriptions mention the same three
  facts as the README (download only on tap, unencrypted storage, consent).
- **Changelog 1.txt** (en and de): a plain description of the first release (recording, photos,
  transcription on the device, open folders).
- **Alternatives**: locale-specific screenshots (rejected: the spec shares them; German screenshots can
  follow in a later release).

## R-6: Signing and reproducibility (FR-007, FR-008, SC-003)

- **Decision**: the release build type stays unsigned in the repository (it already needs no signing
  configuration); F-Droid signs with its own key. `.gitignore` already excludes `*.jks`, `*.keystore` and
  `keystore.properties`. The gate scans for signing material anyway (R-8).
- **Reproducibility**: the existing method (task T173 of Phase 2, `spike-results.md`) builds the same
  commit in two different directories and compares the native libraries byte for byte. The new check
  repeats it on the release tag, over **all** native libraries of the APK, and also reports whether the
  complete unsigned APKs are identical. Native-library identity is the requirement (SC-003); full-APK
  identity is recorded as information.
- **Consequence for users**: an F-Droid-signed build cannot update a debug build (different signatures);
  the submission notes that an uninstall is needed (spec US5 scenario 4).
- **Alternatives**: publishing an own-signed APK for F-Droid to verify (`Binaries:`) (rejected, see R-2).

## R-7: Local verification method (FR-017, SC-001)

- **Decision**: use the image F-Droid's own CI uses (`buildserver-trixie`) in Docker, with a local clone of
  `fdroiddata` outside this repository (a scratch directory, never committed). Steps follow
  `fdroiddata`'s `CONTRIBUTING.md`: `fdroid readmeta`, `fdroid rewritemeta app.meetingrecorder`,
  `fdroid checkupdates app.meetingrecorder`, `fdroid lint app.meetingrecorder`,
  `fdroid build -v -l app.meetingrecorder`. Lint and checkupdates also run in `debian:trixie-slim` with
  the `fdroidserver` package, as the CI does, to match the merge request pipeline.
- **The source address**: the real recipe points at `https://github.com/voneinem/meeting-recorder.git`,
  which does not have the tag until the owner pushes it. The local dry run therefore uses a **scratch
  copy** of the recipe whose `Repo:` is the local repository path (mounted into the container), and a
  commit hash that exists locally. The final dry run against GitHub is a step after the owner pushes
  the tag (quickstart Q9) and is marked as such; the scratch copy is never submitted.
- **Docker note**: a gotify container is running on the development machine; the image pull adds several
  gigabytes (140 GB free). Nothing in the verification touches other containers.
- **Alternatives**: installing `fdroidserver` with `pip` on the host (rejected: it does not reproduce
  the build image, JDK or SDK layout); relying on GitLab CI after submission only (rejected: a failed
  first pipeline costs a review cycle).

## R-8: The new gate `verifyReleaseMetadata` (FR-007, FR-016, FR-003)

- **Decision**: one Gradle task next to the four existing verification tasks, wired into `check`, using
  plain file reading and regular expressions (no YAML library, so no new dependency). It fails when:
  1. the recipe copy's version name or code differs from `app/build.gradle.kts`, or its `rm:` list differs
     from the constant that `verifyNoPrebuiltBinaries` uses, or its `ndk` differs from `ndkVersion`,
     or `submodules` is not true, or the license or author fields differ from the expected values;
  2. a store file is missing, a length limit is exceeded, or the changelog for the version code is missing;
  3. a signing file (`*.jks`, `*.keystore`, `keystore.properties`) is tracked, or a **build file** contains
     a signing secret name (`storePassword`, `keyPassword`, `keyAlias`), or the release build type defines
     a signing configuration. The content scan is limited to the build files (never the specs, the gate's
     own source or the engine submodule), because a scan of all tracked files would match this very
     document;
  4. the author's private git commit address appears in a tracked file (FR-003). The gate does not contain
     the address: it derives the forbidden addresses at run time from `git log --format=%ae`, minus the
     F-Droid address and minus GitHub `noreply` addresses, so neither the gate nor any spec repeats it. The
     F-Droid address is the only author address allowed. Without `git` or history the gate warns and does
     not fail;
  5. the About strings are missing in either language (the existing parity test already covers this; the
     gate checks the new key specifically).
- **Rationale**: it makes FR-016 mechanical, as the spec requires ("a check MUST fail when they differ").
  Its failure paths are exercised by the negative checks in the quickstart (Q2) because a Gradle task body
  is not a unit-test target in this project (the existing four tasks have none either).
- **Alternatives**: a Kotlin unit test in `:core` that reads the files (rejected: `:core` stays free of
  build-layout knowledge); a shell script only (rejected: `check` is the one command everyone runs).

## R-9: Screenshots (FR-012)

- **Decision**: four to six phone screenshots as PNG, light appearance, taken from the **release build**
  on an emulator (preferred; CLAUDE.md warns against running instrumented tests on a phone with real
  data, and an emulator guarantees demo data) or on the test phone using a demo storage folder.
  Screens: meeting list, a running recording, playback with a transcript and photos, the model settings
  screen, optionally the About/settings screen. The demo meeting is a short synthetic recording with
  demo photos (no real people, no real meeting content).
- **Rationale**: F-Droid shows them on the app page; demo data protects the privacy the app promises.
- **Size**: the device's native resolution (for example 1080 x 2400); F-Droid recompresses.
- **Alternatives**: framed or marketing-style images (rejected: unneeded effort, and they can mislead).

## R-10: The store icon (FR-012)

- **Decision**: a 512 x 512 PNG rendered from the adaptive launcher icon's own layers
  (`ic_launcher_foreground.xml` and its background color), so the store and the launcher match. The
  rendering method (an Android Studio-free route: render the vector through the emulator or convert the
  vector to SVG with a script) is chosen in the task; the acceptance rule is: square, 512 x 512, matches
  the launcher icon, no transparency artifacts.
- **Alternatives**: a separately designed icon (rejected: two icons confuse users).

## R-11: The About line (FR-001, FR-025)

- **Decision**: one new string `settings_copyright` ("Copyright © 2026 Volker von Einem") in
  `values/strings.xml` and `values-de/strings.xml` (the text is the same in both languages because the
  name and the notice are not translated, but the key must exist in both for lint), and one `Text` line
  in the About block of `SettingsScreen.kt` between the license and the privacy lines. It uses the
  existing text style; no new component, color or permission, so `ThemeSourceRulesTest` and the
  hard-coded-string scan stay green.
- **Alternatives**: a separate About screen (rejected: unneeded navigation for one line; the existing
  About block is the right place).
