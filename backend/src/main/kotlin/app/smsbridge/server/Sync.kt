package app.smsbridge.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.serialization.Serializable

// ---- API types (docs/sync-design.md) ---------------------------------------------------------

@Serializable data class VaultStatus(
    val exists: Boolean,
    val currentVersion: Int?,
    val deviceTrusted: Boolean,
    /** Approved devices on this account; 0 with an existing vault means only the recovery key or a reset can help. */
    val approvedDevices: Int = 0,
)
@Serializable data class WrappedKey(val keyVersion: Int, val wrappedKey: String)
@Serializable data class VaultInit(val recoveryKeys: List<WrappedKey>, val selfKeys: List<WrappedKey>)
@Serializable data class VaultKeys(val currentVersion: Int, val keys: List<WrappedKey>)
@Serializable data class SelfKeys(val selfKeys: List<WrappedKey>)
@Serializable data class PendingDevice(val id: String, val name: String, val platform: String, val encPublicKey: String, val signPublicKey: String, val createdAt: String)
@Serializable data class Approve(val keys: List<WrappedKey>)
@Serializable data class UploadEvent(val eventId: String, val deviceSequence: Long, val keyVersion: Int, val ciphertext: String, val signature: String)
@Serializable data class UploadBatch(val events: List<UploadEvent>)
@Serializable data class Ack(val eventId: String, val serverSequence: Long)
@Serializable data class UploadResult(val acked: List<Ack>)
@Serializable data class StoredEvent(
    val eventId: String, val deviceId: String, val deviceSequence: Long, val serverSequence: Long,
    val keyVersion: Int, val ciphertext: String, val signature: String,
)
@Serializable data class EventsPage(val events: List<StoredEvent>, val latest: Long)
@Serializable data class StreamNotice(val latest: Long)

private val b64 = Base64.getDecoder()
private val b64e = Base64.getEncoder()

fun requireRawKey(value: String): ByteArray =
    runCatching { b64.decode(value) }.getOrNull()?.takeIf { it.size == 32 }
        ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_key", "Keys must be 32 raw bytes, base64.")

/** The exact bytes a device signs for each event (docs/sync-design.md). */
fun signedMessage(eventId: String, deviceId: String, deviceSequence: Long, keyVersion: Int, ciphertext: ByteArray): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256").digest(ciphertext).joinToString("") { "%02x".format(it) }
    return "sbsync-v1|$eventId|$deviceId|$deviceSequence|$keyVersion|$digest".toByteArray(Charsets.UTF_8)
}

fun verifyEd25519(rawPublicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = runCatching {
    // X.509 SubjectPublicKeyInfo prefix for Ed25519 (RFC 8410) + raw key.
    val spki = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00) + rawPublicKey
    val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spki))
    Signature.getInstance("Ed25519").run { initVerify(key); update(message); verify(signature) }
}.getOrDefault(false)

data class DeviceContext(val deviceId: UUID, val userId: UUID, val trusted: Boolean, val signKey: String?, val encKey: String?)

private const val MAX_BATCH = 500
private const val MAX_CIPHERTEXT = 64 * 1024

class SyncService(private val db: Db) {
    /** Content-free notifications: (userId, latest server sequence). */
    val notices = MutableSharedFlow<Pair<UUID, Long>>(extraBufferCapacity = 64)

    suspend fun device(call: ApplicationCall): DeviceContext {
        val header = call.request.header(HttpHeaders.Authorization)?.takeIf { it.startsWith("Bearer ") }
            ?: throw ApiException(HttpStatusCode.Unauthorized, "unauthorized", "Sign in again.")
        val hash = Secrets.sha256(header.removePrefix("Bearer ").trim())
        return db.tx {
            val ctx = query(
                """
                SELECT d.id, d.user_id, d.vault_trusted_at, d.sign_public_key, d.enc_public_key
                FROM device_tokens t JOIN devices d ON d.id = t.device_id
                WHERE t.token_hash = ? AND t.revoked_at IS NULL AND d.revoked_at IS NULL
                """.trimIndent(),
                hash,
            ) { DeviceContext(it.uuid("id"), it.uuid("user_id"), it.time("vault_trusted_at") != null, it.getString("sign_public_key"), it.getString("enc_public_key")) }
                .singleOrNull() ?: throw ApiException(HttpStatusCode.Unauthorized, "unauthorized", "This device is not registered or was revoked.")
            update("UPDATE devices SET last_seen_at = now() WHERE id = ?", ctx.deviceId)
            ctx
        }
    }

    private fun DeviceContext.requireTrusted() {
        if (!trusted) throw ApiException(HttpStatusCode.Forbidden, "not_trusted", "Approve this device from a trusted device first.")
    }

    suspend fun status(d: DeviceContext): VaultStatus = db.tx {
        val version = query("SELECT current_version FROM vaults WHERE user_id = ?", d.userId) { it.getInt(1) }.singleOrNull()
        VaultStatus(version != null, version, d.trusted, approvedDevices(d.userId))
    }

    /**
     * Starting over when no approved device is left: the server's encrypted copy (events and wrapped
     * keys) is deleted and this device creates a new vault, exactly like the first phone. Refused
     * while any approved device exists, so it cannot be used to take over a working account.
     */
    suspend fun reset(d: DeviceContext, body: VaultInit, ip: String) {
        val recovery = body.recoveryKeys.singleOrNull()?.takeIf { it.keyVersion == 1 }
        val self = body.selfKeys.singleOrNull()?.takeIf { it.keyVersion == 1 }
        if (recovery == null || self == null || d.encKey == null || d.signKey == null) {
            throw ApiException(HttpStatusCode.BadRequest, "invalid_vault", "A new vault needs version-1 recovery and device keys, and registered device keys.")
        }
        db.tx {
            update("LOCK TABLE vaults IN SHARE ROW EXCLUSIVE MODE")
            if (approvedDevices(d.userId) > 0) {
                throw ApiException(HttpStatusCode.Conflict, "approved_device_exists", "Another device is still approved. Approve this one from it instead.")
            }
            update("DELETE FROM sync_events WHERE user_id = ?", d.userId)
            update("DELETE FROM vault_keys WHERE user_id = ?", d.userId)
            update("DELETE FROM vaults WHERE user_id = ?", d.userId)
            update("INSERT INTO vaults (user_id, current_version) VALUES (?, 1)", d.userId)
            putKey(d.userId, 1, "recovery", recovery.wrappedKey, d.deviceId)
            putKey(d.userId, 1, d.deviceId.toString(), self.wrappedKey, d.deviceId)
            update("UPDATE devices SET vault_trusted_at = now() WHERE id = ?", d.deviceId)
            audit(d.userId, d.deviceId, "vault_reset", "ok", ip)
        }
        notices.tryEmit(d.userId to 0L)
    }

    private fun java.sql.Connection.approvedDevices(userId: UUID): Int =
        query("SELECT count(*) FROM devices WHERE user_id = ? AND vault_trusted_at IS NOT NULL AND revoked_at IS NULL", userId) { it.getInt(1) }.single()

    suspend fun init(d: DeviceContext, body: VaultInit, ip: String) {
        val recovery = body.recoveryKeys.singleOrNull()?.takeIf { it.keyVersion == 1 }
        val self = body.selfKeys.singleOrNull()?.takeIf { it.keyVersion == 1 }
        if (recovery == null || self == null || d.encKey == null || d.signKey == null) {
            throw ApiException(HttpStatusCode.BadRequest, "invalid_vault", "A new vault needs version-1 recovery and device keys, and registered device keys.")
        }
        db.tx {
            update("LOCK TABLE vaults IN SHARE ROW EXCLUSIVE MODE")
            if (query("SELECT 1 FROM vaults WHERE user_id = ?", d.userId) { 1 }.isNotEmpty()) {
                throw ApiException(HttpStatusCode.Conflict, "vault_exists", "This account already has a vault. Approve this device from a trusted device or use the recovery key.")
            }
            update("INSERT INTO vaults (user_id, current_version) VALUES (?, 1)", d.userId)
            putKey(d.userId, 1, "recovery", recovery.wrappedKey, d.deviceId)
            putKey(d.userId, 1, d.deviceId.toString(), self.wrappedKey, d.deviceId)
            update("UPDATE devices SET vault_trusted_at = now() WHERE id = ?", d.deviceId)
            audit(d.userId, d.deviceId, "vault_created", "ok", ip)
        }
    }

    suspend fun keysFor(d: DeviceContext, recipient: String): VaultKeys = db.tx {
        val version = query("SELECT current_version FROM vaults WHERE user_id = ?", d.userId) { it.getInt(1) }.singleOrNull()
            ?: throw ApiException(HttpStatusCode.NotFound, "no_vault", "This account has no vault yet.")
        val keys = query(
            "SELECT key_version, wrapped_key FROM vault_keys WHERE user_id = ? AND recipient = ? ORDER BY key_version",
            d.userId, recipient,
        ) { WrappedKey(it.getInt(1), b64e.encodeToString(it.getBytes(2))) }
        VaultKeys(version, keys)
    }

    /** After unwrapping the recovery copy locally, a phone stores copies wrapped for itself and becomes trusted. */
    suspend fun recovered(d: DeviceContext, body: SelfKeys, ip: String) = db.tx {
        val version = query("SELECT current_version FROM vaults WHERE user_id = ?", d.userId) { it.getInt(1) }.singleOrNull()
            ?: throw ApiException(HttpStatusCode.NotFound, "no_vault", "This account has no vault yet.")
        requireAllVersions(body.selfKeys, version)
        body.selfKeys.forEach { putKey(d.userId, it.keyVersion, d.deviceId.toString(), it.wrappedKey, d.deviceId) }
        update("UPDATE devices SET vault_trusted_at = now() WHERE id = ?", d.deviceId)
        audit(d.userId, d.deviceId, "vault_recovered", "ok", ip)
    }

    suspend fun pending(d: DeviceContext): List<PendingDevice> {
        d.requireTrusted()
        return db.tx {
            query(
                """
                SELECT id, name, platform, enc_public_key, sign_public_key, created_at FROM devices
                WHERE user_id = ? AND vault_trusted_at IS NULL AND revoked_at IS NULL
                  AND enc_public_key IS NOT NULL AND sign_public_key IS NOT NULL
                ORDER BY created_at
                """.trimIndent(),
                d.userId,
            ) { PendingDevice(it.uuid("id").toString(), it.getString("name"), it.getString("platform"), it.getString("enc_public_key"), it.getString("sign_public_key"), it.time("created_at").toString()) }
        }
    }

    suspend fun approve(d: DeviceContext, target: UUID, body: Approve, ip: String) {
        d.requireTrusted()
        db.tx {
            val pending = query(
                "SELECT 1 FROM devices WHERE id = ? AND user_id = ? AND vault_trusted_at IS NULL AND revoked_at IS NULL AND enc_public_key IS NOT NULL",
                target, d.userId,
            ) { 1 }.isNotEmpty()
            if (!pending) throw ApiException(HttpStatusCode.NotFound, "not_found", "No such device is waiting for approval.")
            val version = query("SELECT current_version FROM vaults WHERE user_id = ?", d.userId) { it.getInt(1) }.single()
            requireAllVersions(body.keys, version)
            body.keys.forEach { putKey(d.userId, it.keyVersion, target.toString(), it.wrappedKey, d.deviceId) }
            update("UPDATE devices SET vault_trusted_at = now() WHERE id = ?", target)
            audit(d.userId, target, "vault_device_approved", "ok", ip)
        }
    }

    suspend fun upload(d: DeviceContext, batch: UploadBatch): UploadResult {
        d.requireTrusted()
        if (batch.events.isEmpty()) return UploadResult(emptyList())
        if (batch.events.size > MAX_BATCH) throw ApiException(HttpStatusCode.BadRequest, "batch_too_large", "At most $MAX_BATCH events per request.")
        val signKey = b64.decode(d.signKey ?: throw ApiException(HttpStatusCode.Forbidden, "no_key", "This device has no signing key."))
        val deviceId = d.deviceId.toString()
        val parsed = batch.events.map { e ->
            val id = runCatching { UUID.fromString(e.eventId) }.getOrNull()
                ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_event", "Invalid event id.")
            val ciphertext = runCatching { b64.decode(e.ciphertext) }.getOrNull()?.takeIf { it.size in 29..MAX_CIPHERTEXT }
                ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_event", "Invalid ciphertext.")
            val signature = runCatching { b64.decode(e.signature) }.getOrNull()?.takeIf { it.size == 64 }
                ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_event", "Invalid signature.")
            if (e.deviceSequence <= 0) throw ApiException(HttpStatusCode.BadRequest, "invalid_event", "Invalid sequence.")
            if (!verifyEd25519(signKey, signedMessage(e.eventId, deviceId, e.deviceSequence, e.keyVersion, ciphertext), signature)) {
                throw ApiException(HttpStatusCode.BadRequest, "bad_signature", "An event signature does not verify.")
            }
            Triple(e, id, ciphertext to signature)
        }
        val acks = db.tx {
            val version = query("SELECT current_version FROM vaults WHERE user_id = ?", d.userId) { it.getInt(1) }.single()
            parsed.map { (e, id, bytes) ->
                if (e.keyVersion !in 1..version) throw ApiException(HttpStatusCode.BadRequest, "invalid_event", "Unknown key version.")
                update(
                    """
                    INSERT INTO sync_events (event_id, user_id, device_id, device_sequence, event_type, key_version, ciphertext, signature)
                    VALUES (?, ?, ?, ?, 'opaque', ?, ?, ?) ON CONFLICT (event_id) DO NOTHING
                    """.trimIndent(),
                    id, d.userId, d.deviceId, e.deviceSequence, e.keyVersion, bytes.first, bytes.second,
                )
                // Idempotent: a re-sent event returns its original sequence; an id owned by another device is refused.
                val row = query("SELECT device_id, server_sequence FROM sync_events WHERE event_id = ?", id) { it.uuid("device_id") to it.getLong("server_sequence") }.single()
                if (row.first != d.deviceId) throw ApiException(HttpStatusCode.Conflict, "event_conflict", "Event id already used by another device.")
                Ack(e.eventId, row.second)
            }
        }
        acks.maxOfOrNull { it.serverSequence }?.let { notices.tryEmit(d.userId to it) }
        return UploadResult(acks)
    }

    suspend fun page(userId: UUID, after: Long, limit: Int): EventsPage = db.tx {
        val events = query(
            """
            SELECT event_id, device_id, device_sequence, server_sequence, key_version, ciphertext, signature
            FROM sync_events WHERE user_id = ? AND server_sequence > ? ORDER BY server_sequence LIMIT ?
            """.trimIndent(),
            userId, after, limit.coerceIn(1, 1000),
        ) {
            StoredEvent(
                it.uuid("event_id").toString(), it.uuid("device_id").toString(), it.getLong("device_sequence"),
                it.getLong("server_sequence"), it.getInt("key_version"),
                b64e.encodeToString(it.getBytes("ciphertext")), b64e.encodeToString(it.getBytes("signature")),
            )
        }
        val latest = query("SELECT COALESCE(MAX(server_sequence), 0) FROM sync_events WHERE user_id = ?", userId) { it.getLong(1) }.single()
        EventsPage(events, latest)
    }

    private fun java.sql.Connection.putKey(userId: UUID, version: Int, recipient: String, wrapped: String, by: UUID) {
        val bytes = runCatching { b64.decode(wrapped) }.getOrNull()?.takeIf { it.size in 32..512 }
            ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_key", "Invalid wrapped key.")
        update(
            """
            INSERT INTO vault_keys (user_id, key_version, recipient, wrapped_key, created_by) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (user_id, key_version, recipient) DO UPDATE SET wrapped_key = EXCLUDED.wrapped_key, created_by = EXCLUDED.created_by, created_at = now()
            """.trimIndent(),
            userId, version, recipient, bytes, by,
        )
    }

    private fun requireAllVersions(keys: List<WrappedKey>, current: Int) {
        if (keys.map { it.keyVersion }.sorted() != (1..current).toList()) {
            throw ApiException(HttpStatusCode.BadRequest, "invalid_keys", "Every key version 1..$current must be provided exactly once.")
        }
    }
}

fun Route.vaultAndSyncRoutes(auth: AuthService, sync: SyncService) {
    route("/vault") {
        get { call.respond(sync.status(sync.device(call))) }
        post("/init") {
            val d = sync.device(call)
            sync.init(d, call.receive(), call.request.local.remoteHost)
            call.respond(HttpStatusCode.Created, sync.status(d.copy(trusted = true)))
        }
        post("/reset") {
            val d = sync.device(call)
            sync.reset(d, call.receive(), call.request.local.remoteHost)
            call.respond(HttpStatusCode.Created, sync.status(d.copy(trusted = true)))
        }
        get("/keys") {
            val d = sync.device(call)
            call.respond(sync.keysFor(d, d.deviceId.toString()))
        }
        get("/recovery") {
            val d = sync.device(call)
            call.respond(sync.keysFor(d, "recovery"))
        }
        post("/recovered") {
            val d = sync.device(call)
            sync.recovered(d, call.receive(), call.request.local.remoteHost)
            call.respond(HttpStatusCode.NoContent)
        }
        get("/requests") { call.respond(sync.pending(sync.device(call))) }
        post("/requests/{id}/approve") {
            val d = sync.device(call)
            val target = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "No such device.")
            sync.approve(d, target, call.receive(), call.request.local.remoteHost)
            call.respond(HttpStatusCode.NoContent)
        }
    }
    route("/sync") {
        post("/events") {
            val d = sync.device(call)
            call.respond(sync.upload(d, call.receive()))
        }
        get("/events") {
            // A trusted device, or a signed-in browser (Phase 7).
            val userId = if (call.request.header(HttpHeaders.Authorization) != null) {
                sync.device(call).also { if (!it.trusted) throw ApiException(HttpStatusCode.Forbidden, "not_trusted", "Device not approved.") }.userId
            } else {
                auth.requireUser(call).first
            }
            val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0L
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 500
            call.respond(sync.page(userId, after, limit))
        }
        webSocket("/stream") {
            val userId = if (call.request.header(HttpHeaders.Authorization) != null) sync.device(call).userId else auth.requireUser(call).first
            val latest = sync.page(userId, Long.MAX_VALUE, 1).latest
            send(Frame.Text("""{"latest":$latest}"""))
            sync.notices.filter { it.first == userId }.collect { send(Frame.Text("""{"latest":${it.second}}""")) }
        }
    }
}
