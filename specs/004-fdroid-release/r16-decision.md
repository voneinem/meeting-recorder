# Decision record R-16: the model download and F-Droid

Date: 2026-11-19. Status: **decided by the project, final only after the F-Droid maintainers' reply**
(clarification 1: the question travels in the merge request, no separate inclusion issue is opened first).

## The question

Meeting Recorder downloads speech-recognition model files from a third-party host (`huggingface.co`), only
after the user taps Download. Does that need an anti-feature label, or is it a reason to refuse the app?

Outcome chosen: **`no-anti-feature`**, with the question below put to the maintainers in the merge request and
an import-only fallback prepared.

## Sources read (2026-11-19)

| Source | What it says that matters here |
|--------|--------------------------------|
| F-Droid Inclusion Policy, https://f-droid.org/docs/Inclusion_Policy/ | Apps "must not download additional executable binary files" without explicit, opt-in user consent. The policy names no other kind of runtime download; it says nothing about non-executable data such as model weights. All apps must be FLOSS. |
| F-Droid Anti-Features, https://f-droid.org/docs/Anti-Features/ | `NonFreeNet` "is applied to apps that promote or depend entirely on a proprietary network service". `NonFreeAssets` "is applied to apps that contain and make use of Non-Free assets" (restricted images, sounds, music). `NonFreeDep` is for apps that "require things that are not Free Software in order to run". The page does not mention downloaded models or data. |
| Build Metadata Reference, https://f-droid.org/docs/Build_Metadata_Reference/ | `AntiFeatures` is a comma-separated list; valid values include `NonFreeNet`, `NonFreeAssets`, `NonFreeDep`. The reference recommends explaining the reasons in the description. |

The sources are not conclusive for this exact case: no written rule covers a user-started download of
non-executable model data from a third-party host. That is why the question is asked.

## Facts about the app (each with the file that proves it)

| Fact | Proof |
|------|-------|
| The model files are not in the APK. | `verifyNoPrebuiltBinaries` (`gradle/verification-tasks.gradle.kts`); `core/src/main/kotlin/app/meetingrecorder/core/models/ModelCatalog.kt` holds addresses, not files. |
| Each file is MIT-licensed: the Whisper weights as converted by `ggml`, and the Silero voice-activity model. | `ModelCatalogEntry.license`, shown in the app before the download; `README.md` "Third-party notices"; `ModelCatalogInvariantsTest` accepts only licenses on the F-Droid-acceptable list (`MIT`). |
| The download starts only after a confirmation that names the file, its size and the source. | `ModelSettingsScreen` and the dialog strings `model_download_title`, `model_download_message` (`app/src/main/res/values/strings.xml`); device check of scenario S11 in `specs/002-offline-transcription/tasks.md` T115. |
| Each file is pinned: a Hugging Face commit in the address and a SHA-256. | `ModelCatalog.kt`; `ModelCatalogInvariantsTest` (address contains a 40-hex commit, never `main`; `sha256` has 64 hex digits); `scripts/verify-model-catalog.sh` (development only). |
| Only `https`, only `huggingface.co` and `hf.co`, redirects checked hop by hop. | `app/src/main/kotlin/app/meetingrecorder/models/download/ModelDownloader.kt`; `DownloadRules.kt` and `DownloadRulesTest`; `ModelDownloaderTest`; `app/src/main/res/xml/network_security_config.xml` (empty base trust anchors, cleartext off). |
| No other network use exists. | `NetworkSourceScannerTest` (only the `models/download/` package may use `java.net`, sockets or an HTTP client); `verifyManifestPermissions` (the merged manifest has exactly the allowed permissions, and `INTERNET` only with the restricted network configuration). |
| No meeting data is ever sent; the model request carries no user data. | The downloader sends a plain `GET`; no analytics, no tracker (`verifyNoForbiddenDependencies`). |
| The app works without the download. | Recording, photos, playback need no model; a model can be imported from a file (`ModelImporter`, `ModelImporterTest`), with the network switched off. |
| A downloaded file is verified before it is used. | `Sha256Streaming.kt`, `ModelFileHeader.kt` and `ModelInstallRunner` (checksum mismatch leaves the model not installed; `ModelInstallTest`). |

## Reasoning

- **Not `NonFreeNet`**: the label is for apps that promote or depend entirely on a proprietary network
  service. The app depends on no service: it needs a file, which the user may fetch by the app or supply
  by hand (import); the app does not promote Hugging Face; and the host serves the files under their free
  licenses.
- **Not `NonFreeAssets`**: nothing non-free is part of the app, and the downloaded files are MIT-licensed.
- **Not `NonFreeDep`**: the app does not need proprietary software to run; the model file is data, MIT.
- **The policy's download rule** concerns executable binaries. A ggml model file is data read by the speech
  engine, never executed; the engine itself is built from source (the pinned `whisper.cpp` submodule).
- **Residual risk, stated plainly**: whether model weights count as free software when the training data
  is not open is debated, and F-Droid has no written rule. The maintainers may ask for `NonFreeAssets` or
  `NonFreeNet`, or refuse the download. A label does not disqualify an app; a refusal triggers the fallback.

## Consequence for the recipe and the store text

- The recipe `contracts/app.meetingrecorder.yml` has **no `AntiFeatures` line**; `verifyReleaseMetadata`
  fails if one is added without this record naming the outcome (as `anti-feature: <Name>`).
- The store text (`fastlane/metadata/android/*/full_description.txt`) describes the download in plain words:
  once, from `huggingface.co`, after naming file, size and source, nothing before the tap, or import from
  a file. If a label is requested later, the same text gains one sentence that explains it.

## The question for the maintainers (also in the merge request, `submission.md`)

> Meeting Recorder (GPL-3.0-or-later) can download MIT-licensed speech-recognition model data from
> huggingface.co. The download starts only when the user taps Download, after a dialog that names the
> file, its size and the source; the address is pinned to a commit and a SHA-256 and only `huggingface.co`
> and `hf.co` are reachable (network security config); nothing is bundled in the APK; a model can also be
> imported from a file, and the app works without any model. No meeting data is ever sent. Is this
> acceptable without an anti-feature? If you prefer a label (`NonFreeAssets` or `NonFreeNet`) or the
> import-only variant, tell us which and we will change the recipe or the app accordingly.

## Import-only fallback (the prepared switch)

Applied only if the maintainers refuse the download. It removes capability and permission; it adds nothing.
The release is not blocked by it. Lists are built from the code as it is on 2026-11-19 (`grep -rn`).

**Remove**

| What | Where |
|------|-------|
| The `INTERNET` permission and its comment | `app/src/main/AndroidManifest.xml` (lines 12 to 15) |
| The network security config and its manifest attributes (`android:networkSecurityConfig`, `android:usesCleartextTraffic`) | `app/src/main/res/xml/network_security_config.xml`; `app/src/main/AndroidManifest.xml` |
| The download code | `app/src/main/kotlin/app/meetingrecorder/models/download/ModelDownloader.kt` (the package, with `DownloadControl` and `Stop` if only it uses them) and its test `app/src/test/kotlin/app/meetingrecorder/models/download/ModelDownloaderTest.kt` |
| The download paths of the install pipeline | `ModelInstallRunner.runDownload`, `ModelController.download` and `resume` for downloads, `Pending.Download`, the `TransferKind.DOWNLOAD` events, `ModelInstallWorker` (the worker only for downloads; imports can run in the controller) in `app/src/main/kotlin/app/meetingrecorder/models/`; `DownloadRules.kt` and `DownloadRulesTest` in `:core` if nothing else uses them |
| The catalog's download addresses | `core/src/main/kotlin/app/meetingrecorder/core/models/ModelCatalog.kt` keeps ids, sizes and checksums (so an imported file is still recognized) but loses `url`; `ModelCatalogInvariantsTest` drops the address checks; `scripts/verify-model-catalog.sh` is removed or changed to check checksums only |
| The download dialog, progress and notification texts | `RES/values/strings.xml` and `RES/values-de/strings.xml`: `model_download_*`, `model_action_download`, `model_action_resume_download`, `model_action_delete_partial`, `model_state_downloading`, `model_error_network`, `model_error_address`, `model_notification_title`, `model_notification_failed`, `models_network_note` |
| The manifest allow-list entry and network rule | `gradle/verification-tasks.gradle.kts`: `INTERNET` leaves `allowedPermissions`, the rule "`INTERNET` must be contained by the network security config" is replaced by "`INTERNET` must not be requested", `allowedNetworkDomains` is removed |

**Keep**

The model screen (list, state, import from a file, delete), the import path with its checksum and header
checks (`ModelImporter`, `ModelFileHeader`, `Sha256Streaming`), the transcription itself, the foreground
service permissions that transcription needs (`FOREGROUND_SERVICE_DATA_SYNC` before Android 15,
`FOREGROUND_SERVICE_MEDIA_PROCESSING` from Android 15), `WAKE_LOCK`.

**Change**

| What | Change |
|------|--------|
| `NetworkSourceScannerTest` / `NetworkSourceScanner` | No directory is allowed any more: the scan fails for any `java.net`, socket or HTTP client use. |
| `verifyManifestPermissions` | The new allow-list without `INTERNET`. |
| `README.md` "Privacy" and "What it does" | "The app has no network access at all"; the model is obtained by importing a file (name the source address in the text so a user can fetch the file by hand). |
| Store texts and changelog | The download sentences become "import a model from a file" plus where to get it by hand. |
| Settings strings `settings_privacy`, `settings_permissions_internet`, `models_intro` | Reworded without the download; the `settings_permissions_internet` line is removed. |
| `CLAUDE.md` rules "Minimal permissions" and "Network" | Back to "No `INTERNET`" and "No network code". |
| Constitution Principle I and VI | The exception for the model download is removed in a dedicated amendment (Governance): MINOR version, Sync Impact Report. |
| Recipe | Unchanged (no anti-feature either way); the version code is bumped for the new release. |

**Order of work and gates**: (1) strings and texts, (2) code removal, (3) manifest, network config and
allow-list, (4) tests, (5) documents and constitution; then the complete gate
(`:core:test :app:check :app:assembleRelease` and the verify tasks) and a new tag per the release policy.
`verifyNoPrebuiltBinaries`, `verifyWhisperPin` and the reproducibility check are unaffected.
