package app.meetingrecorder.transcription

import android.net.Uri
import android.os.Build
import androidx.core.content.edit
import app.meetingrecorder.AppPreferences
import app.meetingrecorder.MeetingRecorderApp
import app.meetingrecorder.TestTree
import app.meetingrecorder.core.folder.MeetingFolderLayout
import app.meetingrecorder.core.json.MeetingJson
import app.meetingrecorder.core.json.MeetingValidator
import app.meetingrecorder.core.model.AudioInfo
import app.meetingrecorder.core.model.ChannelLayout
import app.meetingrecorder.core.model.MeetingMetadata
import app.meetingrecorder.core.model.MeetingStatus
import app.meetingrecorder.core.model.Source
import app.meetingrecorder.core.model.SourceType
import app.meetingrecorder.core.wav.WavHeader
import app.meetingrecorder.models.ImportListener
import app.meetingrecorder.models.ImportResult
import app.meetingrecorder.models.ImportSource
import app.meetingrecorder.models.ModelImporter
import app.meetingrecorder.storage.SafMeetingStorage
import app.meetingrecorder.targetContext
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue

/** A finished test meeting in the debug document tree. */
class BuiltMeeting(val folderName: String, val folderUri: Uri, val audioUri: Uri, val metadataUri: Uri)

/**
 * What the real-engine transcription tests (T134, T159) share: a document tree the app uses as its storage folder, the
 * tiny model imported into the app, and meetings built from `jfk.wav`. It points the app's storage-folder choice at its
 * own tree, so it works **only on an emulator**; [create] skips the test elsewhere, and also when the files are not
 * pushed:
 *
 *     adb push ggml-tiny-q5_1.bin /data/local/tmp/
 *     adb push jfk.wav /data/local/tmp/
 *
 * [close] cancels a running job, restores the storage choice, removes the model it installed and the tree.
 */
class TranscriptionTestEnvironment private constructor(treeName: String, modelFile: File) : AutoCloseable {
    val app: MeetingRecorderApp = targetContext.applicationContext as MeetingRecorderApp
    private val preferences = AppPreferences(targetContext)
    val tree = TestTree(targetContext, treeName)
    val storage = SafMeetingStorage(targetContext, tree.uri)
    private val previousTree: String? = preferences.storageTreeUri
    private val previousLastModel: String? = preferences.transcriptionLastModelId
    private var installedModelId: String? = null
    val modelId: String

    init {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        targetContext.getSharedPreferences(PREFERENCES, android.content.Context.MODE_PRIVATE).edit(commit = true) { putString(TREE_KEY, tree.uri.toString()) }
        modelId = when (val result = ModelImporter(app.modelRepository).import(FileSource(modelFile), NoListener)) {
            is ImportResult.Installed -> result.model.also { installedModelId = it.id }.id
            is ImportResult.AlreadyInstalled -> result.model.id
            else -> error("could not import the test model: $result")
        }
    }

    override fun close() {
        runBlocking { app.transcriptionController.cancelAndAwait(30_000) }
        // Written with commit(): the instrumentation process is killed right after the last test, which would lose an
        // asynchronous apply() and leave the device with the test tree as its storage folder (it happened once).
        targetContext.getSharedPreferences(PREFERENCES, android.content.Context.MODE_PRIVATE).edit(commit = true) {
            if (previousTree != null) putString(TREE_KEY, previousTree) else remove(TREE_KEY)
            if (previousLastModel != null) putString(LAST_MODEL_KEY, previousLastModel) else remove(LAST_MODEL_KEY)
        }
        installedModelId?.let { app.modelRepository.delete(it) }
        tree.close()
    }

    /** A finished meeting whose audio is `jfk.wav` ([repeat] times), converted to 48 kHz stereo like a recording. */
    fun buildMeeting(repeat: Int): BuiltMeeting {
        val name = "2026-01-01 1000 Test meeting"
        val folder = checkNotNull(storage.createDirectory(storage.rootUri, name)) { "cannot create the folder" }
        val pcm = jfkAs48kStereo(repeat)
        val frames = pcm.size / BYTES_PER_FRAME
        val audio = checkNotNull(storage.createFile(folder, MeetingFolderLayout.AUDIO_FILE, "audio/wav"))
        storage.openStore(audio).use { store ->
            store.writeAt(0, WavHeader.build(SAMPLE_RATE, CHANNELS, pcm.size.toLong()))
            store.writeAt(WavHeader.SIZE.toLong(), pcm)
            store.sync()
        }
        val metadata = MeetingMetadata(
            title = "Test meeting",
            startedAt = "2026-01-01T10:00:00+00:00",
            status = MeetingStatus.COMPLETE,
            audio = AudioInfo(sampleRate = SAMPLE_RATE, channels = CHANNELS, channelLayout = ChannelLayout.STEREO, durationFrames = frames.toLong(), durationMs = frames * 1000L / SAMPLE_RATE),
            sources = listOf(Source(id = 0, name = "Built-in microphone", type = SourceType.BUILTIN, deliveredChannels = 2, channels = 2)),
            initialSourceId = 0,
        )
        assertEquals(emptyList<MeetingValidator.Violation>(), MeetingValidator.validate(metadata))
        val metadataUri = checkNotNull(storage.createFile(folder, MeetingFolderLayout.METADATA_FILE, "application/json"))
        storage.writeText(metadataUri, MeetingJson.encode(metadata))
        return BuiltMeeting(name, folder, audio, metadataUri)
    }

    /**
     * A finished meeting whose audio is a 16 kHz mono 16 bit WAV ([wav16k]) converted to 48 kHz stereo like a recording,
     * streamed in chunks so that a 10 minute file needs no 115 MB array.
     */
    fun buildMeetingFromWav(wav16k: File, title: String = "Test meeting"): BuiltMeeting {
        val name = "2026-01-01 1000 $title"
        val folder = checkNotNull(storage.createDirectory(storage.rootUri, name)) { "cannot create the folder" }
        val audio = checkNotNull(storage.createFile(folder, MeetingFolderLayout.AUDIO_FILE, "audio/wav"))
        val samples = (wav16k.length() - JFK_HEADER) / 2
        val frames = samples * UPSAMPLE
        storage.openStore(audio).use { store ->
            store.writeAt(0, WavHeader.build(SAMPLE_RATE, CHANNELS, frames * BYTES_PER_FRAME))
            var offset = WavHeader.SIZE.toLong()
            wav16k.inputStream().buffered().use { input ->
                input.skip(JFK_HEADER.toLong())
                val chunk = ByteArray(STREAM_SAMPLES * 2)
                while (true) {
                    val read = input.readNBytesCompat(chunk)
                    if (read <= 0) break
                    val mono = ByteBuffer.wrap(chunk, 0, read).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val out = ByteBuffer.allocate(mono.remaining() * UPSAMPLE * BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN)
                    while (mono.hasRemaining()) {
                        val sample = mono.get()
                        repeat(UPSAMPLE) { out.putShort(sample).putShort(sample) }
                    }
                    store.writeAt(offset, out.array())
                    offset += out.capacity()
                }
            }
            store.sync()
        }
        val metadata = MeetingMetadata(
            title = title,
            startedAt = "2026-01-01T10:00:00+00:00",
            status = MeetingStatus.COMPLETE,
            audio = AudioInfo(sampleRate = SAMPLE_RATE, channels = CHANNELS, channelLayout = ChannelLayout.STEREO, durationFrames = frames, durationMs = frames * 1000L / SAMPLE_RATE),
            sources = listOf(Source(id = 0, name = "Built-in microphone", type = SourceType.BUILTIN, deliveredChannels = 2, channels = 2)),
            initialSourceId = 0,
        )
        assertEquals(emptyList<MeetingValidator.Violation>(), MeetingValidator.validate(metadata))
        val metadataUri = checkNotNull(storage.createFile(folder, MeetingFolderLayout.METADATA_FILE, "application/json"))
        storage.writeText(metadataUri, MeetingJson.encode(metadata))
        return BuiltMeeting(name, folder, audio, metadataUri)
    }

    private fun java.io.InputStream.readNBytesCompat(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    fun readMetadata(meeting: BuiltMeeting): MeetingMetadata =
        (MeetingJson.decode(checkNotNull(storage.readText(meeting.metadataUri))) as MeetingJson.DecodeResult.Success).meeting

    fun sha256(file: Uri): String = storage.openReadOnlyStore(file).use { store ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(CHUNK)
        var offset = 0L
        while (true) {
            val read = store.readAt(offset, buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
            offset += read
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 16 kHz mono 16 bit samples repeated [repeat] times, each sample tripled (48 kHz) and written to both channels. */
    private fun jfkAs48kStereo(repeat: Int): ByteArray {
        val bytes = SAMPLE.readBytes()
        val mono = ByteBuffer.wrap(bytes, JFK_HEADER, bytes.size - JFK_HEADER).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = ShortArray(mono.remaining()).also { mono.get(it) }
        val out = ByteBuffer.allocate(samples.size * repeat * UPSAMPLE * BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN)
        repeat(repeat) {
            for (sample in samples) repeat(UPSAMPLE) { out.putShort(sample).putShort(sample) }
        }
        return out.array()
    }

    private class FileSource(private val file: File) : ImportSource {
        override val displayName: String = file.name
        override val sizeBytes: Long = file.length()
        override fun open(): InputStream = file.inputStream()
    }

    private object NoListener : ImportListener {
        override fun onProgress(bytesDone: Long, total: Long) = Unit
        override fun isCancelled(): Boolean = false
    }

    companion object {
        private val MODEL = File("/data/local/tmp/ggml-tiny-q5_1.bin")
        private val SAMPLE = File("/data/local/tmp/jfk.wav")
        private const val PREFERENCES = "meeting_recorder_prefs"
        private const val TREE_KEY = "storage_tree_uri"
        private const val LAST_MODEL_KEY = "transcription.lastModelId"
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = 4
        private const val UPSAMPLE = 3
        private const val JFK_HEADER = 44
        private const val CHUNK = 64 * 1024
        private const val STREAM_SAMPLES = 16_000 * 10

        /**
         * Skips the calling test unless it runs on an emulator (or on a device when the instrumentation argument
         * `allowDevice=true` is given: the tests replace the storage folder choice for their duration and restore it)
         * with the model and the sample pushed.
         */
        fun create(treeName: String, modelFile: File = MODEL, requiredFiles: List<File> = listOf(SAMPLE)): TranscriptionTestEnvironment {
            val emulator = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"
            val allowed = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("allowDevice") == "true"
            assumeTrue("runs only on an emulator (it replaces the storage folder choice); pass -e allowDevice true to run it on a device", emulator || allowed)
            assumeTrue("files not pushed to /data/local/tmp", modelFile.isFile && requiredFiles.all { it.isFile })
            return TranscriptionTestEnvironment(treeName, modelFile)
        }
    }
}
