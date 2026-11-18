package app.meetingrecorder.core.models

import java.security.MessageDigest

/** SHA-256 over data that arrives in chunks (a download, an import), on the JDK's `MessageDigest`. */
public class Sha256Streaming {
    private val digest = MessageDigest.getInstance("SHA-256")

    public fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Sha256Streaming {
        digest.update(bytes, offset, length)
        return this
    }

    /** The lower-case hex digest of everything fed so far. The instance can be used again after this. */
    public fun hex(): String = (digest.clone() as MessageDigest).digest().joinToString("") { "%02x".format(it) }

    public companion object {
        /** A digest that has already seen [existing], for a download that continues after an interruption. */
        public fun resume(existing: ByteArray): Sha256Streaming = Sha256Streaming().update(existing)
    }
}
