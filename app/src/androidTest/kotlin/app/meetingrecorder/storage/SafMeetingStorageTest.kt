package app.meetingrecorder.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.meetingrecorder.TestTree
import app.meetingrecorder.targetContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** [SafMeetingStorage] against a real document tree: folders, text, seek-and-patch, listing, delete. */
@RunWith(AndroidJUnit4::class)
class SafMeetingStorageTest {
    private lateinit var tree: TestTree
    private lateinit var storage: SafMeetingStorage

    @Before
    fun setUp() {
        tree = TestTree(targetContext, "saf-storage-test")
        storage = SafMeetingStorage(targetContext, tree.uri)
    }

    @After
    fun tearDown() {
        tree.close()
    }

    @Test
    fun createsFoldersAndFilesAndListsThem() {
        val folder = checkNotNull(storage.createDirectory(storage.rootUri, "2026-01-01_10-00-00"))
        checkNotNull(storage.createDirectory(folder, "photos"))
        checkNotNull(storage.createFile(folder, "meeting.json", SafMeetingStorage.MIME_JSON))

        val root = storage.listChildren(storage.rootUri)
        assertEquals(listOf("2026-01-01_10-00-00"), root.map { it.name })
        assertTrue(root.single().isDirectory)

        val children = storage.listChildren(folder).associateBy { it.name }
        assertEquals(setOf("photos", "meeting.json"), children.keys)
        assertTrue(children.getValue("photos").isDirectory)
        assertEquals(false, children.getValue("meeting.json").isDirectory)
        assertNotNull(storage.findChild(folder, "photos"))
        assertNull(storage.findChild(folder, "missing"))
    }

    @Test
    fun writesAndReadsTextAndAShorterTextReplacesTheOldOne() {
        val file = checkNotNull(storage.createFile(storage.rootUri, "meeting.json", SafMeetingStorage.MIME_JSON))
        storage.writeText(file, """{"title":"a long first version"}""")
        assertEquals("""{"title":"a long first version"}""", storage.readText(file))

        storage.writeText(file, """{"title":"b"}""")
        assertEquals("""{"title":"b"}""", storage.readText(file))
        assertEquals("""{"title":"b"}""".length.toLong(), storage.findChild(storage.rootUri, "meeting.json")?.sizeBytes)
    }

    @Test
    fun seeksAndPatchesBytesInAnExistingFile() {
        val file = checkNotNull(storage.createFile(storage.rootUri, "audio.wav", SafMeetingStorage.MIME_WAV))
        storage.openStore(file).use { store ->
            store.writeAt(0, ByteArray(100) { it.toByte() })
            store.writeAt(10, byteArrayOf(-1, -2, -3)) // patch in the middle
            store.writeAt(100, byteArrayOf(7, 7)) // grow by appending
            store.sync()
            assertEquals(102L, store.length())
        }
        storage.openStore(file).use { store ->
            val patched = ByteArray(5)
            assertEquals(5, store.readAt(9, patched))
            assertArrayEquals(byteArrayOf(9, -1, -2, -3, 13), patched)
            val tail = ByteArray(2)
            assertEquals(2, store.readAt(100, tail))
            assertArrayEquals(byteArrayOf(7, 7), tail)
        }
    }

    @Test
    fun deletesAFileAndAWholeFolder() {
        val folder = checkNotNull(storage.createDirectory(storage.rootUri, "meeting"))
        val photos = checkNotNull(storage.createDirectory(folder, "photos"))
        val photo = checkNotNull(storage.createFile(photos, "photo-001.jpg", SafMeetingStorage.MIME_JPEG))
        storage.writeText(photo, "jpeg")

        assertTrue(storage.delete(photo))
        assertTrue(storage.listChildren(photos).isEmpty())

        checkNotNull(storage.createFile(photos, "photo-002.jpg", SafMeetingStorage.MIME_JPEG))
        assertTrue(storage.delete(folder))
        assertTrue(storage.listChildren(storage.rootUri).isEmpty())
    }

    @Test
    fun theFolderPassesTheUsabilityProbeAndReportsFreeSpace() {
        assertTrue("probe failed: ${storage.probe().exceptionOrNull()}", storage.probe().isSuccess)
        assertTrue(storage.listChildren(storage.rootUri).isEmpty()) // the probe file is gone again
        assertTrue((storage.freeBytes() ?: 0L) > 0L)
    }
}
