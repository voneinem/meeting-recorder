# Contract: Release Policy

## Where the version lives

Only in `app/build.gradle.kts`, in `defaultConfig`: `versionName` and `versionCode`. No other file
repeats them except the recipe's `Builds` entry and `CurrentVersion*` fields, the changelog file name and
the tag; `:app:verifyReleaseMetadata` checks the first three against the build file.

## Numbering

| Item | Rule | Example |
|------|------|---------|
| `versionName` | semantic version `MAJOR.MINOR.PATCH` | `0.1.0` |
| `versionCode` | integer, increased by exactly 1 for every release, never reused, never decreased | `1` |
| tag | annotated tag `v<versionName>` on the release commit | `v0.1.0` |

- PATCH: fixes only. MINOR: new user-visible capability, backward-compatible meeting folders. MAJOR:
  an incompatible change of the meeting folder format (which also needs a migration, constitution
  Principle IV).
- A single APK contains both supported ABIs, so one version code covers one release (clarification 3).

## Release steps (in this order)

1. All checks green: `./gradlew :core:test :app:check :app:assembleDebug :app:assembleRelease` and the
   verification tasks, including `:app:verifyReleaseMetadata`.
2. The version in `app/build.gradle.kts`, the changelog `<versionCode>.txt` (both locales) and the
   recipe copy's `versionName`, `versionCode`, `CurrentVersion` and `CurrentVersionCode` are updated in
   one commit, the **release commit**.
3. Create the annotated tag on the release commit: `git tag -a v<versionName> -m "Meeting Recorder <versionName>"`.
4. Write the 40-hex commit of the tag into the recipe copy (`git rev-parse v<versionName>^{commit}`) in a
   following documentation commit; the recipe copy is not part of the build.
5. Record the verification (build, lint, reproducibility) in the build record.
6. **Owner only**: push the branch and the tag; update the recipe in `fdroiddata`.

## Signing

The repository holds no keystore, key, password or signing configuration, and the release build type needs
none. F-Droid signs the app with its own key. `.gitignore` excludes `*.jks`, `*.keystore` and
`keystore.properties`, and `:app:verifyReleaseMetadata` fails when signing material is tracked or a signing
configuration appears in a build file.

Consequence: a build signed by F-Droid cannot update a debug build or a build signed with another key;
the user uninstalls first. Debug builds are not a supported upgrade path.

## Reproducibility

On the tag, the release build is made twice in two different directories. All native libraries must be
byte-identical (requirement); whether the complete unsigned APKs are identical is recorded as information.
The method and flags are those of Phase 2 (`specs/002-offline-transcription/spike-results.md`,
"Reproducibility"): `-ffile-prefix-map`, `-Wl,--build-id=none`, no RUNPATH, pinned toolchain.
