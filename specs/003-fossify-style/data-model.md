# Data Model: Fossify Visual Style

This feature stores nothing. The "data" is the set of design tokens that every screen reads through the
theme. The values are the single source of truth together with the spec's Visual reference; code and
documents must agree with the tables below (`PaletteContrastTest` checks the code against them).

## Palette

Hex values are `RRGGBB`. "Published" values come from the Fossify website stylesheet; "Derived" values are
added by this feature (research R4).

| Token | Light | Dark | Origin | Material role |
|-------|-------|------|--------|---------------|
| `paper` | `FBFCF8` | `0B130F` | Published | `background` |
| `surface` | `FFFFFF` | `121D17` | Published | `surface`, low surface containers |
| `surfaceQuiet` | `F2F6F1` | `18251E` | Published | `surfaceContainer`, `surfaceVariant`, inactive tracks, chips |
| `ink` | `102019` | `EEF6EE` | Published | `onBackground`, `onSurface` |
| `inkSoft` | `3F4D45` | `C8D6CB` | Published | `onSurfaceVariant` |
| `muted` | `5B685F` | `96AA9B` | Published | hints, captions (text role, never below 4.5:1) |
| `line` | `DCE5DC` | `2B3C31` | Published | `outlineVariant` (card edges, dividers; decorative) |
| `outlineControl` | `7A8A7F` | `6B8172` | **Derived** | `outline` (field borders, unselected control edges) |
| `green` | `216E39` | `78D58D` | Published | `primary` |
| `onGreen` | `FFFFFF` | `0B130F` | **Derived** | `onPrimary` |
| `greenStrong` | `14552A` | `9DE8AD` | Published | pressed state, `onPrimaryContainer` |
| `mint` | `DFF5E4` | `173D25` | Published | `primaryContainer` |
| `blue` | `245F92` | `8BBCE8` | Published | information (`secondary`) |
| `blueSoft` | `E6F0F8` | `142B3C` | Published | information container |
| `amber` | `8F4B08` | `F0B05F` | Published | warning (`tertiary`) |
| `amberSoft` | `FBECD8` | `392716` | Published | warning container |
| `rose` | `A94442` | `EF9A96` | Published | `error` |
| `onRose` | `FFFFFF` | `0B130F` | **Derived** | `onError` |
| `roseSoft` | `F7E3E2` | `3A1F1E` | **Derived** | `errorContainer` (banner for failures) |
| `onRoseSoft` | `7A2E2C` | `F7C6C4` | **Derived** | `onErrorContainer` |

Rules:
- A derived token must keep the contrast thresholds in the table below and may not be used for anything
  the website's tokens already cover.
- The palette has no alpha values; states (pressed, disabled, focus) are computed (research R4).
- No token names a brand (no "fossify" in code identifiers or resources).

### Required contrast pairs (checked by `PaletteContrastTest`, both appearances)

| Foreground | Background | Minimum |
|------------|------------|---------|
| `ink`, `inkSoft`, `muted` | `paper`, `surface`, `surfaceQuiet` | 4.5 |
| `onGreen` | `green` | 4.5 |
| `green` | `paper`, `surface` | 3.0 |
| `greenStrong` | `mint` | 4.5 |
| `green` | `mint` | 4.5 |
| `blue` | `blueSoft` | 4.5 |
| `amber` | `amberSoft` | 4.5 |
| `rose` | `paper`, `surface` | 4.5 |
| `rose` | `roseSoft` | 4.5 |
| `amber`, `blue` | `paper`, `surface` | 4.5 |
| `onRose` | `rose` | 4.5 |
| `onRoseSoft` | `roseSoft` | 4.5 |
| `outlineControl` | `paper`, `surface`, `surfaceQuiet` | 3.0 |

## Status roles (no Material slot)

| Role | Foreground | Container | On container | Meaning |
|------|------------|-----------|--------------|---------|
| `success` | `green` | `mint` | `greenStrong` | active, recording, finished |
| `information` | `blue` | `blueSoft` | `blue` | neutral notes, in-progress |
| `warning` | `amber` | `amberSoft` | `amber` | needs attention, interrupted |
| `error` | `rose` | `roseSoft` | `onRoseSoft` | failed, denied (Material `error`) |

Each status is always shown together with its existing text label (FR-006).

## Shape scale

| Slot | Radius | Used by |
|------|--------|---------|
| `extraSmall` | 8 dp | outlined text fields (Material reads this slot), tooltips |
| `small` | 8 dp | text fields, menus, snackbars |
| `medium` | 8 dp | cards (`AppCard`), buttons (`AppButton`, `AppOutlinedButton`, `AppTextButton` set it explicitly) |
| `large` | 8 dp | large cards, list items |
| `extraLarge` | 12 dp | dialogs, bottom sheets |
| pill | fully round | status chips (explicit `CircleShape`; Material's assist chip default is 8 dp), toggle tracks (Material default) |

Card border: 1 dp `line`; card elevation: 0 dp (research R6).

## Type scale

System font family, Material 3 default sizes; weights adjusted:

| Style | Weight |
|-------|--------|
| `titleLarge`, `titleMedium`, `titleSmall` | Medium |
| `headline*` | Medium |
| `bodyLarge`, `bodyMedium`, `bodySmall` | Regular |
| `labelLarge`, `labelMedium`, `labelSmall` | Medium |

No size below 12 sp; all sizes scale with the system font setting (FR-013).

## Relationships

`Palette` (plain values) → `ColorScheme` (Material roles) + `StatusColors` (extra roles) → provided by
`MeetingRecorderTheme` together with the shape and type scales → read by all screens. No screen reads a
hex value.
