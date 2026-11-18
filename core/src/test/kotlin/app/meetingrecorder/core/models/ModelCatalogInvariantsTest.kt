package app.meetingrecorder.core.models

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The invariants of contracts/model-catalog.md, including that the catalog is not empty (task T100).
 */
class ModelCatalogInvariantsTest {

    private val acceptableLicenses = setOf("MIT")
    private val hex64 = Regex("^[0-9a-f]{64}$")
    private val commitInUrl = Regex("/resolve/[0-9a-f]{40}/")

    private fun stringKeys(directory: String): Set<String> {
        val file = File("../app/src/main/res/$directory/strings.xml")
        check(file.exists()) { "missing ${file.absolutePath}" }
        return Regex("<string name=\"([^\"]+)\"").findAll(file.readText()).map { it.groupValues[1] }.toSet()
    }

    /** One violation per broken rule, for a whole catalog. */
    private fun violations(entries: List<ModelCatalogEntry>, english: Set<String>, german: Set<String>): List<String> {
        val found = mutableListOf<String>()
        if (entries.map { it.id }.toSet().size != entries.size) found += "ids are not unique"
        for (e in entries) {
            if (!hex64.matches(e.sha256)) found += "${e.id}: sha256 has no 64 hex digits"
            if (e.sizeBytes <= 0) found += "${e.id}: sizeBytes must be positive"
            if (!e.url.startsWith("https://")) found += "${e.id}: url is not https"
            if (DownloadRules.parse(e.url)?.host != "huggingface.co") found += "${e.id}: the host is not huggingface.co"
            if (!commitInUrl.containsMatchIn(e.url)) found += "${e.id}: the url has no pinned 40-hex commit"
            if (e.license !in acceptableLicenses) found += "${e.id}: license ${e.license} is not acceptable for F-Droid"
            for (key in listOf(e.displayNameKey, e.descriptionKey)) {
                if (key !in english) found += "${e.id}: $key is missing in values/strings.xml"
                if (key !in german) found += "${e.id}: $key is missing in values-de/strings.xml"
            }
            if (e.vadCompanionId != null && entries.firstOrNull { it.id == e.vadCompanionId }?.kind != ModelKind.VAD) {
                found += "${e.id}: vadCompanionId does not name a VAD entry"
            }
        }
        for (vad in entries.filter { it.kind == ModelKind.VAD }) {
            if (entries.none { it.vadCompanionId == vad.id }) found += "${vad.id}: a VAD entry that no speech model refers to would be offered on its own"
        }
        return found
    }

    private val valid = ModelCatalogEntry(
        id = "small-q5_1", kind = ModelKind.SPEECH, displayNameKey = "model_small_name", descriptionKey = "model_small_description",
        languages = ModelLanguages.MULTILINGUAL, sizeBytes = 190_085_487,
        sha256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-small-q5_1.bin",
        sourceName = "Hugging Face (huggingface.co)", license = "MIT", approxMemoryMb = 600,
    )
    private val keys = setOf("model_small_name", "model_small_description")

    @Test
    fun `the shipped catalog satisfies every invariant`() {
        assertEquals(emptyList(), violations(ModelCatalog.entries, stringKeys("values"), stringKeys("values-de")))
    }

    @Test
    fun `the catalog is not empty and has exactly one recommended speech model`() {
        assertTrue(ModelCatalog.entries.isNotEmpty())
        assertEquals(1, ModelCatalog.offered().count { it.recommended })
        assertEquals("small-q8_0", ModelCatalog.offered().single { it.recommended }.id)
    }

    @Test
    fun `only speech models are offered`() {
        assertTrue(ModelCatalog.offered().all { it.kind == ModelKind.SPEECH })
    }

    @Test
    fun `a valid entry passes`() {
        assertEquals(emptyList(), violations(listOf(valid), keys, keys))
    }

    @Test
    fun `each rule is detected`() {
        fun broken(entry: ModelCatalogEntry) = violations(listOf(entry), keys, keys)
        assertTrue(broken(valid.copy(sha256 = "abc")).isNotEmpty())
        assertTrue(broken(valid.copy(sha256 = valid.sha256.uppercase())).isNotEmpty())
        assertTrue(broken(valid.copy(sizeBytes = 0)).isNotEmpty())
        assertTrue(broken(valid.copy(url = valid.url.replace("https", "http"))).isNotEmpty())
        assertTrue(broken(valid.copy(url = valid.url.replace("huggingface.co", "example.org"))).isNotEmpty())
        assertTrue(broken(valid.copy(url = valid.url.replace("5359861c739e955e79d9a303bcbc70fb988958b1", "main"))).isNotEmpty())
        assertTrue(broken(valid.copy(license = "CC-BY-NC-4.0")).isNotEmpty())
        assertTrue(violations(listOf(valid), keys, emptySet()).isNotEmpty())
        assertTrue(violations(listOf(valid), emptySet(), keys).isNotEmpty())
        assertTrue(violations(listOf(valid, valid), keys, keys).isNotEmpty())
    }

    @Test
    fun `a voice-activity entry must belong to a speech model`() {
        val vad = valid.copy(id = "vad-silero", kind = ModelKind.VAD)
        assertTrue(violations(listOf(valid, vad), keys, keys).isNotEmpty())
        assertEquals(emptyList(), violations(listOf(valid.copy(vadCompanionId = "vad-silero"), vad), keys, keys))
        assertTrue(violations(listOf(valid.copy(vadCompanionId = "missing")), keys, keys).isNotEmpty())
    }
}
