# Implementation Plan: Fossify Visual Style

**Branch**: `003-fossify-style` (spec directory only; work continues on `main`) | **Date**: 2026-11-18 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/003-fossify-style/spec.md`

## Summary

Give the app the Fossify look in light and dark: fixed palette (no wallpaper colors), modest 8 dp
corners, thin soft outlines, system font. The UI is already 100% Material 3 with the default
components and a single theme function, so the change is concentrated in the `ui/theme` package
(palette, shapes, type scale, theme) plus a small number of semantic status colors, the window theme
resources (start-up background, system bars) and the notification accent. Screens are touched only
where they hard-wire default styling that the theme cannot reach (cards, the status chip, the level
meter, error text). No behavior, string, permission, dependency or data change.

Approach (details in [research.md](research.md)):

1. Define the palette once as plain Kotlin color values (testable on the JVM) and map it onto a
   Material 3 color scheme; add a small extension for the roles Material has no slot for (success,
   information, warning with their containers, control outline).
2. Define shapes and a type scale in the theme. Material 3 has no theme-level defaults for cards and buttons
   and its buttons ignore the shape scale, so add three small shared pieces in `ui/components`: `AppCard`
   (flat, thin line) and `AppButton`/`AppOutlinedButton`/`AppTextButton` (8 dp, owner decision), and use
   them on the screens instead of the raw Material calls.
3. Replace the dynamic-color branch of `MeetingRecorderTheme` with the fixed light/dark schemes.
4. Add window-level resources so the very first frame and the system bars match the page background.
5. Replace the few raw `error` / default-container usages that carry status meaning with the semantic
   roles, always paired with the existing text.
6. Guard the contrast rules (FR-011) with a pure JVM unit test over the palette, and verify the rest by
   a screen-by-screen device review (manual scenarios V1 to V5 in [quickstart.md](quickstart.md)).

## Technical Context

**Language/Version**: Kotlin (project toolchain), JDK 21; Jetpack Compose with Material 3 from the
existing Compose BOM `2026.09.00`

**Primary Dependencies**: none added. Existing `androidx.compose.material3`, `androidx.activity:activity-compose`
(edge-to-edge already enabled in `MainActivity`)

**Storage**: N/A (no stored state; the app keeps following the device light/dark setting)

**Testing**: JUnit on the JVM in `:app` (`app/src/test`) for palette contrast and token mapping;
existing suites unchanged (`./gradlew :core:test :app:check :app:assembleDebug`); manual device review

**Target Platform**: Android, `minSdk` 30 (so dynamic color exists on every supported device and must be
deliberately removed, not merely avoided)

**Project Type**: mobile-app (single `:app` module change; `:core` and `:whisper` untouched)

**Performance Goals**: theme switch visible within 1 s without losing screen state (SC-006); no extra
work on the recording path

**Constraints**: no bundled font files, no network, no new dependency, no new permission, no
hard-coded UI strings, no change to the `:recording` process or the capture thread

**Scale/Scope**: 7 screens (list, record, playback with transcript, settings, models, setup, consent),
about 22 UI files, 4 theme files, 4 resource files, 5 notification builders in 3 files

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design: still passing.*

| # | Principle | Result | Note |
|---|-----------|--------|------|
| I | Offline & Privacy First | Pass | Fonts are the system font; nothing is fetched from fossify.org. The website is only a design reference that was read once at specification time. |
| II | F-Droid Compatibility | Pass | No new dependency, no font or image binaries, nothing prebuilt. `verifyNoForbiddenDependencies` and `verifyNoPrebuiltBinaries` stay green. |
| III | No Data Loss | Pass | Nothing changes on the recording path or in storage. |
| IV | Open File Format | Pass | `meeting.json`, the WAV, the photos and the generated `CLAUDE.md` are untouched. |
| V | Single Timeline | Pass | Not involved. |
| VI | Minimal Permissions | Pass | No permission is added or changed; `verifyManifestPermissions` stays green. |
| VII | Testability | Pass | The palette lives in plain Kotlin so contrast is unit-tested on the JVM; Android code stays a thin layer. No format logic is touched. |
| VIII | Incremental Delivery | Pass | The change ships on its own and the app stays installable at every step (the tasks are ordered theme-first). |
| IX | English Everywhere, Localized UI | Pass | No string is added, removed or reworded; code and docs are English. |

No violations, so the Complexity Tracking table is not needed.

## Project Structure

### Documentation (this feature)

```text
specs/003-fossify-style/
├── plan.md              # This file
├── research.md          # Phase 0: decisions, derived tokens, contrast findings
├── data-model.md        # Phase 1: palette, shape and type tokens, role mapping
├── quickstart.md        # Phase 1: automated checks and manual scenarios V1 to V5
├── contracts/
│   └── theme-contract.md  # Phase 1: the rules every screen follows
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
app/src/main/
├── kotlin/app/meetingrecorder/
│   ├── MainActivity.kt                    # keep edge-to-edge; system bar style follows the app palette
│   ├── ui/theme/
│   │   ├── Palette.kt                     # NEW: plain Kotlin hex values, light and dark (JVM-testable)
│   │   ├── Color.kt                       # maps Palette to Material color schemes + status roles
│   │   ├── Shape.kt                       # NEW: 8 dp scale (cards, fields), 12 dp dialogs
│   │   ├── Type.kt                        # system font, calm size/weight hierarchy
│   │   └── Theme.kt                       # fixed schemes (dynamic-color branch removed), provides status roles
│   ├── ui/components/AppCard.kt           # NEW: flat card with a thin line, optional warning/error tone
│   ├── ui/components/AppButtons.kt        # NEW: 8 dp Button, OutlinedButton, TextButton wrappers
│   ├── ui/components/LevelMeterBar.kt     # accent track/indicator colors, no default blue
│   ├── ui/transcription/StatusChip.kt     # status roles (green/blue/amber/rose) + existing text
│   ├── ui/transcription/JobBanner.kt      # warning role container instead of default card
│   ├── ui/**/ *Screen.kt                  # AppCard/App*Button instead of raw calls; status roles where color carries status
│   ├── permissions/PermissionRationale.kt # App*Button
│   ├── ui/transcription/StartTranscriptionDialog.kt # AppTextButton
│   ├── recording/RecordingNotification.kt # accent color on the notification
│   ├── transcription/TranscriptionNotification.kt
│   └── models/ModelInstallWorker.kt       # notification accent
└── res/
    ├── values/themes.xml                  # window background only
    ├── values-v31/themes.xml              # NEW: splash background (API 31 attribute; lint is warningsAsErrors)
    ├── values/colors.xml                  # NEW: paper and accent for the window theme
    └── values-night/colors.xml            # NEW: dark counterparts

app/src/test/kotlin/app/meetingrecorder/ui/theme/
├── PaletteContrastTest.kt                 # NEW: FR-011, token drift and colors.xml checks on the JVM
├── ColorSchemeRolesTest.kt                # NEW: every color role comes from the palette
└── ThemeSourceRulesTest.kt                # NEW: no raw colors, dynamic color, fonts or raw Card/Button calls
```

**Structure Decision**: Single-module change in `:app`. `:core` stays free of UI and Android, `:whisper`
is not involved. The theme package is the only place that knows hex values; screens read roles through
the theme (FR-014). The `Palette` object is deliberately free of Compose types so the JVM unit test needs
no Android runtime.

## Complexity Tracking

Not needed: the Constitution Check has no violations.
