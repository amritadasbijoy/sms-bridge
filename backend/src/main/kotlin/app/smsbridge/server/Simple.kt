package app.smsbridge.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

// ---- Simple mode (0.10.0) -------------------------------------------------------------------
//
// Phone names, typed sign-in codes, auto-unlink, retention, and an optional account password that
// opens the vault. The password never reaches the server: the browser derives (a) a login secret
// that is sent instead of the password and (b) an X25519 key pair from a separate random salt. The
// vault key is HPKE-wrapped for that public key like for any device ("recipient 'password'"), and a
// new browser that derives the same key may approve itself, proven by [PasswordVault.verifier].

@Serializable data class Label(val label: String? = null)
@Serializable data class CodeLookup(val requestId: String, val encPublicKey: String, val signPublicKey: String, val name: String)
/** What a typed code is: a browser signing in ("login", with its details) or an "Add another phone" code ("pair"). */
@Serializable data class TypedCode(
    val kind: String, val requestId: String? = null, val encPublicKey: String? = null, val signPublicKey: String? = null, val name: String? = null,
)
@Serializable data class SaveAccount(val username: String, val password: String)
@Serializable data class PasswordVaultInfo(val salt: String, val iterations: Int, val keys: List<WrappedKey>)
@Serializable data class SetPasswordVault(
    /** The approved browser that wrapped the keys. */
    val deviceId: String,
    val salt: String,
    val iterations: Int,
    val publicKey: String,
    val verifier: String,
    val keys: List<WrappedKey>,
)
@Serializable data class SelfApprove(val verifier: String, val keys: List<WrappedKey>)
@Serializable data class Retention(val days: Int? = null)
@Serializable data class LinkedDevice(
    val id: String, val name: String, val label: String?, val platform: String, val trusted: Boolean,
    val lastSeenAt: String?, val expiresAt: String?,
)

private val dec = Base64.getDecoder()
private val enc = Base64.getEncoder()
private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
private fun bytes32(value: String, what: String): ByteArray =
    runCatching { dec.decode(value) }.getOrNull()?.takeIf { it.size == 32 }
        ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_$what", "Invalid $what.")

private const val MIN_ITERATIONS = 100_000
private val RETENTION_CHOICES = setOf(1, 7, 30)

class SimpleService(private val db: Db, private val wake: Notices) {

    fun cleanLabel(label: String?): String? = label?.trim()?.take(40)?.takeIf { it.isNotEmpty() }

    suspend fun setLabel(userId: UUID, deviceId: UUID, label: String?) {
        val changed = db.tx { update("UPDATE devices SET label = ? WHERE id = ? AND user_id = ?", cleanLabel(label), deviceId, userId) }
        if (changed == 0) throw ApiException(HttpStatusCode.NotFound, "not_found", "No such device.")
        wake.bump(userId)
    }

    /** The account's phones and browsers, for the phone's "Who can see my SMS" list. */
    suspend fun linked(userId: UUID): List<LinkedDevice> = db.tx {
        query(
            """
            SELECT id, name, label, platform, vault_trusted_at, last_seen_at, expires_at FROM devices
            WHERE user_id = ? AND revoked_at IS NULL ORDER BY created_at
            """.trimIndent(),
            userId,
        ) {
            LinkedDevice(
                it.uuid("id").toString(), it.getString("name"), it.getString("label"), it.getString("platform"),
                it.time("vault_trusted_at") != null, it.time("last_seen_at")?.toString(), it.time("expires_at")?.toString(),
            )
        }
    }

    /** A phone stops sharing with a browser (or another phone) of its account. */
    suspend fun revokeByPhone(d: DeviceContext, target: UUID, ip: String) {
        if (!d.trusted) throw ApiException(HttpStatusCode.Forbidden, "not_trusted", "Approve this phone first.")
        db.tx {
            val live = query("SELECT 1 FROM devices WHERE id = ? AND user_id = ? AND revoked_at IS NULL", target, d.userId) { 1 }.isNotEmpty()
            if (!live) throw ApiException(HttpStatusCode.NotFound, "not_found", "No such device.")
            removeDevice(d.userId, target, "removed on phone")
            audit(d.userId, target, "device_revoke_by_phone", "ok", ip)
        }
        wake.bump(d.userId)
    }

    /** "Unlink this phone": the phone leaves its account, and its messages leave the server. */
    suspend fun leave(d: DeviceContext, ip: String) {
        db.tx {
            removeDevice(d.userId, d.deviceId, "unlinked on phone")
            audit(d.userId, d.deviceId, "phone_left", "ok", ip)
        }
        wake.bump(d.userId)
    }

    suspend fun setRetention(d: DeviceContext, days: Int?) {
        if (!d.trusted) throw ApiException(HttpStatusCode.Forbidden, "not_trusted", "Approve this phone first.")
        if (days != null && days !in RETENTION_CHOICES) throw ApiException(HttpStatusCode.BadRequest, "invalid_retention", "Choose 1, 7 or 30 days, or keep everything.")
        db.tx { update("UPDATE users SET retention_days = ? WHERE id = ?", days, d.userId) }
    }

    /** The phone typed the browser's 8-digit code: the same details a QR code would carry. */
    suspend fun lookupCode(code: String): CodeLookup {
        val digits = code.filter(Char::isDigit)
        if (digits.length != 8) throw ApiException(HttpStatusCode.BadRequest, "invalid_code", "The code has 8 digits.")
        return db.tx {
            query(
                """
                SELECT id, enc_public_key, sign_public_key, name FROM login_requests
                WHERE short_code = ? AND status = 'pending' AND expires_at > now() AND user_id IS NULL
                ORDER BY created_at DESC LIMIT 1
                """.trimIndent(),
                digits,
            ) { CodeLookup(it.uuid("id").toString(), it.getString("enc_public_key"), it.getString("sign_public_key"), it.getString("name")) }
                .singleOrNull()
        } ?: throw ApiException(HttpStatusCode.NotFound, "invalid_code", "That code is wrong or has expired. Check the code on the computer.")
    }

    /** Any typed 8-digit code: a browser's sign-in, or an "Add another phone" code (registered with /devices/register). */
    suspend fun lookupTyped(code: String): TypedCode {
        val digits = code.filter(Char::isDigit)
        val login = runCatching { lookupCode(digits) }.getOrNull()
        if (login != null) return TypedCode("login", login.requestId, login.encPublicKey, login.signPublicKey, login.name)
        if (digits.length == 8) {
            val pair = db.tx {
                query("SELECT 1 FROM pairing_codes WHERE short_code = ? AND used_at IS NULL AND expires_at > now()", digits) { 1 }.isNotEmpty()
            }
            if (pair) return TypedCode("pair")
        }
        throw ApiException(HttpStatusCode.NotFound, "invalid_code", "That code is wrong or has expired. Check the code on the computer.")
    }

    // ---- Account and password ------------------------------------------------------------------

    /**
     * "Save your account": an account made by scanning (generated name, no password) gets a username
     * and a password. [SaveAccount.password] is the browser-derived login secret, never the password.
     */
    suspend fun saveAccount(userId: UUID, body: SaveAccount, ip: String) {
        val name = body.username.trim()
        if (!Regex("[A-Za-z0-9._-]{3,64}").matches(name)) {
            throw ApiException(HttpStatusCode.BadRequest, "invalid_username", "Use 3–64 letters, digits, dots, dashes or underscores.")
        }
        if (body.password.length !in Passwords.MIN_LENGTH..Passwords.MAX_LENGTH) {
            throw ApiException(HttpStatusCode.BadRequest, "weak_password", "Use a longer password.")
        }
        val hash = Passwords.hash(body.password.toCharArray())
        db.tx {
            if (query("SELECT 1 FROM users WHERE lower(username) = lower(?) AND id <> ?", name, userId) { 1 }.isNotEmpty()) {
                throw ApiException(HttpStatusCode.Conflict, "username_taken", "That username is taken. Try another.")
            }
            val changed = update("UPDATE users SET username = ?, password_hash = ? WHERE id = ? AND password_hash IS NULL", name, hash, userId)
            if (changed == 0) throw ApiException(HttpStatusCode.Conflict, "has_password", "This account already has a password.")
            audit(userId, null, "account_saved", "ok", ip)
        }
    }

    suspend fun passwordVault(userId: UUID): PasswordVaultInfo = db.tx {
        val row = query("SELECT salt, iterations FROM password_vault WHERE user_id = ?", userId) { enc.encodeToString(it.getBytes(1)) to it.getInt(2) }
            .singleOrNull() ?: throw ApiException(HttpStatusCode.NotFound, "no_password_vault", "Your password does not open messages yet. Link with a phone once.")
        val keys = query("SELECT key_version, wrapped_key FROM vault_keys WHERE user_id = ? AND recipient = 'password' ORDER BY key_version", userId) {
            WrappedKey(it.getInt(1), enc.encodeToString(it.getBytes(2)))
        }
        PasswordVaultInfo(row.first, row.second, keys)
    }

    /** An approved browser that knows the password stores the vault key wrapped for it. */
    suspend fun setPasswordVault(userId: UUID, body: SetPasswordVault, ip: String) {
        val salt = runCatching { dec.decode(body.salt) }.getOrNull()?.takeIf { it.size in 16..64 }
            ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_salt", "Invalid salt.")
        if (body.iterations < MIN_ITERATIONS) throw ApiException(HttpStatusCode.BadRequest, "weak_kdf", "Too few iterations.")
        requireRawKey(body.publicKey)
        val verifier = bytes32(body.verifier, "verifier")
        val browser = runCatching { UUID.fromString(body.deviceId) }.getOrNull()
            ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_device", "Invalid device id.")
        db.tx {
            val trusted = query(
                "SELECT 1 FROM devices WHERE id = ? AND user_id = ? AND platform = 'web' AND vault_trusted_at IS NOT NULL AND revoked_at IS NULL",
                browser, userId,
            ) { 1 }.isNotEmpty()
            if (!trusted) throw ApiException(HttpStatusCode.Forbidden, "not_trusted", "Open your messages in this browser first.")
            val version = query("SELECT current_version FROM vaults WHERE user_id = ?", userId) { it.getInt(1) }.single()
            if (body.keys.map { it.keyVersion }.sorted() != (1..version).toList()) {
                throw ApiException(HttpStatusCode.BadRequest, "invalid_keys", "Every key version 1..$version must be provided exactly once.")
            }
            update(
                """
                INSERT INTO password_vault (user_id, salt, iterations, public_key, verifier_hash) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET salt = EXCLUDED.salt, iterations = EXCLUDED.iterations,
                    public_key = EXCLUDED.public_key, verifier_hash = EXCLUDED.verifier_hash, updated_at = now()
                """.trimIndent(),
                userId, salt, body.iterations, body.publicKey, sha256(verifier),
            )
            update("DELETE FROM vault_keys WHERE user_id = ? AND recipient = 'password'", userId)
            body.keys.forEach { k ->
                val wrapped = runCatching { dec.decode(k.wrappedKey) }.getOrNull()?.takeIf { it.size in 32..512 }
                    ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_key", "Invalid wrapped key.")
                update(
                    "INSERT INTO vault_keys (user_id, key_version, recipient, wrapped_key, created_by) VALUES (?, ?, 'password', ?, ?)",
                    userId, k.keyVersion, wrapped, browser,
                )
            }
            audit(userId, browser, "password_vault_set", "ok", ip)
        }
    }

    /** A browser signed in with the password proves it derived the password key, then approves itself. */
    fun checkVerifier(userId: UUID, verifier: String, conn: java.sql.Connection): Boolean {
        val stored = conn.query("SELECT verifier_hash FROM password_vault WHERE user_id = ?", userId) { it.getBytes(1) }.singleOrNull() ?: return false
        return MessageDigest.isEqual(stored, sha256(bytes32(verifier, "verifier")))
    }

    suspend fun selfApprove(userId: UUID, browser: UUID, body: SelfApprove, sync: SyncService, ip: String) {
        val ok = db.tx { checkVerifier(userId, body.verifier, this) }
        if (!ok) {
            db.tx { audit(userId, browser, "password_self_approve", "denied", ip) }
            throw ApiException(HttpStatusCode.Forbidden, "wrong_password", "The password does not open these messages.")
        }
        sync.approveBy(userId, browser, browser, Approve(body.keys), ip)
    }

    // ---- Background cleanup ----------------------------------------------------------------------

    /**
     * Unlinks browsers past their auto-unlink time, drops server copies older than each account's
     * range, and deletes abandoned temporary accounts.
     */
    suspend fun cleanup() {
        val touched = db.tx {
            // Auto-unlink time passed, or a browser started linking a day ago and never finished.
            val expired = query(
                """
                SELECT id, user_id FROM devices WHERE revoked_at IS NULL AND (expires_at < now()
                   OR (platform = 'web' AND vault_trusted_at IS NULL AND created_at < now() - interval '1 day'))
                """.trimIndent(),
            ) { it.uuid("id") to it.uuid("user_id") }
            expired.forEach { (id, user) ->
                update("UPDATE devices SET revoked_at = now() WHERE id = ?", id)
                update("UPDATE device_tokens SET revoked_at = now() WHERE device_id = ? AND revoked_at IS NULL", id)
                update("DELETE FROM vault_keys WHERE user_id = ? AND recipient = ?", user, id.toString())
                audit(user, id, "device_auto_unlinked", "ok", null)
            }
            update(
                """
                DELETE FROM sync_events e USING users u
                WHERE e.user_id = u.id AND u.retention_days IS NOT NULL
                  AND e.received_at < now() - make_interval(days => u.retention_days)
                """.trimIndent(),
            )
            // Temporary accounts (made by scanning, never given a password) that lost their phone
            // and have not been used for a week: nothing can add messages to them any more.
            val abandoned = query(
                """
                SELECT u.id FROM users u
                WHERE u.password_hash IS NULL AND u.created_at < now() - interval '7 days'
                  AND NOT EXISTS (SELECT 1 FROM devices d WHERE d.user_id = u.id AND d.platform <> 'web' AND d.revoked_at IS NULL)
                  AND NOT EXISTS (SELECT 1 FROM devices d WHERE d.user_id = u.id AND d.last_seen_at > now() - interval '7 days')
                  AND NOT EXISTS (SELECT 1 FROM user_sessions s WHERE s.user_id = u.id AND s.last_used_at > now() - interval '7 days')
                """.trimIndent(),
            ) { it.uuid("id") }
            abandoned.forEach { id ->
                update("DELETE FROM users WHERE id = ?", id)
                audit(null, null, "abandoned_account_deleted", "ok", null)
            }
            expired.map { it.second }.toSet() - abandoned.toSet()
        }
        touched.forEach(wake::bump)
    }
}

fun Application.startCleanup(simple: SimpleService) {
    launch {
        while (isActive) {
            runCatching { simple.cleanup() }.onFailure { environment.log.warn("cleanup failed: ${it.javaClass.simpleName}") }
            delay(5 * 60_000L)
        }
    }
}

fun Route.simpleRoutes(auth: AuthService, sync: SyncService, simple: SimpleService) {
    // Website: rename a phone or browser.
    put("/devices/{id}/label") {
        val user = auth.requireUser(call).first
        val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
            ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "No such device.")
        simple.setLabel(user, id, call.receive<Label>().label)
        call.respond(HttpStatusCode.NoContent)
    }
    // Phone: its own name, its account's devices, stop sharing, and how long the server keeps messages.
    put("/vault/label") {
        val d = sync.device(call)
        simple.setLabel(d.userId, d.deviceId, call.receive<Label>().label)
        call.respond(HttpStatusCode.NoContent)
    }
    get("/vault/linked") {
        val d = sync.device(call)
        if (!d.trusted) throw ApiException(HttpStatusCode.Forbidden, "not_trusted", "Approve this phone first.")
        call.respond(simple.linked(d.userId))
    }
    post("/vault/linked/{id}/revoke") {
        val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
            ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "No such device.")
        simple.revokeByPhone(sync.device(call), id, call.clientIp())
        call.respond(HttpStatusCode.NoContent)
    }
    post("/vault/leave") {
        simple.leave(sync.device(call), call.clientIp())
        call.respond(HttpStatusCode.NoContent)
    }
    put("/vault/retention") {
        simple.setRetention(sync.device(call), call.receive<Retention>().days)
        call.respond(HttpStatusCode.NoContent)
    }
    rateLimit(AUTH_LIMIT) {
        get("/auth/login-codes/{code}") { call.respond(simple.lookupCode(call.parameters["code"].orEmpty())) }
        get("/link-codes/{code}") { call.respond(simple.lookupTyped(call.parameters["code"].orEmpty())) }
    }
    // Account: save (username + password) and the password's vault copy.
    post("/auth/account") {
        val user = auth.requireUser(call).first
        simple.saveAccount(user, call.receive(), call.clientIp())
        call.respond(HttpStatusCode.NoContent)
    }
    route("/vault/password") {
        get { call.respond(simple.passwordVault(auth.requireUser(call).first)) }
        put {
            simple.setPasswordVault(auth.requireUser(call).first, call.receive(), call.clientIp())
            call.respond(HttpStatusCode.NoContent)
        }
    }
    post("/browser/devices/{id}/self-approve") {
        val user = auth.requireUser(call).first
        val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
            ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "Not found.")
        simple.selfApprove(user, id, call.receive(), sync, call.clientIp())
        call.respond(HttpStatusCode.NoContent)
    }
}
