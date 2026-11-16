package app.meetingrecorder.core.recovery

import app.meetingrecorder.core.TestMeetings
import app.meetingrecorder.core.json.MeetingValidator
import app.meetingrecorder.core.model.MeetingMetadata
import app.meetingrecorder.core.model.MeetingStatus
import app.meetingrecorder.core.model.Photo
import app.meetingrecorder.core.recovery.RecoveryPlanner.Action
import app.meetingrecorder.core.recovery.RecoveryPlanner.MetadataState
import app.meetingrecorder.core.wav.WavHeader
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecoveryPlannerTest {

    private val zone = ZoneId.of("Europe/Berlin")

    /** A mono meeting that was still being recorded when the app died (header sizes still 0). */
    private val recording: MeetingMetadata = TestMeetings.mono.copy(
        status = MeetingStatus.RECORDING,
        audio = TestMeetings.mono.audio.copy(durationFrames = 0, durationMs = 0),
    )

    private fun snapshot(
        meta: MetadataState,
        audioBytes: Long? = 44 + 96_000, // one second of mono 16-bit at 48 kHz
        header: ByteArray? = WavHeader.build(48_000, 1, 0),
        photos: List<String> = emptyList(),
        folder: String = "2026-11-15_09-05-12",
    ) = RecoveryPlanner.FolderSnapshot(folder, photos, audioBytes, header, meta)

    private fun planned(plan: RecoveryPlanner.Plan): MeetingMetadata =
        plan.actions.filterIsInstance<Action.WriteMetadata>().single().meeting

    @Test
    fun `an unfinished meeting is repaired and marked recovered`() {
        val plan = RecoveryPlanner.plan(snapshot(MetadataState.Readable(recording)), zone)
        assertNull(plan.unrecoverable)
        assertEquals(listOf(Action.RepairWav, Action.WriteMetadata(planned(plan)), Action.WriteGuide), plan.actions)
        val meeting = planned(plan)
        assertEquals(MeetingStatus.RECOVERED, meeting.status)
        assertEquals(48_000, meeting.audio.durationFrames)
        assertEquals(1_000, meeting.audio.durationMs)
        assertEquals(emptyList(), MeetingValidator.validate(meeting))
    }

    @Test
    fun `the duration comes from the file length and ignores a trailing partial frame`() {
        val plan = RecoveryPlanner.plan(snapshot(MetadataState.Readable(recording), audioBytes = 44 + 96_001), zone)
        assertEquals(48_000, planned(plan).audio.durationFrames)
    }

    @Test
    fun `finished meetings get no actions`() {
        for (status in listOf(MeetingStatus.COMPLETE, MeetingStatus.RECOVERED)) {
            val meeting = TestMeetings.mono.copy(status = status)
            val plan = RecoveryPlanner.plan(snapshot(MetadataState.Readable(meeting)), zone)
            assertEquals(emptyList(), plan.actions, status.name)
            assertNull(plan.unrecoverable)
        }
    }

    @Test
    fun `listed positions beyond the recovered audio are clamped`() {
        val withLatePhoto = recording.copy(photos = listOf(Photo("photos/photo-001.jpg", 10_000_000, 208_333)))
        val plan = RecoveryPlanner.plan(snapshot(MetadataState.Readable(withLatePhoto)), zone)
        val photo = planned(plan).photos.single()
        assertEquals(48_000, photo.atFrame)
        assertEquals(1_000, photo.atMs)
        assertEquals(emptyList(), MeetingValidator.validate(planned(plan)))
    }

    @Test
    fun `the format of the file wins over a wrong format in the metadata`() {
        val stereoHeader = WavHeader.build(44_100, 2, 0)
        val plan = RecoveryPlanner.plan(
            snapshot(MetadataState.Readable(recording), audioBytes = 44 + 4 * 44_100, header = stereoHeader),
            zone,
        )
        val audio = planned(plan).audio
        assertEquals(44_100, audio.sampleRate)
        assertEquals(2, audio.channels)
        assertEquals(44_100, audio.durationFrames)
    }

    @Test
    fun `photo files that the metadata does not list are reported`() {
        val listed = recording.copy(photos = listOf(Photo("photos/photo-001.jpg", 480, 10)))
        val plan = RecoveryPlanner.plan(
            snapshot(MetadataState.Readable(listed), photos = listOf("photo-001.jpg", "photo-003.jpg", "photo-002.jpg")),
            zone,
        )
        assertEquals(listOf("photo-002.jpg", "photo-003.jpg"), plan.unlistedPhotos)
    }

    @Test
    fun `unreadable or missing metadata is reconstructed from the folder name and the wav header`() {
        for (state in listOf(MetadataState.Missing, MetadataState.Unreadable("truncated"))) {
            val plan = RecoveryPlanner.plan(snapshot(state), zone)
            val meeting = planned(plan)
            assertEquals("2026-11-15_09-05-12", meeting.title)
            assertEquals("2026-11-15T09:05:12+01:00", meeting.startedAt)
            assertEquals(MeetingStatus.RECOVERED, meeting.status)
            assertEquals(1, meeting.audio.channels)
            assertEquals(48_000, meeting.audio.durationFrames)
            assertEquals(RecoveryPlanner.UNKNOWN_SOURCE_NAME, meeting.sources.single().name)
            assertEquals(emptyList(), MeetingValidator.validate(meeting))
            assertIs<Action.RepairWav>(plan.actions.first())
        }
    }

    @Test
    fun `a stereo wav header gives a stereo reconstruction`() {
        val plan = RecoveryPlanner.plan(
            snapshot(MetadataState.Missing, audioBytes = 44 + 4 * 48_000, header = WavHeader.build(48_000, 2, 0)),
            zone,
        )
        assertEquals(2, planned(plan).audio.channels)
        assertEquals(48_000, planned(plan).audio.durationFrames)
    }

    @Test
    fun `an unparsable folder name falls back to the epoch start`() {
        val plan = RecoveryPlanner.plan(snapshot(MetadataState.Missing, folder = "my notes"), zone)
        assertEquals("1970-01-01T00:00:00Z", planned(plan).startedAt)
        assertEquals("my notes", planned(plan).title)
    }

    @Test
    fun `a folder without usable audio is reported and left alone`() {
        val noAudio = RecoveryPlanner.plan(snapshot(MetadataState.Readable(recording), audioBytes = null, header = null), zone)
        assertTrue(noAudio.actions.isEmpty())
        assertNotNull(noAudio.unrecoverable)

        val tooShort = RecoveryPlanner.plan(snapshot(MetadataState.Readable(recording), audioBytes = 20, header = ByteArray(20)), zone)
        assertTrue(tooShort.actions.isEmpty())
        assertNotNull(tooShort.unrecoverable)

        val badHeader = RecoveryPlanner.plan(snapshot(MetadataState.Missing, audioBytes = 5_000, header = ByteArray(44)), zone)
        assertTrue(badHeader.actions.isEmpty())
        assertNotNull(badHeader.unrecoverable)
        assertFalse(badHeader.actions.any { it is Action.WriteMetadata })
    }
}
