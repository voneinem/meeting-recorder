package app.meetingrecorder.ui.theme

import app.meetingrecorder.NetworkSourceScanner

/**
 * Finds breaches of the theme contract in Kotlin sources (specs/003-fossify-style/contracts/theme-contract.md):
 * raw colors outside the palette, dynamic (wallpaper) color, and raw Material cards and buttons outside the shared
 * wrappers. Comments and string literals are ignored, so a sentence that mentions a color is not a hit.
 */
object ThemeSourceScanner {

    data class Hit(val line: Int, val text: String)

    /** The only file that may hold color values. */
    const val PALETTE_FILE = "app/src/main/kotlin/app/meetingrecorder/ui/theme/Palette.kt"

    /** The only directory that may call the raw Material card and button composables. */
    const val COMPONENTS_DIRECTORY = "app/src/main/kotlin/app/meetingrecorder/ui/components/"

    private val rawColor = Regex("""Color\(\s*0x|\b0x[Ff]{2}[0-9A-Fa-f]{6}\b""")
    private val dynamicColor = Regex("""\bdynamic(Light|Dark)ColorScheme\b""")
    private val rawComponents = Regex("""\b(Card|Button|OutlinedButton|TextButton)\(""")

    private fun scan(source: String, pattern: Regex): List<Hit> {
        val original = source.lines()
        return NetworkSourceScanner.code(source).lines().withIndex()
            .filter { (_, line) -> pattern.containsMatchIn(line) }
            .map { (index, _) -> Hit(index + 1, original[index].trim()) }
    }

    /** Rule (a): a `Color(0x` literal or an 8-digit ARGB literal. A byte mask such as `0xFF` alone is no hit. */
    fun rawColors(source: String): List<Hit> = scan(source, rawColor)

    /** Rule (b): wallpaper-based color schemes. */
    fun dynamicColors(source: String): List<Hit> = scan(source, dynamicColor)

    /** Rule (d): a direct call of `Card`, `Button`, `OutlinedButton` or `TextButton`. */
    fun rawComponents(source: String): List<Hit> = scan(source, rawComponents)

    fun mayHoldColors(path: String): Boolean = path.replace('\\', '/') == PALETTE_FILE

    fun mayCallRawComponents(path: String): Boolean = path.replace('\\', '/').contains(COMPONENTS_DIRECTORY)
}
