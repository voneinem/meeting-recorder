package app.meetingrecorder.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Enforces the theme contract of specs/003-fossify-style on the real sources, and tests the scanner itself. */
class ThemeSourceRulesTest {

    private val repository: File = checkNotNull(File(".").canonicalFile.parentFile) // the app module is `./` under Gradle

    private fun sources(): List<File> {
        val root = File(repository, "app/src/main/kotlin")
        assertTrue(root.isDirectory, "expected the app sources at ${root.path}")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun relative(file: File) = file.relativeTo(repository).path.replace('\\', '/')

    @Test
    fun `only the palette holds color values`() {
        val hits = sources().filterNot { ThemeSourceScanner.mayHoldColors(relative(it)) }
            .flatMap { f -> ThemeSourceScanner.rawColors(f.readText()).map { "${relative(f)}:${it.line}: ${it.text}" } }
        assertEquals(emptyList(), hits, "color values outside ui/theme/Palette.kt (theme contract rule 1)")
    }

    @Test
    fun `no wallpaper based color scheme is used`() {
        val hits = sources().flatMap { f -> ThemeSourceScanner.dynamicColors(f.readText()).map { "${relative(f)}:${it.line}: ${it.text}" } }
        assertEquals(emptyList(), hits, "dynamic color breaks the fixed look (FR-003)")
    }

    @Test
    fun `only the shared wrappers call Material cards and buttons`() {
        val hits = sources().filterNot { ThemeSourceScanner.mayCallRawComponents(relative(it)) }
            .flatMap { f -> ThemeSourceScanner.rawComponents(f.readText()).map { "${relative(f)}:${it.line}: ${it.text}" } }
        assertEquals(emptyList(), hits, "use AppCard, AppButton, AppOutlinedButton and AppTextButton (FR-007, FR-014)")
    }

    @Test
    fun `the app bundles no font files`() {
        assertFalse(File(repository, "app/src/main/res/font").exists(), "a font resource directory exists (FR-008)")
    }

    // --- the scanner itself ---

    @Test
    fun `color literals in code are found`() {
        assertEquals(listOf(1), ThemeSourceScanner.rawColors("val c = Color(0xFF123456)").map { it.line })
        assertEquals(listOf(1), ThemeSourceScanner.rawColors("val c = Color( 0x123456)").map { it.line })
        assertEquals(listOf(2), ThemeSourceScanner.rawColors("ok()\nval c = 0xFFABCDEF").map { it.line })
    }

    @Test
    fun `byte masks and mentions in comments or strings are not color hits`() {
        assertEquals(emptyList(), ThemeSourceScanner.rawColors("val b = x and 0xFF"))
        assertEquals(emptyList(), ThemeSourceScanner.rawColors("val b = (x shr 8) and 0xFF0000"))
        assertEquals(emptyList(), ThemeSourceScanner.rawColors("// Color(0xFF123456)"))
        assertEquals(emptyList(), ThemeSourceScanner.rawColors("/* 0xFF123456 */ val x = 1"))
        assertEquals(emptyList(), ThemeSourceScanner.rawColors("val s = \"Color(0xFF123456)\""))
    }

    @Test
    fun `dynamic color calls are found but not their mention`() {
        assertEquals(listOf(1), ThemeSourceScanner.dynamicColors("val c = dynamicLightColorScheme(context)").map { it.line })
        assertEquals(listOf(1), ThemeSourceScanner.dynamicColors("val c = dynamicDarkColorScheme(context)").map { it.line })
        assertEquals(emptyList(), ThemeSourceScanner.dynamicColors("// no dynamicLightColorScheme here"))
    }

    @Test
    fun `raw components are found as whole identifiers`() {
        val code = "Card(onClick = {}) {}\nButton(onClick = {}) {}\nOutlinedButton(onClick = {}) {}\nTextButton(onClick = {}) {}"
        assertEquals(listOf(1, 2, 3, 4), ThemeSourceScanner.rawComponents(code).map { it.line })
        assertEquals(emptyList(), ThemeSourceScanner.rawComponents("AppCard {}\nAppButton(onClick = {}) {}\nCameraPermissionCard(a)\nIconButton(onClick = {}) {}"))
        assertEquals(emptyList(), ThemeSourceScanner.rawComponents("// Card( in a comment"))
    }

    @Test
    fun `only the palette file and the components directory are exempt`() {
        assertTrue(ThemeSourceScanner.mayHoldColors("app/src/main/kotlin/app/meetingrecorder/ui/theme/Palette.kt"))
        assertFalse(ThemeSourceScanner.mayHoldColors("app/src/main/kotlin/app/meetingrecorder/ui/theme/Color.kt"))
        assertTrue(ThemeSourceScanner.mayCallRawComponents("app/src/main/kotlin/app/meetingrecorder/ui/components/AppCard.kt"))
        assertFalse(ThemeSourceScanner.mayCallRawComponents("app/src/main/kotlin/app/meetingrecorder/ui/list/MeetingListScreen.kt"))
    }
}
