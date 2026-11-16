package app.smsbridge.server

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
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.Serializable

// ---- Sign in by phone ---------------------------------------------------------------------------
//
// Like WhatsApp Web: the browser shows a QR code for its sign-in request; an approved phone scans
// it, checks the browser's key fingerprint, wraps the vault key for it and approves. The browser
// then receives a session and is already approved to read messages. Without a camera, the browser
// targets a username and the user taps the matching number on their phone.

@Serializable data class LoginRequestBody(
    val name: String,
    val encPublicKey: String,
    val signPublicKey: String,
    /** "Sign in with your username": that account's phones are notified and confirm by number. */
    val username: String? = null,
)
@Serializable data class LoginRequestCreated(
    val requestId: String, val secret: String, val expiresAt: String,
    /** 8 digits to type into the phone instead of scanning (QR requests only). */
    val code: String? = null,
)
@Serializable data class LoginRequestStatus(val status: String, val challenge: String? = null, val deviceId: String? = null, val userId: String? = null)
@Serializable data class LoginRequestForPhone(
    val id: String, val deviceId: String, val name: String, val encPublicKey: String, val signPublicKey: String, val createdAt: String,
)
@Serializable data class StartAccount(val invite: String? = null)

val LOGIN_REQUEST_LIFETIME: Duration = Duration.ofMinutes(5)

class LoginRequestService(private val db: Db, private val auth: AuthService, private val notices: Notices) {
    private val b64 = Base64.getEncoder()

    suspend fun create(body: LoginRequestBody, ip: String): LoginRequestCreated {
        requireRawKey(body.encPublicKey)
        requireRawKey(body.signPublicKey)
        val id = UUID.randomUUID()
        val secret = Secrets.token()
        val expires = OffsetDateTime.now().plus(LOGIN_REQUEST_LIFETIME)
        var code: String? = null
        val target = db.tx {
            val user = body.username?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
                query("SELECT id FROM users WHERE lower(username) = lower(?)", name) { it.uuid("id") }.singleOrNull()
            }
            if (body.username == null) {
                code = generateSequence { Secrets.digits(8) }.first { candidate ->
                    query("SELECT 1 FROM login_requests WHERE short_code = ? AND status = 'pending' AND expires_at > now()", candidate) { 1 }.isEmpty()
                }
            }
            update(
                """
                INSERT INTO login_requests (id, secret_hash, user_id, device_id, name, enc_public_key, sign_public_key, expires_at, short_code)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                id, Secrets.sha256(secret), user, UUID.randomUUID(), body.name.trim().take(64).ifEmpty { "Web browser" },
                body.encPublicKey, body.signPublicKey, expires, code,
            )
            audit(user, null, "login_requested", if (body.username != null && user == null) "unknown_user" else "ok", ip)
            user
        }
        target?.let(notices::bump)
        // Same answer whether or not the username exists.
        return LoginRequestCreated(id.toString(), secret, expires.toString(), code)
    }

    /**
     * Polled by the browser. Once a phone approved it, the session token is handed out exactly
     * once (the route turns it into the cookie).
     */
    suspend fun poll(id: UUID, secret: String): Pair<LoginRequestStatus, String?> = db.tx {
        val row = query(
            "SELECT status, challenge, session_token, device_id, user_id, expires_at < now() AS expired FROM login_requests WHERE id = ? AND secret_hash = ?",
            id, Secrets.sha256(secret),
        ) {
            listOf(
                it.getString("status"), it.getBytes("challenge")?.let(b64::encodeToString), it.getString("session_token"),
                it.uuid("device_id").toString(), it.getBoolean("expired"), it.getObject("user_id")?.toString(),
            )
        }
            .singleOrNull() ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "Unknown request.")
        val status = row[0] as String
        when {
            status == "approved" && row[2] != null -> {
                update("UPDATE login_requests SET status = 'delivered', session_token = NULL WHERE id = ?", id)
                LoginRequestStatus("approved", deviceId = row[3] as String, userId = row[5] as String?) to row[2] as String
            }
            status == "pending" && row[4] as Boolean -> LoginRequestStatus("expired") to null
            else -> LoginRequestStatus(status, challenge = row[1] as String?) to null
        }
    }

    /** For a phone that scanned the QR code (any pending request), or one of its account's targeted requests. */
    suspend fun forPhone(d: DeviceContext, id: UUID): LoginRequestForPhone {
        if (!d.trusted) throw ApiException(HttpStatusCode.Forbidden, "not_trusted", "Approve this phone first.")
        return db.tx {
            query(
                """
                SELECT id, device_id, name, enc_public_key, sign_public_key, created_at FROM login_requests
                WHERE id = ? AND status = 'pending' AND expires_at > now() AND (user_id IS NULL OR user_id = ?)
                """.trimIndent(),
                id, d.userId,
            ) { it.toForPhone() }.singleOrNull()
        } ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "That sign-in request has expired. Reload the page in the browser.")
    }

    /** Requests that typed this account's username (number matching), for the phone's notifications. */
    suspend fun targeted(d: DeviceContext): List<LoginRequestForPhone> {
        if (!d.trusted) return emptyList()
        return db.tx {
            query(
                """
                SELECT id, device_id, name, enc_public_key, sign_public_key, created_at FROM login_requests
                WHERE user_id = ? AND status = 'pending' AND expires_at > now() ORDER BY created_at
                """.trimIndent(),
                d.userId,
            ) { it.toForPhone() }
        }
    }

    suspend fun setChallenge(d: DeviceContext, id: UUID, salt: String) {
        val bytes = runCatching { Base64.getDecoder().decode(salt) }.getOrNull()?.takeIf { it.size == 16 }
            ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_challenge", "Invalid challenge.")
        forPhone(d, id)
        db.tx { update("UPDATE login_requests SET challenge = ? WHERE id = ? AND user_id = ?", bytes, id, d.userId) }
    }

    suspend fun decline(d: DeviceContext, id: UUID) {
        db.tx { update("UPDATE login_requests SET status = 'declined' WHERE id = ? AND status = 'pending' AND (user_id IS NULL OR user_id = ?)", id, d.userId) }
    }

    /** The phone approves: the browser becomes an approved device of the phone's account and gets a session. */
    suspend fun approve(d: DeviceContext, id: UUID, body: Approve, ip: String, userAgent: String?) {
        val request = forPhone(d, id)
        val deviceId = UUID.fromString(request.deviceId)
        val token = auth.createSession(d.userId, userAgent)
        db.tx {
            val version = query("SELECT current_version FROM vaults WHERE user_id = ?", d.userId) { it.getInt(1) }.single()
            if (body.keys.map { it.keyVersion }.sorted() != (1..version).toList()) {
                throw ApiException(HttpStatusCode.BadRequest, "invalid_keys", "Every key version 1..$version must be provided exactly once.")
            }
            val claimed = update(
                "UPDATE login_requests SET status = 'approved', user_id = ?, session_token = ? WHERE id = ? AND status = 'pending' AND expires_at > now()",
                d.userId, token, id,
            ) > 0
            if (!claimed) throw ApiException(HttpStatusCode.NotFound, "not_found", "That sign-in request has expired.")
            update(
                """
                INSERT INTO devices (id, user_id, name, platform, enc_public_key, sign_public_key, vault_trusted_at, last_seen_at, expires_at)
                VALUES (?, ?, ?, 'web', ?, ?, now(), now(), ?)
                """.trimIndent(),
                deviceId, d.userId, request.name, request.encPublicKey, request.signPublicKey,
                body.expiresInDays?.takeIf { it in 1..365 }?.let { OffsetDateTime.now().plusDays(it.toLong()) },
            )
            body.keys.forEach { k ->
                val bytes = runCatching { Base64.getDecoder().decode(k.wrappedKey) }.getOrNull()?.takeIf { it.size in 32..512 }
                    ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_key", "Invalid wrapped key.")
                update(
                    "INSERT INTO vault_keys (user_id, key_version, recipient, wrapped_key, created_by) VALUES (?, ?, ?, ?, ?)",
                    d.userId, k.keyVersion, deviceId.toString(), bytes, d.deviceId,
                )
            }
            audit(d.userId, deviceId, "login_approved_by_phone", "ok", ip)
        }
        notices.bump(d.userId)
    }

    private fun java.sql.ResultSet.toForPhone() = LoginRequestForPhone(
        uuid("id").toString(), uuid("device_id").toString(), getString("name"), getString("enc_public_key"),
        getString("sign_public_key"), time("created_at").toString(),
    )
}

fun Route.loginRoutes(sync: SyncService, logins: LoginRequestService, onApproved: suspend ApplicationCall.(String) -> Unit) {
    get("/auth/login-requests/{id}") {
        val secret = call.request.header(REQUEST_SECRET_HEADER) ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "Unknown request.")
        val (status, token) = logins.poll(call.loginId(), secret)
        if (token != null) call.onApproved(token)
        call.respond(status)
    }
    route("/vault/login-requests") {
        get { call.respond(logins.targeted(sync.device(call))) }
        get("/{id}") { call.respond(logins.forPhone(sync.device(call), call.loginId())) }
        post("/{id}/challenge") {
            logins.setChallenge(sync.device(call), call.loginId(), call.receive<Challenge>().salt)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/{id}/decline") {
            logins.decline(sync.device(call), call.loginId())
            call.respond(HttpStatusCode.NoContent)
        }
        post("/{id}/approve") {
            logins.approve(sync.device(call), call.loginId(), call.receive(), call.clientIp(), call.request.header(io.ktor.http.HttpHeaders.UserAgent))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun ApplicationCall.loginId(): UUID =
    runCatching { UUID.fromString(parameters["id"]) }.getOrNull() ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "Not found.")
