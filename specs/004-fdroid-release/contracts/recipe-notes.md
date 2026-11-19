# Notes on `app.meetingrecorder.yml`

`app.meetingrecorder.yml` in this folder is the exact file that is submitted to `fdroiddata` as
`metadata/app.meetingrecorder.yml`. It is kept in the form that `fdroid rewritemeta` writes (no comments,
canonical field order), so the notes live here.

- **Reviewed against the build**: `:app:verifyReleaseMetadata` compares version name and code, the `rm:` list,
  `ndk`, `submodules`, license, author name and email, and any `sdkmanager` line with the build files, and it
  rejects an `AntiFeatures` line that `r16-decision.md` does not record.
- **`commit`**: `TAG_COMMIT_PLACEHOLDER` until the release commit and the tag `v0.1.0` exist; then the 40-hex
  commit of the tag (release policy, step 4). The placeholder is replaced by a hash only in scratch copies for
  the local lint and build.
- **Title, summary and description** are not in the recipe: F-Droid reads them from `fastlane/` in the
  source repository (`store-metadata.md`).
- **`AutoName: Meeting Recorder`** is what `fdroid checkupdates` writes from the app label. The CI of `fdroiddata`
  runs `checkupdates --auto` and fails when it changes the file, so the line must already be in the recipe.
- **`ndk: r29`, and the optional `sudo:` or `prebuild:` fallback** that installs NDK `29.0.14206865` and CMake
  `3.31.6` with `sdkmanager` are settled by the local build in F-Droid's image (research R-3, quickstart Q4);
  `build-results.md` records which lines were needed.
- **`UpdateCheckData`** is written on its own line by `rewritemeta` because of its length; the trailing space is
  produced by the tool.
- **Anti-features**: none (research R-1, `r16-decision.md`).
