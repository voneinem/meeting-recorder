# Data Model: F-Droid Release

This feature has no runtime data. The entities below are the release artifacts and the rules that keep
them consistent; `verifyReleaseMetadata` enforces the machine-checkable ones.

## Release

| Field | Source of truth | Rule |
|-------|-----------------|------|
| `versionName` | `app/build.gradle.kts` | `MAJOR.MINOR.PATCH`; `0.1.0` for this release |
| `versionCode` | `app/build.gradle.kts` | integer, +1 per release; `1` for this release |
| `tag` | git | `v<versionName>`, annotated, created locally; pushed by the owner |
| `commit` | git | the commit the tag points to; full 40-hex hash in the recipe |
| `changelog` | `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt` | exists in both locales, at most 500 characters |
| `recipeVersion` | recipe `Builds[*]` and `CurrentVersion*` | name, code and commit equal the four values above |

**Consistency rule**: name, code, tag, commit, changelog file and the recipe's version entry always agree.
Gate checks name, code, changelog and the recipe's name and code; the tag and commit are checked in
quickstart Q6 (they exist only after the release commit).

**State**: `draft` (repository changes in progress) → `tagged` (release commit and local tag exist, recipe
copy holds the commit) → `verified` (recipe linter and local build green on the tag, reproducibility
recorded) → `submitted` (owner opened the merge request) → `published` or `changes-requested`.
Only the owner moves a release from `verified` to `submitted`.

## Recipe

The F-Droid build description `app.meetingrecorder.yml`. Contract: [contracts/app.meetingrecorder.yml](contracts/app.meetingrecorder.yml).

| Field group | Rule |
|-------------|------|
| Identity | `License: GPL-3.0-or-later` equals `LICENSE` and `README.md` |
| Author | `AuthorName: Volker von Einem`, `AuthorEmail: vve-f-droid@gmx.net` |
| Sources | `Repo` is the public GitHub HTTPS address; `SourceCode` and `IssueTracker` are the human pages |
| Build | `submodules: true`; `rm:` equals the six-directory list of `verifyNoPrebuiltBinaries`; `ndk` names the NDK of `whisper/build.gradle.kts` |
| Update | `UpdateCheckMode: Tags`, `AutoUpdateMode: Version`, explicit `UpdateCheckData` for `app/build.gradle.kts` |
| Anti-features | exactly the set in the R-16 decision record (preliminary: none) |

## Store metadata

Contract: [contracts/store-metadata.md](contracts/store-metadata.md).

| Element | Locales | Rule |
|---------|---------|------|
| `title.txt` | en-US, de-DE | at most 50 characters |
| `short_description.txt` | en-US, de-DE | at most 80 characters, one line |
| `full_description.txt` | en-US, de-DE | at most 4000 characters; consistent with README on network use, storage, consent; names the architectures |
| `changelogs/<versionCode>.txt` | en-US, de-DE | at most 500 characters |
| `images/icon.png` | shared (en-US) | 512 x 512 |
| `images/phoneScreenshots/N.png` | shared (en-US) | at least four, light appearance, demo data only |
| `images/featureGraphic.png` | shared (en-US) | optional |

## Decision record (R-16)

Document `r16-decision.md`. Required sections: the question; the sources read with their dates; the
facts about the app; the chosen outcome (`no-anti-feature` | `anti-feature: <name>` | `import-only`);
the consequence for the recipe and the store text; the exact question for the maintainers; the
import-only switch (what is removed, what stays, which gates and tests change, which texts change).

## Submission package

Document `submission.md`. Required sections: merge request title and description (app, license, source,
tag, build requirements, the model-download decision and question); the command sequence from fork to
opened merge request; the pre-submission checks with expected results; the review checklist (maintainer
topic, prepared answer, file that proves it); the owner-only steps.

## Build record

Document `build-results.md`. Required content: for each verification (lint, readmeta, checkupdates, build,
reproducibility, existing gate) the command, tool and image versions, date and result; the screenshot
capture device and date; the open items that remain.
