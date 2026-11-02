package app.smsbridge.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.security.SecureRandom
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

// ---- Wake-up channel ---------------------------------------------------------------------------
//
// Content-free "something changed" counters per account. A phone without push holds
// GET /notices/wait?after=n open; it returns at once if the counter moved past n, otherwise when it
// moves or after the timeout. The phone then asks the normal endpoints what changed.

class Notices {
    private val counters = ConcurrentHashMap<UUID, AtomicLong>()
    private val changes = MutableSharedFlow<UUID>(extraBufferCapacity = 256)

    fun current(userId: UUID): Long = counters[userId]?.get() ?: 0L

    fun bump(userId: UUID) {
        counters.computeIfAbsent(userId) { AtomicLong() }.incrementAndGet()
        changes.tryEmit(userId)
    }

    suspend fun waitPast(userId: UUID, after: Long, timeoutMs: Long): Long {
        if (current(userId) > after) return current(userId)
        withTimeoutOrNull(timeoutMs) { changes.first { it == userId && current(userId) > after } }
        return current(userId)
    }
}

@Serializable data class NoticeState(val counter: Long)

// ---- Browser confirmed by number ----------------------------------------------------------------

@Serializable data class Challenge(val salt: String)

private val random = SecureRandom()
private val b64 = Base64.getEncoder()
private val unb64 = Base64.getDecoder()

class ConfirmService(private val db: Db, private val notices: Notices) {
    /** The waiting browser asks its user's phones to confirm it by number. */
    suspend fun requestConfirm(userId: UUID, deviceId: UUID) {
        val ok = db.tx {
            update(
                """
                UPDATE devices SET confirm_requested_at = now(), confirm_salt = NULL
                WHERE id = ? AND user_id = ? AND platform = 'web' AND vault_trusted_at IS NULL AND revoked_at IS NULL
                """.trimIndent(),
                deviceId, userId,
            ) > 0
        }
        if (!ok) throw ApiException(HttpStatusCode.NotFound, "unknown_browser", "This browser is not waiting for approval.")
        notices.bump(userId)
    }

    /** A trusted phone sets its random challenge for a requesting browser (any time it opens the prompt). */
    suspend fun setChallenge(d: DeviceContext, target: UUID, salt: String) {
        val bytes = runCatching { unb64.decode(salt) }.getOrNull()?.takeIf { it.size == 16 }
            ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_challenge", "Invalid challenge.")
        if (!d.trusted) throw ApiException(HttpStatusCode.Forbidden, "not_trusted", "Approve this device from a trusted device first.")
        val ok = db.tx {
            update(
                """
                UPDATE devices SET confirm_salt = ?
                WHERE id = ? AND user_id = ? AND confirm_requested_at IS NOT NULL AND vault_trusted_at IS NULL AND revoked_at IS NULL
                """.trimIndent(),
                bytes, target, d.userId,
            ) > 0
        }
        if (!ok) throw ApiException(HttpStatusCode.NotFound, "not_found", "That browser is not asking for confirmation.")
        notices.bump(d.userId)
    }

    /** The phone declined (wrong number or "not me"): the browser must ask again. */
    suspend fun decline(d: DeviceContext, target: UUID) {
        db.tx { update("UPDATE devices SET confirm_requested_at = NULL, confirm_salt = NULL WHERE id = ? AND user_id = ?", target, d.userId) }
        notices.bump(d.userId)
    }
}

// ---- Phone connected by number -------------------------------------------------------------------

@Serializable data class ConnectRequestBody(val username: String, val name: String)
@Serializable data class ConnectRequestCreated(val requestId: String, val secret: String, val number: Int, val expiresAt: String)
@Serializable data class ConnectRequestStatus(val status: String, val pairingCode: String? = null)
@Serializable data class PendingConnect(val id: String, val name: String, val createdAt: String, val options: List<Int>)
@Serializable data class ConnectConfirm(val number: Int)
@Serializable data class ConnectConfirmed(val approved: Boolean)

val CONNECT_REQUEST_LIFETIME: Duration = Duration.ofMinutes(10)
private const val MAX_PENDING_CONNECTS = 5

class ConnectRequestService(private val db: Db, private val devices: DeviceService, private val notices: Notices) {
    private fun twoDigits() = 10 + random.nextInt(90)

    suspend fun create(body: ConnectRequestBody, ip: String): ConnectRequestCreated {
        val name = body.name.trim().take(64).ifEmpty { "Android phone" }
        val id = UUID.randomUUID()
        val secret = Secrets.token()
        val number = twoDigits()
        val options = buildSet { add(number); while (size < 3) add(twoDigits()) }.shuffled(random).joinToString(",")
        val expires = OffsetDateTime.now().plus(CONNECT_REQUEST_LIFETIME)
        val userId = db.tx {
            val user = query("SELECT id FROM users WHERE lower(username) = lower(?)", body.username.trim()) { it.uuid("id") }.singleOrNull()
            if (user != null) {
                update("UPDATE connect_requests SET status = 'expired' WHERE user_id = ? AND status = 'pending' AND expires_at < now()", user)
                val pending = query("SELECT count(*) FROM connect_requests WHERE user_id = ? AND status = 'pending'", user) { it.getLong(1) }.single()
                if (pending >= MAX_PENDING_CONNECTS) throw ApiException(HttpStatusCode.TooManyRequests, "too_many_requests", "Too many phones are waiting. Try again in a few minutes.")
            }
            update(
                "INSERT INTO connect_requests (id, user_id, name, number, options, secret_hash, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, user, name, number, options, Secrets.sha256(secret), expires,
            )
            audit(user, null, "connect_requested", if (user == null) "unknown_user" else "ok", ip)
            user
        }
        userId?.let(notices::bump)
        return ConnectRequestCreated(id.toString(), secret, number, expires.toString())
    }

    /** Polled by the phone. The pairing code is handed out once. */
    suspend fun status(id: UUID, secret: String): ConnectRequestStatus = db.tx {
        val row = query(
            "SELECT status, pairing_code, expires_at < now() AS expired FROM connect_requests WHERE id = ? AND secret_hash = ?",
            id, Secrets.sha256(secret),
        ) { Triple(it.getString("status"), it.getString("pairing_code"), it.getBoolean("expired")) }.singleOrNull()
            ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "Unknown request.")
        when {
            row.first == "approved" && row.second != null -> {
                update("UPDATE connect_requests SET pairing_code = NULL, status = 'delivered' WHERE id = ?", id)
                ConnectRequestStatus("approved", row.second)
            }
            row.first == "pending" && row.third -> ConnectRequestStatus("expired")
            else -> ConnectRequestStatus(row.first)
        }
    }

    suspend fun pending(userId: UUID): List<PendingConnect> = db.tx {
        query(
            "SELECT id, name, created_at, options FROM connect_requests WHERE user_id = ? AND status = 'pending' AND expires_at > now() ORDER BY created_at",
            userId,
        ) { PendingConnect(it.uuid("id").toString(), it.getString("name"), it.time("created_at").toString(), it.getString("options").split(',').map(String::toInt)) }
    }

    /** The owner taps a number: right → the phone receives a one-time pairing code; wrong → refused. */
    suspend fun confirm(userId: UUID, id: UUID, number: Int, ip: String): ConnectConfirmed {
        val expected = db.tx {
            query("SELECT number FROM connect_requests WHERE id = ? AND user_id = ? AND status = 'pending' AND expires_at > now()", id, userId) { it.getInt(1) }
                .singleOrNull()
        } ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "That request has expired. Ask again from the phone.")
        if (number != expected) {
            db.tx {
                update("UPDATE connect_requests SET status = 'rejected' WHERE id = ?", id)
                audit(userId, null, "connect_confirmed", "wrong_number", ip)
            }
            return ConnectConfirmed(false)
        }
        val code = devices.createPairingCode(userId, ip).code
        db.tx {
            update("UPDATE connect_requests SET status = 'approved', pairing_code = ? WHERE id = ?", code, id)
            audit(userId, null, "connect_confirmed", "ok", ip)
        }
        return ConnectConfirmed(true)
    }

    suspend fun reject(userId: UUID, id: UUID) {
        db.tx { update("UPDATE connect_requests SET status = 'rejected' WHERE id = ? AND user_id = ? AND status = 'pending'", id, userId) }
    }
}

const val REQUEST_SECRET_HEADER = "X-Request-Secret"

fun Route.confirmRoutes(auth: AuthService, sync: SyncService, notices: Notices, confirm: ConfirmService, connects: ConnectRequestService) {
    // A phone (bearer) or a signed-in browser waits here for "something changed".
    get("/notices/wait") {
        val userId = if (call.request.header(io.ktor.http.HttpHeaders.Authorization) != null) sync.device(call).userId else auth.requireUser(call).first
        val after = call.request.queryParameters["after"]?.toLongOrNull() ?: -1L
        val timeout = (call.request.queryParameters["timeout"]?.toLongOrNull() ?: 45L).coerceIn(1, 55) * 1000
        call.respond(NoticeState(notices.waitPast(userId, after, timeout)))
    }
    post("/browser/devices/{id}/confirm") {
        val user = auth.requireUser(call)
        confirm.requestConfirm(user.first, call.uuidParam())
        call.respond(HttpStatusCode.NoContent)
    }
    post("/vault/requests/{id}/challenge") {
        confirm.setChallenge(sync.device(call), call.uuidParam(), call.receive<Challenge>().salt)
        call.respond(HttpStatusCode.NoContent)
    }
    post("/vault/requests/{id}/decline") {
        confirm.decline(sync.device(call), call.uuidParam())
        call.respond(HttpStatusCode.NoContent)
    }
    route("/connect-requests") {
        get { call.respond(connects.pending(auth.requireUser(call).first)) }
        post("/{id}/confirm") {
            val user = auth.requireUser(call)
            call.respond(connects.confirm(user.first, call.uuidParam(), call.receive<ConnectConfirm>().number, call.clientIp()))
        }
        post("/{id}/reject") {
            val user = auth.requireUser(call)
            connects.reject(user.first, call.uuidParam())
            call.respond(HttpStatusCode.NoContent)
        }
        get("/{id}") {
            // Polled by the phone that made the request; authenticated by its secret.
            val secret = call.request.header(REQUEST_SECRET_HEADER) ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "Unknown request.")
            call.respond(connects.status(call.uuidParam(), secret))
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.uuidParam(): UUID =
    runCatching { UUID.fromString(parameters["id"]) }.getOrNull() ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "Not found.")
