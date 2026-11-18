package app.meetingrecorder.core.transcription

import app.meetingrecorder.core.models.ModelLanguages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StartDefaultsTest {

    @Test
    fun `the default model wins when it is installed`() {
        assertEquals("small", StartDefaults.preselect(listOf("tiny", "small"), "small", "tiny"))
    }

    @Test
    fun `else the last used model when it is installed`() {
        assertEquals("tiny", StartDefaults.preselect(listOf("tiny", "small"), "gone", "tiny"))
        assertEquals("tiny", StartDefaults.preselect(listOf("tiny", "small"), null, "tiny"))
    }

    @Test
    fun `else the only installed model`() {
        assertEquals("tiny", StartDefaults.preselect(listOf("tiny"), "gone", "also-gone"))
        assertEquals("tiny", StartDefaults.preselect(listOf("tiny"), null, null))
    }

    @Test
    fun `else none`() {
        assertNull(StartDefaults.preselect(emptyList(), "small", "tiny"))
        assertNull(StartDefaults.preselect(listOf("tiny", "small"), null, null))
        assertNull(StartDefaults.preselect(listOf("tiny", "small"), "gone", "gone"))
    }

    @Test
    fun `the default language starts as automatic`() {
        assertEquals("auto", StartDefaults.DEFAULT_LANGUAGE)
    }

    @Test
    fun `an English-only model with another language gets the warning`() {
        assertTrue(StartDefaults.languageWarning(ModelLanguages.ENGLISH_ONLY, "de"))
        assertTrue(!StartDefaults.languageWarning(ModelLanguages.ENGLISH_ONLY, "en"))
        assertTrue(!StartDefaults.languageWarning(ModelLanguages.ENGLISH_ONLY, "auto"))
        assertTrue(!StartDefaults.languageWarning(ModelLanguages.MULTILINGUAL, "de"))
    }

    @Test
    fun `the memory warning appears below twice the model memory`() {
        val mib = 1024L * 1024
        assertTrue(StartDefaults.memoryWarning(1_199 * mib, 600))
        assertTrue(!StartDefaults.memoryWarning(1_200 * mib, 600))
        assertTrue(!StartDefaults.memoryWarning(6_000 * mib, 600))
    }
}
