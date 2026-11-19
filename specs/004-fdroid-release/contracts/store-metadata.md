# Contract: Store Metadata

What F-Droid reads from this repository to build the app's page. Source: F-Droid's "All About
Descriptions, Graphics and Screenshots" and Build Metadata Reference (read 2026-11-19).

## Layout

```text
fastlane/metadata/android/
├── en-US/                      # F-Droid's fallback language
│   ├── title.txt
│   ├── short_description.txt
│   ├── full_description.txt
│   ├── changelogs/<versionCode>.txt
│   └── images/
│       ├── icon.png
│       ├── featureGraphic.png          (optional)
│       └── phoneScreenshots/1.png, 2.png, ...
└── de-DE/
    ├── title.txt
    ├── short_description.txt
    ├── full_description.txt
    └── changelogs/<versionCode>.txt
```

Images live only under `en-US/` (the fallback) and are shared by `de-DE`. The changelog file name is the
version code written plainly (`1.txt` for version code 1). Nothing is placed anywhere else (a different
folder is ignored by F-Droid).

## Limits (enforced by `:app:verifyReleaseMetadata`)

| File | Limit |
|------|-------|
| `title.txt` | 50 characters |
| `short_description.txt` | 80 characters, one line |
| `full_description.txt` | 4000 characters |
| `changelogs/<versionCode>.txt` | 500 characters |
| `images/icon.png` | 512 x 512 pixels, PNG |
| `images/phoneScreenshots/N.png` | PNG or JPEG, at least four |

## Content rules

1. The full description is true to the app and to `README.md` on these points, in this order of
   importance: nothing leaves the phone; the only network use is the speech-model download, which starts
   only when the user taps Download; meetings are stored unencrypted in the chosen folder; recorded people
   must consent. A change to any of them in the app or README changes the store text in the same change
   (FR-013).
2. The text names the supported architectures (`arm64-v8a` and `x86_64`) and says that transcription is
   not available on devices without them (FR-014).
3. The text describes the model download in plain words. If the R-16 outcome is an anti-feature, the text
   also explains that label.
4. No claim the app does not fulfill, no promotion of a non-free service, no marketing superlatives.
5. German and English say the same things; the German text is a translation, not a different text.
6. Screenshots show demo data only, taken from the release build in the light appearance, in this order:
   meeting list, running recording, playback with transcript and photos, model settings.
7. The changelog describes the release in plain language: what the app can do in this version.
