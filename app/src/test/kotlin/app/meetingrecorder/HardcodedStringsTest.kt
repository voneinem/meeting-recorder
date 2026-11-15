package app.meetingrecorder

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HardcodedStringsTest {

    @Test
    fun `no source file passes a string literal to a UI call`() {
        val root = File("src/main/kotlin")
        assertTrue(root.isDirectory, "run from the app module directory: ${root.absolutePath}")
        val hits = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> HardcodedStringScanner.scan(file.readText()).map { "${file.path}:${it.line}: ${it.text}" } }
            .toList()
        assertEquals(emptyList(), hits, "hard-coded UI strings found; use string resources instead")
    }

    // --- the scanner itself ---

    private fun hits(code: String) = HardcodedStringScanner.scan(code).map { it.line }

    @Test
    fun `literals in Text calls are found`() {
        assertEquals(listOf(1), hits("""Text("Hello")"""))
        assertEquals(listOf(1), hits("""Text(text = "Hello")"""))
        assertEquals(listOf(2), hits("ok()\nText(\"x\")"))
    }

    @Test
    fun `literals in common UI parameters are found`() {
        assertEquals(listOf(1), hits("""Icon(painter, contentDescription = "Record")"""))
        assertEquals(listOf(1), hits("""OutlinedTextField(value, onChange, label = "Title")"""))
        assertEquals(listOf(1), hits("""builder.setContentTitle("Recording")"""))
        assertEquals(listOf(1), hits("""Toast.makeText(context, "Saved", Toast.LENGTH_SHORT)"""))
    }

    @Test
    fun `resources and other strings are not flagged`() {
        assertEquals(emptyList(), hits("Text(stringResource(R.string.app_name))"))
        assertEquals(emptyList(), hits("Text(text = title)"))
        assertEquals(emptyList(), hits("Icon(painter, contentDescription = null)"))
        assertEquals(emptyList(), hits("""val tag = "RecordingService""""))
        assertEquals(emptyList(), hits("""Log.d("tag", "message")"""))
    }

    @Test
    fun `a marker or a comment opts a line out`() {
        assertEquals(emptyList(), hits("""Text("0:00") // i18n-ignore: not user text"""))
        assertEquals(emptyList(), hits("""// Text("example in a comment")"""))
    }
}
