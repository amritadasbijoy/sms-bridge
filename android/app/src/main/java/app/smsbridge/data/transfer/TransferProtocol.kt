package app.smsbridge.data.transfer

import app.smsbridge.core.model.SmsType
import app.smsbridge.data.backup.BackupRecord
import app.smsbridge.data.backup.toHex
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything a sender needs to reach and authenticate a receiver, shown as a QR code:
 *
 * `smsbridge://pair?v=1&h=<ipv4>&p=<port>&fp=<sha256 of receiver TLS cert, hex>&t=<32-byte token, base64url>`
 *
 * - `fp` lets the sender verify it is talking to exactly the phone it scanned (certificate pinning).
 * - `t` lets the receiver verify the sender actually saw the QR code.
 *
 * Together they replace a certificate authority: physical proximity is the trust anchor.
 */
data class PairingLink(val host: String, val port: Int, val fingerprint: String, val token: ByteArray) {
    fun format(): String =
        "smsbridge://pair?v=$VERSION&h=${enc(host)}&p=$port&fp=$fingerprint&t=${Base64.getUrlEncoder().withoutPadding().encodeToString(token)}"

    override fun toString(): String = "PairingLink(host=$host, port=$port)" // never print the token
    override fun equals(other: Any?) = other is PairingLink && other.host == host && other.port == port &&
        other.fingerprint == fingerprint && other.token.contentEquals(token)
    override fun hashCode() = 31 * fingerprint.hashCode() + port

    companion object {
        const val VERSION = 1
        const val TOKEN_BYTES = 32
        private val HEX64 = Regex("[0-9a-f]{64}")

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /** Returns null for anything that is not a complete, well-formed v1 pairing link. */
        fun parse(text: String): PairingLink? = runCatching {
            val uri = URI(text.trim())
            if (uri.scheme != "smsbridge" || uri.host != "pair") return null
            val q = uri.rawQuery.split('&').associate { part ->
                val (k, v) = part.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                k to URLDecoder.decode(v, "UTF-8")
            }
            if (q["v"] != VERSION.toString()) return null
            val host = q["h"]?.takeIf { it.isNotBlank() } ?: return null
            val port = q["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val fp = q["fp"]?.lowercase()?.takeIf { HEX64.matches(it) } ?: return null
            val token = Base64.getUrlDecoder().decode(q["t"] ?: return null).takeIf { it.size == TOKEN_BYTES } ?: return null
            PairingLink(host, port, fp, token)
        }.getOrNull()
    }
}

/** Message types of the transfer protocol (see docs/architecture.md, "Wi-Fi transfer"). */
object Msg {
    const val HELLO = "HELLO"       // sender → receiver: token, sender identity, optional transferId to resume
    const val WELCOME = "WELCOME"   // receiver → sender: receiver identity
    const val INIT = "INIT"         // sender → receiver: transferId, total, batchSize
    const val READY = "READY"       // receiver → sender: user accepted; resumeFrom = first batch it still needs
    const val BATCH = "BATCH"       // sender → receiver: seq, sha256, payload (JSON array of messages)
    const val ACK = "ACK"           // receiver → sender: seq committed
    const val DONE = "DONE"         // sender → receiver: all batches sent
    const val RESULT = "RESULT"     // receiver → sender: final counts
    const val ERROR = "ERROR"       // either way: code + human-readable reason (never message content)
}

object ErrorCode {
    const val AUTH = "auth"
    const val DECLINED = "declined"
    const val PROTOCOL = "protocol"
    const val CHECKSUM = "checksum"
    const val BUSY = "busy"
    const val FAILED = "failed"
}

class TransferException(val code: String, message: String) : IOException(message)

/**
 * Length-prefixed JSON frames: `u32 length (big endian) | UTF-8 JSON object`.
 * Frames above [MAX_FRAME_BYTES] are refused so a peer cannot make us allocate unbounded memory.
 */
class FrameChannel(private val input: DataInputStream, private val output: DataOutputStream) {
    fun send(type: String, body: JSONObject = JSONObject()) {
        val bytes = body.put("type", type).toString().toByteArray(Charsets.UTF_8)
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    fun receive(): JSONObject {
        val length = input.readInt()
        if (length <= 0 || length > MAX_FRAME_BYTES) throw TransferException(ErrorCode.PROTOCOL, "Invalid frame size.")
        val bytes = ByteArray(length).also(input::readFully)
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        if (json.optString("type") == Msg.ERROR) {
            throw TransferException(json.optString("code", ErrorCode.FAILED), json.optString("reason", "The other phone stopped the transfer."))
        }
        return json
    }

    /** Receives a frame and checks its type. */
    fun expect(type: String): JSONObject {
        val json = receive()
        if (json.optString("type") != type) throw TransferException(ErrorCode.PROTOCOL, "Unexpected message ${json.optString("type")}; expected $type.")
        return json
    }

    fun sendError(code: String, reason: String) = runCatching {
        send(Msg.ERROR, JSONObject().put("code", code).put("reason", reason))
    }

    companion object {
        const val MAX_FRAME_BYTES = 8 * 1024 * 1024
    }
}

/** Batch payload codec. The checksum covers the exact payload bytes that travel. */
object BatchCodec {
    fun encode(records: List<BackupRecord>): String = JSONArray().apply {
        records.forEach { r ->
            put(
                JSONObject()
                    .put("a", r.address ?: JSONObject.NULL)
                    .put("b", r.body)
                    .put("d", r.date)
                    .put("ds", r.dateSent)
                    .put("t", r.type.providerValue)
                    .put("r", r.read)
                    .put("s", r.seen)
                    .put("st", r.status ?: JSONObject.NULL)
                    .put("sub", r.subscriptionId ?: JSONObject.NULL)
                    .put("src", r.sourceDeviceId ?: JSONObject.NULL),
            )
        }
    }.toString()

    fun decode(payload: String): List<BackupRecord> {
        val array = JSONArray(payload)
        return List(array.length()) { i ->
            val o = array.getJSONObject(i)
            val type = SmsType.fromProvider(o.getInt("t"))
            if (type == SmsType.UNKNOWN) throw TransferException(ErrorCode.PROTOCOL, "Unknown message type in batch.")
            BackupRecord(
                address = o.optStringOrNull("a"),
                body = o.getString("b"),
                date = o.getLong("d"),
                dateSent = o.optLong("ds", 0),
                type = type,
                read = o.optBoolean("r"),
                seen = o.optBoolean("s"),
                status = if (o.isNull("st")) null else o.optInt("st"),
                subscriptionId = if (o.isNull("sub")) null else o.optInt("sub"),
                archiveId = null,
                sourceDeviceId = o.optStringOrNull("src"),
            )
        }
    }

    fun checksum(payload: String): String =
        MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).toHex()

    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key)
}
