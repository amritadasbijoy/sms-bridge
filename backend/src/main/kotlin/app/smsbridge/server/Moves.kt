package app.smsbridge.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.Serializable

// ---- Moving a phone into an existing account ------------------------------------------------
//
// 1. The browser of a temporary account (made by scanning, no password) starts a move: it must be
//    that account's approved browser, so only the person who scanned can move the phone.
// 2. It signs in to the old account with the password and names this account as the target, with
//    a fresh link secret; the server makes a one-time pairing code for the old account.
// 3. The phone, woken on its temporary account, fetches the move, leaves (its server copies and,
//    when nothing else is left, the temporary account are deleted) and joins with the code. The
//    browser checks the phone's proof and approves it, exactly like "Add another phone".

@Serializable data class MoveStart(val deviceId: String)
@Serializable data class MoveToken(val token: String)
@Serializable data class MoveTarget(val token: String, val linkSecret: String)
@Serializable data class MoveOffer(val pairingCode: String, val linkSecret: String)

private val MOVE_LIFETIME: Duration = Duration.ofMinutes(10)

class MoveService(private val db: Db, private val devices: DeviceService, private val wake: Notices) {
    private val dec = Base64.getDecoder()
    private val enc = Base64.getEncoder()

    suspend fun start(userId: UUID, browserId: UUID, ip: String): MoveToken {
        val token = Secrets.token()
        db.tx {
            val temporary = query("SELECT password_hash IS NULL FROM users WHERE id = ?", userId) { it.getBoolean(1) }.single()
            if (!temporary) throw ApiException(HttpStatusCode.Conflict, "has_password", "This account already has a password. Sign out and sign in to the other account instead.")
            val trusted = query(
                "SELECT 1 FROM devices WHERE id = ? AND user_id = ? AND platform = 'web' AND vault_trusted_at IS NOT NULL AND revoked_at IS NULL",
                browserId, userId,
            ) { 1 }.isNotEmpty()
            if (!trusted) throw ApiException(HttpStatusCode.Forbidden, "not_trusted", "Open your messages in this browser first.")
            update("DELETE FROM account_moves WHERE expires_at < now() OR from_user = ?", userId)
            update(
                "INSERT INTO account_moves (id, token_hash, from_user, expires_at) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), Secrets.sha256(token), userId, OffsetDateTime.now().plus(MOVE_LIFETIME),
            )
            audit(userId, browserId, "move_started", "ok", ip)
        }
        return MoveToken(token)
    }

    /** Signed in to the old account now: makes the code the phone joins with and wakes the phone. */
    suspend fun target(toUser: UUID, body: MoveTarget, ip: String) {
        val secret = runCatching { dec.decode(body.linkSecret) }.getOrNull()?.takeIf { it.size == 16 }
            ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_secret", "Invalid link secret.")
        val from = db.tx {
            query(
                "SELECT from_user FROM account_moves WHERE token_hash = ? AND to_user IS NULL AND expires_at > now()",
                Secrets.sha256(body.token),
            ) { it.uuid("from_user") }.singleOrNull()
        } ?: throw ApiException(HttpStatusCode.NotFound, "move_expired", "That took too long. Scan the QR code again.")
        if (from == toUser) throw ApiException(HttpStatusCode.Conflict, "same_account", "This phone is already in this account.")
        val code = devices.createPairingCode(toUser, ip).code
        db.tx {
            update(
                "UPDATE account_moves SET to_user = ?, pairing_code = ?, link_secret = ? WHERE token_hash = ?",
                toUser, code, secret, Secrets.sha256(body.token),
            )
            audit(toUser, null, "move_targeted", "ok", ip)
        }
        wake.bump(from)
    }

    /** For a phone of the temporary account: where to go, if a move is waiting. */
    suspend fun offer(d: DeviceContext): MoveOffer? = db.tx {
        if (!d.trusted) return@tx null
        query(
            "SELECT pairing_code, link_secret FROM account_moves WHERE from_user = ? AND to_user IS NOT NULL AND expires_at > now()",
            d.userId,
        ) { MoveOffer(it.getString(1), enc.encodeToString(it.getBytes(2))) }.singleOrNull()
    }

    /**
     * The phone leaves the temporary account to join the other one: its server copies go, and so
     * does the temporary account once no phone is left in it.
     */
    suspend fun accept(d: DeviceContext, ip: String) {
        val deleted = db.tx {
            val waiting = query("SELECT 1 FROM account_moves WHERE from_user = ? AND to_user IS NOT NULL AND expires_at > now()", d.userId) { 1 }.isNotEmpty()
            if (!waiting) throw ApiException(HttpStatusCode.NotFound, "no_move", "Nothing to move.")
            removeDevice(d.userId, d.deviceId, "moved to another account")
            update("DELETE FROM account_moves WHERE from_user = ?", d.userId)
            audit(d.userId, d.deviceId, "phone_moved", "ok", ip)
            val empty = query(
                """
                SELECT u.password_hash IS NULL AND NOT EXISTS (
                    SELECT 1 FROM devices x WHERE x.user_id = u.id AND x.platform <> 'web' AND x.revoked_at IS NULL)
                FROM users u WHERE u.id = ?
                """.trimIndent(),
                d.userId,
            ) { it.getBoolean(1) }.single()
            if (empty) update("DELETE FROM users WHERE id = ?", d.userId)
            empty
        }
        if (!deleted) wake.bump(d.userId)
    }
}

fun Route.moveRoutes(auth: AuthService, sync: SyncService, moves: MoveService) {
    post("/account/move/start") {
        val user = auth.requireUser(call).first
        val browser = runCatching { UUID.fromString(call.receive<MoveStart>().deviceId) }.getOrNull()
            ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_device", "Invalid device id.")
        call.respond(moves.start(user, browser, call.clientIp()))
    }
    post("/account/move/target") {
        moves.target(auth.requireUser(call).first, call.receive(), call.clientIp())
        call.respond(HttpStatusCode.NoContent)
    }
    get("/vault/move") {
        val offer = moves.offer(sync.device(call)) ?: throw ApiException(HttpStatusCode.NotFound, "no_move", "Nothing to move.")
        call.respond(offer)
    }
    post("/vault/move/accept") {
        moves.accept(sync.device(call), call.clientIp())
        call.respond(HttpStatusCode.NoContent)
    }
}
