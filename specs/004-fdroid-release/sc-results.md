# Success criteria: results and gaps (T045)

Written 2026-11-19, after the local verification of this phase. "Met" means measured as the criterion states it;
a criterion with a gap stays a gap. Evidence is in `build-results.md` unless another file is named.

| Criterion | Status | Evidence | Gap |
|---|---|---|---|
| SC-001 local F-Droid build of the tag exits 0, linter 0 errors, commands and versions recorded | **Met** | `fdroid build -v -l app.meetingrecorder` in `registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie` against a scratch clone of the tag with the tag's commit: `Successfully built app.meetingrecorder:1 from cf6f93887c9f01bdea13158f7690d57c3d2c99ad`, exit 0 (also at the commit before the tag). `fdroid readmeta`, `rewritemeta`, `lint` and `checkupdates`: exit 0, no error, no warning, recipe unchanged. | none: the same run against the **real** recipe and GitHub (T044, after the repository was made public and the tag pushed) also gave exit 0 and the same APK. |
| SC-002 zero prebuilt binaries, proprietary dependencies and trackers for the tag | **Met** | F-Droid's source scanner ran in the build ("Scanning source for common problems", signature data `suss`) and reported nothing; the `rm:` directories were removed; `verifyNoPrebuiltBinaries`, `verifyNoForbiddenDependencies` and `verifyManifestPermissions` are green on the tag in a clean clone. | none |
| SC-003 two builds of the tag in different directories give identical native libraries | **Met** | All 24 native libraries identical (`sha256sum` of every `lib/*/*.so`) between `~/fdroid-work/tag-a` and a second checkout in a different path; also identical to the build in F-Droid's image. The complete unsigned APKs are byte-identical too: `159773be74c11a7fcb738928db4eae2c3865891c61d2deb1568f95184381ebde`. | none |
| SC-004 100% of the store elements for English and German, no statement contradicting the app, README or decision record | **Met** | `verifyReleaseMetadata` checks every file, the limits, the icon size, the number of screenshots and the required facts in both languages (negative checks in the build record). The statements on network use, storage, consent and devices were compared with `README.md`, the settings strings and `r16-decision.md`; the one discrepancy found (the German button name) was fixed. | The "no contradiction" part is a reading, not an automated check. |
| SC-005 author name in 4 places, author email in 2, no other personal address anywhere in the repository | **Partly met** | Name: About screen in English and in German, `README.md`, the recipe. Email `vve-f-droid@gmx.net`: `README.md` and the recipe (and the documents under `specs/`). The gate fails when the git commit address appears in any file, and when the F-Droid address appears outside README, recipe copy, specs and the gate's own constant. Verified on the F-Droid-built APK for the About screen. | The **git history** of the 24 earlier commits still carries the author's other address in the commit metadata (no file does). The release commit and the tag use the F-Droid address. The repository is public since 2026-11-19, so that history is public. |
| SC-006 the R-16 record is complete and the import-only fallback needs no new design | **Met** | `r16-decision.md`: sources with date, facts with proof files, outcome, the question, and the fallback as lists of what is removed, kept and changed, built from `grep` of the code, with the order of work and the gates. | The fallback was described, not executed (it is only applied if the maintainers refuse the download). The decision is final only after their reply. |
| SC-007 a first-time submitter reaches an opened merge request from the package alone, in one sitting | **Partly met** | The dry-read (T043) followed the package literally as far as no account is needed, found one blocker (the private repository) and one wording gap, and the package was corrected. Every check in it was run in this phase. | No first-time submitter was observed, and the steps that need the owner's accounts (fork, push, merge request) were not run. |
| SC-008 complete existing gate green on the tag, unit-test count not lower | **Met** | `:core:test :app:check :app:assembleDebug :app:assembleRelease`, the five verify tasks and `:whisper:assembleDebug` green in a clean clone of the tag; `:core` 353 and `:app` 259 tests, 0 failures, equal to the baseline of 2026-11-19 (353 and 259). | none |

Count: **met** SC-001, SC-002, SC-003, SC-004, SC-006, SC-008; **partly met** SC-005, SC-007.

## Findings of this phase that the criteria do not show

- **The F-Droid build failed at first** because `gradle/verification-metadata.xml` lacked 60 lines of build-time
  entries that only an empty Gradle cache exposes (found by `fdroid build`, fixed, proven with a second empty cache).
- **The source repository was private**, which blocked F-Droid; the owner had it made public on 2026-11-19.
- **F-Droid's Quality point 6** (three months of active development unless an exception applies): the project's first
  commit is 2026-11-15.
- **Reproducible builds** is a one-way choice at first publication (F-Droid's template), and the app is technically ready.
- All three are written up with the options in `submission.md` section 1.

## Requirements checklist

`checklists/requirements.md` has no open items (16 of 16 checked; no clarification marker).
