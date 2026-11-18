package app.meetingrecorder.core.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstalledModelTest {

    private val sha = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"
    private val model = InstalledModel(
        id = "small-q5_1", displayName = "Small (q5_1)", origin = ModelOrigin.CATALOG, sha256 = sha,
        sizeBytes = 190_085_487, languages = ModelLanguages.MULTILINGUAL, installedAt = "2026-11-16T22:00:00+01:00",
    )

    @Test
    fun `the sidecar round-trips with the documented fields`() {
        val text = ModelFiles.encodeSidecar(model)
        for (field in listOf("id", "displayName", "origin", "sha256", "sizeBytes", "languages", "installedAt")) {
            assertTrue("\"$field\"" in text, field)
        }
        assertEquals(model, ModelFiles.decodeSidecar(text))
    }

    @Test
    fun `imported ids come from the first 12 hex digits of the hash`() {
        assertEquals("imported-ae85e4a935d7", InstalledModel.importedId(sha))
    }

    @Test
    fun `a model is installed only with its file, a valid sidecar and the right length`() {
        val sidecar = ModelFiles.encodeSidecar(model)
        assertEquals(model, ModelFiles.installedModel("small-q5_1", 190_085_487, sidecar))
        assertNull(ModelFiles.installedModel("small-q5_1", null, sidecar)) // no .bin
        assertNull(ModelFiles.installedModel("small-q5_1", 190_085_487, null)) // no sidecar
        assertNull(ModelFiles.installedModel("small-q5_1", 190_085_487, "{ not json")) // corrupt sidecar
        assertNull(ModelFiles.installedModel("small-q5_1", 190_085_486, sidecar)) // wrong length
        assertNull(ModelFiles.installedModel("base-q5_1", 190_085_487, sidecar)) // sidecar of another model
        assertNull(ModelFiles.installedModel("small-q5_1", 190_085_487, sidecar.replace(sha, "abc"))) // invalid hash
        assertNull(ModelFiles.installedModel("small-q5_1", 190_085_487, sidecar.replace("\"origin\": \"catalog\"", "\"origin\": \"x\"")))
    }

    @Test
    fun `file names follow the layout`() {
        assertEquals("small-q5_1.bin", ModelFiles.binName("small-q5_1"))
        assertEquals("small-q5_1.json", ModelFiles.sidecarName("small-q5_1"))
        assertEquals("small-q5_1.part", ModelFiles.partName("small-q5_1"))
        assertEquals("small-q5_1.part.json", ModelFiles.partStateName("small-q5_1"))
    }

    private val url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-small-q5_1.bin"
    private val state = PartState(url, sha, 190_085_487, etag = "\"abc\"", bytesDone = 4_194_304)

    @Test
    fun `the part state round-trips and bytesDone is bounded by the size`() {
        assertEquals(state, ModelFiles.decodePartState(ModelFiles.encodePartState(state)))
        assertFailsWith<IllegalArgumentException> { state.copy(bytesDone = 190_085_488) }
        assertFailsWith<IllegalArgumentException> { state.copy(bytesDone = -1) }
        val tooMany = ModelFiles.encodePartState(state).replace("4194304", "190085488")
        assertNull(ModelFiles.decodePartState(tooMany))
        assertNull(ModelFiles.decodePartState("garbage"))
        assertNotNull(ModelFiles.decodePartState(ModelFiles.encodePartState(state.copy(etag = null))))
    }

    @Test
    fun `paused needs a matching part file and part state`() {
        fun resumable(length: Long? = 4_194_304, s: PartState? = state, u: String = url, size: Long = 190_085_487, h: String = sha) =
            ModelFiles.isResumable(length, s, u, size, h)
        assertTrue(resumable())
        assertTrue(resumable(length = 5_000_000)) // the file may be longer than the last written bytesDone
        assertTrue(!resumable(length = null))
        assertTrue(!resumable(s = null))
        assertTrue(!resumable(length = 4_000_000))
        assertTrue(!resumable(u = url.replace("small", "base")))
        assertTrue(!resumable(size = 1))
        assertTrue(!resumable(h = "f".repeat(64)))
    }
}
