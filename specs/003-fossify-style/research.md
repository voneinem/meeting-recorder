# Research: Fossify Visual Style

All unknowns of the Technical Context are resolved below. Contrast ratios were computed with the WCAG 2.x
relative-luminance formula on 2026-11-18.

## R1. Where do the reference values come from?

- **Decision**: Use the CSS custom properties of the website's published stylesheet
  (`/_astro/BaseLayout.*.css`) as the palette. The rendered page text carries no style information, so the
  stylesheet is the only machine-readable source. Values are copied into the spec's "Visual reference" and
  into `Palette.kt`; the app never reads them at run time.
- **Rationale**: Exact values avoid guessing; copying them once keeps Principle I intact (no network).
- **Alternatives considered**: Sampling screenshots (imprecise); using Fossify's own app theme source code
  (different Android view toolkit, and not what the request names); asking the owner for a palette (the
  site already answers it).
- **Note**: The website uses `border-radius: 8px` on 25 of 31 rules, `999px` for pills, a 28 px radius once
  (hero panels, not used here), `system-ui` fonts, and a large soft shadow only on the website's floating
  panels. Phone apps get the 8 dp corners and pills, and no floating shadow (FR-007).

## R2. How do the tokens map onto Material 3 roles?

Every role of the color scheme is set explicitly, including the ones the table does not list (`onSecondary`,
`onTertiary`, the lowest, low and highest surface containers, `surfaceDim`, `surfaceBright`, `surfaceTint`, the
`inverse*` roles, `scrim` and the `*Fixed` roles). A role left at its default would show Material's baseline
purple-gray tones and break FR-004; `ColorSchemeRolesTest` guards it (tasks T005, T009).

- **Decision**: Keep the Material 3 components and give them a fixed color scheme:

| Material role | Token (light / dark) |
|---------------|----------------------|
| `background` | paper |
| `surface`, `surfaceContainerLowest`, `surfaceContainer*` low levels | surface |
| `surfaceContainer`, `surfaceVariant` (quiet areas, inactive tracks, chips) | quiet |
| `onBackground`, `onSurface` | ink |
| `onSurfaceVariant` | secondary text (`ink-soft`) |
| `primary` / `onPrimary` | green / white (light), paper (dark) |
| `primaryContainer` / `onPrimaryContainer` | mint / strong green |
| `secondary`, `tertiary` (+ containers) | information blue and warning amber with their soft backgrounds |
| `error` / `onError` | rose / white (light), paper (dark) |
| `outlineVariant` | line (decorative: card edges, dividers) |
| `outline` | derived control outline (see R4) |

- **Rationale**: Every control in the app is a stock Material 3 component, so one scheme restyles them
  all without touching each screen (FR-004, FR-014). Container roles that Material uses internally
  (chips, switches, sliders, progress tracks) pick up the quiet and mint tones automatically.
- **Alternatives considered**: Custom-drawn components (large change, loses accessibility behavior);
  a `Surface`-only restyle (leaves buttons and switches blue).

## R3. Dynamic color

- **Decision**: Remove the `dynamicLightColorScheme`/`dynamicDarkColorScheme` branch. `minSdk` is 30, so
  today every device uses wallpaper colors; the fixed schemes become the only path.
- **Rationale**: Spec FR-003 and SC-003. A fixed palette is what makes the app recognizable as part of the
  Fossify family. The old fallback palettes (`LightColors`, `DarkColors`, navy and dark red) are deleted
  so no old color survives.
- **Alternatives considered**: Offer "Follow system colors" as a setting (out of scope by the spec's
  assumptions; adds a setting and strings); keep dynamic color on Android 12+ (contradicts the request).

## R4. Contrast findings and derived tokens

Computed ratios (light / dark):

| Pair | Light | Dark | Requirement |
|------|-------|------|-------------|
| ink on paper | 16.40 | 17.10 | 4.5 |
| secondary text on surface | 8.90 | 11.48 | 4.5 |
| muted text on paper / quiet | 5.68 / 5.36 | 7.65 / 6.44 | 4.5 |
| green on paper / surface | 6.08 / 6.26 | 10.50 / 9.64 | 3 (accent as control or large text) |
| green on mint, strong on mint | 5.46, 7.75 | 6.76, 8.42 | 4.5 |
| blue on its soft background | 5.82 | 7.26 | 4.5 |
| amber on its soft background | 5.69 | 7.51 | 4.5 |
| rose on paper / surface | 5.67 / 5.85 | 8.73 / 8.02 | 4.5 |
| rose on roseSoft (derived) | 4.74 | 6.98 | 4.5 |
| amber on paper / surface (warning text) | 6.41 / 6.61 | 9.94 / 9.13 | 4.5 |
| blue on paper / surface (information text) | 6.52 / 6.72 | 9.38 / 8.61 | 4.5 |
| white on green, white on rose (light buttons) | 6.26, 5.85 | | 4.5 |
| paper on green (dark buttons) | | 10.50 | 4.5 |
| **line on paper / surface** | **1.25 / 1.29** | **1.61 / 1.48** | **3 for control outlines: FAILS** |
| surface on paper (card edge) | 1.03 | 1.09 | decorative, needs the line |

- **Finding**: All published text and accent pairs already pass. The published `line` color is a
  decorative divider and fails the 3:1 rule for control outlines (text field borders, unselected
  checkbox and radio edges, switch tracks, outlined buttons). Surface and paper are almost identical, so
  cards are separated only by the thin line, which is decorative and allowed.
- **Decision**: Derive one extra token, `outline-control`, used for the Material `outline` role:
  light `#7A8A7F` (3.53 on paper, 3.64 on surface), dark `#6B8172` (4.49 on paper, 4.13 on surface,
  3.79 on quiet). Both are the website's green-gray hue family and pass 3:1 on every surface they sit
  on. `line` stays as `outlineVariant` for cards and dividers.
- **Rationale**: Honors FR-011 without changing any published value (spec assumption on derived tones).
- **Alternatives considered**: Using `line` for controls (fails FR-011); darkening `line` globally
  (would heavy-up every card, against the soft look).
- **Derived states**: pressed uses `strong green`; disabled uses the `outline-control` at 38% alpha for
  content (disabled controls are exempt from contrast rules by WCAG); focus ring uses `green`.

## R5. Shapes

- **Decision**: Material shape scale `extraSmall` 8 dp, `small` 8 dp, `medium` 8 dp, `large` 8 dp,
  `extraLarge` 12 dp (dialogs and bottom sheets). Because not every component reads the scale, three
  components get explicit shapes: buttons (8 dp, owner decision on 2026-11-18) through the shared wrappers
  `AppButton`, `AppOutlinedButton` and `AppTextButton`; the status chip (fully round, `CircleShape`); the card
  (`medium`) through `AppCard`.
- **Rationale**: Matches "about 8 dp for cards, buttons and fields; fully round for chips and pills"
  (FR-007). Checked against the Material 3 defaults (analysis finding C1): cards read `medium` and dialogs
  `extraLarge`, but the filled, outlined and text buttons are fully round regardless of the scale, the outlined
  text field reads `extraSmall` (hence 8 dp there, not 4) and the assist chip defaults to 8 dp, not a pill. The
  app has 56 button calls (11 `Button`, 15 `OutlinedButton`, 30 `TextButton`) in 11 files, so one wrapper per button kind keeps
  the shape in one place (FR-014) and call sites change only their name.
- **Alternatives considered**: Keeping round buttons and amending FR-007 (rejected by the owner: the website
  uses 8 px buttons); a custom `Button` shape at every call site (spreads the decision); a custom component
  theme (Material 3 has none for shapes of buttons).
- **Alternatives considered**: 28 dp hero radius (website-only); square corners (not the reference).

## R6. Cards: thin outline, no shadow

- **Decision**: Material 3 has no theme-level card defaults, so define one shared wrapper `AppCard` in
  `ui/components` (surface container, zero elevation, 1 dp `outlineVariant` border, `medium` shape) and
  replace the direct `Card(...)` calls with it in the files that use cards (list, record, playback, models,
  setup, transcript section, job banner). No call site gets its own colors. A source test keeps direct `Card`
  calls out of the screens (tasks T024).
- **Rationale**: One place defines the look (FR-014). Zero elevation matches "thin soft outlines instead
  of heavy shadows" (FR-007).
- **Alternatives considered**: Tonal elevation (surface and paper are nearly the same, so cards would be
  invisible); Material `OutlinedCard` at every call site (spreads the decision across files).

## R7. Typography

- **Decision**: Keep the platform default font family (system sans), no font resources. Set a calm
  hierarchy: titles medium weight, body regular, labels medium, with Material default sizes (no size
  below 12 sp).
- **Rationale**: The website itself uses the system font stack, so this is the faithful match; it also
  keeps Principles I and II and FR-008 (no font files, no network). System fonts follow the user's font
  scale (FR-013).
- **Alternatives considered**: Bundling Roboto or Inter (adds binaries, no benefit on Android).

## R8. Icons

- **Finding**: The app uses no in-app icon library (no `Icons.*`, no `material-icons` dependency); all
  actions are text. The only icons are the launcher icon (out of scope) and the monochrome notification
  small icon.
- **Decision**: FR-009 needs no code change today. It becomes a rule for future work: any new icon is a
  simple line icon with thin round-ended stroke, drawn as a vector resource, and no icon library
  dependency is added without a Principle II check.
- **Alternatives considered**: Adding `material-icons-extended` now (large dependency, unused).

## R9. Start-up window, splash and system bars

- **Decision**: In `values/themes.xml` set only `android:windowBackground` from new `colors.xml` entries
  (`values/` = light paper, `values-night/` = dark paper); set the Android 12 splash background
  (`android:windowSplashScreenBackground`) in a new `values-v31/themes.xml`, because it is an API 31
  attribute, `minSdk` is 30 and `app/lint.xml` makes lint warnings errors (analysis finding C3). Do not set
  `statusBarColor` or `navigationBarColor`: they are obsolete at `targetSdk` 37 and `enableEdgeToEdge()`
  already makes the bars transparent. `MainActivity` keeps `enableEdgeToEdge()`; its
  default style follows the system dark setting, which is the same signal the theme uses, so bar icons
  stay readable. The Compose `Surface` in `MainActivity` keeps `colorScheme.background`.
- **Rationale**: FR-010 and FR-015; removes the white flash in dark mode.
- **Alternatives considered**: AndroidX SplashScreen library (new dependency; not needed because the
  platform splash attributes exist on API 31+ and the window background covers API 30); the splash and bar
  attributes in `values/` (lint errors under `warningsAsErrors`).

## R10. Status colors

- **Decision**: Add a small immutable `StatusColors` set (success, information, warning, each with its
  container and on-container, plus the existing error) provided through a composition local in
  `MeetingRecorderTheme` and read as `MaterialTheme.statusColors`. Where a screen uses status today it
  does so with raw `colorScheme.error` text (15 sites); those stay for true errors and the others switch
  to the right role:
  - transcription chip: in progress = information, done = success, failed = error;
  - job banner: interrupted = warning container, failed = error container;
  - recording notices and permission cards: warning container for "needs attention", error only for
    "denied/failed";
  - level meter: success accent while recording, no default blue.
- **Rationale**: FR-006; text labels already exist in every case (`status_in_progress`,
  `status_done`, `status_failed`, banner titles), so color is never the only carrier.
- **Alternatives considered**: Reusing `tertiary` only (loses the distinction between information and
  warning).

## R11. Notifications

- **Decision**: Set the accent color of the three notification builders (recording, transcription
  progress and result, model install) to the green via the platform `setColor` with a color resource. Text,
  channels and behavior stay unchanged; the small icon is the existing monochrome vector.
- **Rationale**: Edge case in the spec ("accent color follows the new green where the system allows").
- **Alternatives considered**: Leaving notifications unstyled (inconsistent accent chip).

## R12. Tests and verification

- **Decision**:
  - Unit test `PaletteContrastTest` (JVM): computes WCAG contrast for the pairs of R4 from the plain-Kotlin
    palette and fails if any text or control pair drops below its threshold; also asserts that the
    palette contains exactly the reference values, so a drift is visible. It also reads the two `colors.xml`
    files and asserts that `window_background` and `notification_accent` equal `paper` and `green` of the
    matching appearance, so the XML copies cannot drift from `Palette.kt` (FR-014).
  - Unit test `ColorSchemeRolesTest` (JVM): every `Color` role of the light and dark Material color schemes is a
    palette token, so no role keeps Material's baseline purple tones (analysis finding C2).
  - `ThemeSourceRulesTest` also fails on a direct `Card`, `Button`, `OutlinedButton` or `TextButton` call
    outside `ui/components/`.
  - Existing guards stay as they are: `HardcodedStringsTest`, `NetworkSourceScannerTest`, manifest and
    dependency verification.
  - Manual scenarios V1 to V5 (14 views, font scale, wallpaper change, theme switch while recording) are added
    to the quickstart; recording is never touched by the restyle, but V3 includes a short recording to
    prove it.
- **Rationale**: Contrast is the only spec rule that can be checked without a device; everything else is
  visual and belongs in a manual review, in line with the earlier phases.
- **Alternatives considered**: Screenshot tests (new tooling and dependencies, not justified for 14 views).

## R13. Trademark and attribution

- **Decision**: Take the visual style only. No Fossify name, logo, or domain appears in the app, in the
  manifest or in resources; the README may state that the look is inspired by the Fossify project.
- **Rationale**: The spec's assumption on independence; avoids implying that this is a Fossify app.
