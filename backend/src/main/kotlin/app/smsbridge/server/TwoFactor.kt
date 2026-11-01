package app.smsbridge.server

import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable

/** RFC 6238 TOTP (HMAC-SHA1, 30 s, 6 digits): what every authenticator app implements. */
object Totp {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    const val STEP_SECONDS = 30L

    fun newSecret(): String = base32(ByteArray(20).also(SecureRandom()::nextBytes))

    fun step(epochSeconds: Long = System.currentTimeMillis() / 1000) = epochSeconds / STEP_SECONDS

    fun code(secret: String, step: Long): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(unbase32(secret), "HmacSHA1")) }
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array())
        val offset = hash.last().toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or (hash[offset + 3].toInt() and 0xFF)
        return "%06d".format(binary % 1_000_000)
    }

    /** The matching step within ±1 step (clock drift), or null. Constant-time per comparison. */
    fun verify(secret: String, input: String, now: Long = step()): Long? {
        val digits = input.filter { it.isDigit() }
        if (digits.length != 6) return null
        return (now - 1..now + 1).firstOrNull { java.security.MessageDigest.isEqual(code(secret, it).toByteArray(), digits.toByteArray()) }
    }

    fun uri(secret: String, username: String) =
        "otpauth://totp/${URLEncoder.encode("SMS Bridge:$username", "UTF-8").replace("+", "%20")}?secret=$secret&issuer=SMS%20Bridge&digits=6&period=30"

    private fun base32(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF); bits += 8
            while (bits >= 5) { sb.append(ALPHABET[(buffer ushr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    fun unbase32(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in text.uppercase().filter { it != '=' && !it.isWhitespace() }) {
            buffer = (buffer shl 5) or ALPHABET.indexOf(c).also { require(it >= 0) { "invalid base32" } }; bits += 5
            if (bits >= 8) { out.write((buffer ushr (bits - 8)) and 0xFF); bits -= 8 }
        }
        return out.toByteArray()
    }
}

@Serializable data class TotpSetup(val secret: String, val otpauthUri: String)
@Serializable data class TotpCode(val code: String)
@Serializable data class TotpDisable(val password: String, val code: String)
@Serializable data class BrowserVault(val exists: Boolean, val currentVersion: Int?, val recoveryKeys: List<WrappedKey>)

const val BROWSER_COOKIE = "sb_browser"
val TRUSTED_BROWSER_LIFETIME: java.time.Duration = java.time.Duration.ofDays(90)

fun trustedBrowserCookie(value: String, config: Config, maxAge: Int) = Cookie(
    name = BROWSER_COOKIE, value = value, encoding = CookieEncoding.RAW, maxAge = maxAge, path = "/api",
    secure = config.cookieSecure, httpOnly = true, extensions = mapOf("SameSite" to "Strict"),
)

class TwoFactorService(private val db: Db) {
    suspend fun enabled(userId: UUID): Boolean =
        db.tx { query("SELECT totp_enabled_at FROM users WHERE id = ?", userId) { it.time("totp_enabled_at") }.single() != null }

    suspend fun setup(userId: UUID, username: String): TotpSetup {
        if (enabled(userId)) throw ApiException(HttpStatusCode.Conflict, "totp_enabled", "Two-factor authentication is already on.")
        val secret = Totp.newSecret()
        db.tx { update("UPDATE users SET totp_secret = ? WHERE id = ?", secret, userId) }
        return TotpSetup(secret, Totp.uri(secret, username))
    }

    /** Proves the authenticator works before 2FA is switched on. */
    suspend fun enable(userId: UUID, code: String, ip: String) = db.tx {
        val secret = query("SELECT totp_secret FROM users WHERE id = ? AND totp_enabled_at IS NULL", userId) { it.getString(1) }.singleOrNull()
            ?: throw ApiException(HttpStatusCode.Conflict, "totp_not_pending", "Start the setup first.")
        val step = Totp.verify(secret, code) ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_code", "That code is not correct.")
        update("UPDATE users SET totp_enabled_at = now(), totp_last_step = ? WHERE id = ?", step, userId)
        audit(userId, null, "totp_enabled", "ok", ip)
    }

    /**
     * Second factor at login. Returns true if the code is valid and has not been used before
     * (replay protection: the accepted step must be later than the last one).
     */
    fun verifyAtLogin(conn: java.sql.Connection, userId: UUID, code: String?): Boolean {
        if (code.isNullOrBlank()) return false
        val (secret, last) = conn.query("SELECT totp_secret, totp_last_step FROM users WHERE id = ?", userId) {
            it.getString(1) to it.getObject(2) as Long?
        }.single()
        val step = Totp.verify(secret, code) ?: return false
        if (last != null && step <= last) return false
        conn.update("UPDATE users SET totp_last_step = ? WHERE id = ?", step, userId)
        return true
    }

    suspend fun disable(userId: UUID, code: String, ip: String) = db.tx {
        if (!verifyAtLogin(this, userId, code)) throw ApiException(HttpStatusCode.BadRequest, "invalid_code", "That code is not correct.")
        update("UPDATE users SET totp_secret = NULL, totp_enabled_at = NULL, totp_last_step = NULL WHERE id = ?", userId)
        update("DELETE FROM trusted_browsers WHERE user_id = ?", userId)
        audit(userId, null, "totp_disabled", "ok", ip)
    }

    suspend fun createTrustedBrowser(userId: UUID, userAgent: String?): String {
        val token = Secrets.token()
        db.tx {
            update("DELETE FROM trusted_browsers WHERE expires_at < now()")
            update(
                "INSERT INTO trusted_browsers (token_hash, user_id, expires_at, user_agent) VALUES (?, ?, ?, ?)",
                Secrets.sha256(token), userId, OffsetDateTime.now().plus(TRUSTED_BROWSER_LIFETIME), userAgent?.take(200),
            )
        }
        return token
    }

    fun isTrustedBrowser(conn: java.sql.Connection, userId: UUID, token: String?): Boolean {
        if (token == null) return false
        val hash = Secrets.sha256(token)
        val ok = conn.query("SELECT 1 FROM trusted_browsers WHERE token_hash = ? AND user_id = ? AND expires_at > now()", hash, userId) { 1 }.isNotEmpty()
        if (ok) conn.update("UPDATE trusted_browsers SET last_used_at = now() WHERE token_hash = ?", hash)
        return ok
    }

    suspend fun forgetBrowsers(userId: UUID, ip: String) = db.tx {
        update("DELETE FROM trusted_browsers WHERE user_id = ?", userId)
        audit(userId, null, "trusted_browsers_cleared", "ok", ip)
    }
}

fun Route.twoFactorRoutes(auth: AuthService, twoFactor: TwoFactorService, db: Db) {
    route("/auth/totp") {
        post("/setup") {
            val user = auth.requireUser(call)
            call.respond(twoFactor.setup(user.first, user.second))
        }
        post("/enable") {
            val user = auth.requireUser(call)
            twoFactor.enable(user.first, call.receive<TotpCode>().code, call.clientIp())
            call.respond(HttpStatusCode.NoContent)
        }
        post("/disable") {
            val user = auth.requireUser(call)
            val body = call.receive<TotpDisable>()
            if (auth.login(user.second, body.password.toCharArray(), call.clientIp(), skipSecondFactor = true) == null) {
                throw ApiException(HttpStatusCode.Unauthorized, "invalid_credentials", "Wrong password.")
            }
            twoFactor.disable(user.first, body.code, call.clientIp())
            call.respond(HttpStatusCode.NoContent)
        }
    }
    post("/auth/browsers/forget") {
        val user = auth.requireUser(call)
        twoFactor.forgetBrowsers(user.first, call.clientIp())
        call.respond(HttpStatusCode.NoContent)
    }
    // The browser unlocks the vault locally with the recovery key; the server only hands out the
    // recovery-wrapped copies, which are useless without that key.
    get("/vault/browser") {
        val user = auth.requireUser(call)
        val result = db.tx {
            val version = query("SELECT current_version FROM vaults WHERE user_id = ?", user.first) { it.getInt(1) }.singleOrNull()
            val keys = query("SELECT key_version, wrapped_key FROM vault_keys WHERE user_id = ? AND recipient = 'recovery' ORDER BY key_version", user.first) {
                WrappedKey(it.getInt(1), java.util.Base64.getEncoder().encodeToString(it.getBytes(2)))
            }
            BrowserVault(version != null, version, keys)
        }
        call.respond(result)
    }
}

fun ApplicationCall.clientIp(): String = request.origin.remoteHost
