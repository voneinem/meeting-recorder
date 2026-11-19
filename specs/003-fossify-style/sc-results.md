# Success criteria: results and gaps (T042)

Written 2026-11-19, after the device checks that could be driven and the owner's decisions to skip or accept the rest.
"Met" means measured or accepted on the reference phone (Gigaset E940, Android 14). Nothing here is rounded up: a
criterion with a gap stays a gap. The owner accepted these gaps on 2026-11-19 so that the open tasks could be closed;
the gaps themselves are unchanged. Screenshots: `build/restyle-before/`, `build/restyle-after/` and `build/restyle-v3/`
(git-ignored, not committed).

| Criterion | Status | Evidence | Gap |
|---|---|---|---|
| SC-001 14 views match the reference, no old color | **Partly met** | 10 of 14 views (record, list, playback with transcript, settings, models; light and dark) were captured on the phone after the restyle and compared with the Visual reference: paper background, green accent, 8 dp buttons, thin card lines, no navy or dark red. The delete dialog was seen in dark, the done chip in light and dark, the recording screen in light and dark. | Setup and consent (4 views) were not seen; the rename and start-transcription dialogs, the permission cards and the in-progress and failed chips were not seen. |
| SC-002 contrast thresholds in all 14 views | **Partly met** | `PaletteContrastTest` checks every text, accent and control-outline pair of both appearances (9 tests, green), including the launcher glyph on its green. Three component edges that failed by eye were fixed on the phone: the seek track, the outlined button border and the level meter track. | No Accessibility Scanner run; component states such as disabled and pressed were not measured; the 4 unseen views are unchecked. |
| SC-003 wallpaper changes nothing | **Not measured** | By code: the dynamic color branch is gone and `ThemeSourceRulesTest` fails if it returns; `ColorSchemeRolesTest` shows that every role is a palette token. | T032 skipped: no wallpaper was changed on the phone. |
| SC-004 usable with the largest font | **Not measured** | One clipping problem at the normal font size in German (the list's action row) was found and fixed. | T032 skipped: nothing was checked with the largest font or display size or with high-contrast text. |
| SC-005 existing tests and checks pass unchanged | **Met** | `:core:test :app:check :app:assembleDebug` and the four verify tasks are green with the new tests added; no existing test file was changed (git shows only the new `ui/theme` test directory); `strings.xml` (both) and `verification-metadata.xml` have no diff. | none |
| SC-006 light/dark switch within 1 s, state kept | **Partly met** | On the phone, dark mode was switched while a recording ran: the dark look was on screen in the first screenshot, 1.5 s after the command including the adb round trip and a 1 s wait, so under 1.5 s. The timer ran on (00:04, 00:19, 00:24), the level meter kept moving, and the recording ended complete (39.04 s, no dropouts) and played back. | The 1 s limit is not strictly shown (the measurement includes the adb round trip). Typed text and a running transcription were not checked across the switch. |
| SC-007 recognized as the same family | **Met** | The owner looked at the restyled app and the icon on the phone and accepted it on 2026-11-19. | none |
| SC-008 launcher icon matches a Fossify icon | **Partly met** | The green `#106D20` was measured on the Fossify icons in the owner's screenshot; the glyph is a bold white microphone on a full circle; `PaletteContrastTest` checks the green and a contrast of 6.2:1; the owner looked at it on the phone next to the Fossify icons and accepted it. | Round, squircle and square launcher masks, the themed-icon mode and the splash screen were not checked. |

Count: **met** SC-005, SC-007; **partly met** SC-001, SC-002, SC-006, SC-008; **not measured** SC-003, SC-004.

## Requirements checklist

`checklists/requirements.md` has no open items (checked 2026-11-19: 0 unchecked boxes).

## Tasks that stay unticked

T032 (V4: largest font, high-contrast text, wallpapers) and T039 (V5: recording with photos, transcription,
airplane mode) were skipped by the owner on 2026-11-19; their notes in `tasks.md` list the evidence meanwhile.
