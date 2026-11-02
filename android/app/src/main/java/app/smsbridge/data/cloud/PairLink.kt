package app.smsbridge.data.cloud

import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64

/**
 * What the web dashboard's QR code carries: `smsbridge://pair?v=1&s=<server>&c=<pairing code>&d=<device id>&f=<fingerprint>`.
 * [pairingCode] is present when a phone should connect; [deviceId] and [fingerprint] when a browser
 * waits for approval. Scanning replaces comparing safety codes: the phone approves the browser only
 * if the keys the server lists for [deviceId] hash to exactly [fingerprint].
 */
data class PairLink(val server: String, val pairingCode: String?, val deviceId: String?, val fingerprint: ByteArray?) {
    val approvesBrowser: Boolean get() = deviceId != null && fingerprint != null

    override fun equals(other: Any?) = other is PairLink && other.server == server && other.pairingCode == pairingCode &&
        other.deviceId == deviceId && (other.fingerprint?.contentEquals(fingerprint ?: ByteArray(0)) ?: (fingerprint == null))

    override fun hashCode() = listOf(server, pairingCode, deviceId).hashCode()
}

object PairLinks {
    private const val PREFIX = "smsbridge://pair?"

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
        if (code == null && (device == null || fingerprint == null)) return null
        return PairLink(server, code, if (fingerprint != null) device else null, if (device != null) fingerprint else null)
    }

    /** SHA-256 of the device's X25519 and Ed25519 public keys; the browser computes the same. */
    fun fingerprint(encPublic: ByteArray, signPublic: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(encPublic + signPublic)

    fun matches(expected: ByteArray, encPublic: ByteArray, signPublic: ByteArray): Boolean =
        MessageDigest.isEqual(expected, fingerprint(encPublic, signPublic))
}
