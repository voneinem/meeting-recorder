# Contract: The Theme

The app has no external interface for this feature; the "contract" is the set of rules that every screen
and component follows when it uses the theme. Reviewers check new and changed UI code against it.
Values are in [../data-model.md](../data-model.md).

## Provided by the theme

`MeetingRecorderTheme` (the only entry point) provides, for the current light or dark appearance:

1. a Material 3 color scheme built from the fixed palette (never from the wallpaper);
2. the status roles `success`, `information`, `warning`, `error` (foreground, container, on-container);
3. the shape scale and the type scale;
4. the shared card style (flat, 1 dp `line` border, 8 dp corners).

The theme follows the device's light/dark setting and nothing else. There is no in-app switch.

## Rules for screens and components

1. **No raw colors.** UI code never contains a hex value or `Color(...)` literal; it reads
   `MaterialTheme.colorScheme.*` or `MaterialTheme.statusColors.*`. Only `ui/theme/Palette.kt` has hex
   values.
2. **No raw radii or fonts.** Corners come from `MaterialTheme.shapes`, text from
   `MaterialTheme.typography`. Buttons use `AppButton`, `AppOutlinedButton` and `AppTextButton` (8 dp), never the
   raw Material calls. No font resource files, no remote fonts.
3. **Cards** use the shared card style (`AppCard`). No call site sets its own container color, elevation or
   border; a status card is requested with `AppCard(tone = CardTone.Warning)` or `CardTone.Error`, and the card
   sets its content color itself.
4. **Status needs text.** Every status color is accompanied by the existing text (or an icon with a
   content description). Color alone never carries meaning.
5. **Use the right role.**
   - `success`: recording active, transcription done, model installed.
   - `information`: transcription in progress, neutral notes.
   - `warning`: interrupted work, a condition that needs attention but loses nothing.
   - `error`: failure and denied permissions only.
6. **Contrast.** Any new foreground/background pair is added to `PaletteContrastTest` with its
   threshold (4.5 for text, 3.0 for large text and control outlines).
7. **Window level.** The window background, the splash background and the system bars use the paper color
   of the current appearance (resource colors in `values/` and `values-night/`), so the first frame has no
   flash and bar icons stay readable.
8. **Notifications** set the accent color to `green` through a color resource; their text and behavior
   are not part of the theme.
9. **Icons** (none today): simple line icons, thin round-ended stroke, vector resources, content
   descriptions required. No icon-library dependency without a Principle II check.
10. **Photos and user content** are never tinted or filtered.

## What must not change

Behavior, navigation, stored data and file formats, permissions, network access, dependencies, and all
string resources in `values/strings.xml` and `values-de/strings.xml`.
