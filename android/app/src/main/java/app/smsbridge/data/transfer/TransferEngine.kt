package app.smsbridge.data.transfer

import app.smsbridge.data.backup.BackupRecord
import app.smsbridge.data.backup.RestoreResult
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class TransferOffer(val transferId: String, val senderName: String, val total: Int)

/**
 * Receiving side. Serves one sender at a time over TLS with its own [TlsIdentity] and accepts
 * only senders that present [token] (from the QR code). Stops after one completed transfer,
 * after [MAX_FAILED_AUTH] bad tokens, or when [close] is called.
 *
 * Ordering guarantees: a batch is applied only if its checksum matches and it is the next one
 * expected; it is acknowledged only after [Delegate.apply] returned (committed). Re-sent batches
 * that were already committed are acknowledged without being applied again.
 */
class TransferServer(
    identity: TlsIdentity,
    private val token: ByteArray,
    private val receiverName: String,
    private val delegate: Delegate,
    port: Int = 0,
) {
    interface Delegate {
        /** Ask the user whether to accept. Called once per new transfer id. */
        suspend fun confirm(offer: TransferOffer): Boolean

        /** First batch sequence number still needed for [transferId] (1 for a new transfer). */
        suspend fun resumeFrom(transferId: String): Int

        /** Must commit durably before returning. */
        suspend fun apply(transferId: String, seq: Int, records: List<BackupRecord>)

        suspend fun complete(transferId: String): RestoreResult

        fun onSenderConnected(senderName: String) = Unit
        fun onAuthFailed() = Unit
        fun onConnectionError(error: Exception) = Unit
    }

    private val serverSocket = TransferTls.serverSocket(identity, port)
    val port: Int get() = serverSocket.localPort
    val fingerprint: String = identity.certificate.sha256Fingerprint()

    private var failedAuth = 0
    private val accepted = mutableSetOf<String>()

    /** True when serving stopped because of too many wrong tokens. */
    val lockedOut: Boolean get() = failedAuth >= MAX_FAILED_AUTH

    /** Blocks (on IO) until a transfer completed or the server was closed. Returns true on completion. */
    suspend fun serve(): Boolean = withContext(Dispatchers.IO) {
        while (isActive && !serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept() as SSLSocket
            } catch (e: IOException) {
                if (serverSocket.isClosed) break else continue
            }
            val completed = try {
                handle(socket)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Connection lost, malformed frame or protocol error: drop this connection and
                // keep listening so the (authenticated) sender can resume.
                delegate.onConnectionError(e)
                false
            } finally {
                runCatching { socket.close() }
            }
            if (completed) return@withContext true
            if (failedAuth >= MAX_FAILED_AUTH) break
        }
        false
    }

    fun close() = runCatching { serverSocket.close() }

    private suspend fun handle(socket: SSLSocket): Boolean {
        socket.soTimeout = READ_TIMEOUT_MS
        val channel = FrameChannel(
            DataInputStream(BufferedInputStream(socket.inputStream)),
            DataOutputStream(BufferedOutputStream(socket.outputStream)),
        )

        val hello = channel.expect(Msg.HELLO)
        val presented = runCatching { Base64.getDecoder().decode(hello.optString("token")) }.getOrNull()
        if (presented == null || !MessageDigest.isEqual(presented, token)) {
            failedAuth++
            delegate.onAuthFailed()
            channel.sendError(ErrorCode.AUTH, "This phone has not scanned the current QR code.")
            return false
        }
        if (hello.optInt("v") != PairingLink.VERSION) {
            channel.sendError(ErrorCode.PROTOCOL, "The two phones run incompatible SMS Bridge versions.")
            return false
        }
        val senderName = hello.optString("deviceName").take(64).ifBlank { "Another phone" }
        delegate.onSenderConnected(senderName)
        channel.send(Msg.WELCOME, JSONObject().put("v", PairingLink.VERSION).put("deviceName", receiverName))

        val init = channel.expect(Msg.INIT)
        val transferId = init.optString("transferId").takeIf { isUuid(it) }
            ?: throw TransferException(ErrorCode.PROTOCOL, "Invalid transfer id.")
        val total = init.optInt("total", -1).takeIf { it >= 0 } ?: throw TransferException(ErrorCode.PROTOCOL, "Invalid total.")
        val batchSize = init.optInt("batchSize", -1).takeIf { it in 1..MAX_BATCH } ?: throw TransferException(ErrorCode.PROTOCOL, "Invalid batch size.")

        if (transferId !in accepted) {
            if (!delegate.confirm(TransferOffer(transferId, senderName, total))) {
                channel.sendError(ErrorCode.DECLINED, "The other phone declined the transfer.")
                return false
            }
            accepted += transferId
        }
        var expected = delegate.resumeFrom(transferId)
        channel.send(Msg.READY, JSONObject().put("resumeFrom", expected))

        while (true) {
            val frame = channel.receive()
            when (frame.optString("type")) {
                Msg.BATCH -> {
                    val seq = frame.getInt("seq")
                    val payload = frame.getString("payload")
                    if (BatchCodec.checksum(payload) != frame.optString("sha256")) {
                        channel.sendError(ErrorCode.CHECKSUM, "A batch arrived damaged.")
                        throw TransferException(ErrorCode.CHECKSUM, "Batch $seq checksum mismatch")
                    }
                    if (seq > expected) {
                        channel.sendError(ErrorCode.PROTOCOL, "A batch is missing.")
                        throw TransferException(ErrorCode.PROTOCOL, "Expected batch $expected, got $seq")
                    }
                    if (seq == expected) {
                        val records = BatchCodec.decode(payload)
                        if (records.size > batchSize) throw TransferException(ErrorCode.PROTOCOL, "Batch too large")
                        delegate.apply(transferId, seq, records)
                        expected++
                    }
                    channel.send(Msg.ACK, JSONObject().put("seq", seq))
                }
                Msg.DONE -> {
                    val result = delegate.complete(transferId)
                    channel.send(
                        Msg.RESULT,
                        JSONObject()
                            .put("read", result.read)
                            .put("restored", result.restored)
                            .put("duplicates", result.duplicates)
                            .put("draftsSkipped", result.draftsSkipped),
                    )
                    return true
                }
                else -> throw TransferException(ErrorCode.PROTOCOL, "Unexpected message")
            }
        }
    }

    companion object {
        const val MAX_FAILED_AUTH = 5
        const val MAX_BATCH = 1_000
        const val READ_TIMEOUT_MS = 60_000
        private fun isUuid(s: String) = runCatching { UUID.fromString(s) }.isSuccess
    }
}

/** What the sender transfers: a fixed snapshot, split into numbered batches (1-based). */
interface TransferSource {
    val total: Int
    val batchSize: Int

    /** Emits batches in order, starting at [fromSeq]. Must produce the same batches every time. */
    suspend fun batches(fromSeq: Int, sink: suspend (seq: Int, records: List<BackupRecord>) -> Unit)
}

data class TransferOutcome(val read: Int, val restored: Int, val duplicates: Int, val draftsSkipped: Int)

/**
 * Sending side. Connects to the receiver from a [PairingLink], verifies its certificate against
 * the pinned fingerprint, authenticates with the token, and sends batches one at a time, each
 * acknowledged before the next. [abort] closes the connection from any thread.
 */
class TransferClient(
    private val link: PairingLink,
    private val senderDeviceId: String,
    private val senderName: String,
    private val source: TransferSource,
) {
    @Volatile
    private var socket: SSLSocket? = null

    fun abort() = runCatching { socket?.close() }

    suspend fun run(
        transferId: String,
        isResume: Boolean,
        confirm: suspend (receiverName: String, total: Int) -> Boolean,
        onWaitingForReceiver: () -> Unit,
        onProgress: (sent: Int, total: Int) -> Unit,
    ): TransferOutcome = withContext(Dispatchers.IO) {
        TransferTls.clientSocket(link, CONNECT_TIMEOUT_MS).also { socket = it }.use { s ->
            s.soTimeout = TransferServer.READ_TIMEOUT_MS
            val channel = FrameChannel(
                DataInputStream(BufferedInputStream(s.inputStream)),
                DataOutputStream(BufferedOutputStream(s.outputStream)),
            )
            channel.send(
                Msg.HELLO,
                JSONObject()
                    .put("v", PairingLink.VERSION)
                    .put("token", Base64.getEncoder().encodeToString(link.token))
                    .put("deviceId", senderDeviceId)
                    .put("deviceName", senderName),
            )
            val receiverName = channel.expect(Msg.WELCOME).optString("deviceName").take(64).ifBlank { "the other phone" }
            if (!isResume && !confirm(receiverName, source.total)) {
                channel.sendError(ErrorCode.DECLINED, "The sender cancelled.")
                throw TransferException(ErrorCode.DECLINED, "You cancelled the transfer.")
            }
            channel.send(
                Msg.INIT,
                JSONObject().put("transferId", transferId).put("total", source.total).put("batchSize", source.batchSize),
            )
            onWaitingForReceiver()
            s.soTimeout = CONFIRM_TIMEOUT_MS
            val from = channel.expect(Msg.READY).getInt("resumeFrom").coerceAtLeast(1)
            s.soTimeout = TransferServer.READ_TIMEOUT_MS

            var sent = minOf((from - 1) * source.batchSize, source.total)
            onProgress(sent, source.total)
            source.batches(from) { seq, records ->
                val payload = BatchCodec.encode(records)
                channel.send(
                    Msg.BATCH,
                    JSONObject().put("seq", seq).put("sha256", BatchCodec.checksum(payload)).put("payload", payload),
                )
                val ack = channel.expect(Msg.ACK)
                if (ack.optInt("seq") != seq) throw TransferException(ErrorCode.PROTOCOL, "Acknowledgement out of order")
                sent += records.size
                onProgress(sent, source.total)
            }
            channel.send(Msg.DONE, JSONObject().put("transferId", transferId))
            val result = channel.expect(Msg.RESULT)
            TransferOutcome(
                read = result.optInt("read"),
                restored = result.optInt("restored"),
                duplicates = result.optInt("duplicates"),
                draftsSkipped = result.optInt("draftsSkipped"),
            )
        }.also { socket = null }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        /** How long the receiver's owner has to tap Accept. */
        const val CONFIRM_TIMEOUT_MS = 5 * 60_000
    }
}
