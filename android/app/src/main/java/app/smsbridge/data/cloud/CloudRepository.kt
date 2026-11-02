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
import kotlinx.coroutines.sync.withLock
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

data class PendingApproval(
    val deviceId: String,
    val name: String,
    val safetyCode: String,
    internal val encPublic: ByteArray,
    internal val signPublic: ByteArray = ByteArray(0),
    /** The browser asked to be confirmed by number on a phone. */
    val confirmRequested: Boolean = false,
    val isPhone: Boolean = false,
    /** Set when this is a browser signing in (not an already registered device). */
    val loginRequestId: String? = null,
)

/** An open number-matching prompt on this phone ([loginRequestId]: a browser signing in by username). */
data class ConfirmPrompt(val request: PendingApproval, val options: List<Int>, internal val correct: Int, val loginRequestId: String? = null)

/** A new SMS received by another phone on the account (listener mode). */
data class RemoteSms(val deviceName: String, val address: String?, val body: String, val date: Long)

/** This phone waits for its owner to tap [number] in the browser. */
data class ConnectWait(val server: String, val requestId: String, val secret: String, val number: Int)

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
        var deviceId = cloudDeviceId()
        val name = devices.localDevice.first()?.name ?: "Android device"
        val token = try {
            CloudApi(server).register(pairingCode, deviceId, name, keys.encPublic, keys.signPublic)
        } catch (e: CloudHttpException) {
            if (e.code != "device_unavailable") throw e
            // This phone was connected to another account before: join with a fresh identity.
            // (A refused registration does not use up the pairing code.)
            deviceId = UUID.randomUUID().toString()
            CloudApi(server).register(pairingCode, deviceId, name, keys.encPublic, keys.signPublic)
        }
        saveConnection(server, deviceId, token, keys)

        val status = api().vaultStatus()
        rememberCanStartOver(status)
        AppLog.i(TAG, "registered with server; vault exists=${status.exists}")
        if (!status.exists) return@io createVault()
        refresh()
    }

    /**
     * No account yet, and the user scanned a browser's QR code: like WhatsApp, this phone gets its
     * own account on that server (no password) and becomes its first, approved device.
     */
    private suspend fun startOwnAccount(serverUrl: String): CloudState {
        val server = CloudApi.normalize(serverUrl)
        val keys = DeviceKeys.generate()
        val deviceId = UUID.randomUUID().toString()
        val name = devices.localDevice.first()?.name ?: "Android device"
        val token = CloudApi(server).startPhone(deviceId, name, keys.encPublic, keys.signPublic)
        saveConnection(server, deviceId, token, keys)
        AppLog.i(TAG, "created this phone's own account")
        return createVault()
    }

    private suspend fun saveConnection(server: String, deviceId: String, token: String, keys: DeviceKeys) {
        settings.putString(K_CLOUD_DEVICE_ID, deviceId)
        settings.deletePrefix(PREFIX)
        settings.deletePrefix(K_REVOKED_NOTICE)
        settings.putString(K_SERVER, server)
        settings.putString(K_LAST_SERVER, server)
        putSecret(K_TOKEN, token.toByteArray())
        putSecret(K_ENC_PRIVATE, keys.encPrivate)
        putSecret(K_SIGN_PRIVATE, keys.signPrivate)
        settings.putString(K_ENC_PUBLIC, b64(keys.encPublic))
        settings.putString(K_SIGN_PUBLIC, b64(keys.signPublic))
    }

    /**
     * First phone: create the vault at once. The recovery key is made and kept on this phone
     * (viewable later in Sync) but never put in the user's way.
     */
    private suspend fun createVault(): CloudState {
        putSecret(vkKey(1), CloudCrypto.newVaultKey())
        settings.putString(K_VK_CURRENT, "1")
        putSecret(K_PENDING_RECOVERY, CloudCrypto.newRecoveryKey())
        return runCatching { confirmRecoverySaved(remindLater = true) }.getOrElse { refresh() }
    }

    /**
     * Acts on a scanned dashboard QR code: connects with its pairing code, and/or approves the browser
     * it names (now if this phone is approved, otherwise as soon as it becomes approved). Returns a
     * short message for the user.
     */
    suspend fun handleLink(link: PairLink): String = io {
        var connected = false
        if (refresh() is CloudState.NotConnected) {
            val code = link.pairingCode ?: throw IllegalArgumentException("Connect this phone first: scan the QR code on the dashboard's Devices page.")
            connect(link.server, code)
            connected = true
            val secret = link.linkSecret
            if (secret != null && refresh() is CloudState.WaitingForApproval) {
                // "Add another phone": the browser that shows the code checks this proof and approves us.
                val keys = loadDeviceKeys()!!
                api().linkProof(PairLinks.linkProof(secret, keys.encPublic, keys.signPublic))
                return@io "Added. Finishing on your computer…"
            }
        } else if (link.linkSecret != null) {
            throw IllegalArgumentException("This phone is already linked. To add it to another account, tap Disconnect below first, then scan again.")
        }
        if (!link.approvesBrowser) return@io "Connected."
        if (refresh() is CloudState.Ready) {
            "Approved ${approveScanned(link.deviceId!!, link.fingerprint!!)}."
        } else {
            settings.putString(K_SCAN_APPROVAL, "${link.deviceId}|${b64(link.fingerprint!!)}")
            if (connected) "Connected. The browser opens once setup finishes." else "The browser will be approved once this phone is approved."
        }
    }

    /** Approves the pending device with this id if its keys match the scanned fingerprint. Returns its name. */
    suspend fun approveScanned(deviceId: String, fingerprint: ByteArray): String = io {
        val request = pendingApprovals().firstOrNull { it.deviceId == deviceId }
            ?: throw IllegalArgumentException("That browser is not waiting for approval any more. Reload the dashboard and scan again.")
        if (!PairLinks.matches(fingerprint, request.encPublic, request.signPublic)) {
            // The server lists different keys than the browser showed: never approve.
            AppLog.w(TAG, "scanned fingerprint does not match the server's keys; not approving")
            throw IllegalArgumentException("Security check failed: this browser's keys do not match the QR code. It was not approved.")
        }
        approve(request)
        request.name
    }

    // ---- Number matching -------------------------------------------------------------------

    /**
     * Opens the prompt for a browser that asked to be confirmed: sends a fresh random challenge
     * (the browser turns it into the number it shows) and returns the three numbers to offer.
     */
    suspend fun startConfirm(request: PendingApproval): ConfirmPrompt = io {
        val salt = ConfirmNumbers.newSalt()
        api().setChallenge(request.deviceId, salt)
        val correct = ConfirmNumbers.number(salt, PairLinks.fingerprint(request.encPublic, request.signPublic))
        ConfirmPrompt(request, ConfirmNumbers.options(correct), correct)
    }

    /** The user tapped [picked]: the right number approves; anything else declines. True if approved. */
    suspend fun finishConfirm(prompt: ConfirmPrompt, picked: Int?): Boolean = io {
        val login = prompt.loginRequestId
        when {
            picked == prompt.correct && login != null -> { approveLogin(login, prompt.request); true }
            picked == prompt.correct -> { approve(prompt.request); true }
            login != null -> { api().loginDecline(login); false }
            else -> { api().decline(prompt.request.deviceId); false }
        }
    }

    // ---- Signing browsers in ----------------------------------------------------------------

    /** A scanned sign-in QR code: checks the browser's keys against the scanned fingerprint and signs it in. */
    suspend fun approveScannedLogin(link: LoginLink): String = io {
        if (refresh() is CloudState.NotConnected) startOwnAccount(link.server)
        if (refresh() !is CloudState.Ready) throw IllegalArgumentException("This phone is still waiting for approval. Finish that first, then scan the code again.")
        val ask = api().loginRequest(link.requestId)
        if (!PairLinks.matches(link.fingerprint, ask.encPublic, ask.signPublic)) {
            AppLog.w(TAG, "sign-in fingerprint does not match the server's keys; not approving")
            throw IllegalArgumentException("Security check failed: this browser's keys do not match the QR code. It was not signed in.")
        }
        approveLogin(link.requestId, ask.toApproval())
        ask.name
    }

    /** Browsers that typed this account's username and wait for a number confirmation. */
    suspend fun loginAsks(): List<PendingApproval> = io {
        if (refresh() !is CloudState.Ready) return@io emptyList()
        api().loginRequests().map { it.toApproval(loginId = it.id) }
    }

    suspend fun startLoginConfirm(ask: PendingApproval): ConfirmPrompt = io {
        val loginId = ask.loginRequestId ?: throw IllegalArgumentException("Not a sign-in request.")
        val salt = ConfirmNumbers.newSalt()
        api().loginChallenge(loginId, salt)
        val correct = ConfirmNumbers.number(salt, PairLinks.fingerprint(ask.encPublic, ask.signPublic))
        ConfirmPrompt(ask, ConfirmNumbers.options(correct), correct, loginId)
    }

    /** Wraps every vault key version for the browser's new device id and approves the sign-in. */
    private suspend fun approveLogin(loginId: String, request: PendingApproval) {
        val current = settings.getString(K_VK_CURRENT)?.toIntOrNull() ?: throw IllegalStateException("no vault key")
        val wrapped = (1..current).map { v -> WrappedKeyDto(v, CloudCrypto.wrapForDevice(secret(vkKey(v))!!, request.encPublic, request.deviceId, v)) }
        api().loginApprove(loginId, wrapped)
        AppLog.i(TAG, "signed a browser in")
    }

    private fun LoginAskDto.toApproval(loginId: String? = null) =
        PendingApproval(deviceId, name, CloudCrypto.safetyCode(encPublic, signPublic), encPublic, signPublic, confirmRequested = true, isPhone = false, loginRequestId = loginId)

    /** This waiting phone asks to be confirmed by number on an approved phone. */
    suspend fun requestConfirmSelf() = io { api().requestConfirm() }

    /** The number this waiting phone shows once an approved phone opened the prompt; null until then. */
    suspend fun myConfirmNumber(): Int? = io {
        val salt = api().vaultStatus().challenge ?: return@io null
        val keys = loadDeviceKeys() ?: return@io null
        ConfirmNumbers.number(salt, PairLinks.fingerprint(keys.encPublic, keys.signPublic))
    }

    /** Asks to join [username]'s account without a QR code; the owner then taps [ConnectWait.number] in the browser. */
    suspend fun requestConnect(serverUrl: String, username: String): ConnectWait = io {
        val server = CloudApi.normalize(serverUrl)
        val name = devices.localDevice.first()?.name ?: "Android device"
        val r = CloudApi(server).createConnectRequest(username.trim(), name)
        settings.putString(K_LAST_SERVER, server)
        ConnectWait(server, r.requestId, r.secret, r.number)
    }

    /**
     * Checks a connect request. Connects (as with a pairing code) once the owner tapped the right
     * number and returns true; false while waiting; throws if it was refused or expired.
     */
    suspend fun checkConnect(wait: ConnectWait): Boolean = io {
        val (status, code) = CloudApi(wait.server).connectRequestStatus(wait.requestId, wait.secret)
        when (status) {
            "pending" -> false
            "approved" -> {
                connect(wait.server, code ?: throw IllegalArgumentException("The server did not send a pairing code. Try again."))
                true
            }
            "rejected" -> throw IllegalArgumentException("The number tapped in the browser did not match. Try again.")
            else -> throw IllegalArgumentException("The request expired. Try again.")
        }
    }

    /** Waits (up to [timeoutSeconds]) for any change on the account; returns the new counter. */
    suspend fun waitForNews(after: Long, timeoutSeconds: Int): Pair<Long, Int> = io { api().waitForNews(after, timeoutSeconds) }

    // ---- Listener mode -----------------------------------------------------------------------

    private val remoteLock = kotlinx.coroutines.sync.Mutex()

    suspend fun isListening(): Boolean = settings.getString(K_LISTEN) == "true"

    /** Turns listener mode on (fetching what is already on the server) or off (forgetting it on this phone). */
    suspend fun setListening(on: Boolean) = io {
        if (on) {
            settings.putString(K_LISTEN, "true")
            pullRemote()
        } else {
            settings.deletePrefix(K_LISTEN)
            settings.deletePrefix(K_REMOTE_AFTER)
            db.remoteMessageDao().clear()
        }
        Unit
    }

    /**
     * Downloads and decrypts new events from the account's other phones into the remote list.
     * Returns SMS received by them that are new to this phone (for notifications).
     */
    suspend fun pullRemote(): List<RemoteSms> = io {
        if (!isListening() || refresh() !is CloudState.Ready) return@io emptyList()
        remoteLock.withLock {
            val me = cloudDeviceId()
            val api = api()
            val names by lazy { runCatching { api.accountDevices() }.getOrDefault(emptyMap()) }
            val keys = HashMap<Int, ByteArray?>()
            val dao = db.remoteMessageDao()
            val fresh = mutableListOf<RemoteSms>()
            var after = settings.getString(K_REMOTE_AFTER)?.toLongOrNull() ?: 0L
            try {
                while (true) {
                    val page = api.events(after, 500)
                    if (page.isEmpty()) break
                    for (e in page) {
                        if (e.deviceId == me) continue
                        val vk = keys.getOrPut(e.keyVersion) { secret(vkKey(e.keyVersion)) } ?: continue
                        val json = runCatching {
                            org.json.JSONObject(String(CloudCrypto.decryptEvent(vk, e.ciphertext, e.eventId, e.deviceId, e.deviceSequence, e.keyVersion), Charsets.UTF_8))
                        }.getOrNull() ?: continue // tampered or not ours: never shown
                        val type = json.optString("type")
                        val messageId = json.optString("messageId").takeIf { it.isNotEmpty() && it != "null" } ?: continue
                        if (type !in REMOTE_TYPES || !json.has("body")) continue
                        val key = "${e.deviceId}:$messageId"
                        val previous = dao.sequenceOf(key)
                        if (previous != null && previous >= e.serverSequence) continue
                        val address = json.optString("address").takeIf { it.isNotEmpty() && it != "null" }
                        val box = json.optString("box", "UNKNOWN")
                        dao.upsert(
                            app.smsbridge.core.database.entity.RemoteMessageEntity(
                                key, e.deviceId, address, json.getString("body"), json.optLong("date"), box, e.serverSequence,
                            ),
                        )
                        if (previous == null && box == "INBOX") {
                            fresh += RemoteSms(names[e.deviceId] ?: "another phone", address, json.getString("body"), json.optLong("date"))
                        }
                    }
                    after = page.last().serverSequence
                    settings.putString(K_REMOTE_AFTER, after.toString())
                }
            } finally {
                keys.values.forEach { it?.fill(0) }
            }
            fresh
        }
    }

    /** A browser scanned before this phone was approved: approve it now (best effort). */
    private suspend fun approveRememberedScan() {
        val saved = settings.getString(K_SCAN_APPROVAL) ?: return
        settings.deletePrefix(K_SCAN_APPROVAL)
        val (id, fp) = saved.split('|').takeIf { it.size == 2 } ?: return
        runCatching { approveScanned(id, unb64(fp)) }.onFailure { AppLog.w(TAG, "could not approve the scanned browser", it) }
    }

    /** The recovery key the user chose to save later, formatted, or null. */
    suspend fun recoveryReminder(): String? = io { secret(K_RECOVERY_REMINDER)?.let { CloudCrypto.formatRecoveryKey(it) } }

    suspend fun dismissRecoveryReminder() = io { settings.deletePrefix(K_RECOVERY_REMINDER) }

    /** The user confirmed the recovery key is written down: upload the wrapped copies and become trusted. */
    suspend fun confirmRecoverySaved(remindLater: Boolean = false): CloudState = io {
        val recovery = secret(K_PENDING_RECOVERY) ?: return@io refresh()
        val keys = loadDeviceKeys()!!
        val deviceId = cloudDeviceId()
        val vk = secret(vkKey(1))!!
        val wrappedRecovery = WrappedKeyDto(1, CloudCrypto.wrapForRecovery(vk, recovery, 1))
        val wrappedSelf = WrappedKeyDto(1, CloudCrypto.wrapForDevice(vk, keys.encPublic, deviceId, 1))
        if (settings.getString(K_RESET) == "true") api().vaultReset(wrappedRecovery, wrappedSelf) else api().vaultInit(wrappedRecovery, wrappedSelf)
        // "Remind me later": keep the key (Keystore-encrypted, like every secret here) until the user saves it.
        if (remindLater) putSecret(K_RECOVERY_REMINDER, recovery)
        recovery.fill(0)
        settings.deletePrefix(K_PENDING_RECOVERY)
        settings.deletePrefix(K_RESET)
        becomeTrusted()
    }

    /** Polls the server for keys a trusted device has wrapped for us. */
    suspend fun checkApproval(): CloudState = io {
        val keys = loadDeviceKeys() ?: return@io refresh()
        val deviceId = cloudDeviceId()
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
        val deviceId = cloudDeviceId()
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
        api().pendingDevices().map {
            PendingApproval(it.id, it.name, CloudCrypto.safetyCode(it.encPublic, it.signPublic), it.encPublic, it.signPublic, it.confirmRequested, it.platform != "web")
        }
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
        // No recovery-key screen: the new key is kept on this phone, viewable later in Sync.
        runCatching { confirmRecoverySaved(remindLater = true) }.getOrElse { refresh() }
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
            deviceId = cloudDeviceId(),
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
        approveRememberedScan()
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

    /**
     * The id this phone uses on the server. It starts as the archive's device id (so phones connected
     * before this existed keep theirs) and changes only when that id belongs to another account.
     */
    private suspend fun cloudDeviceId(): String = settings.getString(K_CLOUD_DEVICE_ID) ?: devices.localDeviceId()

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
        const val K_SCAN_APPROVAL = "cloud.scan_approval"
        const val K_LISTEN = "cloud.listen"
        const val K_REMOTE_AFTER = "cloud.remote_after"
        private val REMOTE_TYPES = setOf("SMS_RECEIVED", "SMS_SENT", "SMS_STATUS_CHANGED", "SMS_IMPORTED")
        const val K_RECOVERY_REMINDER = "cloud.recovery_reminder"
        const val K_FULL_HISTORY = "cloud.full_history"

        /** The first upload covers the latest day; the rest of the archive only on request. */
        const val INITIAL_SYNC_WINDOW_MS = 24L * 60 * 60 * 1000
        // Outside PREFIX on purpose: they survive a disconnect.
        const val K_REVOKED_NOTICE = "cloudnotice.revoked"
        const val K_LAST_SERVER = "cloudnotice.last_server"
        const val K_CLOUD_DEVICE_ID = "cloudnotice.device_id"
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
