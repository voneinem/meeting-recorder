# Feature Specification: Fossify Visual Style

**Feature Branch**: `003-fossify-style` (no git branch is created; work continues on `main`)

**Created**: 2026-11-18

**Status**: Draft

**Input**: User description: "Update the application style to match https://www.fossify.org"

## Overview

Meeting Recorder gets a new look that matches the visual identity of the Fossify project website
(https://www.fossify.org): a calm, green-accented, clutter-free style with soft neutral surfaces,
modest rounded corners and system typography, in both a light and a dark appearance. The change is
purely visual. No behavior, data, permission, file format or text of the app changes.

### Visual reference (taken from the Fossify website)

The reference values below were read from the website's published stylesheet on 2026-11-18. They are
the single source of truth for this feature; the app uses them as its design palette.

| Role | Light appearance | Dark appearance |
|------|------------------|-----------------|
| Page background ("paper") | `#FBFCF8` | `#0B130F` |
| Card / sheet surface | `#FFFFFF` | `#121D17` |
| Quiet surface (secondary areas) | `#F2F6F1` | `#18251E` |
| Primary text ("ink") | `#102019` | `#EEF6EE` |
| Secondary text | `#3F4D45` | `#C8D6CB` |
| Muted text and hints | `#5B685F` | `#96AA9B` |
| Dividers and outlines ("line") | `#DCE5DC` | `#2B3C31` |
| Primary accent (green) | `#216E39` | `#78D58D` |
| Strong accent (pressed, emphasis) | `#14552A` | `#9DE8AD` |
| Accent container (mint) | `#DFF5E4` | `#173D25` |
| Information accent (blue) | `#245F92` on `#E6F0F8` | `#8BBCE8` on `#142B3C` |
| Warning accent (amber) | `#8F4B08` on `#FBECD8` | `#F0B05F` on `#392716` |
| Error accent (rose) | `#A94442` | `#EF9A96` |

Shape and type: corners are modestly rounded (about 8 dp for cards, buttons and fields; fully round
for chips and pills), outlines are thin and soft instead of heavy shadows, text uses the system font
(no custom font files), and iconography is simple line icons with a thin, round-ended stroke.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - The whole app wears the Fossify look (Priority: P1)

A person opens Meeting Recorder and sees the Fossify look on every screen: the meeting list, the
recording screen, playback with transcript, settings, model management, the first-run setup and the
consent screen. Backgrounds, cards, text, buttons, switches, progress indicators and dialogs use the
reference palette and shapes. The person can tell at a glance that the app belongs to the Fossify
family of apps.

**Why this priority**: This is the whole request. Without a consistent look across all screens, the app
would look half-finished, so the core delivers value only when every screen is covered.

**Independent Test**: Open each screen listed above in the light appearance and compare it with the
Visual reference table: the page background, card surfaces, text colors, accent color, corner shape
and line weight match, and no screen still shows the old colors or default component styling.

**Acceptance Scenarios**:

1. **Given** the device uses the light appearance, **When** the person opens any screen of the app,
   **Then** the page background is the light paper color, cards and sheets are white with soft thin
   outlines, text uses the light ink colors and the primary actions use the green accent.
2. **Given** the device uses the light appearance, **When** the person looks at buttons, switches,
   selection controls, chips, text fields, dialogs, progress bars and the sound level bar,
   **Then** they use the green accent, the modest corner rounding and the thin outlines of the
   reference, with no leftover blue or red colors from the previous design.
3. **Given** a screen that showed items in different states (recording active, transcription running,
   transcription failed, model missing), **When** the person views it, **Then** the states are told
   apart with the reference accents (green for active or done, blue for information, amber for
   attention, rose for errors) and each state is also recognizable by its text or icon, not by color
   alone.

---

### User Story 2 - The dark appearance matches too (Priority: P1)

A person who uses the dark appearance on their phone sees the dark Fossify look: a near-black green
page background, dark green-gray cards, light text and softer, brighter accents. The app follows the
phone's light or dark setting automatically, as it does today.

**Why this priority**: Many people use the dark appearance, especially in meetings and rooms with low
light. A light-only restyle would look broken for them, so this is as important as the light look.

**Independent Test**: Switch the phone to the dark appearance, open every screen and compare it with
the dark column of the Visual reference table. Switch back to light and confirm the app changes
without being restarted manually (the same behavior as today).

**Acceptance Scenarios**:

1. **Given** the device uses the dark appearance, **When** the person opens any screen, **Then** the
   background, surfaces, text, dividers and accents use the dark column of the reference.
2. **Given** the app is open, **When** the person switches the device between light and dark,
   **Then** the app shows the matching look without losing the screen state (for example a running
   recording, a transcription in progress or text typed into a field).
3. **Given** the dark appearance, **When** the person views the status bar and the navigation bar area,
   **Then** they blend with the page background and their icons stay readable.

---

### User Story 3 - The look is the same on every phone and stays readable (Priority: P2)

A person on any supported phone sees the same Fossify colors, regardless of the wallpaper or the
system's own color theme. All text and controls stay easy to read: contrast is sufficient, touch
targets remain comfortably large, and the layout holds up with larger system font sizes.

**Why this priority**: A recognizable brand look requires fixed colors, and a restyle must never make
the app harder to use. It builds on the first two stories but is checked separately.

**Independent Test**: Install the app on a phone that offers wallpaper-based system colors, change the
wallpaper to a very different color and confirm the app colors do not change. Then set the system font
size to the largest setting and open all screens.

**Acceptance Scenarios**:

1. **Given** a phone that generates system colors from the wallpaper, **When** the wallpaper changes,
   **Then** the app keeps its Fossify colors.
2. **Given** any screen in either appearance, **When** the person reads body text, hints and button
   labels, **Then** their contrast against the background meets at least 4.5:1 for normal text and
   3:1 for large text and for control outlines.
3. **Given** the system font size is set to its largest value, **When** the person opens every screen,
   **Then** no text is cut off or overlaps and every action stays reachable.

---

### User Story 4 - The app icon matches the Fossify icons (Priority: P3)

A person who has Fossify apps on their phone sees Meeting Recorder next to them on the home screen and in the app
drawer: a solid green circle with a simple white glyph, in the same green and the same bold, flat style, so the
app fits in with the others. The icon stays recognizable as a microphone.

**Why this priority**: The icon is the first thing a person sees of the app and the clearest sign of the same
family, but the app works without it, so it comes after the in-app look.

**Independent Test**: Install the app and look at the launcher next to a Fossify app (for example the voice
recorder): same green, same flat white glyph on a full circle, on a light and on a dark wallpaper; with the
phone's themed-icon option on, the icon takes the system tint and stays recognizable.

**Acceptance Scenarios**:

1. **Given** a launcher with round icons, **When** the person looks at the app icon, **Then** it is a full green
   circle (no navy, no outline, no shadow of its own) with a bold white microphone in the middle.
2. **Given** a launcher that masks icons to other shapes (square, squircle, teardrop), **When** the person looks
   at the icon, **Then** the glyph is never cut off and the green fills the whole shape.
3. **Given** the themed-icon option of the phone is on, **When** the person looks at the icon, **Then** it is
   shown in the system tint and the microphone is still recognizable.

---

### Edge Cases

- A running recording: the restyle must not change timing, audio capture or the recording notification
  behavior. The recording screen keeps all of its information and controls in the same places.
- The recording notification and the transcription progress notification keep their text and
  behavior; only their accent color follows the new green where the system allows it.
- Dialogs, bottom sheets, menus and the system share and file pickers shown from the app: app-owned
  ones use the new look; system-owned ones keep the system look.
- Content that is not ours: photos shown in the list and in playback keep their original colors and are
  not tinted.
- High contrast or "increase contrast" system settings and the largest font size must not make any
  screen unusable.
- Longer German strings must not break the new layouts. (The app has no right-to-left translation, so right-to-left layouts are out of scope.)
- The system's high-contrast text option must not make any screen unreadable.
- The very first frame while the app starts (before the screen is drawn) should use the page
  background of the current appearance, so there is no white flash in dark mode.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The app MUST use the light palette of the Visual reference for its backgrounds, surfaces,
  text, dividers and accents when the device is in the light appearance.
- **FR-002**: The app MUST use the dark palette of the Visual reference when the device is in the dark
  appearance, and MUST switch between the two with the device setting.
- **FR-003**: The app MUST show the same colors on all supported phones. It MUST NOT take its colors
  from the wallpaper or other system-generated color themes.
- **FR-004**: Every screen of the app (meeting list, recording, playback and transcript, settings, model
  management, setup, consent) and every app-owned dialog, sheet and menu MUST use the new palette,
  corner shapes and outline weights. No element may keep colors or styling from the previous design.
- **FR-005**: Interactive controls (buttons, switches, checkboxes, radio buttons, sliders, chips, text
  fields, progress indicators, the sound level bar) MUST use the green accent for active and primary
  states and the quiet surface and line colors for inactive states.
- **FR-006**: Status information MUST use green for active or finished, blue for information, amber for
  attention and rose for errors, and MUST always be accompanied by text or an icon so that color is
  never the only carrier of meaning.
- **FR-007**: Corners MUST be modestly rounded (about 8 dp) for cards, buttons and fields and fully round
  for chips and pills. Separation between areas MUST rely on thin soft outlines and quiet surface
  changes rather than heavy shadows.
- **FR-008**: Text MUST use the system font. The app MUST NOT bundle font files or load fonts from the
  network. Headings, body and labels use a clear size and weight hierarchy that keeps the calm,
  clutter-free feel of the reference.
- **FR-009**: Icons MUST be simple line icons with a thin, round-ended stroke, consistent in size and
  weight across the app.
- **FR-010**: The status bar and the navigation bar MUST blend with the page background in both
  appearances, with readable icons.
- **FR-011**: Normal text MUST have a contrast ratio of at least 4.5:1 against its background, and large
  text and control outlines at least 3:1, in both appearances.
- **FR-012**: The restyle MUST NOT change any behavior, screen flow, stored data, file format, permission,
  network access or dependency of the app. It MUST NOT remove or reword any text; all UI strings stay
  localized in English and German as they are.
- **FR-013**: The restyle MUST NOT reduce touch target sizes below the current ones and MUST keep every
  screen usable with the largest system font size.
- **FR-014**: The palette and shapes MUST be defined in one place, so that a later change of the look
  needs no edits on individual screens.
- **FR-015**: The window shown while the app starts MUST already use the page background of the current
  appearance.
- **FR-016**: The launcher icon MUST be a solid green circle in the green of the Fossify launcher icons
  (`#106D20`) with a bold white microphone glyph inside the safe zone of an adaptive icon, MUST have a
  monochrome variant for themed icons, and MUST NOT contain any Fossify name, logo or glyph. The white glyph
  MUST have a contrast of at least 4.5:1 against the green.

### Key Entities

- **Palette**: The set of named colors of the Visual reference (backgrounds, surfaces, text levels,
  divider, accent, strong accent, accent container, information, warning, error) in a light and a dark
  variant. It is the only source of color for the app.
- **Shape and type scale**: The agreed corner radii (cards and fields, pills), the outline weight and the
  text hierarchy used on all screens.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In a screen-by-screen review of all 7 screens in light and dark (14 views) against the
  Visual reference, 100% of the views use the reference palette, shapes and line weights, and none
  shows a leftover color from the previous design. (This covers the in-app screens; the launcher icon is covered by SC-008.)
- **SC-002**: All text and control outlines pass the contrast thresholds of FR-011 in all 14 views.
- **SC-003**: Changing the phone's wallpaper or system color theme changes none of the app's colors
  (0 differences on 3 different wallpapers).
- **SC-004**: With the largest system font size, 100% of the screens remain fully usable (no cut-off
  text, no unreachable action).
- **SC-005**: The existing automated test suite and the existing verification checks (permissions,
  dependencies, hard-coded strings, network use) pass unchanged, and no test needed a behavior change.
- **SC-006**: When the device is switched between light and dark, the app shows the matching look within
  1 second (judged on the device, no instrument needed) and without losing the state of any open screen.
- **SC-007**: In a quick look-alike check, a person who knows other Fossify apps or the Fossify website
  recognizes the app as visually belonging to the same family (accepted by the project owner).
- **SC-008**: Next to a Fossify launcher icon on the same phone, the app icon shows the same green and the same
  flat white glyph on a full circle in 100% of three checked launcher shapes (round, squircle, square), and in
  the themed-icon mode the glyph is still recognizable as a microphone (checked on the phone).

## Assumptions

- "Style" means the look of the app itself: colors, shapes, typography, icon style and system bars. It
  does not mean the layout, navigation, features or wording, which stay as they are.
- The fixed Fossify palette replaces the current wallpaper-based color behavior on newer Android
  versions, because matching a brand look requires stable colors (FR-003). A user-selectable theme
  (for example "follow system colors") is out of scope and can be added later.
- The app keeps following the device's light or dark setting. A manual in-app light/dark switch is out of
  scope.
- The launcher icon is in scope (User Story 4, added on 2026-11-18 at the owner's request). The Play Store or
  F-Droid listing graphics and screenshots are out of scope; they can be restyled in a later change.
- The launcher icon takes over the Fossify icon style (a solid green circle with a simple white glyph) and the
  green measured on the Fossify launcher icons on the owner's phone, `#106D20`. The glyph stays the app's own
  microphone; no Fossify glyph, name or logo is copied.
- The Fossify website's "system font" approach is mirrored in the app by using the system font, which also
  satisfies the project's rules of no bundled binaries and no network access for fonts.
- The website's tokens were read on 2026-11-18. The website is a reference, not a dependency: the app has
  no link to the website, and later changes of the website do not change the app automatically.
- The palette tokens are used exactly as published; derived tones needed for states that the website
  does not show (pressed, disabled, focus) are computed from them and must keep the contrast rules of
  FR-011.
- Fossify is an independent open-source project. The app takes over a visual style only; it does not use
  Fossify names, logos or trademarks and does not claim to be a Fossify app.
- Existing instrumented and unit tests that assert behavior are unaffected. Visual checks are done
  manually on a device, in the same style as the manual scenarios of the earlier phases.
