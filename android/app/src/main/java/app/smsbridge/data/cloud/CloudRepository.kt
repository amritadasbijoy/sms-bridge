package app.smsbridge.data.cloud

import androidx.room.withTransaction
import app.smsbridge.core.common.AppLog
import app.smsbridge.core.common.TimeSource
import app.smsbridge.core.database.SmsBridgeDatabase
import app.smsbridge.core.database.entity.SyncOperationEntity
import app.smsbridge.core.model.PendingSyncEvent
import app.smsbridge.core.model.SyncEventType
import app.smsbridge.core.model.SyncState
import app.smsbridge.data.settings.AppSettingsRepository
import app.smsbridge.data.settings.LocalDeviceRepository
import app.smsbridge.data.sync.SyncScheduler
import app.smsbridge.data.sync.SyncTransport
import app.smsbridge.data.sync.SyncUploadResult
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed interface CloudState {
    data object NotConnected : CloudState
    /** Vault created locally; the user must write down the recovery key before it is uploaded. */
    data class NeedsRecoverySetup(val server: String, val recoveryKey: String) : CloudState
    /** Registered, but no vault key yet: approve from a trusted device, or enter the recovery key. */
    /** [canStartOver]: no approved device is left on the account, so nobody can approve this phone. */
    data class WaitingForApproval(val server: String, val safetyCode: String, val canStartOver: Boolean = false) : CloudState
    data class Ready(val server: String, val safetyCode: String) : CloudState
}

data class HistoryStatus(val uploadedFrom: Long?, val fullHistoryQueued: Boolean)

data class PendingApproval(val deviceId: String, val name: String, val safetyCode: String, internal val encPublic: ByteArray)

/**
 * The phone's side of docs/sync-design.md. Secrets (device private keys, vault keys, device token,
 * a not-yet-confirmed recovery key) are stored only after [KeyProtector] encryption.
 */
@Singleton
class CloudRepository @Inject constructor(
    private val settings: AppSettingsRepository,
    private val protector: KeyProtector,
    private val devices: LocalDeviceRepository,
    private val db: SmsBridgeDatabase,
    private val scheduler: SyncScheduler,
    private val time: TimeSource,
) {
    private val _state = MutableStateFlow<CloudState>(CloudState.NotConnected)
    val state: StateFlow<CloudState> = _state.asStateFlow()

    suspend fun refresh(): CloudState = io {
        val server = settings.getString(K_SERVER)
        val keys = loadDeviceKeys()
        val next = when {
            server == null || keys == null || settings.getString(K_TOKEN) == null -> CloudState.NotConnected
            settings.getString(K_PENDING_RECOVERY) != null ->
                CloudState.NeedsRecoverySetup(server, CloudCrypto.formatRecoveryKey(secret(K_PENDING_RECOVERY)!!))
            settings.getString(K_TRUSTED) == "true" -> CloudState.Ready(server, CloudCrypto.safetyCode(keys.encPublic, keys.signPublic))
            else -> CloudState.WaitingForApproval(
                server, CloudCrypto.safetyCode(keys.encPublic, keys.signPublic), settings.getString(K_CAN_START_OVER) == "true",
            )
        }
        _state.value = next
        next
    }

    suspend fun isReady(): Boolean = refresh() is CloudState.Ready

    /** Registers with a pairing code from the web dashboard, then either creates the vault or waits for approval. */
    suspend fun connect(serverUrl: String, pairingCode: String): CloudState = io {
        val server = CloudApi.normalize(serverUrl)
        val keys = DeviceKeys.generate()
        val deviceId = devices.localDeviceId()
        val name = devices.localDevice.first()?.name ?: "Android device"
        val token = CloudApi(server).register(pairingCode, deviceId, name, keys.encPublic, keys.signPublic)
        settings.deletePrefix(PREFIX)
        settings.deletePrefix(K_REVOKED_NOTICE)
        settings.putString(K_SERVER, server)
        settings.putString(K_LAST_SERVER, server)
        putSecret(K_TOKEN, token.toByteArray())
        putSecret(K_ENC_PRIVATE, keys.encPrivate)
        putSecret(K_SIGN_PRIVATE, keys.signPrivate)
        settings.putString(K_ENC_PUBLIC, b64(keys.encPublic))
        settings.putString(K_SIGN_PUBLIC, b64(keys.signPublic))

        val status = api().vaultStatus()
        if (!status.exists) {
            // First phone: create VK v1 locally; nothing goes to the server until the recovery key is saved.
            putSecret(vkKey(1), CloudCrypto.newVaultKey())
            settings.putString(K_VK_CURRENT, "1")
            putSecret(K_PENDING_RECOVERY, CloudCrypto.newRecoveryKey())
        }
        rememberCanStartOver(status)
        AppLog.i(TAG, "registered with server; vault exists=${status.exists}")
        refresh()
    }

    /** The user confirmed the recovery key is written down: upload the wrapped copies and become trusted. */
    suspend fun confirmRecoverySaved(): CloudState = io {
        val recovery = secret(K_PENDING_RECOVERY) ?: return@io refresh()
        val keys = loadDeviceKeys()!!
        val deviceId = devices.localDeviceId()
        val vk = secret(vkKey(1))!!
        val wrappedRecovery = WrappedKeyDto(1, CloudCrypto.wrapForRecovery(vk, recovery, 1))
        val wrappedSelf = WrappedKeyDto(1, CloudCrypto.wrapForDevice(vk, keys.encPublic, deviceId, 1))
        if (settings.getString(K_RESET) == "true") api().vaultReset(wrappedRecovery, wrappedSelf) else api().vaultInit(wrappedRecovery, wrappedSelf)
        recovery.fill(0)
        settings.deletePrefix(K_PENDING_RECOVERY)
        settings.deletePrefix(K_RESET)
        becomeTrusted()
    }

    /** Polls the server for keys a trusted device has wrapped for us. */
    suspend fun checkApproval(): CloudState = io {
        val keys = loadDeviceKeys() ?: return@io refresh()
        val deviceId = devices.localDeviceId()
        val wrapped = try {
            api().myKeys()
        } catch (e: CloudHttpException) {
            if (e.status == 401) return@io handleRevoked()
            throw e
        }
        if (wrapped.isEmpty()) {
            rememberCanStartOver(api().vaultStatus())
            return@io refresh()
        }
        val unwrapped = try {
            wrapped.associate { it.keyVersion to CloudCrypto.unwrapForDevice(it.wrappedKey, keys, deviceId, it.keyVersion) }
        } catch (e: java.security.GeneralSecurityException) {
            // Not wrapped for this phone's current key (e.g. left over from before a re-pairing):
            // still waiting for a real approval.
            AppLog.w(TAG, "vault keys on the server are not for this phone's current key", e)
            return@io refresh()
        }
        storeVaultKeys(unwrapped)
        becomeTrusted()
    }

    /** Recovers every vault key version with the recovery key, then registers copies wrapped for this device. */
    suspend fun recover(recoveryText: String): CloudState = io {
        val recovery = CloudCrypto.parseRecoveryKey(recoveryText) ?: throw IllegalArgumentException("That is not a valid recovery key.")
        val keys = loadDeviceKeys()!!
        val deviceId = devices.localDeviceId()
        val vaultKeys = try {
            api().recoveryKeys().associate { it.keyVersion to CloudCrypto.unwrapForRecovery(it.wrappedKey, recovery, it.keyVersion) }
        } catch (e: java.security.GeneralSecurityException) {
            throw IllegalArgumentException("That recovery key does not unlock this account's vault.")
        } finally {
            recovery.fill(0)
        }
        api().recovered(vaultKeys.map { (v, vk) -> WrappedKeyDto(v, CloudCrypto.wrapForDevice(vk, keys.encPublic, deviceId, v)) })
        storeVaultKeys(vaultKeys)
        becomeTrusted()
    }

    suspend fun pendingApprovals(): List<PendingApproval> = io {
        api().pendingDevices().map { PendingApproval(it.id, it.name, CloudCrypto.safetyCode(it.encPublic, it.signPublic), it.encPublic) }
    }

    /** Call only after the user confirmed both screens show the same safety code. */
    suspend fun approve(request: PendingApproval) = io {
        val current = settings.getString(K_VK_CURRENT)!!.toInt()
        val wrapped = (1..current).map { v -> WrappedKeyDto(v, CloudCrypto.wrapForDevice(secret(vkKey(v))!!, request.encPublic, request.deviceId, v)) }
        api().approve(request.deviceId, wrapped)
        AppLog.i(TAG, "approved a new device for the vault")
    }

    /**
     * No approved device is left, so nobody can approve this phone: start a new vault from this
     * phone, as if it were the first one. The server deletes its old encrypted copy when the new
     * recovery key is confirmed; this phone's messages are then uploaded again. Local messages are untouched.
     */
    suspend fun startOver(): CloudState = io {
        putSecret(vkKey(1), CloudCrypto.newVaultKey())
        settings.putString(K_VK_CURRENT, "1")
        putSecret(K_PENDING_RECOVERY, CloudCrypto.newRecoveryKey())
        settings.putString(K_RESET, "true")
        settings.deletePrefix(K_SNAPSHOT_DONE)
        settings.deletePrefix(K_CAN_START_OVER)
        AppLog.i(TAG, "starting over with a new vault")
        refresh()
    }

    private suspend fun rememberCanStartOver(status: VaultStatusDto) {
        if (status.exists && status.approvedDevices == 0 && !status.trusted) settings.putString(K_CAN_START_OVER, "true")
        else settings.deletePrefix(K_CAN_START_OVER)
    }

    /**
     * The server no longer accepts this phone's token (revoked on the Devices page). Drop the
     * connection so the Sync tab says so instead of showing "connected" while uploads fail.
     */
    suspend fun handleRevoked(): CloudState = io {
        settings.deletePrefix(PREFIX)
        settings.putString(K_REVOKED_NOTICE, "true")
        AppLog.i(TAG, "server no longer accepts this phone; disconnected")
        refresh()
    }

    /** True once after this phone was found revoked; cleared by the next connect. */
    suspend fun wasRevoked(): Boolean = settings.getString(K_REVOKED_NOTICE) == "true"

    /** The server address last connected to, to prefill the connect form. */
    suspend fun lastServer(): String? = settings.getString(K_LAST_SERVER)

    /** Forgets the server connection and keys on this phone. The local archive is untouched. */
    suspend fun disconnect(): CloudState = io {
        settings.deletePrefix(PREFIX)
        refresh()
    }

    /** Everything an upload needs, or null if this phone is not ready to sync. */
    internal suspend fun uploadContext(): UploadContext? = io {
        if (refresh() !is CloudState.Ready) return@io null
        val version = settings.getString(K_VK_CURRENT)?.toIntOrNull() ?: return@io null
        UploadContext(
            api = api(),
            deviceId = devices.localDeviceId(),
            keyVersion = version,
            vaultKey = secret(vkKey(version)) ?: return@io null,
            signPrivate = secret(K_SIGN_PRIVATE) ?: return@io null,
        )
    }

    private suspend fun becomeTrusted(): CloudState {
        settings.putString(K_TRUSTED, "true")
        if (settings.getString(K_SNAPSHOT_DONE) == null) {
            // Recent messages only by default; older history is uploaded on request (uploadFullHistory).
            val since = time.nowMillis() - INITIAL_SYNC_WINDOW_MS
            settings.putString(K_HISTORY_FROM, since.toString())
            val queued = enqueueSnapshot(since, before = false)
            settings.putString(K_SNAPSHOT_DONE, "true")
            AppLog.i(TAG, "queued initial snapshot of $queued recent messages")
        }
        scheduler.scheduleSync()
        return refresh()
    }

    /** Whether messages older than the first upload's window are on the server too. */
    suspend fun historyStatus(): HistoryStatus = io {
        val from = settings.getString(K_HISTORY_FROM)?.toLongOrNull()
        HistoryStatus(uploadedFrom = from, fullHistoryQueued = settings.getString(K_FULL_HISTORY) == "true")
    }

    /** Queues every archived message older than the first upload's window. */
    suspend fun uploadFullHistory(): Int = io {
        if (refresh() !is CloudState.Ready) return@io 0
        val from = settings.getString(K_HISTORY_FROM)?.toLongOrNull() ?: return@io 0
        if (settings.getString(K_FULL_HISTORY) == "true") return@io 0
        val queued = enqueueSnapshot(from, before = true)
        settings.putString(K_FULL_HISTORY, "true")
        AppLog.i(TAG, "queued $queued older messages")
        scheduler.scheduleSync()
        queued
    }

    /**
     * Queues one SMS_IMPORTED event per archived message dated from [since] (or, with [before],
     * older than it), newest first: the queue uploads in this order, so recent messages arrive first.
     */
    private suspend fun enqueueSnapshot(since: Long, before: Boolean): Int {
        val dao = db.smsMessageDao()
        val max = dao.maxRowId() ?: return 0
        val from = if (before) Long.MIN_VALUE else since
        val until = if (before) since else Long.MAX_VALUE
        var cursorDate = Long.MAX_VALUE
        var cursorRow = Long.MAX_VALUE
        var total = 0
        while (true) {
            val page = dao.newestFirstPage(max, from, until, cursorDate, cursorRow, 500)
            if (page.isEmpty()) break
            db.withTransaction {
                val now = time.nowMillis()
                page.forEach { row ->
                    db.syncOperationDao().insert(
                        SyncOperationEntity(UUID.randomUUID().toString(), row.id, SyncEventType.SMS_IMPORTED, settings.nextDeviceSequence(), SyncState.PENDING, 0, now, now, null),
                    )
                }
            }
            cursorDate = page.last().date
            cursorRow = page.last().rowId
            total += page.size
        }
        return total
    }

    private suspend fun storeVaultKeys(keys: Map<Int, ByteArray>) {
        keys.forEach { (v, vk) -> putSecret(vkKey(v), vk) }
        settings.putString(K_VK_CURRENT, keys.keys.max().toString())
    }

    private suspend fun api(): CloudApi = CloudApi(settings.getString(K_SERVER)!!, secret(K_TOKEN)?.let { String(it) })

    private suspend fun loadDeviceKeys(): DeviceKeys? {
        val encPublic = settings.getString(K_ENC_PUBLIC) ?: return null
        val signPublic = settings.getString(K_SIGN_PUBLIC) ?: return null
        return DeviceKeys(secret(K_ENC_PRIVATE) ?: return null, unb64(encPublic), secret(K_SIGN_PRIVATE) ?: return null, unb64(signPublic))
    }

    private suspend fun putSecret(key: String, value: ByteArray) = settings.putString(key, b64(protector.protect(value)))
    private suspend fun secret(key: String): ByteArray? = settings.getString(key)?.let { protector.unprotect(unb64(it)) }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    internal class UploadContext(val api: CloudApi, val deviceId: String, val keyVersion: Int, val vaultKey: ByteArray, val signPrivate: ByteArray)

    private companion object {
        const val TAG = "Cloud"
        const val PREFIX = "cloud."
        const val K_SERVER = "cloud.server"
        const val K_TOKEN = "cloud.token"
        const val K_ENC_PRIVATE = "cloud.enc_private"
        const val K_SIGN_PRIVATE = "cloud.sign_private"
        const val K_ENC_PUBLIC = "cloud.enc_public"
        const val K_SIGN_PUBLIC = "cloud.sign_public"
        const val K_VK_CURRENT = "cloud.vk_current"
        const val K_TRUSTED = "cloud.trusted"
        const val K_SNAPSHOT_DONE = "cloud.snapshot_done"
        const val K_PENDING_RECOVERY = "cloud.pending_recovery"
        const val K_RESET = "cloud.reset_pending"
        const val K_HISTORY_FROM = "cloud.history_from"
        const val K_FULL_HISTORY = "cloud.full_history"

        /** The first upload covers the latest day; the rest of the archive only on request. */
        const val INITIAL_SYNC_WINDOW_MS = 24L * 60 * 60 * 1000
        // Outside PREFIX on purpose: they survive a disconnect.
        const val K_REVOKED_NOTICE = "cloudnotice.revoked"
        const val K_LAST_SERVER = "cloudnotice.last_server"
        const val K_CAN_START_OVER = "cloud.can_start_over"
        fun vkKey(version: Int) = "cloud.vk.$version"
        fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
        fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)
    }
}

/** Uploads queued events, encrypted with the vault key and signed with the device key. */
class HttpSyncTransport @Inject constructor(
    private val cloud: CloudRepository,
    private val db: SmsBridgeDatabase,
) : SyncTransport {

    override suspend fun isConfigured(): Boolean = cloud.isReady()

    override suspend fun upload(events: List<PendingSyncEvent>): SyncUploadResult {
        val ctx = cloud.uploadContext() ?: return SyncUploadResult.RetryableFailure("not_configured")
        return try {
            val dtos = withContext(Dispatchers.IO) {
                events.map { e ->
                    val payload = payloadFor(e).toByteArray(Charsets.UTF_8)
                    val ciphertext = CloudCrypto.encryptEvent(ctx.vaultKey, payload, e.eventId, ctx.deviceId, e.deviceSequence, ctx.keyVersion)
                    val signature = CloudCrypto.sign(ctx.signPrivate, CloudCrypto.signedMessage(e.eventId, ctx.deviceId, e.deviceSequence, ctx.keyVersion, ciphertext))
                    UploadEventDto(e.eventId, e.deviceSequence, ctx.keyVersion, ciphertext, signature)
                }
            }
            SyncUploadResult.Acknowledged(withContext(Dispatchers.IO) { ctx.api.upload(dtos) })
        } catch (e: CloudHttpException) {
            when (e.status) {
                401 -> {
                    cloud.handleRevoked()
                    SyncUploadResult.PermanentFailure("unauthorized")
                }
                403 -> SyncUploadResult.PermanentFailure("not_trusted")
                400, 409 -> SyncUploadResult.PermanentFailure("rejected_${e.code}")
                else -> SyncUploadResult.RetryableFailure("http_${e.status}")
            }
        } catch (e: IOException) {
            SyncUploadResult.RetryableFailure("network")
        } finally {
            ctx.vaultKey.fill(0)
            ctx.signPrivate.fill(0)
        }
    }

    /** The encrypted payload: the event type and the message's current state (docs/sync-design.md). */
    private suspend fun payloadFor(e: PendingSyncEvent): String {
        val m = e.messageId?.let { db.smsMessageDao().getById(it) }
        val o = JSONObject().put("v", 1).put("type", e.eventType.name).put("messageId", e.messageId ?: JSONObject.NULL)
        if (m != null) {
            o.put("address", m.address ?: JSONObject.NULL)
                .put("body", m.body)
                .put("date", m.date)
                .put("dateSent", m.dateSent)
                .put("box", m.type.name)
                .put("read", m.read)
                .put("seen", m.seen)
                .put("status", m.status ?: JSONObject.NULL)
                .put("sub", m.subscriptionId ?: JSONObject.NULL)
                .put("src", m.sourceDeviceId ?: JSONObject.NULL)
        }
        return o.toString()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CloudModule {
    @Binds
    abstract fun bindSyncTransport(impl: HttpSyncTransport): SyncTransport

    companion object {
        @Provides
        @Singleton
        fun provideKeyProtector(): KeyProtector = KeystoreKeyProtector()
    }
}
