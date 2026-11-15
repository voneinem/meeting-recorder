package app.meetingrecorder.core.guide

import app.meetingrecorder.core.model.ChannelLayout
import app.meetingrecorder.core.model.MeetingMetadata
import app.meetingrecorder.core.model.MeetingStatus
import app.meetingrecorder.core.model.Source
import app.meetingrecorder.core.time.Timeline

/**
 * Generates the `CLAUDE.md` of a meeting folder: an English guide for people and AI assistants
 * (contracts/meeting-folder.md). The text never depends on the device language.
 */
public object ClaudeGuide {

    public fun generate(meeting: MeetingMetadata): String = buildString {
        val audio = meeting.audio
        val layout = if (audio.channelLayout == ChannelLayout.STEREO) "stereo" else "mono"

        appendLine("# ${meeting.title}")
        appendLine()
        appendLine(
            "Meeting recorded with Meeting Recorder on ${meeting.startedAt}. " +
                "Duration: ${Timeline.format(audio.durationMs)}. " +
                "Audio: $layout, ${audio.sampleRate} Hz, 16-bit PCM WAV.",
        )
        statusNote(meeting)?.let {
            appendLine()
            appendLine(it)
        }
        appendLine()

        appendLine("## Folder contents")
        appendLine()
        appendLine("| Path | Description |")
        appendLine("|------|-------------|")
        appendLine("| `meeting.json` | Metadata of this meeting (schema version ${meeting.schemaVersion}). |")
        appendLine("| `audio.wav` | The complete meeting audio as one continuous file. |")
        appendLine("| `photos/` | ${photoCountText(meeting.photos.size)} |")
        appendLine("| `CLAUDE.md` | This guide. |")
        appendLine()

        appendLine("## How to read the timeline")
        appendLine()
        appendLine(
            "Every position is a sample-frame index into `audio.wav`, counted from the first frame " +
                "(`atFrame`). The value `atMs` is derived: `atMs = atFrame * 1000 / sampleRate`. " +
                "In this meeting the sample rate is ${audio.sampleRate} Hz.",
        )
        appendLine()
        appendLine(
            "Paused time and the short gap while a microphone was switched are not part of the timeline. " +
                "Wall-clock values such as `startedAt` are informational only and are never used to position content.",
        )
        appendLine()

        appendLine("## Channels")
        appendLine()
        if (audio.channelLayout == ChannelLayout.STEREO) {
            appendLine(
                "The audio is stereo. When it was recorded with a two-speaker microphone in split mode, " +
                    "the left channel is speaker A and the right channel is speaker B.",
            )
        } else {
            appendLine("The audio is mono (one channel).")
        }
        appendLine()
        appendLine("Sources used:")
        appendLine()
        for (source in meeting.sources) appendLine(sourceLine(source, meeting.initialSourceId))
        if (meeting.sourceSwitches.isNotEmpty()) {
            appendLine()
            appendLine("Source switches:")
            appendLine()
            for (switch in meeting.sourceSwitches) {
                appendLine(
                    "- At ${Timeline.format(switch.atMs)} (frame ${switch.atFrame}): from source ${switch.fromSourceId} " +
                        "to source ${switch.toSourceId} (${switch.reason.name.lowercase().replace('_', ' ')})." +
                        if (switch.channelsDuplicated) {
                            " From this position on the channels no longer correspond to individual speakers: " +
                                "the signal of the new source is copied into all channels."
                        } else {
                            ""
                        },
                )
            }
        }
        if (meeting.dropouts.isNotEmpty()) {
            appendLine()
            appendLine("Dropouts (audio that could not be stored and is missing from the file):")
            appendLine()
            for (dropout in meeting.dropouts) {
                val lostMs = Timeline.framesToMs(dropout.droppedFrames, audio.sampleRate)
                appendLine("- At ${Timeline.format(dropout.atMs)} (frame ${dropout.atFrame}): $lostMs ms lost.")
            }
        }
        appendLine()

        appendLine("## Photos")
        appendLine()
        if (meeting.photos.isEmpty()) {
            appendLine("No photos were taken.")
        } else {
            for (photo in meeting.photos) {
                appendLine("- ${Timeline.format(photo.atMs)} (frame ${photo.atFrame}): `${photo.file}`")
            }
        }
        appendLine()

        appendLine("## Suggested follow-up tasks")
        appendLine()
        appendLine(
            "Transcription is not part of this folder yet. Useful tasks for an AI assistant, for example " +
                "Claude Code, with this folder as input:",
        )
        appendLine()
        appendLine("1. **Summary**: \"Summarize this meeting in 10 lines. Use the photos as visual context.\"")
        appendLine("2. **Minutes**: \"Write meeting minutes with topics, decisions and open questions.\"")
        appendLine("3. **Action items**: \"List the action items with owner and due date, if mentioned.\"")
        appendLine()
        appendLine("Write results as new Markdown files in this folder (for example `summary.md`); do not edit `meeting.json`.")
        appendLine()

        appendLine("## Format reference")
        appendLine()
        appendLine("`meeting.json` is UTF-8 JSON. Unknown fields must be ignored. Fields:")
        appendLine()
        appendLine("- `schemaVersion`: format version (currently 1).")
        appendLine("- `title`: the meeting title. `startedAt`: wall-clock start, ISO-8601 with UTC offset (informational).")
        appendLine("- `status`: `recording` (not finished), `complete`, or `recovered` (repaired after an interruption).")
        appendLine("- `audio`: `file`, `format` (`pcm_s16le`), `sampleRate`, `channels` (1 or 2), `channelLayout`")
        appendLine("  (`mono` or `stereo`), `durationFrames` (authoritative) and `durationMs` (derived).")
        appendLine("- `sources`: microphones used, each with `id`, `name`, `type` (`builtin`, `usb` or `other`),")
        appendLine("  `deliveredChannels` and `channels` (recorded). `initialSourceId`: the source the recording started with.")
        appendLine("- `sourceSwitches`: `atFrame`, `atMs`, `fromSourceId`, `toSourceId`, `reason`, `channelsDuplicated`.")
        appendLine("- `photos`: `file` (path inside this folder), `atFrame`, `atMs`.")
        appendLine("- `dropouts`: `atFrame`, `atMs`, `droppedFrames` (audio missing from the file at that position).")
    }

    private fun statusNote(meeting: MeetingMetadata): String? = when (meeting.status) {
        MeetingStatus.RECOVERED ->
            "Note: this meeting was recovered after the app was interrupted. The end of the recording may be missing."
        MeetingStatus.RECORDING ->
            "Note: this meeting was still being recorded when this guide was written; duration and lists may be incomplete."
        MeetingStatus.COMPLETE -> null
    }

    private fun photoCountText(count: Int): String = when (count) {
        0 -> "Photos taken during the meeting (none)."
        1 -> "Photos taken during the meeting (1 photo)."
        else -> "Photos taken during the meeting ($count photos)."
    }

    private fun sourceLine(source: Source, initialSourceId: Int): String {
        val initial = if (source.id == initialSourceId) ", initial source" else ""
        val partial = if (source.deliveredChannels > source.channels) {
            "; only the first ${source.channels} channels were recorded"
        } else {
            ""
        }
        return "- Source ${source.id}: ${source.name} (${source.type.name.lowercase()}$initial), " +
            "delivers ${source.deliveredChannels} channel(s), recorded ${source.channels}$partial."
    }
}
