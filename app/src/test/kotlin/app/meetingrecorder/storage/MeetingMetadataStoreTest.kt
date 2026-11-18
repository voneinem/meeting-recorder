package app.meetingrecorder.storage

import app.meetingrecorder.core.folder.MeetingFolderLayout
import app.meetingrecorder.core.json.MeetingJson
import app.meetingrecorder.core.model.MeetingEditor
import app.meetingrecorder.core.model.MeetingMetadata
import app.meetingrecorder.core.model.SourceInfo
import app.meetingrecorder.core.model.SourceType
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeetingMetadataStoreTest {
    private val meeting = MeetingEditor.create(
        title = "Weekly",
        startedAt = OffsetDateTime.parse("2026-11-16T10:00:00+01:00"),
        sampleRate = 48_000,
        initialSource = SourceInfo("Built-in microphone", SourceType.BUILTIN, deliveredChannels = 1),
    )

    private fun json(m: MeetingMetadata) = MeetingJson.encode(m)

    private fun decoded(text: String?): MeetingMetadata =
        (MeetingJson.decode(checkNotNull(text)) as MeetingJson.DecodeResult.Success).meeting

    private fun storeFor(vararg folders: Pair<String, FakeFolderFiles>): MeetingMetadataStore {
        val byName = folders.toMap()
        return MeetingMetadataStore(warn = { _, _ -> }) { byName[it] }
    }

    @Test
    fun updateWritesTheTransformedMeetingAndRegeneratesTheGuide() {
        val files = FakeFolderFiles(mapOf(MeetingFolderLayout.METADATA_FILE to json(meeting)))
        val store = storeFor("m1" to files)

        assertTrue(store.update("m1") { MeetingEditor.rename(it, "Renamed") })

        assertEquals("Renamed", decoded(files.text(MeetingFolderLayout.METADATA_FILE)).title)
        assertTrue(files.text(MeetingFolderLayout.GUIDE_FILE)!!.contains("Renamed"))
    }

    @Test
    fun anUnknownFolderGivesFalse() {
        assertFalse(storeFor().update("missing") { it })
    }

    @Test
    fun theWriteGoesThroughTheStagedReplace() {
        val files = FakeFolderFiles(mapOf(MeetingFolderLayout.METADATA_FILE to json(meeting)))

        storeFor("m1" to files).update("m1") { MeetingEditor.rename(it, "Renamed") }

        val operations = files.operations.filter { it.endsWith(":meeting.json") }
        assertEquals(listOf("replace:meeting.json", "part-written:meeting.json", "old-deleted:meeting.json", "renamed:meeting.json"), operations)
        assertFalse(MeetingFolderLayout.METADATA_FILE + MeetingFolderLayout.PART_SUFFIX in files.names())
    }

    @Test
    fun aRenameAndAJobUpdateRunningTogetherBothSurvive() {
        val files = FakeFolderFiles(mapOf(MeetingFolderLayout.METADATA_FILE to json(meeting)))
        val store = storeFor("m1" to files)
        val insideFirst = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        var secondSaw: MeetingMetadata? = null

        val first = thread {
            store.update("m1") { current ->
                insideFirst.countDown()
                assertTrue(releaseFirst.await(10, TimeUnit.SECONDS))
                MeetingEditor.rename(current, "Renamed")
            }
        }
        assertTrue(insideFirst.await(10, TimeUnit.SECONDS))
        val second = thread {
            store.update("m1") { current ->
                secondSaw = current
                MeetingEditor.startJob(current, "small-q5_1", "de", OffsetDateTime.parse("2026-11-16T11:00:00+01:00"))
            }
        }
        // The second update must wait for the lock instead of working on a stale copy.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (second.state != Thread.State.WAITING && second.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.sleep(5)
        }
        assertTrue(second.state == Thread.State.WAITING || second.state == Thread.State.TIMED_WAITING)
        releaseFirst.countDown()
        first.join(10_000)
        second.join(10_000)

        assertEquals("Renamed", secondSaw?.title) // re-read inside the lock
        val final = decoded(files.text(MeetingFolderLayout.METADATA_FILE))
        assertEquals("Renamed", final.title)
        assertNotNull(final.transcription?.job)
    }

    @Test
    fun aPartFileThatDecodesIsUsedWhenTheMetadataIsMissing() {
        val files = FakeFolderFiles(mapOf("meeting.json.part" to json(MeetingEditor.rename(meeting, "From part"))))

        assertTrue(storeFor("m1" to files).update("m1") { MeetingEditor.rename(it, "${it.title} 2") })

        assertEquals("From part 2", decoded(files.text("meeting.json")).title)
        assertNull(files.text("meeting.json.part"), "the stale part file is removed after a successful write")
    }

    @Test
    fun aPartFileIsUsedWhenTheMetadataIsUnreadable() {
        val files = FakeFolderFiles(
            mapOf("meeting.json" to "{ not json", "meeting.json.part" to json(MeetingEditor.rename(meeting, "From part"))),
        )

        assertTrue(storeFor("m1" to files).update("m1") { it })

        assertEquals("From part", decoded(files.text("meeting.json")).title)
    }

    @Test
    fun withoutAReadableMetadataOrPartFileNothingIsWritten() {
        val files = FakeFolderFiles(mapOf("meeting.json" to "{ not json", "meeting.json.part" to "also broken"))

        assertFalse(storeFor("m1" to files).update("m1") { it })

        assertEquals("{ not json", files.text("meeting.json"))
    }

    @Test
    fun aMetadataFileFromANewerAppVersionIsNeverOverwritten() {
        val newer = json(meeting).replace("\"schemaVersion\": 1", "\"schemaVersion\": 2").replace("\"schemaVersion\":1", "\"schemaVersion\":2")
        val files = FakeFolderFiles(mapOf("meeting.json" to newer, "meeting.json.part" to json(meeting)))

        assertFalse(storeFor("m1" to files).update("m1") { MeetingEditor.rename(it, "x") })

        assertEquals(newer, files.text("meeting.json"))
    }

    @Test
    fun aFailingWriteLeavesThePreviousContent() {
        val before = json(meeting)
        val files = FakeFolderFiles(mapOf("meeting.json" to before))
        files.failAt(FolderStep.DELETE_OLD)

        assertFalse(storeFor("m1" to files).update("m1") { MeetingEditor.rename(it, "Renamed") })

        assertEquals(before, files.text("meeting.json"))
    }

    @Test
    fun aWriteInterruptedAfterTheOldFileWasDeletedIsHealedByTheNextUpdate() {
        val files = FakeFolderFiles(mapOf("meeting.json" to json(meeting)))
        val store = storeFor("m1" to files)
        files.failAt(FolderStep.RENAME)

        assertFalse(store.update("m1") { MeetingEditor.rename(it, "Renamed") })
        assertNull(files.text("meeting.json"))
        assertNotNull(files.text("meeting.json.part"))

        assertTrue(store.update("m1") { it })
        assertEquals("Renamed", decoded(files.text("meeting.json")).title)
    }

    @Test
    fun twoMeetingsDoNotBlockEachOther() {
        val one = FakeFolderFiles(mapOf("meeting.json" to json(meeting)))
        val two = FakeFolderFiles(mapOf("meeting.json" to json(meeting)))
        val store = storeFor("one" to one, "two" to two)
        val insideOne = CountDownLatch(1)
        val releaseOne = CountDownLatch(1)

        val blocked = thread {
            store.update("one") { current ->
                insideOne.countDown()
                releaseOne.await(10, TimeUnit.SECONDS)
                current
            }
        }
        assertTrue(insideOne.await(10, TimeUnit.SECONDS))
        var finished = false
        val other = thread { finished = store.update("two") { MeetingEditor.rename(it, "Two") } }
        other.join(5_000)
        val done = !other.isAlive && finished
        releaseOne.countDown()
        blocked.join(10_000)

        assertTrue(done, "the update of the second meeting waited for the first")
        assertEquals("Two", decoded(two.text("meeting.json")).title)
    }

    @Test
    fun replaceWritesWithoutReadingTheCurrentFile() {
        val files = FakeFolderFiles(mapOf("meeting.json" to "{ broken"))

        assertTrue(storeFor("m1" to files).replace("m1", meeting))

        assertEquals(meeting, decoded(files.text("meeting.json")))
    }
}
