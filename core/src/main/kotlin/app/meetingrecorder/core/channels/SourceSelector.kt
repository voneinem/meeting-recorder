package app.meetingrecorder.core.channels

import app.meetingrecorder.core.model.SourceInfo
import app.meetingrecorder.core.model.SourceType

/** A microphone that can be chosen right now. [id] is the platform's id of the device, opaque here. */
public data class SourceOption(val id: Int, val info: SourceInfo) {
    /** Channels that would be recorded from this source (FR-008, FR-009). */
    public val recordedChannels: Int get() = ChannelPolicy.recordedChannels(info.type, info.deliveredChannels)
}

/**
 * Which microphone to offer, suggest and fall back to (FR-006, FR-007, FR-012). Pure functions over
 * the list of currently available sources, so the rules are testable without a device.
 */
public object SourceSelector {

    /** The external source to suggest when one is plugged in: a USB source first, then any other external one. */
    public fun suggested(options: List<SourceOption>): SourceOption? =
        options.firstOrNull { it.info.type == SourceType.USB }
            ?: options.firstOrNull { it.info.type == SourceType.OTHER }

    /** The phone's own microphone, the fallback that is always there. */
    public fun builtIn(options: List<SourceOption>): SourceOption? =
        options.firstOrNull { it.info.type == SourceType.BUILTIN }

    /**
     * The source to use for a new recording: the user's choice while it is still available, otherwise
     * the suggested external source, otherwise the built-in microphone.
     */
    public fun resolve(selectedId: Int?, options: List<SourceOption>): SourceOption? =
        options.firstOrNull { it.id == selectedId } ?: suggested(options) ?: builtIn(options)

    /**
     * What a running recording continues on when its source [activeId] is no longer in [options]:
     * the built-in microphone. Null means the active source is still available (or nothing can take over).
     */
    public fun fallbackFor(activeId: Int, options: List<SourceOption>): SourceOption? =
        if (options.any { it.id == activeId }) null else builtIn(options)

    /** External sources that appeared in [now] compared with [before]; they are only reported, never switched to (FR-015). */
    public fun newlyAvailableExternal(before: List<SourceOption>, now: List<SourceOption>): List<SourceOption> {
        val known = before.map { it.id }.toSet()
        return now.filter { it.id !in known && it.info.type != SourceType.BUILTIN }
    }
}
