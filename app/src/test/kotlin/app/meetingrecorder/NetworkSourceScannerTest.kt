package app.meetingrecorder

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkSourceScannerTest {

    @Test
    fun `only the download package uses network classes`() {
        val module = File(".").canonicalFile // the app module directory when Gradle runs the test
        val repository = checkNotNull(module.parentFile)
        val roots = listOf("app/src/main", "core/src/main", "whisper/src/main").map { File(repository, it) }
        assertTrue(roots.all { it.isDirectory }, "expected the module sources next to ${module.path}")
        val hits = roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .filter { !NetworkSourceScanner.isAllowed(it.relativeTo(repository).path) }
                .flatMap { file -> NetworkSourceScanner.scan(file.readText()).map { "${file.relativeTo(repository).path}:${it.line}: ${it.text}" } }
                .toList()
        }
        assertEquals(emptyList(), hits, "network classes outside app/meetingrecorder/models/download (Principle I)")
    }

    // --- the scanner itself ---

    private fun hits(code: String) = NetworkSourceScanner.scan(code).map { it.line }

    @Test
    fun `network classes in code are found`() {
        assertEquals(listOf(1), hits("import java.net.URL"))
        assertEquals(listOf(1), hits("import javax.net.ssl.SSLContext"))
        assertEquals(listOf(1), hits("val c = url.openConnection() as HttpURLConnection"))
        assertEquals(listOf(1), hits("val c: HttpsURLConnection"))
        assertEquals(listOf(1), hits("import okhttp3.OkHttpClient"))
        assertEquals(listOf(1), hits("val s = Socket(host, 80)"))
        assertEquals(listOf(1), hits("val s = ServerSocket(0)"))
        assertEquals(listOf(1), hits("val u = URL(text)"))
        assertEquals(listOf(1), hits("getSystemService(ConnectivityManager::class.java)"))
        assertEquals(listOf(2), hits("ok()\nval s = Socket()"))
    }

    @Test
    fun `mentions in comments and strings are ignored`() {
        assertEquals(emptyList(), hits("// uses a Socket, java.net.URL and HttpURLConnection"))
        assertEquals(emptyList(), hits("val x = 1 // ConnectivityManager"))
        assertEquals(emptyList(), hits("/* java.net.Socket */ val x = 1"))
        assertEquals(emptyList(), hits("/*\n * Socket\n * /* nested okhttp */ HttpURLConnection\n */\nval x = 1"))
        assertEquals(emptyList(), hits("""val s = "connect via a Socket to java.net.host""""))
        assertEquals(emptyList(), hits("val s = \"escaped \\\" Socket \\\" quote\""))
        assertEquals(emptyList(), hits("val s = \"\"\"\nSocket\nokhttp\n\"\"\""))
        assertEquals(emptyList(), hits("/**\n * Socket in a KDoc line\n */\nclass A"))
    }

    @Test
    fun `code after a comment or a string on the same line is still scanned`() {
        assertEquals(listOf(1), hits("""val a = "x"; val s = Socket()"""))
        assertEquals(listOf(1), hits("/* c */ val s = Socket()"))
        assertEquals(listOf(3), hits("/* a\n b */\nval s = Socket()"))
    }

    @Test
    fun `similar words are not hits`() {
        assertEquals(emptyList(), hits("val u = Uri.parse(text)"))
        assertEquals(emptyList(), hits("val renderURLs = 1"))
        assertEquals(emptyList(), hits("val connectivity = 1"))
    }

    @Test
    fun `only the download package directory is allowed`() {
        assertTrue(NetworkSourceScanner.isAllowed("app/src/main/kotlin/app/meetingrecorder/models/download/ModelDownloader.kt"))
        assertFalse(NetworkSourceScanner.isAllowed("app/src/main/kotlin/app/meetingrecorder/models/ModelRepository.kt"))
        assertFalse(NetworkSourceScanner.isAllowed("core/src/main/kotlin/app/meetingrecorder/core/models/DownloadRules.kt"))
        assertFalse(NetworkSourceScanner.isAllowed("whisper/src/main/kotlin/app/meetingrecorder/whisper/WhisperContext.kt"))
        assertTrue(NetworkSourceScanner.isAllowed("""app\src\main\kotlin\app\meetingrecorder\models\download\X.kt"""))
    }
}
