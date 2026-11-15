# Meeting Recorder Constitution

Meeting Recorder is an Android app for recording, photographing and (in later phases)
transcribing meetings. The principles below are non-negotiable unless amended through the
Governance procedure.

## Core Principles

### I. Offline & Privacy First

The app MUST NOT access the network, with exactly one exception: an explicit, user-initiated
download of ML models. The user MUST be told what is downloaded and from where before the
download starts, and nothing may be downloaded in the background or without a user action.
The app MUST NOT contain telemetry, analytics, crash-reporting uploads, tracking, advertising
or cloud features of any kind. Meeting data MUST never leave the device unless the user
explicitly exports or shares it.

Rationale: Meetings contain confidential conversations. Trust is only credible if the app is
technically incapable of leaking data.

### II. F-Droid Compatibility

All dependencies MUST be free/open-source software with licenses acceptable to F-Droid. The
app MUST NOT use Google Play Services, Firebase, proprietary SDKs or prebuilt binary
libraries (`.so`, `.aar` or `.jar` blobs). Native code MUST be built from source as part of
the project build. The build MUST aim to be reproducible (pinned dependency versions and
toolchain, no build-time network downloads of unverified artifacts). Every new dependency
MUST be checked against these rules before it is added.

Rationale: The app is distributed through F-Droid, which builds from source and rejects
non-free components.

### III. No Data Loss

An ongoing recording MUST survive app crashes, process death, screen-off, app switching and
unplugging of an external microphone. Audio MUST be written to disk incrementally, so that
all audio recorded up to the failure is always playable. A recording interrupted by a
crash or device event MUST be recoverable on next app start without user effort, and an
input-device change MUST NOT silently end the recording. Features MUST NOT trade durability
of captured data for convenience or performance.

Rationale: A meeting cannot be repeated. Losing a recording is the worst possible failure.

### IV. Open File Format

Every meeting MUST be a self-describing folder that uses documented standard formats only:
WAV (audio), JPEG (photos), JSON (metadata, e.g. `meeting.json`) and Markdown (human- and
machine-readable text such as transcripts and notes). The folder is the single source of
truth; the app MUST NOT keep essential meeting state in a private database or other
location outside it. The format MUST be documented and versioned, so the folder can be
understood and processed without the app, in particular by Claude Code. Format changes MUST
remain backward compatible or ship with a versioned migration.

Rationale: Users own their data. Open formats enable processing with arbitrary tools and
guarantee long-term readability.

### V. Single Timeline

All time references inside a meeting (photos, transcript segments, speaker changes, markers)
MUST refer to the audio timeline, expressed as a sample position (with the sample rate
recorded in the metadata). Wall-clock time MAY be stored only as informational metadata
(for example the meeting start time) and MUST NOT be used to position or order content
within a meeting. Conversion between sample position and display time MUST be implemented
in one place.

Rationale: Wall-clock time drifts and breaks across pauses, device interruptions and
time-zone changes; the audio timeline is the one reference that always stays consistent.

### VI. Minimal Permissions

The app MUST request only the permissions a feature strictly needs, at the moment the
feature needs them. Every permission MUST be accompanied by a user-facing explanation of why
it is needed, and the app MUST degrade gracefully when a permission is denied. The
`INTERNET` permission MUST NOT be declared except to the extent required by the model
download in Principle I. Adding a permission MUST be justified in the spec or plan of the
feature that requires it.

Rationale: Fewer permissions mean a smaller attack surface and verifiable trust.

### VII. Testability

Format logic (folder structure, time calculation, JSON schema, serialization and migration)
MUST be implemented as pure Kotlin without Android dependencies and MUST be fully
unit-tested. Android-specific code (recording service, camera, UI) MUST be a thin layer
over this logic. Tests for format logic MUST run on the JVM without an emulator or device.
A change to format logic without accompanying tests MUST NOT be merged.

Rationale: The file format is the long-lived contract of the project; it must be verifiable
quickly and independently of the Android framework.

### VIII. Incremental Delivery

Development MUST proceed in phases, and every phase MUST end in a usable, installable app
that delivers value on its own. Later phases (for example transcription) MUST be additive
and MUST NOT be required for earlier functionality to work. Specs, plans and tasks MUST be
scoped so that each user story can be built, tested and shipped independently.

Rationale: A working small app beats a large unfinished one, and early usage feeds back
into later design.

### IX. English Everywhere, Localized UI

Code, identifiers, comments, commit messages, specs, plans, documentation, file formats and
generated files (such as `meeting.json` keys and `CLAUDE.md`) MUST be written in English.
Only user-facing UI strings are localized, via Android string resources: English is the
default (`values/`) and German is the first translation (`values-de/`). UI strings MUST NOT
be hard-coded in code or layouts; every new user-facing string MUST be added to
`values/strings.xml` and, in the same change, to `values-de/strings.xml`.

Rationale: One working language keeps the project, its tooling and its file formats
universally understandable, while the UI stays accessible to its users.

## Technology & Platform Constraints

- The app targets Android and is written in Kotlin; native code, where needed, is built from
  source via the project's build (see Principle II).
- Format logic lives in a pure Kotlin module free of Android dependencies (Principle VII);
  Android components depend on it, never the reverse.
- Audio is stored as WAV on the audio timeline (Principle V); photos as JPEG; metadata as
  JSON; text as Markdown (Principle IV).
- ML models are not bundled in the APK. They are obtained only through the explicit,
  user-initiated download described in Principle I, and their licenses MUST be FOSS-compatible.
- Data is stored in app-accessible folders that the user can reach and copy without
  special tooling.

## Development Workflow & Quality Gates

- Work follows the Spec Kit flow: specify → plan → tasks → implement. Each plan MUST
  include a Constitution Check against all nine principles.
- Every change MUST pass the unit tests of the pure Kotlin module before merge.
- Reviews MUST verify: no new network access, no non-FOSS or binary dependency, no new
  permission without justification, no hard-coded UI string, and no loss of
  crash-resilience of recording.
- Commit messages MUST be in English and describe the change and its reason.
- A phase is complete only when the resulting app is installable and usable (Principle VIII).

## Governance

This constitution supersedes all other practices and conventions in the project. Amendments
MUST be made by editing this file in a dedicated change that states the motivation, updates
the Sync Impact Report, bumps the version and lists any follow-up changes required in specs,
plans or code.

Versioning follows semantic versioning:

- MAJOR: removal or backward-incompatible redefinition of a principle or governance rule.
- MINOR: a new principle or section, or materially expanded guidance.
- PATCH: clarifications, wording and typo fixes without semantic change.

Compliance is reviewed at every plan (Constitution Check) and every code review. Violations
MUST either be fixed or be documented as a justified, time-boxed exception in the plan's
complexity tracking; an exception to Principles I–IV requires a constitution amendment
instead. Runtime development guidance for agents and contributors lives in `CLAUDE.md` and
MUST NOT contradict this constitution.

**Version**: 1.0.0 | **Ratified**: 2026-11-15 | **Last Amended**: 2026-11-15
