package app.smsbridge.data.sms

import app.smsbridge.core.model.AddressKey
import app.smsbridge.core.model.SmsDirection
import app.smsbridge.core.model.SmsType
import java.security.MessageDigest

/**
 * Content hashes used to recognise the same message arriving through different paths
 * (broadcast vs. provider scan, re-imports, future cross-device transfer).
 *
 * The timestamp uses `dateSent` when present because it is the PDU/service-centre timestamp
 * and is identical in the broadcast and in the provider row; `date` is the local receive time
 * and differs by milliseconds between the two. Second precision absorbs provider truncation.
 *
 * These hashes stay on the device. They are derived from content and must not be sent to the
 * server unencrypted (they could be brute-forced for short messages).
 */
object SmsFingerprint {
    private const val VERSION = "v1"
    private val HEX = "0123456789abcdef".toCharArray()

    fun compute(address: String?, body: String, date: Long, dateSent: Long, type: SmsType): String {
        val seconds = (if (dateSent > 0) dateSent else date) / 1000
        return sha256(VERSION, direction(type), normalizeAddress(address), seconds.toString(), body)
    }

    fun contentKey(address: String?, body: String, type: SmsType): String =
        sha256(VERSION, direction(type), normalizeAddress(address), body)

    fun normalizeAddress(address: String?): String = AddressKey.of(address)

    private fun direction(type: SmsType): String = when (type.direction) {
        SmsDirection.INCOMING -> "in"
        SmsDirection.OUTGOING -> "out"
        SmsDirection.UNKNOWN -> "unknown"
    }

    private fun sha256(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEachIndexed { index, part ->
            if (index > 0) digest.update(0)
            digest.update(part.toByteArray(Charsets.UTF_8))
        }
        val bytes = digest.digest()
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b ->
            val v = b.toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }
}
