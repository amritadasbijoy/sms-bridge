package app.smsbridge.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.Serializable

// ---- Browser devices --------------------------------------------------------------------------
//
// A signed-in browser registers its own X25519/Ed25519 public keys as a device (platform "web").
// It is approved exactly like a second phone: a trusted phone compares the safety code and wraps
// the vault key for the browser's key (HPKE). The browser's private key never leaves the browser.

@Serializable data class RegisterBrowser(val name: String, val encPublicKey: String, val signPublicKey: String)
@Serializable data class BrowserDevice(val deviceId: String)
@Serializable data class BrowserKeys(
    val trusted: Boolean, val currentVersion: Int?, val keys: List<WrappedKey>,
    /** The approving phone's number-matching challenge, once it opened the prompt. */
    val challenge: String? = null,
)

private const val MAX_PENDING_BROWSERS = 5

class BrowserDeviceService(private val db: Db, private val wake: Notices = Notices()) {
    suspend fun register(userId: UUID, body: RegisterBrowser, ip: String): BrowserDevice {
        requireRawKey(body.encPublicKey)
        requireRawKey(body.signPublicKey)
        val name = body.name.trim().take(64).ifEmpty { "Web browser" }
        val id = UUID.randomUUID()
        db.tx {
            val pending = query(
                "SELECT count(*) FROM devices WHERE user_id = ? AND platform = 'web' AND vault_trusted_at IS NULL AND revoked_at IS NULL",
                userId,
            ) { it.getLong(1) }.single()
            if (pending >= MAX_PENDING_BROWSERS) {
                throw ApiException(HttpStatusCode.Conflict, "too_many_pending", "Too many browsers are waiting for approval. Approve or revoke them on the Devices page.")
            }
            update(
                "INSERT INTO devices (id, user_id, name, platform, enc_public_key, sign_public_key, last_seen_at) VALUES (?, ?, ?, 'web', ?, ?, now())",
                id, userId, name, body.encPublicKey, body.signPublicKey,
            )
            audit(userId, id, "browser_registered", "ok", ip)
        }
        wake.bump(userId)
        return BrowserDevice(id.toString())
    }

    /** Only an approved browser of [userId] may see and approve waiting phones. */
    suspend fun requireTrusted(userId: UUID, deviceId: UUID) {
        val ok = db.tx {
            query(
                "SELECT 1 FROM devices WHERE id = ? AND user_id = ? AND platform = 'web' AND vault_trusted_at IS NOT NULL AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now())",
                deviceId, userId,
            ) { 1 }.isNotEmpty()
        }
        if (!ok) throw ApiException(HttpStatusCode.Forbidden, "not_trusted", "This browser is not approved.")
    }

    /** Vault keys wrapped for this browser; 404 once it is revoked or not this user's. */
    suspend fun keys(userId: UUID, deviceId: UUID): BrowserKeys = db.tx {
        val trusted = query(
            "SELECT vault_trusted_at FROM devices WHERE id = ? AND user_id = ? AND platform = 'web' AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now())",
            deviceId, userId,
        ) { it.time("vault_trusted_at") != null }.singleOrNull()
            ?: throw ApiException(HttpStatusCode.NotFound, "unknown_browser", "This browser is not registered or was revoked.")
        update("UPDATE devices SET last_seen_at = now() WHERE id = ?", deviceId)
        val version = query("SELECT current_version FROM vaults WHERE user_id = ?", userId) { it.getInt(1) }.singleOrNull()
        val keys = if (!trusted) emptyList() else query(
            "SELECT key_version, wrapped_key FROM vault_keys WHERE user_id = ? AND recipient = ? ORDER BY key_version",
            userId, deviceId.toString(),
        ) { WrappedKey(it.getInt(1), Base64.getEncoder().encodeToString(it.getBytes(2))) }
        val challenge = query("SELECT confirm_salt FROM devices WHERE id = ? AND confirm_requested_at IS NOT NULL", deviceId) {
            it.getBytes(1)?.let(Base64.getEncoder()::encodeToString)
        }.singleOrNull()
        BrowserKeys(trusted, version, keys, challenge)
    }
}

// ---- Invites ----------------------------------------------------------------------------------

@Serializable data class InviteCode(val code: String, val expiresAt: String)
@Serializable data class InviteInfo(val createdAt: String, val expiresAt: String, val used: Boolean, val usedAt: String?)

val INVITE_LIFETIME: Duration = Duration.ofDays(7)
private const val MAX_OPEN_INVITES = 10

class InviteService(private val db: Db) {
    suspend fun create(userId: UUID, ip: String): InviteCode {
        val code = Secrets.pairingCode()
        val expires = OffsetDateTime.now().plus(INVITE_LIFETIME)
        db.tx {
            val open = query("SELECT count(*) FROM invites WHERE created_by = ? AND used_at IS NULL AND expires_at > now()", userId) { it.getLong(1) }.single()
            if (open >= MAX_OPEN_INVITES) throw ApiException(HttpStatusCode.Conflict, "too_many_invites", "You have $MAX_OPEN_INVITES unused invites already.")
            update("INSERT INTO invites (code_hash, created_by, expires_at) VALUES (?, ?, ?)", Secrets.sha256(Secrets.normalizePairingCode(code)), userId, expires)
            audit(userId, null, "invite_created", "ok", ip)
        }
        return InviteCode(code, expires.toString())
    }

    suspend fun list(userId: UUID): List<InviteInfo> = db.tx {
        query("SELECT created_at, expires_at, used_at FROM invites WHERE created_by = ? ORDER BY created_at DESC LIMIT 50", userId) {
            InviteInfo(it.time("created_at").toString(), it.time("expires_at").toString(), it.time("used_at") != null, it.time("used_at")?.toString())
        }
    }
}

/** Consumes an invite inside the registration transaction. True if it was valid and unused. */
fun java.sql.Connection.consumeInvite(code: String?, newUserId: UUID): Boolean {
    if (code.isNullOrBlank()) return false
    return query(
        "UPDATE invites SET used_at = now(), used_by = ? WHERE code_hash = ? AND used_at IS NULL AND expires_at > now() RETURNING 1",
        newUserId, Secrets.sha256(Secrets.normalizePairingCode(code)),
    ) { 1 }.isNotEmpty()
}

fun Route.browserAndInviteRoutes(auth: AuthService, browsers: BrowserDeviceService, invites: InviteService, sync: SyncService) {
    route("/browser/devices") {
        post {
            val user = auth.requireUser(call)
            val body = call.receive<RegisterBrowser>()
            val named = body.copy(name = body.name.ifBlank { browserName(call.request.header(HttpHeaders.UserAgent)) })
            call.respond(HttpStatusCode.Created, browsers.register(user.first, named, call.clientIp()))
        }
        get("/{id}/keys") {
            val user = auth.requireUser(call)
            val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "unknown_browser", "This browser is not registered or was revoked.")
            call.respond(browsers.keys(user.first, id))
        }
        // "Add another phone": the approved browser checks a new phone's link proof and wraps the vault key for it.
        get("/{id}/pending") {
            val user = auth.requireUser(call).first
            val id = call.uuidParam("id")
            browsers.requireTrusted(user, id)
            call.respond(sync.pendingFor(user))
        }
        post("/{id}/approve/{target}") {
            val user = auth.requireUser(call).first
            val id = call.uuidParam("id")
            browsers.requireTrusted(user, id)
            sync.approveBy(user, id, call.uuidParam("target"), call.receive(), call.clientIp())
            call.respond(HttpStatusCode.NoContent)
        }
    }
    route("/invites") {
        get { call.respond(invites.list(auth.requireUser(call).first)) }
        post {
            val user = auth.requireUser(call)
            call.respond(HttpStatusCode.Created, invites.create(user.first, call.clientIp()))
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.uuidParam(name: String): UUID =
    runCatching { UUID.fromString(parameters[name]) }.getOrNull() ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "Not found.")

/** A readable default name for a browser device, e.g. "Chrome on macOS". */
fun browserName(userAgent: String?): String {
    val ua = userAgent.orEmpty()
    val browser = when {
        "Edg/" in ua -> "Edge"; "Firefox/" in ua -> "Firefox"; "Chrome/" in ua -> "Chrome"; "Safari/" in ua -> "Safari"; else -> "Browser"
    }
    val os = when {
        "Android" in ua -> "Android"; "iPhone" in ua || "iPad" in ua -> "iOS"; "Mac OS X" in ua -> "macOS"; "Windows" in ua -> "Windows"; "Linux" in ua -> "Linux"; else -> null
    }
    return if (os != null) "$browser on $os" else browser
}
