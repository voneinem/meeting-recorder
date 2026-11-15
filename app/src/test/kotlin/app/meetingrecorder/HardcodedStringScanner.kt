package app.meetingrecorder

/**
 * Finds string literals that are passed straight to user-visible UI calls. Stock Android lint does
 * not see these in Jetpack Compose code (research R-13), so the build scans the sources instead
 * (Constitution Principle IX: no hard-coded UI strings).
 *
 * A line can opt out with the marker `i18n-ignore` in a comment, for text that is not shown to users.
 */
object HardcodedStringScanner {

    data class Hit(val line: Int, val text: String)

    private const val IGNORE_MARKER = "i18n-ignore"

    // A string literal that starts directly after one of these UI call sites or named arguments.
    private val uiCalls = listOf(
        """Text\(\s*(?:text\s*=\s*)?""",
        """contentDescription\s*=\s*""",
        """label\s*=\s*""",
        """title\s*=\s*""",
        """placeholder\s*=\s*""",
        """supportingText\s*=\s*""",
        """setContentTitle\(\s*""",
        """setContentText\(\s*""",
        """setSubText\(\s*""",
        """setTicker\(\s*""",
        """Toast\.makeText\([^,]+,\s*""",
        """\.addAction\(\s*[^,]+,\s*""",
    )
    private val pattern = Regex("(?:" + uiCalls.joinToString("|") + ")\"")

    fun scan(source: String): List<Hit> =
        source.lineSequence().withIndex().mapNotNull { (index, line) ->
            if (IGNORE_MARKER in line) return@mapNotNull null
            val code = line.substringBefore("//")
            if (pattern.containsMatchIn(code)) Hit(index + 1, line.trim()) else null
        }.toList()
}
