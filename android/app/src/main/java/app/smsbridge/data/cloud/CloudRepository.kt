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
    data class WaitingForApproval(val server: String, val safetyCode: String) : CloudState
    data class Ready(val server: String, val safetyCode: String) : CloudState
}

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
            else -> CloudState.WaitingForApproval(server, CloudCrypto.safetyCode(keys.encPublic, keys.signPublic))
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
        settings.putString(K_SERVER, server)
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
        AppLog.i(TAG, "registered with server; vault exists=${status.exists}")
        refresh()
    }

    /** The user confirmed the recovery key is written down: upload the wrapped copies and become trusted. */
    suspend fun confirmRecoverySaved(): CloudState = io {
        val recovery = secret(K_PENDING_RECOVERY) ?: return@io refresh()
        val keys = loadDeviceKeys()!!
        val deviceId = devices.localDeviceId()
        val vk = secret(vkKey(1))!!
        api().vaultInit(
            recovery = WrappedKeyDto(1, CloudCrypto.wrapForRecovery(vk, recovery, 1)),
            self = WrappedKeyDto(1, CloudCrypto.wrapForDevice(vk, keys.encPublic, deviceId, 1)),
        )
        recovery.fill(0)
        settings.deletePrefix(K_PENDING_RECOVERY)
        becomeTrusted()
    }

    /** Polls the server for keys a trusted device has wrapped for us. */
    suspend fun checkApproval(): CloudState = io {
        val keys = loadDeviceKeys() ?: return@io refresh()
        val deviceId = devices.localDeviceId()
        val wrapped = api().myKeys()
        if (wrapped.isEmpty()) return@io refresh()
        storeVaultKeys(wrapped.associate { it.keyVersion to CloudCrypto.unwrapForDevice(it.wrappedKey, keys, deviceId, it.keyVersion) })
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
            val queued = enqueueSnapshot()
            settings.putString(K_SNAPSHOT_DONE, "true")
            AppLog.i(TAG, "queued initial snapshot of $queued messages")
        }
        scheduler.scheduleSync()
        return refresh()
    }

    /** Queues one SMS_IMPORTED event per archived message (the initial upload), in pages. */
    private suspend fun enqueueSnapshot(): Int {
        val dao = db.smsMessageDao()
        val max = dao.maxRowId() ?: return 0
        var after = 0L
        var total = 0
        while (true) {
            val ids = dao.idPage(after, max, 500)
            if (ids.isEmpty()) break
            db.withTransaction {
                val now = time.nowMillis()
                ids.forEach { id ->
                    db.syncOperationDao().insert(
                        SyncOperationEntity(UUID.randomUUID().toString(), id, SyncEventType.SMS_IMPORTED, settings.nextDeviceSequence(), SyncState.PENDING, 0, now, now, null),
                    )
                }
            }
            after = dao.rowIdOf(ids.last()) ?: break
            total += ids.size
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
                401 -> SyncUploadResult.PermanentFailure("unauthorized")
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
