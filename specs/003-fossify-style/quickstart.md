# Quickstart: Validating the Fossify Style

A run-and-validate guide. Tokens are in [data-model.md](data-model.md), the rules in
[contracts/theme-contract.md](contracts/theme-contract.md), decisions in [research.md](research.md).
Phase 1 scenarios are S1 to S10 and Phase 2's are S11 to S24
([../002-offline-transcription/quickstart.md](../002-offline-transcription/quickstart.md)); this
feature's manual scenarios are V1 to V5.

## Prerequisites

- JDK 21 via `JAVA_HOME`, `ANDROID_HOME`, `adb`, the phone (same as before). No NDK work is needed, but
  `:app:assembleDebug` builds the engine, so the Phase 2 toolchain must be present.
- No new dependency: `gradle/verification-metadata.xml` does not change.

## Automated gate

```bash
./gradlew :core:test :app:check :app:assembleDebug
./gradlew :app:verifyNoForbiddenDependencies :app:verifyManifestPermissions
./gradlew :app:verifyNoPrebuiltBinaries :app:verifyWhisperPin :whisper:assembleDebug
```

Expected: all green, with the three new tests (`PaletteContrastTest`, `ColorSchemeRolesTest`,
`ThemeSourceRulesTest`) included. The test fails when a palette value drifts from
[data-model.md](data-model.md) or a pair of its contrast table drops below its threshold. Existing
`HardcodedStringsTest` and `NetworkSourceScannerTest` stay green without edits (SC-005), and no
`strings.xml` changes.

A quick search proves that the old look is gone:

```bash
grep -rn "dynamicLightColorScheme\|dynamicDarkColorScheme\|0xFF1F3A5F\|0xFF8A1C1C" app/src/main   # no output
grep -rn "Color(0x" app/src/main/kotlin | grep -v "ui/theme/"                                       # no output
```

## Install on the phone

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Never run `connectedDebugAndroidTest` on a phone with real data (see the project notes).

## Manual scenarios

**V1: every screen in light (US1, SC-001, SC-002).** Set the phone to light. Open the 7 screens: meeting
list, record, playback with transcript, settings, models, setup and consent (clear the app data on a
spare install to see setup and consent). On each, check against the Visual reference: paper background,
white cards with a thin line and no shadow, green primary actions, 8 dp corners, no leftover navy or dark
red. Open one dialog (delete meeting), the transcription start dialog and the permission cards. Check the
status chip in the states in progress, done and failed, and that each also shows its text. Buttons (filled,
outlined and text) have 8 dp corners, text fields 8 dp, chips are fully round. Control-edge spot check (FR-011): field
borders, unselected radio buttons, outlined buttons and chips are clearly visible against their background; use
Accessibility Scanner or the 3:1 rule by eye.

**V2: every screen in dark (US2, SC-001, SC-002).** Repeat V1 in the dark appearance with the dark column
of the reference, including the status bar and navigation bar area. Start the app from the launcher in the
dark appearance and watch the first frame: no white flash.

**V3: switching live (US2, SC-006).** Open the record screen and start a short recording, then pull down
the quick settings and toggle the dark theme. The screen changes in under a second, the timer and level
meter keep running, the recording is unaffected and stops cleanly. Repeat on the transcript screen with a
transcription running and a text field with typed content.

**V4: fixed colors and legibility (US3, SC-003, SC-004).** Change the wallpaper to three very different
colors (red, blue, yellow): no app color changes. Set the system font size to the largest value and the
display size to the largest: open all 7 screens, nothing is cut off, every action is reachable. Switch
the phone to German once and confirm that the longer strings still fit. Switch on the system's high-contrast text
option and confirm that every screen stays readable.

**V5: regression guard (FR-012).** Make a recording with two photos, transcribe it, and check that
`meeting.json`, the audio file, the photos and the transcript are produced exactly as before, that the
recording and transcription notifications look the same except for the green accent, and that the
airplane-mode check of S22 still passes (no new network use).

Record the results and any skipped step next to the tasks, in the same way as the earlier phases.
