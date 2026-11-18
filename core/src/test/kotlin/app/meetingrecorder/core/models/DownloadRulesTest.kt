package app.meetingrecorder.core.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadRulesTest {

    private val mib = 1024L * 1024

    @Test
    fun `required free space is the remainder plus 2 percent, at least 16 MiB`() {
        assertEquals(190_085_487 + 16 * mib, DownloadRules.requiredFreeBytes(190_085_487)) // 2% is below 16 MiB
        assertEquals(874_188_075 + 874_188_075 * 2 / 100, DownloadRules.requiredFreeBytes(874_188_075))
        assertEquals(190_085_487 - 100_000_000 + 16 * mib, DownloadRules.requiredFreeBytes(190_085_487, 100_000_000))
        assertEquals(16 * mib, DownloadRules.requiredFreeBytes(1_000, 1_000))
    }

    @Test
    fun `the release policy accepts https to huggingface and hf dot co hosts only`() {
        val p = HostPolicy.RELEASE
        assertTrue(p.isAllowedHop("https://huggingface.co/ggerganov/whisper.cpp/resolve/abc/ggml-tiny-q5_1.bin"))
        assertTrue(p.isAllowedHop("https://cdn-lfs-us-1.hf.co/repos/ab/cd/file?X-Amz-Signature=1&x=y"))
        assertTrue(p.isAllowedHop("https://cas-bridge.xethub.hf.co/xet-bridge-us/abc"))
        assertTrue(p.isAllowedHop("HTTPS://HuggingFace.CO:443/x"))
        for (bad in listOf(
            "http://huggingface.co/x", // cleartext
            "https://example.org/x",
            "https://evilhuggingface.co/x", // look-alike suffix
            "https://huggingface.co.evil.com/x", // look-alike prefix
            "https://hf.co.evil.com/x",
            "https://nothf.co/x",
            "https://huggingface.co@evil.example/x", // user information
            "https://evil.example@huggingface.co/x",
            "https://user:pw@huggingface.co/x",
            "https://huggingface.co\\@evil.example/x",
            "https://huggingface.co:notaport/x",
            "https://huggingface.co:0/x",
            "https://[::1]/x",
            "https://huggingface.co /x",
            "https://huggingface.co%2eevil.com/x",
            "ftp://huggingface.co/x",
            "huggingface.co/x",
            "",
            "https://",
            "https:///huggingface.co/x",
        )) {
            assertTrue(!p.isAllowedHop(bad), bad)
        }
    }

    @Test
    fun `loopback is accepted by the debug policy only`() {
        assertTrue(HostPolicy.DEBUG_LOOPBACK.isAllowedHop("http://127.0.0.1:8080/model.bin"))
        assertTrue(HostPolicy.DEBUG_LOOPBACK.isAllowedHop("https://huggingface.co/x"))
        assertTrue(!HostPolicy.DEBUG_LOOPBACK.isAllowedHop("https://127.0.0.1/x"))
        assertTrue(!HostPolicy.DEBUG_LOOPBACK.isAllowedHop("http://localhost:8080/x"))
        assertTrue(!HostPolicy.DEBUG_LOOPBACK.isAllowedHop("http://127.0.0.2/x"))
        assertTrue(!HostPolicy.RELEASE.isAllowedHop("http://127.0.0.1:8080/model.bin"))
    }

    @Test
    fun `at most five hops and every hop is checked`() {
        val ok = "https://huggingface.co/a"
        val cdn = "https://cdn.hf.co/b"
        assertEquals(HopCheck.Ok, DownloadRules.checkHops(HostPolicy.RELEASE, listOf(ok, cdn)))
        assertEquals(HopCheck.Ok, DownloadRules.checkHops(HostPolicy.RELEASE, List(5) { cdn }))
        assertEquals(HopCheck.TooManyHops, DownloadRules.checkHops(HostPolicy.RELEASE, List(6) { cdn }))
        assertEquals(HopCheck.UnexpectedAddress("https://evil.example/c"), DownloadRules.checkHops(HostPolicy.RELEASE, listOf(ok, "https://evil.example/c")))
        assertEquals(5, DownloadRules.MAX_HOPS)
    }

    @Test
    fun `parse splits scheme host and port`() {
        assertEquals(DownloadRules.ParsedUrl("https", "huggingface.co", null), DownloadRules.parse("https://huggingface.co/x?y#z"))
        assertEquals(DownloadRules.ParsedUrl("http", "127.0.0.1", 8080), DownloadRules.parse("http://127.0.0.1:8080"))
        assertNull(DownloadRules.parse("https://huggingface.co:99999/"))
    }

    @Test
    fun `a 206 must carry a Content-Range starting at the requested byte`() {
        val total = 1_000L
        assertEquals(ResponseCheck.Accept(400), DownloadRules.validateResponse(206, 600, "bytes 400-999/1000", 400, total))
        assertIs<ResponseCheck.Reject>(DownloadRules.validateResponse(206, 600, "bytes 0-999/1000", 400, total))
        assertIs<ResponseCheck.Reject>(DownloadRules.validateResponse(206, 600, null, 400, total))
        assertIs<ResponseCheck.Reject>(DownloadRules.validateResponse(206, 600, "garbage", 400, total))
        assertIs<ResponseCheck.Reject>(DownloadRules.validateResponse(206, 600, "bytes 400-999/2000", 400, total))
    }

    @Test
    fun `a 200 to a ranged request means start over`() {
        assertEquals(ResponseCheck.Restart, DownloadRules.validateResponse(200, 1_000, null, 400, 1_000))
        assertEquals(ResponseCheck.Accept(0), DownloadRules.validateResponse(200, 1_000, null, 0, 1_000))
    }

    @Test
    fun `a missing or wrong length is an error and so is any other status`() {
        assertIs<ResponseCheck.Reject>(DownloadRules.validateResponse(200, null, null, 0, 1_000))
        assertIs<ResponseCheck.Reject>(DownloadRules.validateResponse(200, 999, null, 0, 1_000))
        for (status in listOf(301, 403, 404, 416, 500)) {
            assertIs<ResponseCheck.Reject>(DownloadRules.validateResponse(status, 1_000, null, 0, 1_000), "status $status")
        }
    }

    @Test
    fun `the linked etag must equal the catalog hash when present`() {
        val sha = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"
        assertEquals(EtagCheck.Ok, DownloadRules.earlyEtagCheck(null, sha))
        assertEquals(EtagCheck.Ok, DownloadRules.earlyEtagCheck(sha, sha))
        assertEquals(EtagCheck.Ok, DownloadRules.earlyEtagCheck("\"$sha\"", sha))
        assertEquals(EtagCheck.Ok, DownloadRules.earlyEtagCheck("W/\"${sha.uppercase()}\"", sha))
        assertEquals(EtagCheck.Mismatch, DownloadRules.earlyEtagCheck("\"${"0".repeat(64)}\"", sha))
        assertEquals(EtagCheck.Mismatch, DownloadRules.earlyEtagCheck("", sha))
    }
}
