package app.meetingrecorder.core.models

/** Which addresses a download may contact. The release policy never accepts a loopback address. */
public enum class HostPolicy {
    RELEASE,
    DEBUG_LOOPBACK,
    ;

    /** True for an `https` address of `huggingface.co` or a `*.hf.co` host (and, in debug, `http://127.0.0.1`). */
    public fun isAllowedHop(url: String): Boolean {
        val parsed = DownloadRules.parse(url) ?: return false
        if (parsed.scheme == "https" && (parsed.host == HUGGING_FACE || parsed.host.endsWith(".hf.co"))) return true
        return this == DEBUG_LOOPBACK && parsed.scheme == "http" && parsed.host == LOOPBACK
    }

    private companion object {
        const val HUGGING_FACE = "huggingface.co"
        const val LOOPBACK = "127.0.0.1"
    }
}

/** Answer of [DownloadRules.validateResponse]. */
public sealed interface ResponseCheck {
    /** The body starts at [startAt] of the file: continue ([startAt] > 0) or start (0). */
    public data class Accept(val startAt: Long) : ResponseCheck

    /** The server ignored the range: truncate the partial file and start over from byte 0. */
    public data object Restart : ResponseCheck

    public data class Reject(val reason: String) : ResponseCheck
}

public sealed interface HopCheck {
    public data object Ok : HopCheck

    public data class UnexpectedAddress(val url: String) : HopCheck

    public data object TooManyHops : HopCheck
}

public sealed interface EtagCheck {
    public data object Ok : EtagCheck

    public data object Mismatch : EtagCheck
}

/** The pure rules of the download protocol (contracts/model-catalog.md). `:core` uses no `java.net`; URLs are parsed by hand. */
public object DownloadRules {

    /** At most this many requests (the original address plus its redirects) per download. */
    public const val MAX_HOPS: Int = 5

    private const val MIB = 1024L * 1024
    private const val MIN_MARGIN_BYTES = 16 * MIB
    private const val MARGIN_PERCENT = 2
    private const val PERCENT = 100
    private const val MAX_PORT = 65_535

    public data class ParsedUrl(val scheme: String, val host: String, val port: Int?)

    private val urlPattern = Regex("^(https?)://([^/?#\\\\]+)(?:[/?#].*)?$", RegexOption.IGNORE_CASE)
    private val hostPattern = Regex("^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$")

    /** Free space needed: `sizeBytes - bytesAlreadyDownloaded` plus the larger of 2% of the size and 16 MiB. */
    public fun requiredFreeBytes(sizeBytes: Long, bytesAlreadyDownloaded: Long = 0): Long {
        require(sizeBytes > 0) { "sizeBytes must be positive" }
        require(bytesAlreadyDownloaded in 0..sizeBytes) { "bytesAlreadyDownloaded is outside the file" }
        return sizeBytes - bytesAlreadyDownloaded + maxOf(sizeBytes * MARGIN_PERCENT / PERCENT, MIN_MARGIN_BYTES)
    }

    /**
     * Splits an `http` or `https` address into scheme, lower-cased host and port. Returns `null` for
     * anything else, including user information (`https://huggingface.co@evil.example/`), backslashes,
     * whitespace, percent escapes in the host and IPv6 literals: such an address is never allowed.
     */
    public fun parse(url: String): ParsedUrl? {
        if (url.any { it.isWhitespace() || it.isISOControl() }) return null
        val match = urlPattern.matchEntire(url) ?: return null
        val authority = match.groupValues[2]
        if ('@' in authority) return null
        val hostText = authority.substringBefore(':')
        val port = if (':' in authority) authority.substringAfter(':').toIntOrNull()?.takeIf { it in 1..MAX_PORT } ?: return null else null
        val host = hostText.lowercase()
        if (!hostPattern.matches(host)) return null
        return ParsedUrl(match.groupValues[1].lowercase(), host, port)
    }

    /** Checks every address of a download: the original one and each redirect target. */
    public fun checkHops(policy: HostPolicy, urls: List<String>): HopCheck {
        if (urls.size > MAX_HOPS) return HopCheck.TooManyHops
        urls.firstOrNull { !policy.isAllowedHop(it) }?.let { return HopCheck.UnexpectedAddress(it) }
        return HopCheck.Ok
    }

    /**
     * Judges the final response. [requestedFrom] is the first byte requested (0 for a new download).
     * `206` needs a `Content-Range` that starts at [requestedFrom] and ends in a total of [expectedSize];
     * `200` to a ranged request means the server ignored the range; a missing or wrong length is an error.
     */
    public fun validateResponse(
        status: Int,
        contentLength: Long?,
        contentRange: String?,
        requestedFrom: Long,
        expectedSize: Long,
    ): ResponseCheck = when (status) {
        STATUS_OK -> when {
            contentLength == null -> ResponseCheck.Reject("the server did not say how large the file is")
            contentLength != expectedSize -> ResponseCheck.Reject("the file on the server has another size ($contentLength, expected $expectedSize)")
            requestedFrom > 0 -> ResponseCheck.Restart
            else -> ResponseCheck.Accept(0)
        }
        STATUS_PARTIAL -> {
            val range = contentRange?.let { rangePattern.matchEntire(it.trim()) }
            val start = range?.groupValues?.get(1)?.toLongOrNull()
            val total = range?.groupValues?.get(RANGE_TOTAL_GROUP)?.toLongOrNull()
            when {
                range == null || start == null || total == null -> ResponseCheck.Reject("the answer has no usable Content-Range")
                start != requestedFrom -> ResponseCheck.Reject("the answer starts at byte $start, not at $requestedFrom")
                total != expectedSize -> ResponseCheck.Reject("the file on the server has another size ($total, expected $expectedSize)")
                else -> ResponseCheck.Accept(start)
            }
        }
        else -> ResponseCheck.Reject("the server answered with status $status")
    }

    /**
     * The first hop's `x-linked-etag`, when present, must equal the catalog hash (quotes and a weak
     * marker are ignored); a mismatch aborts before any bytes are stored ("the file on the server changed").
     */
    public fun earlyEtagCheck(linkedEtag: String?, expectedSha256: String): EtagCheck {
        if (linkedEtag == null) return EtagCheck.Ok
        val value = linkedEtag.trim().removePrefix("W/").trim('"')
        return if (value.equals(expectedSha256, ignoreCase = true)) EtagCheck.Ok else EtagCheck.Mismatch
    }

    private val rangePattern = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$")
    private const val STATUS_OK = 200
    private const val STATUS_PARTIAL = 206
    private const val RANGE_TOTAL_GROUP = 3
}
