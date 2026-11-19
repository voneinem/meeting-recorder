# Submission package: Meeting Recorder on F-Droid

Prepared 2026-11-19. Everything here is ready except the steps that need your accounts and decisions (section 5).
Nothing has been pushed, no account was created and no merge request was opened.

| What | Where |
|------|-------|
| The recipe that is submitted | `specs/004-fdroid-release/contracts/app.meetingrecorder.yml` (commit field filled with the tag's commit) |
| Release | `v0.1.0`, version code 1, commit `cf6f93887c9f01bdea13158f7690d57c3d2c99ad` (local tag, not pushed) |
| Evidence that it builds | `specs/004-fdroid-release/build-results.md` |
| The model-download question | `specs/004-fdroid-release/r16-decision.md` |

## Decisions taken by the owner (2026-11-19)

| Point | Decision | What follows |
|-------|----------|--------------|
| 1.0 Private repository | Make **this** repository public | The owner switches the visibility on GitHub (step 0 of section 3); the history becomes public, including the earlier commits' author address. Nothing in the recipe changes. |
| 1.1 Quality point 6 (project age) | Send now and say it plainly | The merge request text states the age and asks whether it can be considered anyway (drafted below). |
| 1.2 Signing | F-Droid signs | The recipe stays as it is; the merge request gives the reason in the reproducible-builds checklist item (drafted below). |
| 1.3 ABI split | One APK with both ABIs (specification decision) | No change. |

The sections below keep the options and reasoning behind these decisions.

## 1. Read this before you send: a blocker and three points that need your decision

The dry-read of this package found one blocker (resolved on 2026-11-19: the repository is public), and the F-Droid merge request template and policy raised three
things that this phase could not decide for you.

### 1.0 Blocker: the source repository is private

`https://github.com/voneinem/meeting-recorder` is **private**: its public page and the GitHub API answer 404 and
`git clone` asks for a login (checked 2026-11-19; `fdroid checkupdates` with the real recipe failed with
"Authentication failed for https://github.com/voneinem/meeting-recorder.git"). F-Droid builds from a public
source, so nothing below works until the repository is public.

Making it public is yours to decide, because it publishes everything at once: the whole git history (all
commits, the specs and notes, and the **author address of the earlier commits**, which is the address that this
project otherwise keeps out of its files), and the issue tracker named in the recipe. The recorded spike data
(`spike/audio/`, `spike/models/`, `spike/out/`) is ignored by git and not part of the history. If you do not want the
early history public, the alternative is a new public repository with a fresh history, which changes `Repo:`,
`SourceCode:` and `IssueTracker:` in the recipe and the commit hash; this is not prepared here.

### 1.1 Quality point 6: "actively developed for at least 3 months"

The inclusion policy (Quality Control, point 6) says an app "should have been actively developed for at least 3 months,
unless: the application has been proven to have some users; the author has been maintaining other apps on F-Droid;
the application is developed only for an event." The template says to pay special attention to it.

The git history of this repository starts on **2026-11-15** (four days before this package). None of the three
exceptions is documented anywhere in the project. Unless you maintain other F-Droid apps or the app already has users,
the maintainers can fairly ask you to wait; three months from the first commit is **2027-02-15**.

Options (not decided here):

| Option | What it means |
|--------|---------------|
| A. Send now and say it plainly | The merge request states the age of the project and asks whether it can be considered anyway. Risk: closed with "come back later". |
| B. Wait until about 2027-02-15 | The project then meets the rule by age. Meanwhile it can gain users (a GitHub release with the signed APK, for example) and maintenance history. |
| C. Send now with an exception | Only if one of the exceptions is true for you (other F-Droid apps, existing users). Say which, with proof. |

### 1.2 Reproducible builds (the template's checklist item)

The template says: "Enable Reproducible Builds. We'll use your signature ... if you don't enable reproducible build
then the apk will be signed with our key so you can't enable it later. If you can't enable this, please add the
reasons here." This is a one-way choice at the first publication.

Facts from this phase: the release APK of `v0.1.0` is **byte-identical** when built in two different directories and
in F-Droid's build image (SHA-256 `159773be74c11a7fcb738928db4eae2c3865891c61d2deb1568f95184381ebde`, 39,370,114
bytes; all 24 native libraries identical; `build-results.md`). The app is therefore technically able to use
reproducible builds.

| Option | What it needs from you | Consequence |
|--------|------------------------|-------------|
| A. F-Droid signs (the recipe as it is) | nothing | Users get F-Droid's signature; the app can never switch to your own signature, and a build signed by you elsewhere (GitHub release) cannot update the F-Droid one. The merge request must give a reason in the checklist item (suggested: "no signing key is kept for this project yet; the app is built reproducibly and can be verified by F-Droid's rebuild"). |
| B. Reproducible builds with your signature | an own signing key kept **outside** this repository (FR-007: no key in the repository), the signed APK of every release published at a versioned HTTPS address, and two lines in the recipe (`Binaries:` with that address and `AllowedAPKSigningKeys:` with the key's SHA-256 fingerprint) | One signature for F-Droid and for your own releases; F-Droid publishes a version only if its rebuild matches your signed APK. You carry the key and publish before each release. |

Not decided here. The recipe copy is Option A. Switching to B changes only the recipe (and the release steps in
`contracts/release-policy.md`), not the app.

### 1.3 APK size and ABI split (the template's checklist item)

The template asks to "setup abi split if the APK is large and the splitted ones can be much smaller". The release APK
is 39.4 MB because it carries the native speech engine for both supported ABIs (`arm64-v8a` and `x86_64`, extracted
because the engine loads its CPU variant libraries by path). Specification decision (clarification 3): one APK with
both ABIs, no change to the build in this phase. A split by ABI (a `VercodeOperation` scheme in the recipe and a
Gradle `splits` block) is a later release decision; the review checklist (section 4) has the prepared answer.

## 2. Merge request

**Title** (the template requires this form): `New app: Meeting Recorder`

**Description** (paste, then tick the template's boxes; answers for the boxes that need words are in section 4):

```markdown
## Abstract for new app inclusion

Meeting Recorder (`app.meetingrecorder`, GPL-3.0-or-later) records the audio of a meeting and lets you take photos
of whiteboards or slides while it runs. Every photo is placed at the exact moment of the recording where it was
taken. A meeting is transcribed on the phone (whisper.cpp, built from source as a git submodule), also with the
screen off, and the transcript places the photos in the text. Everything is stored as a plain folder
(`audio.wav`, `photos/`, `meeting.json`, `transcript.md`, a generated `CLAUDE.md` that explains the format), so it
can be read without the app. No tracking, no accounts, no cloud; the app has no Google or other proprietary
dependency. Screenshots (also in `fastlane/metadata/android/en-US/images/phoneScreenshots/` of the source
repository): <attach the four images>.

Source: https://github.com/voneinem/meeting-recorder, tag `v0.1.0`, commit
`cf6f93887c9f01bdea13158f7690d57c3d2c99ad`. Issue tracker: https://github.com/voneinem/meeting-recorder/issues

### Why I wrote this app and what it offers over similar apps in F-Droid

<OWNER: write this in your own words (why you wrote it). I have not compared the app with other apps in the
F-Droid repository, so I do not claim anything here about them. Facts that are true and can be used: the photos are
placed on the audio timeline; transcription runs on the phone; the meeting folder is open, documented and meant to
be handed to an AI assistant; recording survives crashes and is repaired on the next start.>

## Points I want to raise

* **Model download.** The app can download MIT-licensed speech-recognition model data from huggingface.co, only
  after the user taps Download and a dialog names the file, its size and the source. The address is pinned to a
  Hugging Face commit and a SHA-256, only `huggingface.co` and `hf.co` are reachable (network security config),
  nothing is bundled in the APK, and a model can be imported from a file instead; the app works without any
  model. No meeting data is ever sent. I declared **no anti-feature**, because the Inclusion Policy restricts
  downloads of executable binaries and `NonFreeNet` is defined for apps that promote or depend entirely on a
  proprietary service. Is that acceptable? If you prefer a label (`NonFreeAssets` or `NonFreeNet`) or an
  import-only variant, tell me which and I will change the recipe or the app. Details:
  specs/004-fdroid-release/r16-decision.md in the source repository.
* **Age of the project (Quality point 6).** The first commit of the project is from 2026-11-15, so it is only a few
  days old and does not meet the three-month rule by age; I know of no exception that applies (I maintain no other
  app on F-Droid and there is no user base yet). I would still like to submit it now to get early feedback on the
  recipe and on the model download. If you prefer to wait until the project is three months old, tell me and I
  will come back then.
* **Reproducible builds.** I am not enabling signature-based reproducible builds: I do not keep a signing key for
  this project and want F-Droid to sign it. The release APK is byte-identical when built in two directories and
  in the F-Droid build image, so F-Droid's own rebuild can be verified. <OWNER: adapt the reason if it is
  different.>
* **Size.** The APK is about 39 MB: it carries the speech engine for `arm64-v8a` and `x86_64` (extracted
  native libraries). I can split by ABI if you prefer.

## Build notes

* `submodules: true`: the speech engine is the git submodule `whisper/src/main/cpp/whisper.cpp`
  (https://github.com/ggml-org/whisper.cpp), pinned at `d1be6fde11ac6e0407606b4e42fe72d34add8037` (v1.9.5);
  `./gradlew :app:verifyWhisperPin` fails when the checkout differs.
* `ndk: r29` (fdroidserver downloads it; the project pins NDK 29.0.14206865) and CMake 3.31.6 (installed by the
  Android Gradle Plugin). No `sudo:` or `prebuild:` is needed.
* `rm:` removes six directories of the engine (`examples`, `bindings`, `models`, `samples`, `tests`, `ci`): they
  hold prebuilt test files (`.bin` models, `jfk.wav`, three `gradle-wrapper.jar`) that nothing builds. The project
  enforces the same list (`:app:verifyNoPrebuiltBinaries`, `:app:verifyReleaseMetadata`).
* Built locally with `fdroid build -v -l app.meetingrecorder` in
  `registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie` (exit 0); `fdroid lint`, `rewritemeta`, `readmeta`
  and `checkupdates` are clean for the recipe.
* Permissions: `RECORD_AUDIO`, `CAMERA`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE` (+ microphone, data sync, media
  processing types), `WAKE_LOCK`, `INTERNET` (the model download only). The merged manifest is checked against this
  allow-list by `:app:verifyManifestPermissions`.

## What was not verified on a real device

The app was developed on an emulator and one phone; device checks that were skipped (details in
`specs/002-offline-transcription/sc-results.md`): a one-hour transcription with the screen off, forced
termination at 50% and a reboot, the cancel button of the transcription notification, the cancel and delete screens,
cancelled and killed re-transcriptions, the model download over Wi-Fi, and measurements of first use time,
tap-to-play latency and 3-hour transcript scrolling. The core, the transcription runner and the model install
logic are covered by 612 unit tests and by instrumented tests on emulators.

Closes rfp#<RFP issue number, if you opened one; otherwise remove this line>
```

## 3. Command sequence

Steps marked **(owner)** use your accounts. Everything else was run in this phase with the same tools (results in
`build-results.md`), so each step has an expected result.

| # | Command or action | Expected result |
|---|-------------------|-----------------|
| 0 | **(owner)** Make the repository public (GitHub, Settings, General, Danger Zone, Change visibility; section 1.0). Check: `git ls-remote https://github.com/voneinem/meeting-recorder.git` works in a shell that is not logged in to GitHub (or `curl -I https://github.com/voneinem/meeting-recorder` answers 200). | The repository page opens without login. |
| 1 | **(owner)** In `~/Projekte/meeting-recorder`: `git push origin main` and `git push origin v0.1.0`. The release commit and the tag are local only until you do this. | The tag `v0.1.0` is visible at https://github.com/voneinem/meeting-recorder/tags; `git ls-remote --tags origin v0.1.0` prints the tag. |
| 2 | **(owner)** Register at https://gitlab.com and fork https://gitlab.com/fdroid/fdroiddata (button "Fork"); keep the fork public (the template requires it). | `https://gitlab.com/<you>/fdroiddata` exists. |
| 3 | **(owner)** `git clone https://gitlab.com/<you>/fdroiddata.git ~/fdroiddata-fork && cd ~/fdroiddata-fork` (a large repository: use `--depth 1` if you like). | A clean checkout of `master`. |
| 4 | `git checkout -b app.meetingrecorder` (never commit to `master`; do not open the merge request from a protected branch). | On branch `app.meetingrecorder`. |
| 5 | `cp ~/Projekte/meeting-recorder/specs/004-fdroid-release/contracts/app.meetingrecorder.yml metadata/app.meetingrecorder.yml` (LF line endings; the file is in `rewritemeta` form). If you chose reproducible builds (section 1.2, option B), add the `Binaries:` and `AllowedAPKSigningKeys:` lines first. | `git status` shows one new file, `metadata/app.meetingrecorder.yml`, and nothing else. |
| 6 | Checks, either with fdroidserver on your machine (`pip install git+https://gitlab.com/fdroid/fdroidserver.git`) or with the container helpers of this repository (they use F-Droid's own images): `fdroid readmeta`, `fdroid rewritemeta app.meetingrecorder`, `fdroid checkupdates app.meetingrecorder`, `fdroid lint app.meetingrecorder`, `fdroid build -v -l app.meetingrecorder`. With the helpers (run them in `~/Projekte/meeting-recorder`, they point at the real recipe once the tag is on GitHub): `scripts/fdroid/lint.sh specs/004-fdroid-release/contracts/app.meetingrecorder.yml`, `scripts/fdroid/checkupdates.sh <recipe>`, `scripts/fdroid/build.sh <recipe>` (the build takes about 12 minutes). | `readmeta`, `lint` and `checkupdates` print no error and change nothing; the build ends with `Successfully built app.meetingrecorder:1`. |
| 7 | `git add metadata/app.meetingrecorder.yml && git commit -m "New app: Meeting Recorder"` (squash is the default in the merge request; the commit shows your git name and address). | One commit. |
| 8 | **(owner)** `git push origin app.meetingrecorder` | The branch is on your fork; its CI pipeline starts. |
| 9 | **(owner)** Watch the pipeline of your fork (CI/CD, Pipelines). All jobs must pass and the Reports tab must have no unexplained warning; F-Droid's CI runs are free (GitLab FOSS program). If GitLab asks for a phone number or credit card, do not enter any: say so in the merge request. | Green pipeline. |
| 10 | **(owner)** Open the merge request against `fdroid/fdroiddata` (the GitLab banner after the push, or "Merge requests, New"). Title and description from section 2; tick the template's checklist; keep "Squash commits" enabled. | The merge request is open and labelled "New App". |
| 11 | **(owner)** Answer the maintainers' questions promptly, using the prepared answers of section 4. | |

## 4. Review checklist: prepared answers

| Maintainer topic | Prepared factual answer | Proof in the repository |
|------------------|-------------------------|-------------------------|
| Prebuilt binaries and the `rm:` list | None in the project's sources. The six `rm:` directories hold the engine's prebuilt test files; the build never uses them, and the project fails its own build when a binary appears outside that list. F-Droid's build scanner ran and reported nothing. | `gradle/verification-tasks.gradle.kts` (`verifyNoPrebuiltBinaries`), `build-results.md` |
| The engine submodule | `whisper.cpp` v1.9.5 as a git submodule at a pinned commit; the checkout is verified. | `.gitmodules`, `gradle/whisper.pin`, `verifyWhisperPin` |
| Permissions and why each | `RECORD_AUDIO` (recording), `CAMERA` (photos), `POST_NOTIFICATIONS` (recording and progress notifications), `FOREGROUND_SERVICE` with microphone, data-sync and media-processing types (recording and transcription in the background), `WAKE_LOCK` (a transcription or download with the screen off), `INTERNET` (the model download only). Runtime permissions are requested at the moment a feature needs them. | `app/src/main/AndroidManifest.xml` (a comment per permission), `verifyManifestPermissions` |
| Network use and the model download | The only network code is in `models/download/`; only `huggingface.co` and `hf.co` over HTTPS are reachable (network security config with empty base trust anchors, cleartext off); the download starts after a dialog; pinned address and SHA-256; no meeting data is sent. No anti-feature declared, with the reasoning and the question. | `r16-decision.md`, `network_security_config.xml`, `NetworkSourceScannerTest`, `ModelDownloaderTest` |
| Anti-features | None declared. If asked for `NonFreeAssets` or `NonFreeNet`: accept, add the line to the recipe and one explaining sentence to the store text (the gate requires the decision record to name it). | `contracts/app.meetingrecorder.yml`, `r16-decision.md` |
| Trackers and proprietary dependencies | None: no Google Play Services, Firebase or other proprietary SDK; the release runtime classpath is checked on every build. | `verifyNoForbiddenDependencies` |
| License and third-party notices | GPL-3.0-or-later. whisper.cpp and ggml: MIT (source, submodule). The model files: MIT, shown in the app before the download, never bundled. AndroidX libraries: Apache-2.0. | `LICENSE`, `README.md` "Third-party notices" |
| Reproducibility | The unsigned release APK is byte-identical in two directories and in F-Droid's build image; all 24 native libraries identical. Pinned toolchain (NDK, CMake, Gradle distribution checksum, dependency verification metadata). | `build-results.md` |
| APK size and ABIs | 39.4 MB: the speech engine for `arm64-v8a` and `x86_64`, extracted (`useLegacyPackaging`) because the engine loads its CPU variant libraries by path. A split by ABI is possible if asked. | `app/build.gradle.kts`, `whisper/build.gradle.kts` |
| NDK and CMake versions | NDK 29.0.14206865 (`ndk: r29`, downloaded by fdroidserver) and CMake 3.31.6 (installed by the Android Gradle Plugin during the build), both pinned in `whisper/build.gradle.kts`. | `build-results.md` |
| Signing | F-Droid signs (or the choice of section 1.2). The repository holds no key. An F-Droid-signed build cannot update a debug build: uninstall the debug build first. | `contracts/release-policy.md`, `verifyReleaseMetadata` |
| The description's claims against the app | Every claim about privacy, network use, storage, consent and supported devices is checked against `README.md` and the app's strings. Screenshots are from the F-Droid-built APK with demo data. | `contracts/store-metadata.md`, `build-results.md` |
| Author fields | `AuthorName: Volker von Einem`, `AuthorEmail: vve-f-droid@gmx.net`; the About screen shows the copyright line. | `contracts/app.meetingrecorder.yml`, `README.md` |
| Version scheme and update detection | `versionName` semantic version, `versionCode` +1 per release, tag `v<versionName>`; `UpdateCheckMode: Tags` with an explicit `UpdateCheckData`; `AutoUpdateMode: Version`. A throwaway tag with a higher version was detected correctly. | `contracts/release-policy.md`, `build-results.md` |
| Age of the project | See section 1.1: the first commit is from 2026-11-15. | `git log` |

**If the maintainers say ...**

| They say | You do |
|----------|--------|
| Add `NonFreeAssets` or `NonFreeNet` | Accept. Add `AntiFeatures: NonFreeAssets` (or `NonFreeNet`) to the recipe copy, record it in `r16-decision.md` as `anti-feature: <Name>` (the gate fails otherwise), add one sentence to both store texts, re-run lint. |
| Remove the download | Apply the import-only fallback of `r16-decision.md`, bump the version code, tag `v0.1.1`, update the recipe. |
| Make the APK smaller | A later per-ABI decision (a `VercodeOperation` scheme and Gradle `splits`), not part of this phase; ask them which ABIs they want. |
| Wait three months | Option B of section 1.1. Nothing in the repository needs to change; the recipe copy stays valid and its commit field must then be the commit of the release you tag later. |
| Use your own signature (reproducible builds) | Option B of section 1.2. |

## 5. Owner-only steps (FR-023)

Nothing in this phase pushed, created an account or opened a merge request. These steps are yours:

1. Make the repository public (decided; section 1.0). Sections 1.1 to 1.3 are decided (see above).
2. Write the one passage of the merge request text that only you can write (why you wrote the app) and check
   the drafted age statement and the signing reason (both are drafted from your decisions of 2026-11-19).
3. Push the branch `main` and the tag `v0.1.0` to `https://github.com/voneinem/meeting-recorder` (section 3, step 1).
4. Create the GitLab account, fork `fdroiddata`, push the branch `app.meetingrecorder`, open the merge request,
   answer the review.
5. **Final dry run against GitHub (task T044, done 2026-11-19)**: after the repository was made public and the tag pushed, `scripts/fdroid/lint.sh`,
   `scripts/fdroid/checkupdates.sh` and `scripts/fdroid/build.sh` were run with the real recipe (`build-results.md`); to repeat it, run `scripts/fdroid/lint.sh`,
   `scripts/fdroid/checkupdates.sh` and `scripts/fdroid/build.sh` with the **real** recipe copy (its `Repo:` is the
   GitHub address). It could not run here because the tag is not on GitHub yet; the same three steps passed against a
   scratch clone of the tag with the same commit.
