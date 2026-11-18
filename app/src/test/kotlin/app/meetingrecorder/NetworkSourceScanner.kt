package app.meetingrecorder

/**
 * Finds uses of network classes in Kotlin and Java sources. Only the package
 * `app/meetingrecorder/models/download/` may use them (Principle I, research R-11): the model download is
 * the only network use of the whole app. Comments and string literals are ignored, so a sentence that
 * mentions a socket is not a hit.
 */
object NetworkSourceScanner {

    data class Hit(val line: Int, val text: String)

    /** The only directory that may use network classes. */
    const val ALLOWED_DIRECTORY = "app/src/main/kotlin/app/meetingrecorder/models/download/"

    private val forbidden = Regex(
        listOf(
            """\bjava\.net\.""",
            """\bjavax\.net\.""",
            """\bHttpURLConnection\b""",
            """\bHttpsURLConnection\b""",
            """okhttp""",
            """Socket""",
            """\bURL\(""",
            """\bConnectivityManager\b""",
        ).joinToString("|"),
        RegexOption.IGNORE_CASE,
    )

    fun scan(source: String): List<Hit> {
        val original = source.lines()
        return code(source).lines().withIndex()
            .filter { (_, line) -> forbidden.containsMatchIn(line) }
            .map { (index, _) -> Hit(index + 1, original[index].trim()) }
    }

    /** True when a file at [path] (slash-separated, relative to the repository root) may use network classes. */
    fun isAllowed(path: String): Boolean = path.replace('\\', '/').contains(ALLOWED_DIRECTORY)

    /**
     * The source with comments and the content of string and character literals replaced by spaces, so line
     * numbers stay the same. Understands `//`, nested `/* */`, `"..."` with escapes and `"""..."""`.
     */
    internal fun code(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        var blockDepth = 0
        fun blank(c: Char) = out.append(if (c == '\n') '\n' else ' ')
        while (i < source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)
            when {
                blockDepth > 0 -> {
                    if (c == '*' && next == '/') { blockDepth--; blank(c); blank(next); i += 2 } else if (c == '/' && next == '*') { blockDepth++; blank(c); blank(next); i += 2 } else { blank(c); i++ }
                }
                c == '/' && next == '/' -> { while (i < source.length && source[i] != '\n') { blank(source[i]); i++ } }
                c == '/' && next == '*' -> { blockDepth = 1; blank(c); blank(next); i += 2 }
                source.startsWith("\"\"\"", i) -> {
                    out.append("   "); i += 3
                    while (i < source.length && !source.startsWith("\"\"\"", i)) { blank(source[i]); i++ }
                    if (i < source.length) { out.append("   "); i += 3 }
                }
                c == '"' -> {
                    out.append(' '); i++
                    while (i < source.length && source[i] != '"' && source[i] != '\n') {
                        if (source[i] == '\\' && i + 1 < source.length) { blank(source[i]); i++ }
                        blank(source[i]); i++
                    }
                    if (i < source.length && source[i] == '"') { out.append(' '); i++ }
                }
                c == '\'' && next != null && (source.getOrNull(i + 2) == '\'' || next == '\\') -> {
                    out.append(' '); i++
                    while (i < source.length && source[i] != '\'' && source[i] != '\n') { blank(source[i]); i++ }
                    if (i < source.length && source[i] == '\'') { out.append(' '); i++ }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}
