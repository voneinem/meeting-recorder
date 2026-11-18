package app.meetingrecorder.core.folder

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeetingFolderLayoutTest {

    private val start = LocalDateTime.of(2026, 11, 15, 9, 5, 7)

    @Test
    fun `constants match the folder contract`() {
        assertEquals("meeting.json", MeetingFolderLayout.METADATA_FILE)
        assertEquals("CLAUDE.md", MeetingFolderLayout.GUIDE_FILE)
        assertEquals("audio.wav", MeetingFolderLayout.AUDIO_FILE)
        assertEquals("photos", MeetingFolderLayout.PHOTOS_DIR)
    }

    @Test
    fun `folder name and default title come from the local start time`() {
        assertEquals("2026-11-15_09-05-07", MeetingFolderLayout.folderName(start))
        assertEquals("2026-11-15 09:05", MeetingFolderLayout.defaultTitle(start))
    }

    @Test
    fun `colliding folder names get a numeric suffix`() {
        val base = "2026-11-15_09-05-07"
        assertEquals(base, MeetingFolderLayout.uniqueFolderName(base, emptyList()))
        assertEquals("$base-2", MeetingFolderLayout.uniqueFolderName(base, listOf(base)))
        assertEquals("$base-3", MeetingFolderLayout.uniqueFolderName(base, listOf(base, "$base-2")))
        assertEquals("$base-2", MeetingFolderLayout.uniqueFolderName(base, listOf(base, "$base-3")))
    }

    @Test
    fun `photo names have three digits and grow`() {
        assertEquals("photo-001.jpg", MeetingFolderLayout.photoFileName(1))
        assertEquals("photo-042.jpg", MeetingFolderLayout.photoFileName(42))
        assertEquals("photo-1000.jpg", MeetingFolderLayout.photoFileName(1000))
        assertEquals("photos/photo-002.jpg", MeetingFolderLayout.photoPath(2))
        assertFailsWith<IllegalArgumentException> { MeetingFolderLayout.photoFileName(0) }
    }

    @Test
    fun `the next photo number follows the highest existing one`() {
        assertEquals(1, MeetingFolderLayout.nextPhotoNumber(emptyList()))
        assertEquals(3, MeetingFolderLayout.nextPhotoNumber(listOf("photo-001.jpg", "photo-002.jpg")))
        assertEquals(8, MeetingFolderLayout.nextPhotoNumber(listOf("photo-001.jpg", "photo-007.jpg", "notes.txt")))
    }

    @Test
    fun `a meeting folder contains metadata or audio`() {
        assertTrue(MeetingFolderLayout.isMeetingFolder(listOf("meeting.json")))
        assertTrue(MeetingFolderLayout.isMeetingFolder(listOf("audio.wav", "photos")))
        assertFalse(MeetingFolderLayout.isMeetingFolder(listOf("notes.txt", "photos")))
        assertFalse(MeetingFolderLayout.isMeetingFolder(emptyList()))
    }

    @Test
    fun `transcript file names and the staging suffix match the transcript contract`() {
        assertEquals("transcript.json", MeetingFolderLayout.TRANSCRIPT_JSON_FILE)
        assertEquals("transcript.md", MeetingFolderLayout.TRANSCRIPT_MD_FILE)
        assertEquals("transcript.work.jsonl", MeetingFolderLayout.TRANSCRIPT_WORK_FILE)
        assertEquals(".part", MeetingFolderLayout.PART_SUFFIX)
        assertEquals("transcript.json.part", MeetingFolderLayout.partName("transcript.json"))
    }

    @Test
    fun `staging files are recognised by name`() {
        assertTrue(MeetingFolderLayout.isStagingFile("transcript.json.part"))
        assertTrue(MeetingFolderLayout.isStagingFile("transcript.md.part"))
        assertTrue(MeetingFolderLayout.isStagingFile("meeting.json.part"))
        assertFalse(MeetingFolderLayout.isStagingFile("transcript.json"))
        assertFalse(MeetingFolderLayout.isStagingFile("transcript.work.jsonl"))
        assertFalse(MeetingFolderLayout.isStagingFile("audio.wav.part"))
    }

    @Test
    fun `staging and work files alone do not make a meeting folder`() {
        assertFalse(MeetingFolderLayout.isMeetingFolder(listOf("transcript.json", "transcript.json.part")))
        assertTrue(MeetingFolderLayout.isMeetingFolder(listOf("transcript.json", "meeting.json")))
        assertTrue(MeetingFolderLayout.isMeetingFolder(listOf("audio.wav", "transcript.work.jsonl")))
    }
}
