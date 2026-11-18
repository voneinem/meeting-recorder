# Success criteria: results and gaps (T181)

Written 2026-11-18, after the device scenarios that could be driven and the owner's decision to skip or defer the rest.
"Met" means measured on the reference phone (Gigaset E940, Android 14) or, where stated, on the API 34 and 35 emulators.
Nothing here is rounded up: a criterion with a gap stays a gap. The owner accepted these gaps on 2026-11-18 so that T181 could be ticked; the gaps themselves are unchanged.

| Criterion | Status | Evidence | Gap |
|---|---|---|---|
| SC-001 no network except the download | **Partly met** | `TranscriptionWorkerTest` asserts zero per-uid traffic bytes during a transcription (API 34 and 35). The S13 transcription on the phone ran in airplane mode. `verifyManifestPermissions` and `NetworkSourceScannerTest` are green. | Import, delete, resume and re-transcribe were not driven offline on the phone (T116, T167 skipped). No netstats check on Wi-Fi (T175 skipped). |
| SC-002 first use within 5 minutes, 9 of 10 | **Not measured** | none | T179 skipped. |
| SC-003 speed and estimate | **Met** | Tiny at 0.17x real time, default `small-q8_0` at 0.36 (spike 0.48 to 0.56) for the whole 10-minute run through the real worker. Remaining-time error 13.0 / 14.6 / 0.2 / 2.5 % at 10 / 30 / 60 / 90 %. | none. The percentage stands still during the first window (accepted by the owner, T136). |
| SC-004 screen off for 1 hour, percentages agree | **Partly met** | App and notification percentage agree to 0.00 points over 4006 readings. A 5-minute run finished with the screen off. | No 1-hour screen-off run (T174 skipped). |
| SC-005 forced termination, resume redoes at most 2 minutes | **Partly met** | Recovery and resume logic is unit-tested. On API 35 a foreground-service timeout stops the job as failed/interrupted/resumable with the work file kept. On the phone a half-committed job was completed by the start-up recovery. | No force-stop at 50% and no reboot run on the phone (T161 skipped). |
| SC-006 cancel within 5 s, meeting as before | **Partly met** | `TranscriptionWorkerTest.cancellingLeavesTheMeetingAsBefore` passes on API 34, API 35 and the phone (controller cancel, 5 s limit). | The cancel button of the transcription notification and the cancel and delete screens were not driven (T160 skipped). |
| SC-007 tap-to-play within 1 s | **Not measured** | none | T149 skipped. |
| SC-008 folder alone is enough | **Not measured** | The folder format is validated against the schemas for a real phone result (S13). | No Claude Code or person comprehension check (T180 skipped). |
| SC-009 3-hour detail screen opens in 3 s, smooth | **Partly met** | `TranscriptLoadPerformanceTest` (JVM) for a 3-hour transcript. | Not measured on the phone, scrolling not judged (T149, T174 skipped). |
| SC-010 damaged download is never usable | **Met** | S11 on the phone: cancel at about 4 MB leaves no partial file; a lost connection keeps the partial file and shows "Pausiert", not installed; the final sha256 matched. `ModelInstallTest`: wrong checksum is refused and nothing is installed. | none |
| SC-011 re-transcribing never loses the old transcript | **Partly met** | The commit protocol keeps the old file until the new one is complete (unit tests). On the phone a finished re-transcription replaced all four files consistently. | Cancelled, failed and killed re-runs not driven on the phone (T167 skipped). |
| SC-012 recording has priority, no gaps | **Partly met** | `RecordingPriorityTest` passes on API 34, API 35 and the phone: the transcription stops within 5 s, no dropouts, duration within 0.5 s of a recording without a transcription, a transcription is refused during a recording. | The one-minute scenario through the screens and the Phase 1 gap method were not run (T162 skipped). |

Count: **met** SC-003, SC-010; **partly met** SC-001, SC-004, SC-005, SC-006, SC-009, SC-011, SC-012; **not measured** SC-002, SC-007, SC-008.

## Requirements checklist

`checklists/requirements.md` has no open items (checked 2026-11-18: 0 unchecked boxes).
