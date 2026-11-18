package app.meetingrecorder.storage

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.meetingrecorder.debug.TestDocumentsProvider
import app.meetingrecorder.TestTree
import app.meetingrecorder.targetContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
        setRenameSupported(true)
        tree.close()
    }

    private fun setRenameSupported(supported: Boolean) {
        targetContext.contentResolver.call(
            "content://${TestDocumentsProvider.AUTHORITY}".let(android.net.Uri::parse),
            TestDocumentsProvider.METHOD_SET_RENAME_SUPPORTED,
            null,
            Bundle().apply { putBoolean(TestDocumentsProvider.EXTRA_SUPPORTED, supported) },
        )
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

    @Test
    fun stagedReplaceOverAnExistingFileLeavesTheNewContentAndNoPartFile() {
        val file = checkNotNull(storage.createFile(storage.rootUri, "meeting.json", SafMeetingStorage.MIME_JSON))
        storage.writeText(file, "old content, longer than the new one")
        assertTrue(storage.probe().isSuccess)
        assertEquals(ReplaceStrategy.STAGED, storage.replaceStrategy)

        val replaced = storage.replaceText(storage.rootUri, "meeting.json", "new", SafMeetingStorage.MIME_JSON)

        assertEquals("new", storage.readText(replaced))
        assertEquals(listOf("meeting.json"), storage.listChildren(storage.rootUri).map { it.name })
        assertEquals(3L, storage.findChild(storage.rootUri, "meeting.json")?.sizeBytes)
    }

    @Test
    fun replaceWithoutAnExistingFileCreatesIt() {
        val created = storage.replaceText(storage.rootUri, "transcript.json", "{}", SafMeetingStorage.MIME_JSON)
        assertEquals("{}", storage.readText(created))
        assertEquals(listOf("transcript.json"), storage.listChildren(storage.rootUri).map { it.name })
    }

    @Test
    fun aStalePartFileFromAnEarlierCrashIsReplaced() {
        val file = checkNotNull(storage.createFile(storage.rootUri, "meeting.json", SafMeetingStorage.MIME_JSON))
        storage.writeText(file, "current")
        val stale = checkNotNull(storage.createFile(storage.rootUri, "meeting.json.part", SafMeetingStorage.MIME_OCTET_STREAM))
        storage.writeText(stale, "half written")

        storage.replaceText(storage.rootUri, "meeting.json", "fresh", SafMeetingStorage.MIME_JSON)

        assertEquals(listOf("meeting.json"), storage.listChildren(storage.rootUri).map { it.name })
        assertEquals("fresh", storage.readText(checkNotNull(storage.findChild(storage.rootUri, "meeting.json")).uri))
    }

    @Test
    fun withoutRenameSupportTheCapabilityIsDirectAndTheFileIsWrittenInPlace() {
        setRenameSupported(false)
        val file = checkNotNull(storage.createFile(storage.rootUri, "meeting.json", SafMeetingStorage.MIME_JSON))
        storage.writeText(file, "old content, longer than the new one")
        assertTrue(storage.probe().isSuccess)
        assertEquals(ReplaceStrategy.DIRECT, storage.replaceStrategy)

        val replaced = storage.replaceText(storage.rootUri, "meeting.json", "new", SafMeetingStorage.MIME_JSON)

        assertEquals(file, replaced)
        assertEquals("new", storage.readText(replaced))
        assertEquals(listOf("meeting.json"), storage.listChildren(storage.rootUri).map { it.name })
    }

    @Test
    fun theCapabilityIsReadFromTheProviderFlags() {
        assertTrue(storage.probe().isSuccess)
        assertEquals(ReplaceStrategy.STAGED, storage.replaceStrategy)
        setRenameSupported(false)
        val other = SafMeetingStorage(targetContext, tree.uri)
        assertEquals(ReplaceStrategy.DIRECT, other.replaceStrategy) // safe default before probing
        assertTrue(other.probe().isSuccess)
        assertEquals(ReplaceStrategy.DIRECT, other.replaceStrategy)
    }

    @Test
    fun renameRefusesANameThatAlreadyExists() {
        val first = checkNotNull(storage.createFile(storage.rootUri, "a.txt", SafMeetingStorage.MIME_OCTET_STREAM))
        checkNotNull(storage.createFile(storage.rootUri, "b.txt", SafMeetingStorage.MIME_OCTET_STREAM))
        assertNull(storage.rename(first, "b.txt"))
        assertNotNull(storage.rename(first, "c.txt"))
        assertEquals(setOf("b.txt", "c.txt"), storage.listChildren(storage.rootUri).map { it.name }.toSet())
    }

    @Test
    fun appendStoreAddsBytesAtTheEndOfAnExistingFile() {
        val file = checkNotNull(storage.createFile(storage.rootUri, "transcript.work.jsonl", SafMeetingStorage.MIME_OCTET_STREAM))
        storage.writeText(file, "line 1\n")
        storage.appendStore(file).use { store ->
            store.append("line 2\n".toByteArray())
            store.sync()
        }
        storage.appendStore(file).use { it.append("line 3\n".toByteArray()) }
        assertEquals("line 1\nline 2\nline 3\n", storage.readText(file))
    }

    @Test
    fun theReadOnlyStoreReadsAndRejectsWrites() {
        val file = checkNotNull(storage.createFile(storage.rootUri, "audio.wav", SafMeetingStorage.MIME_WAV))
        storage.writeText(file, "abcdef")
        storage.openReadOnlyStore(file).use { store ->
            val bytes = ByteArray(3)
            assertEquals(3, store.readAt(2, bytes))
            assertEquals("cde", String(bytes))
            try {
                store.writeAt(0, byteArrayOf(1))
                fail("a write through a read-only store must fail")
            } catch (_: java.io.IOException) {
                // expected
            }
        }
        assertEquals("abcdef", storage.readText(file))
    }

    /**
     * The platform's storage provider appends the extension of the MIME type to a name that does not fit it. A
     * staged file must keep the exact name `<name>.part`, because recovery and the commit read it back by that
     * name (found on the phone: `transcript.json.part` became `transcript.json.part.json`).
     */
    @Test
    fun aStagedPartFileKeepsItsExactNameOnAProviderThatAppendsExtensions() {
        val files = SafFolderFiles(storage, storage.rootUri)
        files.replaceText("transcript.json.part", "{}")
        files.replaceText("transcript.md.part", "# x")
        val names = files.list().map { it.name }
        assertTrue(names.toString(), "transcript.json.part" in names && "transcript.md.part" in names)
        assertEquals("{}", files.readText("transcript.json.part"))

        // And the replace of an existing file stages its part under the exact name too, then swaps it in.
        files.replaceText("meeting.json", "{\"a\":1}")
        files.replaceText("meeting.json", "{\"a\":2}")
        assertEquals("{\"a\":2}", files.readText("meeting.json"))
        assertEquals(emptyList<String>(), files.list().map { it.name }.filter { it.startsWith("meeting.json.") })
    }
}
