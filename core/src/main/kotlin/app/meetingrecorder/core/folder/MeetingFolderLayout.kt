package app.meetingrecorder.core.folder

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** File and folder names of a meeting folder (contracts/meeting-folder.md). */
public object MeetingFolderLayout {
    public const val METADATA_FILE: String = "meeting.json"
    public const val GUIDE_FILE: String = "CLAUDE.md"
    public const val AUDIO_FILE: String = "audio.wav"
    public const val PHOTOS_DIR: String = "photos"

    private val folderTime = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    private val titleTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val photoName = Regex("""^photo-(\d{3,})\.jpg$""")

    /** `YYYY-MM-DD_HH-mm-ss` in the device's local time at recording start. */
    public fun folderName(localStart: LocalDateTime): String = folderTime.format(localStart)

    /** [base] when free, otherwise [base] with `-2`, `-3`, ... appended. */
    public fun uniqueFolderName(base: String, existing: Collection<String>): String {
        if (base !in existing) return base
        var suffix = 2
        while ("$base-$suffix" in existing) suffix++
        return "$base-$suffix"
    }

    /** The default title when none is given: the start date and time (`YYYY-MM-DD HH:mm`). */
    public fun defaultTitle(localStart: LocalDateTime): String = titleTime.format(localStart)

    public fun photoFileName(number: Int): String {
        require(number >= 1) { "photo numbers start at 1" }
        return "photo-%03d.jpg".format(number)
    }

    /** Path of a photo relative to the meeting folder, as written to `meeting.json`. */
    public fun photoPath(number: Int): String = "$PHOTOS_DIR/${photoFileName(number)}"

    /** The number for the next photo, given the file names already in `photos/`. */
    public fun nextPhotoNumber(existingPhotoFileNames: Collection<String>): Int =
        (existingPhotoFileNames.mapNotNull { photoName.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1

    /** A directory is a meeting folder when it contains `meeting.json` or `audio.wav`. */
    public fun isMeetingFolder(childNames: Collection<String>): Boolean =
        METADATA_FILE in childNames || AUDIO_FILE in childNames
}
