package app.meetingrecorder

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.meetingrecorder.core.folder.MeetingFolderLayout
import app.meetingrecorder.core.json.MeetingJson
import app.meetingrecorder.core.model.MeetingMetadata
import app.meetingrecorder.core.wav.WavFormat
import app.meetingrecorder.core.wav.WavHeader
import app.meetingrecorder.debug.TestDocumentsProvider
import app.meetingrecorder.storage.SafMeetingStorage
import java.io.File
import org.junit.Assert.assertTrue

/**
 * A fresh document tree for one test, backed by a folder in the app's cache and served by the
 * debug-only [TestDocumentsProvider]. Closing it deletes everything it contains.
 */
class TestTree(context: Context, name: String) : AutoCloseable {
    private val dir = File(File(context.cacheDir, TestDocumentsProvider.BASE_DIR), name)

    val uri: Uri

    init {
        dir.deleteRecursively()
        dir.mkdirs()
        uri = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, name)
    }

    override fun close() {
        dir.deleteRecursively()
    }
}

val targetContext: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

/** Grants what a recording needs, so the test never waits for a permission dialog. */
fun grantRecordingPermissions() {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS).forEach {
        automation.grantRuntimePermission(targetContext.packageName, it)
    }
}

/** Polls [condition] every 50 ms and fails the test with [message] when it does not hold within [timeoutMs]. */
fun waitUntil(message: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (!condition()) {
        assertTrue("timed out waiting for: $message", SystemClock.elapsedRealtime() < deadline)
        Thread.sleep(50)
    }
}

/** What a finished or interrupted meeting looks like on disk. */
class MeetingOnDisk(val folderName: String, val children: List<String>, val meeting: MeetingMetadata, val wav: WavFormat, val wavBytes: Long)

/** Reads the only meeting folder in [tree]: its files, its `meeting.json` and the WAV header. */
fun readOnlyMeeting(tree: TestTree): MeetingOnDisk {
    val storage = SafMeetingStorage(targetContext, tree.uri)
    val folder = storage.listChildren(storage.rootUri).single { it.isDirectory }
    val children = storage.listChildren(folder.uri)
    val metadata = checkNotNull(children.firstOrNull { it.name == MeetingFolderLayout.METADATA_FILE }) { "no meeting.json" }
    val decoded = MeetingJson.decode(checkNotNull(storage.readText(metadata.uri)))
    val meeting = (decoded as MeetingJson.DecodeResult.Success).meeting
    val audio = checkNotNull(children.firstOrNull { it.name == MeetingFolderLayout.AUDIO_FILE }) { "no audio.wav" }
    val (format, length) = storage.openStore(audio.uri).use { store ->
        val header = ByteArray(WavHeader.SIZE)
        check(store.readAt(0, header) == WavHeader.SIZE) { "audio.wav is shorter than a header" }
        checkNotNull(WavHeader.parse(header)) { "audio.wav has no valid header" } to store.length()
    }
    return MeetingOnDisk(folder.name, children.map { it.name }, meeting, format, length)
}
