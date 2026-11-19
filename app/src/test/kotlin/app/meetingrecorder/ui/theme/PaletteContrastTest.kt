package app.meetingrecorder.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the palette of specs/003-fossify-style/data-model.md: the values must not drift, and every pair of
 * the "Required contrast pairs" table must keep its minimum in both appearances (FR-011).
 */
class PaletteContrastTest {

    private fun hex(argb: Int) = "%06X".format(argb and 0xFFFFFF)

    private fun assertAlpha(set: Palette.Set) {
        set.tokens().forEach { (name, value) -> assertEquals(0xFF, (value ushr 24) and 0xFF, "$name must be opaque") }
    }

    @Test
    fun `light palette equals the reference table`() {
        val expected = mapOf(
            "paper" to "FBFCF8", "surface" to "FFFFFF", "surfaceQuiet" to "F2F6F1", "ink" to "102019",
            "inkSoft" to "3F4D45", "muted" to "5B685F", "line" to "DCE5DC", "outlineControl" to "7A8A7F",
            "green" to "216E39", "onGreen" to "FFFFFF", "greenStrong" to "14552A", "mint" to "DFF5E4",
            "blue" to "245F92", "blueSoft" to "E6F0F8", "amber" to "8F4B08", "amberSoft" to "FBECD8",
            "rose" to "A94442", "onRose" to "FFFFFF", "roseSoft" to "F7E3E2", "onRoseSoft" to "7A2E2C",
        )
        assertEquals(expected, Palette.Light.tokens().mapValues { hex(it.value) })
        assertAlpha(Palette.Light)
    }

    @Test
    fun `dark palette equals the reference table`() {
        val expected = mapOf(
            "paper" to "0B130F", "surface" to "121D17", "surfaceQuiet" to "18251E", "ink" to "EEF6EE",
            "inkSoft" to "C8D6CB", "muted" to "96AA9B", "line" to "2B3C31", "outlineControl" to "6B8172",
            "green" to "78D58D", "onGreen" to "0B130F", "greenStrong" to "9DE8AD", "mint" to "173D25",
            "blue" to "8BBCE8", "blueSoft" to "142B3C", "amber" to "F0B05F", "amberSoft" to "392716",
            "rose" to "EF9A96", "onRose" to "0B130F", "roseSoft" to "3A1F1E", "onRoseSoft" to "F7C6C4",
        )
        assertEquals(expected, Palette.Dark.tokens().mapValues { hex(it.value) })
        assertAlpha(Palette.Dark)
    }

    private fun check(name: String, set: Palette.Set, foreground: String, backgrounds: List<String>, minimum: Double) {
        val tokens = set.tokens()
        val fg = checkNotNull(tokens[foreground]) { "unknown token $foreground" }
        for (background in backgrounds) {
            val bg = checkNotNull(tokens[background]) { "unknown token $background" }
            val ratio = Palette.contrastRatio(fg, bg)
            assertTrue(ratio >= minimum, "$name: $foreground on $background is %.2f, needs $minimum".format(ratio))
        }
    }

    private fun checkAll(name: String, set: Palette.Set) {
        val pages = listOf("paper", "surface", "surfaceQuiet")
        for (text in listOf("ink", "inkSoft", "muted")) check(name, set, text, pages, 4.5)
        check(name, set, "onGreen", listOf("green"), 4.5)
        check(name, set, "green", listOf("paper", "surface"), 3.0)
        check(name, set, "greenStrong", listOf("mint"), 4.5)
        check(name, set, "green", listOf("mint"), 4.5)
        check(name, set, "blue", listOf("blueSoft"), 4.5)
        check(name, set, "amber", listOf("amberSoft"), 4.5)
        check(name, set, "rose", listOf("paper", "surface"), 4.5)
        check(name, set, "rose", listOf("roseSoft"), 4.5)
        // warning and information text drawn directly on the page (specs/003-fossify-style/tasks.md T022)
        check(name, set, "amber", listOf("paper", "surface"), 4.5)
        check(name, set, "blue", listOf("paper", "surface"), 4.5)
        check(name, set, "onRose", listOf("rose"), 4.5)
        check(name, set, "onRoseSoft", listOf("roseSoft"), 4.5)
        check(name, set, "outlineControl", pages, 3.0)
    }

    @Test
    fun `light palette keeps the required contrast`() = checkAll("light", Palette.Light)

    @Test
    fun `dark palette keeps the required contrast`() = checkAll("dark", Palette.Dark)

    /** The color resources the window theme and the notifications read; they copy palette tokens (FR-014). */
    private fun resourceColors(directory: String): Map<String, String> {
        val module = File(".").canonicalFile // the app module directory when Gradle runs the test
        val file = File(module, "src/main/res/$directory/colors.xml")
        assertTrue(file.isFile, "missing ${file.path}")
        return Regex("""<color name="(\w+)">#([0-9A-Fa-f]{6})</color>""").findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2].uppercase() }
    }

    @Test
    fun `color resources equal the palette`() {
        val light = resourceColors("values")
        val dark = resourceColors("values-night")
        assertEquals(hex(Palette.Light.paper), light["window_background"], "light window background is the paper")
        assertEquals(hex(Palette.Light.green), light["notification_accent"], "light notification accent is the green")
        assertEquals(hex(Palette.Dark.paper), dark["window_background"], "dark window background is the paper")
        assertEquals(hex(Palette.Dark.green), dark["notification_accent"], "dark notification accent is the green")
        assertEquals(light.keys, dark.keys, "both appearances define the same color resources")
    }

    @Test
    fun `launcher icon background is the Fossify green and carries the white glyph`() {
        val module = File(".").canonicalFile
        val file = File(module, "src/main/res/values/ic_launcher_background.xml")
        assertTrue(file.isFile, "missing ${file.path}")
        val value = Regex("""<color name="ic_launcher_background">#([0-9A-Fa-f]{6})</color>""").find(file.readText())?.groupValues?.get(1)
        assertEquals("106D20", value?.uppercase(), "launcher icon green (FR-016)")
        val green = value!!.toInt(16) or (0xFF shl 24)
        assertTrue(Palette.contrastRatio(0xFFFFFFFF.toInt(), green) >= 4.5, "the white glyph needs 4.5:1 on the green")
    }

    @Test
    fun `contrast ratio of black on white is 21`() {
        assertEquals(21.0, Palette.contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.001)
    }

    @Test
    fun `contrast ratio of equal colors is 1`() {
        assertEquals(1.0, Palette.contrastRatio(0xFF216E39.toInt(), 0xFF216E39.toInt()), 0.0001)
    }

    @Test
    fun `contrast ratio is symmetric`() {
        val a = 0xFF216E39.toInt()
        val b = 0xFFFBFCF8.toInt()
        assertEquals(Palette.contrastRatio(a, b), Palette.contrastRatio(b, a), 0.0)
    }
}
