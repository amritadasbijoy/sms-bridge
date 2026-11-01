package app.smsbridge.data.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.smsbridge.core.common.AppLog
import app.smsbridge.core.common.ApplicationScope
import app.smsbridge.core.common.TimeSource
import app.smsbridge.core.database.SmsBridgeDatabase
import app.smsbridge.core.database.entity.TransferSessionEntity
import app.smsbridge.data.backup.BackupRecord
import app.smsbridge.data.backup.RestoreEngine
import app.smsbridge.data.backup.RestoreOptions
import app.smsbridge.data.backup.RestoreResult
import app.smsbridge.data.backup.RestoreSession
import app.smsbridge.data.backup.RestoreTarget
import app.smsbridge.data.settings.AppSettingsRepository
import app.smsbridge.data.settings.LocalDeviceRepository
import app.smsbridge.data.sms.ScanMode
import app.smsbridge.data.sms.SmsPermissionManager
import app.smsbridge.data.sync.SyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.Inet4Address
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface ReceiverState {
    data object Idle : ReceiverState
    data object Starting : ReceiverState
    data class Waiting(val link: String, val address: String) : ReceiverState
    data class Offer(val senderName: String, val total: Int) : ReceiverState
    data class Receiving(val senderName: String, val received: Int, val total: Int) : ReceiverState
    data class Finished(val senderName: String, val result: RestoreResult, val target: RestoreTarget) : ReceiverState
    data class Failed(val message: String) : ReceiverState
}

sealed interface SenderState {
    data object Idle : SenderState
    data object Connecting : SenderState
    data class Confirm(val receiverName: String, val total: Int) : SenderState
    data object WaitingForReceiver : SenderState
    data class Sending(val sent: Int, val total: Int) : SenderState
    data class Finished(val outcome: TransferOutcome) : SenderState
    data class Failed(val message: String, val canResume: Boolean) : SenderState
}

/** Sender source: the archive as of a `rowid` snapshot, in fixed-size batches. */
class ArchiveTransferSource(
    private val db: SmsBridgeDatabase,
    private val maxRowId: Long,
    override val total: Int,
    override val batchSize: Int = DEFAULT_BATCH,
) : TransferSource {
    override suspend fun batches(fromSeq: Int, sink: suspend (seq: Int, records: List<BackupRecord>) -> Unit) {
        var after = 0L
        var seq = 0
        while (true) {
            val page = db.smsMessageDao().exportPage(after, maxRowId, batchSize)
            if (page.isEmpty()) return
            seq++
            after = page.last().rowId
            if (seq >= fromSeq) sink(seq, page.map { BackupRecord.from(it.message) })
        }
    }

    companion object {
        const val DEFAULT_BATCH = 200

        suspend fun snapshot(db: SmsBridgeDatabase, batchSize: Int = DEFAULT_BATCH): ArchiveTransferSource {
            val max = db.smsMessageDao().maxRowId() ?: 0L
            return ArchiveTransferSource(db, max, db.smsMessageDao().countUpTo(max), batchSize)
        }
    }
}

/**
 * Receiver persistence: applies batches through the same [RestoreSession] rules as file restore
 * and records the last committed batch in `transfer_sessions`, so a dropped connection resumes.
 */
class ReceiverStore(
    private val db: SmsBridgeDatabase,
    private val restoreEngine: RestoreEngine,
    private val options: RestoreOptions,
    private val emitSyncEvents: Boolean,
    private val time: TimeSource,
) {
    private val sessions = mutableMapOf<String, RestoreSession>()
    private val dao get() = db.transferSessionDao()

    suspend fun resumeFrom(transferId: String): Int = (dao.get(transferId)?.lastBatchSequence ?: 0) + 1

    suspend fun begin(offer: TransferOffer, peer: String) {
        if (dao.get(offer.transferId) == null) {
            val now = time.nowMillis()
            dao.upsert(TransferSessionEntity(offer.transferId, ROLE_RECEIVER, peer, STATE_RUNNING, offer.total, 0, 0, now, now))
        }
    }

    suspend fun apply(transferId: String, seq: Int, records: List<BackupRecord>): RestoreSession {
        val session = sessions.getOrPut(transferId) { restoreEngine.newSession(options, emitSyncEvents) }
        session.apply(records)
        val row = dao.get(transferId) ?: error("unknown transfer")
        dao.upsert(
            row.copy(
                lastBatchSequence = seq,
                transferredMessages = row.transferredMessages + records.size,
                updatedAt = time.nowMillis(),
            ),
        )
        return session
    }

    suspend fun complete(transferId: String): RestoreResult {
        val result = sessions[transferId]?.result() ?: RestoreResult(0, 0, 0, 0)
        dao.get(transferId)?.let { dao.upsert(it.copy(state = STATE_COMPLETED, updatedAt = time.nowMillis())) }
        return result
    }

    fun receivedSoFar(transferId: String): Int = sessions[transferId]?.read ?: 0

    companion object {
        const val ROLE_RECEIVER = "RECEIVER"
        const val STATE_RUNNING = "RUNNING"
        const val STATE_COMPLETED = "COMPLETED"
    }
}

/**
 * Process-wide owner of the receive and send sessions, so a transfer keeps running when the user
 * leaves the screen. Not a foreground service: the screen is kept on while a transfer is active,
 * and an interrupted transfer resumes where it stopped.
 */
@Singleton
class TransferManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val db: SmsBridgeDatabase,
    private val restoreEngine: RestoreEngine,
    private val devices: LocalDeviceRepository,
    private val settings: AppSettingsRepository,
    private val permissions: SmsPermissionManager,
    private val syncScheduler: SyncScheduler,
    private val time: TimeSource,
) {
    private val _receiver = MutableStateFlow<ReceiverState>(ReceiverState.Idle)
    val receiver: StateFlow<ReceiverState> = _receiver.asStateFlow()
    private val _sender = MutableStateFlow<SenderState>(SenderState.Idle)
    val sender: StateFlow<SenderState> = _sender.asStateFlow()

    private var receiverJob: Job? = null
    private var server: TransferServer? = null
    private var offerDecision: CompletableDeferred<Boolean>? = null

    private var senderJob: Job? = null
    private var client: TransferClient? = null
    private var sendDecision: CompletableDeferred<Boolean>? = null
    private var lastLink: PairingLink? = null
    private var lastTransferId: String? = null
    private var lastSource: ArchiveTransferSource? = null

    // ---- Receiving ----------------------------------------------------------------------------

    fun startReceiving(target: RestoreTarget) {
        if (receiverJob?.isActive == true) return
        receiverJob = scope.launch {
            _receiver.value = ReceiverState.Starting
            if (target == RestoreTarget.PHONE && !permissions.refresh().isDefaultSmsApp) {
                _receiver.value = ReceiverState.Failed("To receive into this phone's messages, make SMS Bridge the default SMS app first.")
                return@launch
            }
            val address = localWifiAddress()
            if (address == null) {
                _receiver.value = ReceiverState.Failed("Connect this phone to Wi-Fi first. Both phones must be on the same Wi-Fi network.")
                return@launch
            }
            val identity = runCatching { KeystoreTlsIdentity.create() }.getOrElse {
                AppLog.w(TAG, "could not create transfer key", it)
                _receiver.value = ReceiverState.Failed("Could not prepare a secure connection on this phone.")
                return@launch
            }
            val token = ByteArray(PairingLink.TOKEN_BYTES).also(SecureRandom()::nextBytes)
            val store = ReceiverStore(db, restoreEngine, RestoreOptions(target), settings.isInitialImportCompleted(), time)
            var senderName = "Another phone"
            var currentTotal = 0
            val delegate = object : TransferServer.Delegate {
                override fun onSenderConnected(senderName: String) {
                    AppLog.i(TAG, "sender connected and authenticated")
                }

                override fun onAuthFailed() = AppLog.w(TAG, "connection with a wrong pairing token refused")

                override fun onConnectionError(error: Exception) =
                    AppLog.w(TAG, "receiver connection ended: ${(error as? TransferException)?.code ?: "-"}", error)

                override suspend fun confirm(offer: TransferOffer): Boolean {
                    senderName = offer.senderName
                    currentTotal = offer.total
                    val decision = CompletableDeferred<Boolean>().also { offerDecision = it }
                    _receiver.value = ReceiverState.Offer(offer.senderName, offer.total)
                    val accepted = withTimeoutOrNull(TransferClient.CONFIRM_TIMEOUT_MS.toLong() - 10_000) { decision.await() } == true
                    if (accepted) store.begin(offer, offer.senderName)
                    return accepted
                }

                override suspend fun resumeFrom(transferId: String) = store.resumeFrom(transferId)

                override suspend fun apply(transferId: String, seq: Int, records: List<BackupRecord>) {
                    store.apply(transferId, seq, records)
                    _receiver.value = ReceiverState.Receiving(senderName, store.receivedSoFar(transferId), currentTotal)
                }

                override suspend fun complete(transferId: String): RestoreResult {
                    val result = store.complete(transferId)
                    if (target == RestoreTarget.PHONE && result.restored > 0) syncScheduler.scheduleReconcile(ScanMode.INCREMENTAL)
                    _receiver.value = ReceiverState.Finished(senderName, result, target)
                    AppLog.i(TAG, "received transfer: read=${result.read} restored=${result.restored} duplicates=${result.duplicates}")
                    return result
                }
            }
            devices.localDeviceId() // make sure this phone's device record (and name) exists
            val srv = try {
                TransferServer(identity, token, devices.localDevice.first()?.name ?: "SMS Bridge", delegate).also { server = it }
            } catch (e: IOException) {
                identity.destroy()
                _receiver.value = ReceiverState.Failed("Could not open a connection point on this phone.")
                return@launch
            }
            _receiver.value = ReceiverState.Waiting(PairingLink(address, srv.port, srv.fingerprint, token).format(), "$address:${srv.port}")
            try {
                srv.serve()
                if (srv.lockedOut) {
                    _receiver.value = ReceiverState.Failed("Receiving stopped after repeated connections with a wrong pairing code.")
                }
            } finally {
                srv.close()
                identity.destroy()
                token.fill(0)
                server = null
            }
        }
    }

    fun answerOffer(accept: Boolean) {
        offerDecision?.complete(accept)
        offerDecision = null
        if (!accept) {
            _receiver.value = ReceiverState.Failed("You declined the transfer. Start receiving again to get a new code.")
            server?.close()
        }
    }

    fun stopReceiving() {
        offerDecision?.complete(false)
        server?.close()
        receiverJob?.cancel()
        _receiver.value = ReceiverState.Idle
    }

    // ---- Sending ------------------------------------------------------------------------------

    /** Returns false if [linkText] is not a valid pairing code. */
    fun startSending(linkText: String): Boolean {
        val link = PairingLink.parse(linkText) ?: return false
        if (senderJob?.isActive == true) return true
        lastLink = link
        lastTransferId = UUID.randomUUID().toString()
        lastSource = null
        runSender(isResume = false)
        return true
    }

    fun resumeSending() {
        if (senderJob?.isActive == true || lastLink == null) return
        runSender(isResume = true)
    }

    fun answerSend(confirm: Boolean) {
        sendDecision?.complete(confirm)
        sendDecision = null
    }

    fun cancelSending() {
        sendDecision?.complete(false)
        client?.abort()
        senderJob?.cancel()
        _sender.value = SenderState.Idle
    }

    private fun runSender(isResume: Boolean) {
        val link = lastLink ?: return
        val transferId = lastTransferId ?: return
        senderJob = scope.launch {
            _sender.value = SenderState.Connecting
            val source = lastSource ?: ArchiveTransferSource.snapshot(db).also { lastSource = it }
            devices.localDeviceId()
            val device = devices.localDevice.first()
            val c = TransferClient(link, devices.localDeviceId(), device?.name ?: "SMS Bridge", source).also { client = it }
            _sender.value = try {
                val outcome = c.run(
                    transferId = transferId,
                    isResume = isResume,
                    confirm = { receiverName, total ->
                        val decision = CompletableDeferred<Boolean>().also { sendDecision = it }
                        _sender.value = SenderState.Confirm(receiverName, total)
                        decision.await()
                    },
                    onWaitingForReceiver = { _sender.value = SenderState.WaitingForReceiver },
                    onProgress = { sent, total -> _sender.value = SenderState.Sending(sent, total) },
                )
                AppLog.i(TAG, "sent transfer: restored=${outcome.restored} duplicates=${outcome.duplicates}")
                SenderState.Finished(outcome)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransferException) {
                SenderState.Failed(e.message ?: "The transfer failed.", canResume = e.code !in setOf(ErrorCode.AUTH, ErrorCode.DECLINED))
            } catch (e: SSLException) {
                AppLog.w(TAG, "TLS failure", e)
                SenderState.Failed("Secure connection failed. The phone at this address is not the one whose QR code you scanned.", canResume = false)
            } catch (e: IOException) {
                AppLog.w(TAG, "connection failure", e)
                SenderState.Failed("Could not reach the other phone, or the connection was lost. Check that both are on the same Wi-Fi.", canResume = true)
            } finally {
                client = null
            }
        }
    }

    /** IPv4 address on the active Wi-Fi/Ethernet network, or null if not on one. */
    private fun localWifiAddress(): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null
        return cm.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }

    private companion object {
        const val TAG = "Transfer"
    }
}
