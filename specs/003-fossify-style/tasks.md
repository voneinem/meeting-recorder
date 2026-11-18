---

description: "Task list for the Fossify Visual Style (Phase 3)"
---

# Tasks: Fossify Visual Style (Phase 3)

**Input**: Design documents from `/specs/003-fossify-style/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/theme-contract.md, quickstart.md

**Tests**: Constitution Principle VII asks for logic to be unit-tested on the JVM. The only logic here is the palette and the theme rules, so two small JVM tests are included (`PaletteContrastTest`, `ThemeSourceRulesTest`) and they come before the code that depends on them. Everything visual is verified by the manual scenarios V1 to V5 of quickstart.md.

**Organization**: Tasks are grouped by user story (US1 to US3, as numbered in spec.md). US1 and US2 share the same palette code, so the palette and the theme are built once in Phase 2 (both appearances at once). US1 then applies the theme to every screen and component, US2 adds the window-level pieces and the dark and live-switch checks, and US3 proves fixed colors and legibility.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1 to US3, as numbered in spec.md
- Paths follow plan.md. `PKG` means `app/meetingrecorder`, so `app/src/main/kotlin/PKG/ui/theme/` is `app/src/main/kotlin/app/meetingrecorder/ui/theme/`. `RES` means `app/src/main/res/`.
- **(device)** marks a task that needs the real phone and **(user)** one that needs the project owner (unlock the phone, accept a result). Do not tick a device task before its scenario passed on the phone; write the result and any skipped step next to the task.
- **Rules that apply to every task** (theme contract, `contracts/theme-contract.md`): no hex value or `Color(...)` literal outside `ui/theme/Palette.kt`; no raw radii or fonts; no new dependency, permission, network use or font file; **no UI string is added, removed or reworded** (so `values/strings.xml` and `values-de/strings.xml` stay unchanged); English in code and comments.
- Use `export JAVA_HOME=~/Android/jdk-21 ANDROID_HOME=~/Android/Sdk` for every Gradle call. Commit only when the project owner asks.

---

## Phase 1: Setup

**Purpose**: A known-good baseline and a record of the old look to compare against.

- [ ] T001 Run the full gate on the untouched tree and note the result as the baseline: `./gradlew :core:test :app:check :app:assembleDebug`, then `./gradlew :app:verifyNoForbiddenDependencies :app:verifyManifestPermissions :app:verifyNoPrebuiltBinaries :app:verifyWhisperPin :whisper:assembleDebug`. All must be green before any change; if one is red, stop and report it.
- [ ] T002 (device) (user) Capture "before" screenshots of the 7 screens (list, record, playback with transcript, settings, models, setup, consent) in light and dark with `adb exec-out screencap -p > <file>` into `build/restyle-before/` (`build/` is already git-ignored). They are the comparison for V1 and V2 and are never committed. The user unlocks the phone and, for setup and consent, uses a spare install or cleared app data (never clear data on the phone with real meetings).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The palette, the color schemes, shapes, type scale and the fixed theme (FR-001 to FR-003, FR-007, FR-008, FR-014). Every user story builds on this.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [ ] T003 Create `app/src/main/kotlin/PKG/ui/theme/Palette.kt`: a plain Kotlin `object Palette` with two nested sets, `Light` and `Dark`, of `Int` ARGB values (no Compose or Android types, so the JVM test can load it). Contains exactly the 20 tokens of the table in `data-model.md` (`paper`, `surface`, `surfaceQuiet`, `ink`, `inkSoft`, `muted`, `line`, `outlineControl`, `green`, `onGreen`, `greenStrong`, `mint`, `blue`, `blueSoft`, `amber`, `amberSoft`, `rose`, `onRose`, `roseSoft`, `onRoseSoft`) with the light and dark hex values copied verbatim, a KDoc line saying which tokens are published by the reference website (read 2026-11-18) and which are derived (`outlineControl`, `onGreen`, `onRose`, `roseSoft`, `onRoseSoft`), and a helper `contrastRatio(foreground: Int, background: Int): Double` using the WCAG 2.x relative-luminance formula. No identifier or comment names a brand.
- [ ] T004 Create `app/src/test/kotlin/PKG/ui/theme/PaletteContrastTest.kt` (JUnit with `kotlin.test`, like the other app tests): (a) asserts every token of both sets equals the value in `data-model.md` (a drift test: write the expected hex strings out in the test); (b) asserts every pair of the "Required contrast pairs" table of `data-model.md` for **both** `Palette.Light` and `Palette.Dark` with its minimum (`ink`, `inkSoft`, `muted` on `paper`, `surface`, `surfaceQuiet` at 4.5; `onGreen` on `green` 4.5; `green` on `paper` and `surface` 3.0; `greenStrong` on `mint` 4.5; `green` on `mint` 4.5; `blue` on `blueSoft` 4.5; `amber` on `amberSoft` 4.5; `rose` on `paper` and `surface` 4.5; `rose` on `roseSoft` 4.5; `amber` and `blue` on `paper` and `surface` 4.5 (warning and information text drawn directly on the page, T022); `onRose` on `rose` 4.5; `onRoseSoft` on `roseSoft` 4.5; `outlineControl` on `paper`, `surface`, `surfaceQuiet` 3.0); (c) tests `contrastRatio` itself (black on white is 21.0, equal colors give 1.0). Run `./gradlew :app:testDebugUnitTest --tests '*PaletteContrastTest*'` and make it green (it should pass: the ratios were pre-computed in research R4).
- [ ] T005 Rewrite `app/src/main/kotlin/PKG/ui/theme/Color.kt`: delete the old `LightColors` and `DarkColors` (navy and dark red, the fallback palettes) and build two Material 3 `ColorScheme`s from `Palette` with the mapping of research R2 (`background` = `paper`; `surface` and the lowest surface containers = `surface`; `surfaceContainer`, `surfaceContainerHigh`, `surfaceVariant` = `surfaceQuiet`; `onBackground`, `onSurface` = `ink`; `onSurfaceVariant` = `inkSoft`; `primary` = `green`, `onPrimary` = `onGreen`; `primaryContainer` = `mint`, `onPrimaryContainer` = `greenStrong`; `secondary` = `blue`, `secondaryContainer` = `blueSoft`, `onSecondaryContainer` = `blue`; `tertiary` = `amber`, `tertiaryContainer` = `amberSoft`, `onTertiaryContainer` = `amber`; `error` = `rose`, `onError` = `onRose`, `errorContainer` = `roseSoft`, `onErrorContainer` = `onRoseSoft`; `outlineVariant` = `line`; `outline` = `outlineControl`). **Set every role of `ColorScheme` explicitly** so that none falls back to Material's baseline purple tones: additionally `onSecondary` = `onGreen`, `onTertiary` = `onGreen`, `surfaceContainerLowest` and `surfaceContainerLow` = `surface`, `surfaceContainerHighest` = `surfaceQuiet`, `surfaceDim` = `paper`, `surfaceBright` = `surface`, `surfaceTint` = `green`, `inverseSurface` = `ink`, `inverseOnSurface` = `paper`, `inversePrimary` = the `green` of the opposite appearance, `scrim` = `ink`, and the `*Fixed`/`*FixedDim` roles where the Material version has them (`primaryFixed`, `primaryFixedDim`, `onPrimaryFixed`, `onPrimaryFixedVariant` = `mint`, `green`, `greenStrong`, `greenStrong`; the secondary and tertiary fixed roles from their soft backgrounds and accents). Also define `@Immutable class StatusColors` with `success`, `information`, `warning` and `error`, each as foreground, container and on-container, built from the table "Status roles" of `data-model.md`, and its light and dark instances.
- [ ] T006 [P] Create `app/src/main/kotlin/PKG/ui/theme/Shape.kt`: `AppShapes` as a Material 3 `Shapes` with `extraSmall` 8 dp, `small` 8 dp, `medium` 8 dp, `large` 8 dp, `extraLarge` 12 dp (data-model.md "Shape scale"; `extraSmall` is 8 dp because Material's outlined text field reads that slot, FR-007). The Material 3 buttons and chips do **not** read this scale, so they get explicit shapes in T012 and T019. No other radius is defined anywhere.
- [ ] T007 [P] Rewrite `app/src/main/kotlin/PKG/ui/theme/Type.kt`: `AppTypography` keeps the platform default font family and the Material 3 default sizes, with the weights of data-model.md "Type scale" (title and headline styles Medium, body styles Regular, label styles Medium). No font resource, no `FontFamily` that loads a file, no size below 12 sp.
- [ ] T008 Rewrite `app/src/main/kotlin/PKG/ui/theme/Theme.kt`: `MeetingRecorderTheme(darkTheme = isSystemInDarkTheme())` chooses the fixed light or dark scheme and nothing else. **Delete the `dynamicLightColorScheme`/`dynamicDarkColorScheme` branch and the `Build` and `LocalContext` use** (FR-003; research R3). Pass `AppShapes` and `AppTypography`, provide `StatusColors` through a `CompositionLocal`, and expose it as an extension property `MaterialTheme.statusColors` (read it with `val MaterialTheme.statusColors @Composable get()`). Keep the function name and signature, so `MainActivity` needs no change.
- [ ] T009 Create `app/src/test/kotlin/PKG/ui/theme/ThemeSourceRulesTest.kt` in the style of `NetworkSourceScannerTest` (walk `app/src/main/kotlin` from the repository root found through the module directory; ignore comments and string literals the same way): fails when (a) any file other than `ui/theme/Palette.kt` contains a `Color(0x` literal or an 8-digit ARGB literal matching `0x[Ff]{2}[0-9A-Fa-f]{6}\b` (byte masks such as `0xFF` alone are not hits; today no file outside `ui/theme/` has either), (b) any file contains `dynamicLightColorScheme` or `dynamicDarkColorScheme`, (c) any file under `app/src/main/res/font/` exists. Also create `app/src/test/kotlin/PKG/ui/theme/ColorSchemeRolesTest.kt`: through reflection over the `Color`-typed properties of the light and the dark `ColorScheme` (so a role added by a later Material version is caught), asserts that every role equals some token of `Palette.Light` or `Palette.Dark` and that none is a Material baseline color (if the Compose classes cannot load in a JVM unit test, report it instead of adding a dependency or Robolectric). Include small tests of the scanner itself (a hit in code, no hit in a comment or a string). Run both: they must pass now (no screen uses raw colors today; `LevelMeterBar` and the others use only theme roles) and keep passing for all later tasks. Rule (d) of the scanner (no raw `Card`, `Button`, `OutlinedButton` or `TextButton` call outside `ui/components/`) is added later in T024, after the screens use the wrappers.
- [ ] T010 Run `./gradlew :app:testDebugUnitTest :app:assembleDebug` and `./gradlew :app:verifyNoForbiddenDependencies :app:verifyNoPrebuiltBinaries`; all green. Install the debug APK on the phone (`adb install -r app/build/outputs/apk/debug/app-debug.apk`, never `connectedDebugAndroidTest`) and check by eye that the buttons, radio buttons, the slider and the progress bars are green and the background is paper instead of the wallpaper colors.

**Checkpoint**: The theme is fixed and green everywhere; the screens still use default card and status styling. US1 completes the look.

---

## Phase 3: User Story 1 - The whole app wears the Fossify look (Priority: P1) 🎯 MVP

**Goal**: Every screen, dialog and control uses the palette, corner shapes and outline weight, with statuses told apart by role and text (FR-004 to FR-008, FR-012, FR-013). FR-009 (line icons) needs no task: the app has no in-app icons today (research R8) and the theme contract governs future ones.

**Independent Test**: Scenario V1 of quickstart.md: open the 7 screens in the light appearance and compare with the Visual reference and the "before" screenshots; no navy or dark red remains, cards have a thin line and no shadow, statuses show their role color and their text.

### Implementation for User Story 1

- [ ] T011 [P] [US1] Create `app/src/main/kotlin/PKG/ui/components/AppCard.kt`: the shared card style of research R6. Two overloads that mirror the two `Card` forms used today, `AppCard(modifier, content)` and `AppCard(onClick, modifier, content)`, each wrapping Material 3 `Card` with `colors` = surface container (`surface`), `elevation` = 0 dp, `border` = `BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)` and the `medium` shape. Both overloads take one extra optional parameter `tone: CardTone = CardTone.Neutral`, an enum declared in the same file with `Neutral`, `Warning` and `Error`. `Neutral` is the style above with content color `onSurface`. `Warning` uses container `statusColors.warning.container`, border `statusColors.warning.foreground` (1 dp) and content color `statusColors.warning.onContainer`. `Error` uses container `colorScheme.errorContainer`, border `colorScheme.error` (1 dp) and content color `colorScheme.onErrorContainer`. The content color is always set explicitly (a status container is not a Material role, so `contentColorFor` would not find it). Content parameter types and the `ColumnScope` receiver must match `Card`, so call sites change only the name and, for a status card, the `tone`. No other file sets a card color, elevation or border.
- [ ] T012 [P] [US1] Create `app/src/main/kotlin/PKG/ui/components/AppButtons.kt` with `AppButton`, `AppOutlinedButton` and `AppTextButton`: thin wrappers around Material 3 `Button`, `OutlinedButton` and `TextButton` whose only difference is `shape = MaterialTheme.shapes.medium` (8 dp, FR-007; Material's default buttons are fully round and do not read the shape scale, research R5). First `grep` every current call site for the parameters it passes (`onClick`, `modifier`, `enabled`, `colors`, `contentPadding`, `content` with the `RowScope` receiver) and mirror exactly those, with the same defaults, so call sites change the name only. Then replace the direct calls in the two files no later screen task covers: `app/src/main/kotlin/PKG/permissions/PermissionRationale.kt` (2 `Button`, 4 `OutlinedButton`) and `app/src/main/kotlin/PKG/ui/transcription/StartTranscriptionDialog.kt` (4 `TextButton`). Add no color, size or text change. Use of the wrappers in the other files is part of T013 to T020.
- [ ] T013 [P] [US1] Replace `Card` with `AppCard` and the buttons with `AppButton`/`AppOutlinedButton`/`AppTextButton` (T012; 1 `Button`, 9 `TextButton`) in `app/src/main/kotlin/PKG/ui/list/MeetingListScreen.kt` (the entry card with `onClick` and the other card, lines about 68 and 193; its `OutlinedTextField` takes the 8 dp from the shape scale) and review its delete dialog and text actions: the delete confirmation keeps its `error` text color for the destructive action only.
- [ ] T014 [P] [US1] Replace `Card` with `AppCard` and the buttons with the `App*Button` wrappers (T012; 3 `Button`, 2 `OutlinedButton`, 2 `TextButton`) in `app/src/main/kotlin/PKG/ui/record/RecordScreen.kt` (about line 120; its `OutlinedTextField` takes the 8 dp from the shape scale) and review the screen's buttons, the level meter area and the notices for default styling that needs a role (see T021 and T022).
- [ ] T015 [P] [US1] Replace `Card` with `AppCard` and the buttons with the `App*Button` wrappers (T012; 1 `Button`, 1 `OutlinedButton`, 3 `TextButton`) in `app/src/main/kotlin/PKG/ui/playback/PlaybackScreen.kt` (three card uses: about lines 84, 194 and the photo viewer at 216, whose `fillMaxSize` card keeps its modifier) and check the seek slider and play controls take the green accent.
- [ ] T016 [P] [US1] Replace `Card` with `AppCard` and the buttons with the `App*Button` wrappers (T012; 2 `Button`, 6 `OutlinedButton`, 6 `TextButton`) in `app/src/main/kotlin/PKG/ui/models/ModelSettingsScreen.kt` (about lines 63 and 126) and review the progress indicators and the buttons.
- [ ] T017 [P] [US1] Replace `Card` with `AppCard` in `app/src/main/kotlin/PKG/ui/setup/SetupScreen.kt` (about line 73; `ConsentScreen.kt` and `SettingsScreen.kt` have no card). Replace the buttons with the `App*Button` wrappers (T012) in `SetupScreen.kt` (1 `Button`), `app/src/main/kotlin/PKG/ui/setup/ConsentScreen.kt` (1 `Button`) and `app/src/main/kotlin/PKG/ui/settings/SettingsScreen.kt` (2 `OutlinedButton`, 1 `TextButton`); the radio buttons and the other controls follow the scheme (make no per-screen colors).
- [ ] T018 [P] [US1] Replace `Card` with `AppCard` in `app/src/main/kotlin/PKG/ui/transcription/TranscriptSection.kt` (about line 99, the photo row with `onClick`) and keep its `semantics` modifier; the timestamp text keeps `colorScheme.primary` (green).
- [ ] T019 [P] [US1] Give `app/src/main/kotlin/PKG/ui/transcription/StatusChip.kt` its status roles (FR-006; research R10): in progress = `statusColors.information`, done = `statusColors.success`, failed = `colorScheme.error`, applied through `AssistChipDefaults.assistChipColors` and a border in the same role, with an explicit fully round shape (`CircleShape`; Material's assist chip defaults to 8 dp, so it must be set, FR-007). Its two `TextButton`s become `AppTextButton` (T012). Keep the existing label text and the `semantics` description; the failed chip still offers Resume and Start over. No new strings.
- [ ] T020 [P] [US1] Restyle `app/src/main/kotlin/PKG/ui/transcription/JobBanner.kt` with `AppCard(tone = ...)` (T011): interrupted or stopped because a recording started = `CardTone.Warning`, failed = `CardTone.Error`; set no color in this file; keep the title style and all texts. Its three `TextButton`s become `AppTextButton` (T012) and keep the default colors.
- [ ] T021 [P] [US1] Update `app/src/main/kotlin/PKG/ui/components/LevelMeterBar.kt`: the `LinearProgressIndicator` gets `color` = `statusColors.success.foreground` and `trackColor` = `colorScheme.surfaceVariant`; keep the 12 dp height, the label and the `contentDescription`. No code on the capture path is touched (the bar reads values only).
- [ ] T022 [US1] Review the other status texts that use `colorScheme.error` (15 sites: `permissions/PermissionRationale.kt` lines about 54 and 87, `ui/setup/SetupScreen.kt` about 70 and 79, `ui/transcription/StartTranscriptionDialog.kt` about 133, 146 and 147, `ui/transcription/TranscriptSection.kt` about 56, `ui/list/MeetingListScreen.kt` about 167, 171 and 202, `ui/models/ModelSettingsScreen.kt` about 70 and 178, `ui/playback/PlaybackScreen.kt` about 133 and 149) against the rule of the theme contract (run this task after T012 and T013 to T018, which edit the same files): `error` is kept for failures and denied permissions; warnings that lose nothing ("replacing an existing transcript", "language may not match", "memory is low", "recording in progress" on the models screen) switch to `statusColors.warning.foreground`; the text stays as it is. List each site and its decision in the task note.
- [ ] T023 [P] [US1] Set the accent color of the notifications to the green through a color resource: add `setColor(context.getColor(R.color.notification_accent))` to the builders in `app/src/main/kotlin/PKG/recording/RecordingNotification.kt`, `app/src/main/kotlin/PKG/transcription/TranscriptionNotification.kt` (two builders) and `app/src/main/kotlin/PKG/models/ModelInstallWorker.kt` (two builders), and add `notification_accent` to a new `RES/values/colors.xml` as the light `green` hex (the dark counterpart is created in T026). Text, channels, importance and behavior stay unchanged. (The resource file is the only place besides `Palette.kt` that may hold the hex; `ThemeSourceRulesTest` scans Kotlin only.)
- [ ] T024 [US1] First extend `ThemeSourceRulesTest` with rule (d): no file outside `app/src/main/kotlin/PKG/ui/components/` calls `Card(`, `Button(`, `OutlinedButton(` or `TextButton(` from `androidx.compose.material3` (match the call as a whole identifier with `\bCard\(`, `\bButton\(`, `\bOutlinedButton\(` and `\bTextButton\(`, so `AppCard(`, `CameraPermissionCard(` and `IconButton(` are not hits; ignore comments and strings; the wrappers are the only direct users). Then run `./gradlew :core:test :app:check :app:assembleDebug` and the four verify tasks and `:whisper:assembleDebug` of T001; all green, including the three new JVM tests and `HardcodedStringsTest` with an unchanged `strings.xml`.
- [ ] T025 [US1] (device) (user) Run scenario V1 of `specs/003-fossify-style/quickstart.md` on the phone in the light appearance and compare with the screenshots of T002 and the Visual reference, including the control-edge spot check of V1 (field borders, unselected radio buttons, outlined buttons and chips are clearly visible against their background; use Accessibility Scanner or the 3:1 rule by eye; `outline` must not be used on `mint`, where it is below 3:1). Record the result, any screen that still shows old styling (fix it first), and any skipped screen next to this task. Tick only after it passed.

**Checkpoint**: User Story 1 is complete and the app is a usable, installable restyled app in the light appearance (MVP).

---

## Phase 4: User Story 2 - The dark appearance matches too (Priority: P1)

**Goal**: The dark Fossify look on every screen, a start-up frame and system bars that match, and a clean live switch between light and dark (FR-002, FR-010, FR-015).

**Independent Test**: Scenarios V2 and V3: dark appearance on all 7 screens with no white flash at start, and a light/dark switch during a recording and a transcription that loses no state.

### Implementation for User Story 2

- [ ] T026 [US2] Create `RES/values-night/colors.xml` with the dark values and create or extend `RES/values/colors.xml` (T023 creates it with `notification_accent`; if T023 has not run, create it here with both entries) with the window colors: `window_background` (light `paper` `#FBFCF8`; dark `#0B130F`), `notification_accent` (dark `#78D58D`; the light entry exists). Use the same hex strings as `data-model.md`; keep the names brand-free. Then extend `PaletteContrastTest` (T004) with check (d): it reads `app/src/main/res/values/colors.xml` and `app/src/main/res/values-night/colors.xml` (found from the module directory like the scanner tests do) and asserts that `window_background` equals `paper` and `notification_accent` equals `green` of the matching appearance, so the XML copies cannot drift from `Palette.kt` (FR-014). `ic_launcher_background.xml` is the old launcher color and is out of scope.
- [ ] T027 [US2] Update the window theme without tripping lint (`app/lint.xml` runs with `warningsAsErrors = true`, `minSdk` is 30, `targetSdk` is 37): in `RES/values/themes.xml` (`Theme.MeetingRecorder`, parent `android:Theme.DeviceDefault.DayNight`) set only `android:windowBackground` to `@color/window_background` and keep `windowActionBar` and `windowNoTitle`; create `RES/values-v31/themes.xml` with the same style name, the same parent and items, plus `android:windowSplashScreenBackground` = `@color/window_background` (an API 31 attribute, so it must not sit in `values/`). Do **not** set `android:statusBarColor` or `android:navigationBarColor`: they are obsolete at `targetSdk` 37 and `enableEdgeToEdge()` in `MainActivity` already makes the bars transparent. Do not add the AndroidX SplashScreen library (research R9). Depends on T026. If `lintDebug` still reports an `UnusedAttribute`, `Deprecated` or similar issue on these files, fix the files, never relax `lint.xml`.
- [ ] T028 [US2] Check `app/src/main/kotlin/PKG/MainActivity.kt`: `enableEdgeToEdge()` must stay, and its default `SystemBarStyle.auto` follows the system dark setting, which is the same signal as `MeetingRecorderTheme`, so the bar icons stay readable in both appearances (FR-010). Only if a mismatch shows on the device, pass an explicit `SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)` with `detectDarkMode` tied to the same value; add no color literal (the rule of `ThemeSourceRulesTest` applies). Make no change if none is needed and say so in the task note.
- [ ] T029 [US2] Run `./gradlew :core:test :app:check :app:assembleDebug` and `./gradlew :app:lintDebug` (inside `:app:check`); all green with no new lint warning (they are errors here), in particular none on `themes.xml`, `values-v31/themes.xml` or `colors.xml`.
- [ ] T030 [US2] (device) (user) Run scenarios V2 and V3 of `specs/003-fossify-style/quickstart.md` on the phone: dark appearance on all 7 screens (against the dark column and the dark "before" screenshots), the first frame without a white flash, and the live switch during a short recording and during a running transcription (the timer, level meter and progress keep running, typed text stays, the recording stops cleanly and its audio plays). Record the result and anything skipped next to this task. Tick only after it passed.

**Checkpoint**: User Stories 1 and 2 are complete: light and dark both match the reference.

---

## Phase 5: User Story 3 - The look is the same on every phone and stays readable (Priority: P2)

**Goal**: Stable colors independent of the wallpaper, sufficient contrast, adequate touch targets and screens that hold up with the largest font and the longer German strings (FR-003, FR-011, FR-013).

**Independent Test**: Scenario V4: three different wallpapers change nothing, and all 7 screens are usable with the largest font and display size, in English and German.

### Implementation for User Story 3

- [ ] T031 [US3] Review the touch targets of the changed components against FR-013: `StatusChip` (the assist chip keeps Material's 48 dp minimum interactive size), `AppCard` rows with `onClick`, the `AppButton`, `AppOutlinedButton` and `AppTextButton` wrappers (including the `AppTextButton`s of `JobBanner`); none of them may call `minimumInteractiveComponentSize` away or set a height below the previous one. Fix any regression in the component, not in the theme, and note the result.
- [ ] T032 [US3] (device) (user) Run scenario V4 of `specs/003-fossify-style/quickstart.md`: change the wallpaper to red, blue and yellow and confirm that no app color changes (SC-003), set the largest system font size and the largest display size and open all 7 screens (no cut-off text, every action reachable, SC-004), then repeat once in German, and switch on the system's high-contrast text option and check that every screen stays readable. Record the findings next to this task.
- [ ] T033 [US3] Fix every clipping or overlap problem found in T032 in the affected screen file under `app/src/main/kotlin/PKG/ui/` (for example by letting a `Row` wrap or by replacing a fixed height with a minimum height), without changing any string or behavior; re-run the affected part of V4 and note the result. If T032 found nothing, mark this task done with the note "nothing to fix".
- [ ] T034 [US3] Confirm the guard that keeps the colors fixed and the contrast intact: run `./gradlew :app:testDebugUnitTest --tests '*PaletteContrastTest*' --tests '*ThemeSourceRulesTest*'` and `grep -rn "dynamicLightColorScheme\|dynamicDarkColorScheme\|0xFF1F3A5F\|0xFF8A1C1C" app/src/main` (no output) and `grep -rn "Color(0x" app/src/main/kotlin | grep -v "ui/theme/"` (no output); write the results next to the task.

**Checkpoint**: All three user stories work independently and together.

---

## Phase 6: Polish and Cross-Cutting Concerns

**Purpose**: Regression guard, documentation, and the final gate.

- [ ] T035 (device) (user) Run scenario V5 of `specs/003-fossify-style/quickstart.md`: record with two photos, transcribe, and check that `meeting.json`, the audio, the photos and the transcript are produced as before, that the notifications differ only by the green accent, and that the airplane-mode check of S22 still passes. Record the result and anything skipped next to this task.
- [ ] T036 [P] Update `CLAUDE.md` of the repository: in "Rules that are easy to break" add one bullet for the theme (fixed palette from `ui/theme/Palette.kt`, no hex values or `Color(...)` elsewhere, no dynamic color, cards through `AppCard`, status colors only with text, any new color pair goes into `PaletteContrastTest`; enforced by `ThemeSourceRulesTest`), and extend the first paragraph so that it names `specs/003-fossify-style/`. Keep the file in English and short.
- [ ] T037 [P] Add a short "Look and feel" note to the existing `README.md`: the visual style is inspired by the Fossify project website, the app is independent and uses no Fossify names or logos (research R13).
- [ ] T038 Write the results of the success criteria SC-001 to SC-007 of `spec.md` into `specs/003-fossify-style/sc-results.md` in the format of `specs/002-offline-transcription/sc-results.md`: for each criterion the evidence (scenario, test, date) or the recorded gap. SC-007 (looks like the same family) needs the project owner's acceptance: ask for it and record the answer.
- [ ] T039 Final gate: `./gradlew :core:test :app:check :app:assembleDebug`, `./gradlew :app:verifyNoForbiddenDependencies :app:verifyManifestPermissions`, `./gradlew :app:verifyNoPrebuiltBinaries :app:verifyWhisperPin :whisper:assembleDebug`; all green, `gradle/verification-metadata.xml` and both `strings.xml` files have no diff (`git diff --stat -- gradle/verification-metadata.xml app/src/main/res/values/strings.xml app/src/main/res/values-de/strings.xml` is empty). Then tick this task and keep `tasks.md` honest: every device task above is ticked only when its scenario passed or was explicitly skipped by the owner, with the note.

---

## Dependencies and Execution Order

### Phase dependencies

- **Phase 1 (Setup)**: no dependency; T001 first.
- **Phase 2 (Foundational)**: after Phase 1. T003 → T004 → T005; T006 and T007 in parallel with T005 (different files); T008 needs T005, T006 and T007; T009 can start after T008 (it only reads sources); T010 last. **Blocks every user story.**
- **Phase 3 (US1)**: after Phase 2. T011 (`AppCard`) and T012 (`AppButton`, `AppOutlinedButton`, `AppTextButton`; they edit different files, so they run in parallel) come first; T013 to T018 need both T011 and T012 and run in parallel with each other; T019 and T020 need T012 (and T020 needs T011); T021 and T023 need neither and run in parallel with them; T024 after all of them; T025 last.
- **Phase 4 (US2)**: after Phase 2; it is independent of US1's screen edits, but V2 and V3 compare against the finished restyle, so run it after T024. T026 and T027 are sequential (T027 uses the colors); T028 any time after T027; T029 then T030 last. T026 creates `colors.xml` itself when T023 has not run, so US2 can also be built before US1's notification task; if T023 ran first, T026 extends the file.
- **Phase 5 (US3)**: after US1 and US2 (it verifies the finished look). T031 → T032 → T033 → T034.
- **Phase 6 (Polish)**: after Phase 5. T036 and T037 are parallel; T035 and T038 after the device checks; T039 last.

### User story dependencies

- **US1 (P1)**: starts after Foundational; no dependency on other stories. It is the MVP.
- **US2 (P1)**: starts after Foundational; its window resources are independent files, its verification needs US1 done.
- **US3 (P2)**: verification of US1 and US2 on the device with different system settings; can only find work in files of US1 and US2.

### Parallel opportunities

- Phase 2: T006 and T007 together with T005.
- Phase 3: T011 and T012 together; then T013, T014, T015, T016, T017, T018 (one screen file each) and T019, T020, T021, T023 (separate files) can all run at the same time. T022 (not marked [P]) touches files that T012 and T013 to T018 also edit (the status text lines), so run it after those seven.

### Parallel example: User Story 1

```text
After T011 (AppCard) and T012 (App*Button):
  T013 MeetingListScreen   T014 RecordScreen   T015 PlaybackScreen
  T016 ModelSettingsScreen T017 SetupScreen    T018 TranscriptSection
  T019 StatusChip          T020 JobBanner      T021 LevelMeterBar
  T023 notification accent
Then T022 (status text review across files), T024 (gate), T025 (device V1).
```

---

## Implementation Strategy

### MVP first (User Story 1)

1. Phase 1 and Phase 2: baseline, palette, tests, fixed theme. The app already turns green and stops using wallpaper colors.
2. Phase 3: cards, statuses, notifications. **Stop and validate** with V1 in the light appearance.
3. The result is installable and complete in the light appearance; Principle VIII (every phase ends usable) holds at every checkpoint.

### Incremental delivery

1. Add US2 (window resources, dark and live-switch checks) and validate with V2 and V3.
2. Add US3 (touch targets, wallpaper and font-size checks) and validate with V4.
3. Polish: V5 regression, documentation, success-criteria results, final gate.

### Notes

- A change that needs a new dependency, permission, string or network use is out of scope; stop and ask instead of adding it (spec FR-012, constitution I, II, VI, IX).
- Do not touch the recording path (`recording/` code other than the one `setColor` line in `RecordingNotification.kt`) or `:core`.
- Commit after a task or a logical group when the owner asks, with English messages that give the reason.
