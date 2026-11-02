package app.smsbridge.data.cloud

import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * What the web dashboard's QR code carries: `smsbridge://pair?v=1&s=<server>&c=<pairing code>&d=<device id>&f=<fingerprint>`.
 * [pairingCode] is present when a phone should connect; [deviceId] and [fingerprint] when a browser
 * waits for approval. Scanning replaces comparing safety codes: the phone approves the browser only
 * if the keys the server lists for [deviceId] hash to exactly [fingerprint].
 *
 * "Add another phone" codes carry `k=<linkSecret>` with the pairing code instead: the phone joins
 * and sends [PairLinks.linkProof], and the browser that showed the code approves it.
 */
data class PairLink(
    val server: String, val pairingCode: String?, val deviceId: String?, val fingerprint: ByteArray?, val linkSecret: ByteArray? = null,
) {
    val approvesBrowser: Boolean get() = deviceId != null && fingerprint != null

    override fun equals(other: Any?) = other is PairLink && other.server == server && other.pairingCode == pairingCode &&
        other.deviceId == deviceId && (other.fingerprint?.contentEquals(fingerprint ?: ByteArray(0)) ?: (fingerprint == null)) &&
        (other.linkSecret?.contentEquals(linkSecret ?: ByteArray(0)) ?: (linkSecret == null))

    override fun hashCode() = listOf(server, pairingCode, deviceId).hashCode()
}

/** A browser's sign-in QR code: `smsbridge://login?v=1&s=<server>&r=<request id>&f=<fingerprint>`. */
data class LoginLink(val server: String, val requestId: String, val fingerprint: ByteArray) {
    override fun equals(other: Any?) = other is LoginLink && other.server == server && other.requestId == requestId && other.fingerprint.contentEquals(fingerprint)
    override fun hashCode() = listOf(server, requestId).hashCode()
}

object PairLinks {
    private const val PREFIX = "smsbridge://pair?"
    private const val LOGIN_PREFIX = "smsbridge://login?"

    private fun params(query: String): Map<String, String> = query.split('&').mapNotNull { part ->
        val i = part.indexOf('=')
        if (i <= 0) null else part.substring(0, i) to URLDecoder.decode(part.substring(i + 1), "UTF-8")
    }.toMap()

    /** Null unless [text] is a well-formed version-1 sign-in link. */
    fun parseLogin(text: String): LoginLink? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(LOGIN_PREFIX)) return null
        val p = params(trimmed.removePrefix(LOGIN_PREFIX))
        if (p["v"] != "1") return null
        val server = p["s"]?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null
        val request = p["r"]?.takeIf { Regex("[0-9a-fA-F-]{36}").matches(it) } ?: return null
        val fingerprint = p["f"]?.let { runCatching { Base64.getUrlDecoder().decode(it) }.getOrNull() }?.takeIf { it.size == 32 } ?: return null
        return LoginLink(server, request, fingerprint)
    }

    /** Null unless [text] is a well-formed version-1 pairing link with something to do. */
    fun parse(text: String): PairLink? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(PREFIX)) return null
        val params = trimmed.removePrefix(PREFIX).split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i) to URLDecoder.decode(part.substring(i + 1), "UTF-8")
        }.toMap()
        if (params["v"] != "1") return null
        val server = params["s"]?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null
        val code = params["c"]?.takeIf { it.isNotBlank() }
        val device = params["d"]?.takeIf { Regex("[0-9a-fA-F-]{36}").matches(it) }
        val fingerprint = params["f"]?.let { runCatching { Base64.getUrlDecoder().decode(it) }.getOrNull() }?.takeIf { it.size == 32 }
        val secret = params["k"]?.let { runCatching { Base64.getUrlDecoder().decode(it) }.getOrNull() }?.takeIf { it.size == 16 }
        if (code == null && (device == null || fingerprint == null)) return null
        return PairLink(server, code, if (fingerprint != null) device else null, if (device != null) fingerprint else null, secret?.takeIf { code != null })
    }

    /** SHA-256 of the device's X25519 and Ed25519 public keys; the browser computes the same. */
    fun fingerprint(encPublic: ByteArray, signPublic: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(encPublic + signPublic)

    /** HMAC-SHA256(secret, fingerprint): proves to the browser that this phone scanned its code (web/lib/link.ts). */
    fun linkProof(secret: ByteArray, encPublic: ByteArray, signPublic: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }.doFinal(fingerprint(encPublic, signPublic))

    fun matches(expected: ByteArray, encPublic: ByteArray, signPublic: ByteArray): Boolean =
        MessageDigest.isEqual(expected, fingerprint(encPublic, signPublic))
}
