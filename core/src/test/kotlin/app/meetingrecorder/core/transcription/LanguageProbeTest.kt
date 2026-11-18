package app.meetingrecorder.core.transcription

import app.meetingrecorder.core.models.ModelLanguages
import app.meetingrecorder.core.transcription.LanguageProbe.Probe
import app.meetingrecorder.core.transcription.LanguageProbe.ProbeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanguageProbeTest {

    private val sr = 16_000
    private fun s(seconds: Int) = seconds.toLong() * sr
    private fun probe(fromSec: Int) = Probe(s(fromSec), s(fromSec + 30))
    private fun p(code: String, value: Double) = LanguageProbability(code, value)

    @Test
    fun `three probes at 10, 40 and 70 percent each 30 seconds wide`() {
        assertEquals(listOf(probe(60), probe(240), probe(420)), LanguageProbe.positions(s(600), sr))
    }

    @Test
    fun `probes are clamped inside the audio`() {
        // 40 s of audio: the 40% and 70% windows start too late and are moved back to 10..40.
        assertEquals(listOf(probe(4), probe(10), probe(10)), LanguageProbe.positions(s(40), sr))
        // Audio shorter than one probe: the whole audio.
        assertEquals(listOf(Probe(0, s(20)), Probe(0, s(20)), Probe(0, s(20))), LanguageProbe.positions(s(20), sr))
    }

    @Test
    fun `silence is below minus 60 dBFS`() {
        assertTrue(LanguageProbe.isSilent(0.0))
        assertTrue(LanguageProbe.isSilent(0.0009))
        assertTrue(!LanguageProbe.isSilent(0.0011))
        assertTrue(!LanguageProbe.isSilent(0.2))
    }

    @Test
    fun `with signal everywhere the nominal windows are used`() {
        assertEquals(listOf(probe(60), probe(240), probe(420)), LanguageProbe.select(s(600), sr) { _, _ -> 0.1 })
    }

    @Test
    fun `a silent probe is moved to the nearest window with signal`() {
        val chosen = LanguageProbe.select(s(600), sr) { from, _ -> if (from == s(240)) 0.0 else 0.1 }
        assertEquals(listOf(probe(60), probe(270), probe(420)), chosen)
    }

    @Test
    fun `when no probe has signal nothing is chosen and at most nine windows are measured`() {
        var calls = 0
        val chosen = LanguageProbe.select(s(600), sr) { _, _ -> calls++; 0.0 }
        assertEquals(emptyList(), chosen)
        assertEquals(LanguageProbe.MAX_TRIES, calls)
    }

    @Test
    fun `short audio gives one probe over all of it`() {
        var calls = 0
        val chosen = LanguageProbe.select(s(20), sr) { _, _ -> calls++; 0.1 }
        assertEquals(listOf(Probe(0, s(20))), chosen)
        assertEquals(1, calls)
    }

    @Test
    fun `the probabilities of all probes are summed and the confidence is the mean of the winner`() {
        val results = listOf(
            ProbeResult(probe(60), listOf(p("de", 0.6), p("en", 0.3))),
            ProbeResult(probe(240), listOf(p("de", 0.2), p("en", 0.7))),
        )
        val choice = LanguageProbe.choose(results, ModelLanguages.MULTILINGUAL, "de")
        assertEquals("en", choice.code)
        assertEquals(0.5, choice.confidence!!, 1e-9)
        assertEquals(listOf(s(60), s(240)), choice.probes)
    }

    @Test
    fun `a clear winner across probes is chosen`() {
        val results = listOf(
            ProbeResult(probe(60), listOf(p("de", 0.97), p("en", 0.01), p("nl", 0.02))),
            ProbeResult(probe(240), listOf(p("de", 0.91), p("en", 0.02), p("nl", 0.07))),
            ProbeResult(probe(420), listOf(p("de", 0.95), p("en", 0.03), p("nl", 0.02))),
        )
        val choice = LanguageProbe.choose(results, ModelLanguages.MULTILINGUAL, "en")
        assertEquals("de", choice.code)
        assertEquals((0.97 + 0.91 + 0.95) / 3, choice.confidence!!, 1e-9)
    }

    @Test
    fun `an English-only model is always English without probing`() {
        assertEquals(LanguageChoice("en", null, emptyList()), LanguageProbe.choose(emptyList(), ModelLanguages.ENGLISH_ONLY, "de"))
        val results = listOf(ProbeResult(probe(60), listOf(p("de", 0.99))))
        assertEquals("en", LanguageProbe.choose(results, ModelLanguages.ENGLISH_ONLY, "de").code)
    }

    @Test
    fun `without signal the device language is used by a multilingual model`() {
        assertEquals(LanguageChoice("de", null, emptyList()), LanguageProbe.choose(emptyList(), ModelLanguages.MULTILINGUAL, "de"))
        assertEquals("en", LanguageProbe.choose(emptyList(), ModelLanguages.MULTILINGUAL, "").code)
    }

    @Test
    fun `equal sums resolve the same way every time`() {
        val results = listOf(ProbeResult(probe(60), listOf(p("fr", 0.5), p("de", 0.5))))
        assertEquals("de", LanguageProbe.choose(results, ModelLanguages.MULTILINGUAL, "en").code)
    }
}
