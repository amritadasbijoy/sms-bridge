package app.smsbridge.server

import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentLength
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

fun main() {
    val config = Config.fromEnv()
    val (db, _) = Db.connect(config)
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { module(config, db) }.start(wait = true)
}

// ---- API types -------------------------------------------------------------------------------

@Serializable data class ApiError(val error: String, val message: String)
@Serializable data class Credentials(val username: String, val password: String, val totp: String? = null, val invite: String? = null)
@Serializable data class Me(val userId: String, val username: String, val totpEnabled: Boolean = false, val hasPassword: Boolean = true)
@Serializable data class SetPassword(val password: String, val currentPassword: String? = null)
@Serializable data class AuthStatus(val registrationOpen: Boolean)
@Serializable data class PairingCodeResponse(val code: String, val expiresAt: String)
@Serializable data class RegisterDevice(
    val pairingCode: String,
    val deviceId: String,
    val name: String,
    val platform: String,
    val publicKey: String? = null,
    /** X25519 public key, raw 32 bytes base64 (Phase 6: receives wrapped vault keys). */
    val encPublicKey: String? = null,
    /** Ed25519 public key, raw 32 bytes base64 (Phase 6: signs uploaded events). */
    val signPublicKey: String? = null,
)
@Serializable data class DeviceToken(val deviceId: String, val deviceToken: String)
@Serializable data class DeviceInfo(
    val id: String, val name: String, val platform: String, val createdAt: String,
    val lastSeenAt: String?, val revokedAt: String?, val vaultTrusted: Boolean = false,
)
@Serializable data class RevokeRequest(val reason: String? = null)
@Serializable data class Health(val status: String, val database: String)

class ApiException(val status: HttpStatusCode, val code: String, override val message: String) : RuntimeException(message)

private fun unauthorized() = ApiException(HttpStatusCode.Unauthorized, "unauthorized", "Sign in again.")

const val SESSION_COOKIE = "sb_session"
const val CSRF_HEADER = "X-Requested-With"
const val CSRF_VALUE = "SmsBridge"
private val SESSION_LIFETIME = java.time.Duration.ofDays(7)
private val SESSION_IDLE = java.time.Duration.ofHours(24)
private val PAIRING_LIFETIME = java.time.Duration.ofMinutes(10)
private const val MAX_BODY_BYTES = 64 * 1024L
private const val MAX_SYNC_BODY_BYTES = 4L * 1024 * 1024
private val AUTH_LIMIT = RateLimitName("auth")

// ---- Module ----------------------------------------------------------------------------------

fun Application.module(config: Config, db: Db) {
    if (config.trustProxy) install(XForwardedHeaders)
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = true; encodeDefaults = true }) }
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        header("Referrer-Policy", "no-referrer")
        header("Cache-Control", "no-store")
    }
    install(CallLogging) {
        level = Level.INFO
        // Method, path and status only: no headers, cookies, tokens or bodies.
        format { call -> "${call.request.httpMethod.value} ${call.request.path()} -> ${call.response.status()?.value}" }
    }
    install(RateLimit) {
        register(AUTH_LIMIT) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteHost }
        }
    }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, ApiError(e.code, e.message)) }
        exception<io.ktor.server.plugins.BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", "The request was not understood."))
        }
        exception<Throwable> { call, e ->
            call.application.environment.log.error("unhandled error: ${e.javaClass.name}")
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal", "Something went wrong."))
        }
    }

    // Small JSON bodies only on these endpoints (sync uploads in Phase 6 get their own limit).
    intercept(ApplicationCallPipeline.Plugins) {
        val length = call.request.contentLength()
        val limit = if (call.request.path() == "/api/v1/sync/events") MAX_SYNC_BODY_BYTES else MAX_BODY_BYTES
        if (length != null && length > limit) {
            call.respond(HttpStatusCode.PayloadTooLarge, ApiError("too_large", "Request too large."))
            finish()
        }
    }

    val twoFactor = TwoFactorService(db)
    val auth = AuthService(db, config, twoFactor)
    val devices = DeviceService(db)
    val wake = Notices()
    val sync = SyncService(db, wake)
    val browsers = BrowserDeviceService(db, wake)
    val invites = InviteService(db)
    val confirm = ConfirmService(db, wake)
    val connects = ConnectRequestService(db, devices, wake)
    val logins = LoginRequestService(db, auth, wake)
    install(io.ktor.server.websocket.WebSockets) { pingPeriodMillis = 30_000 }

    routing {
        get("/health") {
            val ok = runCatching { db.tx { query("SELECT 1") { it.getInt(1) } } }.isSuccess
            call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, Health(if (ok) "ok" else "degraded", if (ok) "ok" else "unreachable"))
        }
        get("/api/openapi.yaml") {
            val spec = Application::class.java.getResource("/openapi.yaml")?.readText() ?: ""
            call.respondText(spec, ContentType.parse("application/yaml"))
        }
        route("/api/v1") {
            authRoutes(auth, config)
            deviceRoutes(auth, devices)
            vaultAndSyncRoutes(auth, sync)
            twoFactorRoutes(auth, twoFactor, db)
            browserAndInviteRoutes(auth, browsers, invites)
            confirmRoutes(auth, sync, wake, confirm, connects)
            loginRoutes(sync, logins) { token -> setSessionCookie(token, config) }
            rateLimit(AUTH_LIMIT) {
                // An unconnected phone asks to join (no session, no code); rate limited like sign-in.
                post("/connect-requests") {
                    call.respond(HttpStatusCode.Created, connects.create(call.receive(), call.ip()))
                }
                // A browser asks to be signed in by a phone (QR or username + number).
                post("/auth/login-requests") {
                    call.respond(HttpStatusCode.Created, logins.create(call.receive(), call.ip()))
                }
                // "Get started": a new account without a password.
                post("/auth/start") {
                    val body = runCatching { call.receive<StartAccount>() }.getOrDefault(StartAccount())
                    val (userId, name) = auth.registerPasswordless(call.ip(), body.invite)
                    call.setSessionCookie(auth.createSession(userId, call.request.header(HttpHeaders.UserAgent)), config)
                    call.respond(HttpStatusCode.Created, Me(userId.toString(), name, hasPassword = false))
                }
            }
            post("/auth/password") {
                val user = auth.requireUser(call)
                val body = call.receive<SetPassword>()
                auth.setPassword(user.first, body.password.toCharArray(), body.currentPassword?.toCharArray(), call.ip())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

// ---- Auth ------------------------------------------------------------------------------------

private fun Route.authRoutes(auth: AuthService, config: Config) = route("/auth") {
    get("/status") { call.respond(AuthStatus(auth.registrationOpen())) }
    rateLimit(AUTH_LIMIT) {
        post("/register") {
            val body = call.receive<Credentials>()
            val userId = auth.register(body.username, body.password.toCharArray(), call.ip(), body.invite)
            val token = auth.createSession(userId, call.request.header(HttpHeaders.UserAgent))
            call.setSessionCookie(token, config)
            call.respond(HttpStatusCode.Created, Me(userId.toString(), body.username.trim()))
        }
        post("/login") {
            val body = call.receive<Credentials>()
            val result = auth.login(
                body.username, body.password.toCharArray(), call.ip(),
                totp = body.totp, browserToken = call.request.cookies[BROWSER_COOKIE],
                userAgent = call.request.header(HttpHeaders.UserAgent),
            ) ?: throw ApiException(HttpStatusCode.Unauthorized, "invalid_credentials", "Wrong username or password.")
            result.newBrowserToken?.let {
                call.response.cookies.append(trustedBrowserCookie(it, config, TRUSTED_BROWSER_LIFETIME.seconds.toInt()))
            }
            val token = auth.createSession(result.userId, call.request.header(HttpHeaders.UserAgent))
            call.setSessionCookie(token, config)
            call.respond(Me(result.userId.toString(), result.username, result.totpEnabled))
        }
    }
    post("/logout") {
        call.requireCsrfHeader()
        call.request.cookies[SESSION_COOKIE]?.let { auth.endSession(it) }
        call.response.cookies.append(sessionCookie("", config, maxAge = 0))
        call.respond(HttpStatusCode.NoContent)
    }
    get("/me") {
        val user = auth.requireUser(call)
        call.respond(Me(user.first.toString(), user.second, auth.twoFactor.enabled(user.first), auth.hasPassword(user.first)))
    }
}

private fun ApplicationCall.ip(): String = request.origin.remoteHost

private fun sessionCookie(value: String, config: Config, maxAge: Int) = Cookie(
    name = SESSION_COOKIE,
    value = value,
    encoding = CookieEncoding.RAW,
    maxAge = maxAge,
    path = "/api",
    secure = config.cookieSecure,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Strict"),
)

internal fun ApplicationCall.setSessionCookie(token: String, config: Config) =
    response.cookies.append(sessionCookie(token, config, SESSION_LIFETIME.seconds.toInt()))

/**
 * Cookie-authenticated state changes must carry a custom header. Browsers cannot add it to a
 * cross-site form post without a CORS preflight, which this server never approves (CSRF defence
 * on top of SameSite=Strict).
 */
private fun ApplicationCall.requireCsrfHeader() {
    if (request.header(CSRF_HEADER) != CSRF_VALUE) {
        throw ApiException(HttpStatusCode.Forbidden, "csrf", "Missing $CSRF_HEADER header.")
    }
}

data class LoginResult(val userId: UUID, val username: String, val totpEnabled: Boolean, val newBrowserToken: String?)

class AuthService(private val db: Db, private val config: Config, val twoFactor: TwoFactorService) {

    suspend fun registrationOpen(): Boolean =
        config.allowRegistration || db.tx { query("SELECT count(*) FROM users") { it.getLong(1) }.single() == 0L }

    suspend fun register(username: String, password: CharArray, ip: String, invite: String? = null): UUID {
        val name = username.trim()
        if (!Regex("[A-Za-z0-9._-]{3,64}").matches(name)) {
            throw ApiException(HttpStatusCode.BadRequest, "invalid_username", "Use 3–64 letters, digits, dots, dashes or underscores.")
        }
        if (password.size < Passwords.MIN_LENGTH || password.size > Passwords.MAX_LENGTH) {
            throw ApiException(HttpStatusCode.BadRequest, "weak_password", "Use a password of at least ${Passwords.MIN_LENGTH} characters.")
        }
        val hash = Passwords.hash(password).also { password.fill('\u0000') }
        return createUser(name, hash, ip, invite)
    }

    /**
     * "Get started": an account without a password (signed into later by scanning with a phone).
     * The username is generated; the same registration rules apply (first account, open, or invite).
     */
    suspend fun registerPasswordless(ip: String, invite: String?): Pair<UUID, String> {
        repeat(5) {
            val name = "user-" + Secrets.pairingCode().replace("-", "").lowercase().take(8)
            try {
                return createUser(name, null, ip, invite) to name
            } catch (e: ApiException) {
                if (e.code != "username_taken") throw e
            }
        }
        throw ApiException(HttpStatusCode.Conflict, "try_again", "Please try again.")
    }

    /** Sets a password: for an account without one, or a change with the current password. */
    suspend fun setPassword(userId: UUID, password: CharArray, current: CharArray?, ip: String) {
        if (password.size < Passwords.MIN_LENGTH || password.size > Passwords.MAX_LENGTH) {
            throw ApiException(HttpStatusCode.BadRequest, "weak_password", "Use a password of at least ${Passwords.MIN_LENGTH} characters.")
        }
        val existing = db.tx { query("SELECT password_hash FROM users WHERE id = ?", userId) { it.getString(1) }.single() }
        if (existing != null && (current == null || !Passwords.verify(current, existing))) {
            current?.fill('\u0000')
            throw ApiException(HttpStatusCode.Unauthorized, "invalid_credentials", "The current password is wrong.")
        }
        current?.fill('\u0000')
        val hash = Passwords.hash(password).also { password.fill('\u0000') }
        db.tx {
            update("UPDATE users SET password_hash = ? WHERE id = ?", hash, userId)
            audit(userId, null, "password_set", "ok", ip)
        }
    }

    suspend fun hasPassword(userId: UUID): Boolean =
        db.tx { query("SELECT password_hash IS NOT NULL FROM users WHERE id = ?", userId) { it.getBoolean(1) }.single() }

    private suspend fun createUser(name: String, hash: String?, ip: String, invite: String?): UUID {
        val id = UUID.randomUUID()
        return db.tx {
            // Serialise first-account creation so two simultaneous requests cannot both succeed.
            update("LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE")
            val count = query("SELECT count(*) FROM users") { it.getLong(1) }.single()
            if (query("SELECT 1 FROM users WHERE lower(username) = lower(?)", name) { 1 }.isNotEmpty()) {
                throw ApiException(HttpStatusCode.Conflict, "username_taken", "That username is taken.")
            }
            update("INSERT INTO users (id, username, password_hash) VALUES (?, ?, ?)", id, name, hash)
            // After the first account: open registration, or a valid unused invite (consumed here, in the same transaction).
            if (count > 0 && !config.allowRegistration && !consumeInvite(invite, id)) {
                throw ApiException(
                    HttpStatusCode.Forbidden,
                    if (invite.isNullOrBlank()) "registration_closed" else "invalid_invite",
                    if (invite.isNullOrBlank()) "Registration is closed on this server. Ask the owner for an invite code." else "The invite code is wrong, used or expired.",
                )
            }
            update("INSERT INTO account_settings (user_id) VALUES (?)", id)
            audit(id, null, "register", "ok", ip)
            id
        }
    }

    /**
     * Password check, then (when 2FA is on) a trusted-browser cookie or a valid authenticator code.
     * Returns null for a wrong password, with the same error and similar timing whether or not the
     * user exists. Throws `totp_required` (401) when only the second factor is missing.
     */
    suspend fun login(
        username: String,
        password: CharArray,
        ip: String,
        totp: String? = null,
        browserToken: String? = null,
        userAgent: String? = null,
        skipSecondFactor: Boolean = false,
    ): LoginResult? {
        val row = db.tx {
            query("SELECT id, username, password_hash, totp_enabled_at FROM users WHERE lower(username) = lower(?)", username.trim()) {
                Triple(it.uuid("id"), it.getString("username"), it.getString("password_hash")) to (it.time("totp_enabled_at") != null)
            }.singleOrNull()
        }
        val passwordOk = Passwords.verify(password, row?.first?.third ?: DUMMY_HASH) && row != null
        password.fill('\u0000')
        if (!passwordOk) {
            db.tx { audit(row?.first?.first, null, "login", "denied", ip) }
            return null
        }
        val (user, totpEnabled) = row!!
        var newBrowserToken: String? = null
        if (totpEnabled && !skipSecondFactor) {
            val outcome = db.tx {
                when {
                    twoFactor.isTrustedBrowser(this, user.first, browserToken) -> "trusted_browser"
                    twoFactor.verifyAtLogin(this, user.first, totp) -> "totp"
                    else -> null
                }.also { audit(user.first, null, "login_second_factor", it ?: if (totp.isNullOrBlank()) "required" else "denied", ip) }
            }
            when (outcome) {
                null -> if (totp.isNullOrBlank()) {
                    throw ApiException(HttpStatusCode.Unauthorized, "totp_required", "Enter the code from your authenticator app.")
                } else {
                    throw ApiException(HttpStatusCode.Unauthorized, "invalid_credentials", "Wrong code.")
                }
                "totp" -> newBrowserToken = twoFactor.createTrustedBrowser(user.first, userAgent)
            }
        }
        db.tx { audit(user.first, null, "login", "ok", ip) }
        return LoginResult(user.first, user.second, totpEnabled, newBrowserToken)
    }

    suspend fun createSession(userId: UUID, userAgent: String?): String {
        val token = Secrets.token()
        db.tx {
            update("DELETE FROM user_sessions WHERE expires_at < now()")
            update(
                "INSERT INTO user_sessions (token_hash, user_id, expires_at, user_agent) VALUES (?, ?, ?, ?)",
                Secrets.sha256(token), userId, OffsetDateTime.now().plus(SESSION_LIFETIME), userAgent?.take(200),
            )
        }
        return token
    }

    suspend fun endSession(token: String) = db.tx { update("DELETE FROM user_sessions WHERE token_hash = ?", Secrets.sha256(token)) }

    /** (userId, username) for a valid, non-idle session cookie; also enforces the CSRF header on state changes. */
    suspend fun requireUser(call: ApplicationCall): Pair<UUID, String> {
        if (call.request.httpMethod != HttpMethod.Get) call.requireCsrfHeader()
        val token = call.request.cookies[SESSION_COOKIE] ?: throw unauthorized()
        val hash = Secrets.sha256(token)
        return db.tx {
            val row = query(
                """
                SELECT u.id, u.username FROM user_sessions s JOIN users u ON u.id = s.user_id
                WHERE s.token_hash = ? AND s.expires_at > now() AND s.last_used_at > ?
                """.trimIndent(),
                hash, OffsetDateTime.now().minus(SESSION_IDLE),
            ) { it.uuid("id") to it.getString("username") }.singleOrNull() ?: throw unauthorized()
            update("UPDATE user_sessions SET last_used_at = now() WHERE token_hash = ?", hash)
            row
        }
    }

    private companion object {
        /** Verified against when the user does not exist, so timing does not reveal valid usernames. */
        val DUMMY_HASH: String = Passwords.hash("dummy-password-for-timing".toCharArray())
    }
}

fun java.sql.Connection.audit(userId: UUID?, deviceId: UUID?, action: String, outcome: String, ip: String?) {
    update("INSERT INTO audit_events (user_id, device_id, action, outcome, ip) VALUES (?, ?, ?, ?, ?)", userId, deviceId, action, outcome, ip)
}

// ---- Devices ---------------------------------------------------------------------------------

private fun Route.deviceRoutes(auth: AuthService, devices: DeviceService) {
    route("/devices") {
        get {
            val user = auth.requireUser(call)
            call.respond(devices.list(user.first))
        }
        post("/pairing-codes") {
            val user = auth.requireUser(call)
            call.respond(HttpStatusCode.Created, devices.createPairingCode(user.first, call.ip()))
        }
        rateLimit(AUTH_LIMIT) {
            post("/register") {
                // Called by the phone: authenticated by the one-time pairing code, not a cookie.
                call.respond(HttpStatusCode.Created, devices.register(call.receive(), call.ip()))
            }
        }
        post("/{id}/revoke") {
            val user = auth.requireUser(call)
            val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "No such device.")
            val body = runCatching { call.receive<RevokeRequest>() }.getOrDefault(RevokeRequest())
            devices.revoke(user.first, id, body.reason?.take(200), call.ip())
            call.respond(HttpStatusCode.NoContent)
        }
    }
    get("/device") {
        // Called by the phone to check its token.
        val device = devices.requireDevice(call)
        call.respond(device)
    }
}

class DeviceService(private val db: Db) {

    suspend fun createPairingCode(userId: UUID, ip: String): PairingCodeResponse {
        val code = Secrets.pairingCode()
        val expires = OffsetDateTime.now().plus(PAIRING_LIFETIME)
        db.tx {
            update("DELETE FROM pairing_codes WHERE expires_at < now() OR used_at IS NOT NULL")
            update(
                "INSERT INTO pairing_codes (code_hash, user_id, expires_at) VALUES (?, ?, ?)",
                Secrets.sha256(Secrets.normalizePairingCode(code)), userId, expires,
            )
            audit(userId, null, "pairing_code_created", "ok", ip)
        }
        return PairingCodeResponse(code, expires.toString())
    }

    suspend fun register(request: RegisterDevice, ip: String): DeviceToken {
        val deviceId = runCatching { UUID.fromString(request.deviceId) }.getOrNull()
            ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_device", "Invalid device id.")
        val name = request.name.trim().take(64).ifEmpty { "Android device" }
        if (request.platform !in setOf("android")) throw ApiException(HttpStatusCode.BadRequest, "invalid_platform", "Unsupported platform.")
        if ((request.publicKey?.length ?: 0) > 512) throw ApiException(HttpStatusCode.BadRequest, "invalid_key", "Public key too long.")
        request.encPublicKey?.let { requireRawKey(it) }
        request.signPublicKey?.let { requireRawKey(it) }
        val codeHash = Secrets.sha256(Secrets.normalizePairingCode(request.pairingCode))
        val token = Secrets.token()
        db.tx {
            // Consume the code atomically: a code works once, before it expires.
            val userId = query(
                "UPDATE pairing_codes SET used_at = now() WHERE code_hash = ? AND used_at IS NULL AND expires_at > now() RETURNING user_id",
                codeHash,
            ) { it.uuid("user_id") }.singleOrNull()
            if (userId == null) {
                audit(null, deviceId, "device_register", "bad_code", ip)
                throw ApiException(HttpStatusCode.Unauthorized, "invalid_code", "The pairing code is wrong or has expired.")
            }
            val existing = query("SELECT user_id, revoked_at FROM devices WHERE id = ?", deviceId) { it.uuid("user_id") to it.time("revoked_at") }.singleOrNull()
            when {
                existing == null -> update(
                    "INSERT INTO devices (id, user_id, name, platform, public_key, enc_public_key, sign_public_key) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    deviceId, userId, name, request.platform, request.publicKey, request.encPublicKey, request.signPublicKey,
                )
                existing.first != userId ->
                    throw ApiException(HttpStatusCode.Conflict, "device_unavailable", "This device id belongs to another account. Reinstall SMS Bridge to get a new one.")
                existing.second != null -> {
                    // A revoked phone comes back only with a fresh code from the owner's own session.
                    // It returns unapproved, with nothing wrapped for it, and its old tokens stay dead.
                    update(
                        """
                        UPDATE devices SET name = ?, public_key = ?, enc_public_key = ?, sign_public_key = ?,
                            revoked_at = NULL, vault_trusted_at = NULL WHERE id = ?
                        """.trimIndent(),
                        name, request.publicKey, request.encPublicKey, request.signPublicKey, deviceId,
                    )
                    update("DELETE FROM vault_keys WHERE user_id = ? AND recipient = ?", userId, deviceId.toString())
                    update("DELETE FROM revoked_devices WHERE device_id = ?", deviceId)
                    audit(userId, deviceId, "device_reconnected", "ok", ip)
                }
                else -> {
                    // Re-pairing the same phone: old tokens stop working.
                    update("UPDATE device_tokens SET revoked_at = now() WHERE device_id = ? AND revoked_at IS NULL", deviceId)
                    // New keys mean the phone lost its vault keys: it must be approved again.
                    update(
                        """
                        UPDATE devices SET name = ?, public_key = COALESCE(?, public_key),
                            enc_public_key = COALESCE(?, enc_public_key), sign_public_key = COALESCE(?, sign_public_key),
                            vault_trusted_at = CASE WHEN ? IS NULL THEN vault_trusted_at ELSE NULL END
                        WHERE id = ?
                        """.trimIndent(),
                        name, request.publicKey, request.encPublicKey, request.signPublicKey, request.encPublicKey, deviceId,
                    )
                    // Keys wrapped for the old public key are useless to the phone now; drop them so it
                    // waits for a fresh approval or recovery instead of fetching undecryptable blobs.
                    if (request.encPublicKey != null) update("DELETE FROM vault_keys WHERE user_id = ? AND recipient = ?", userId, deviceId.toString())
                }
            }
            update("INSERT INTO device_tokens (token_hash, device_id) VALUES (?, ?)", Secrets.sha256(token), deviceId)
            audit(userId, deviceId, "device_register", "ok", ip)
        }
        return DeviceToken(deviceId.toString(), token)
    }

    suspend fun list(userId: UUID): List<DeviceInfo> = db.tx {
        query("SELECT * FROM devices WHERE user_id = ? ORDER BY created_at", userId) { it.toDeviceInfo() }
    }

    suspend fun revoke(userId: UUID, deviceId: UUID, reason: String?, ip: String) = db.tx {
        val owned = query("SELECT 1 FROM devices WHERE id = ? AND user_id = ?", deviceId, userId) { 1 }.isNotEmpty()
        if (!owned) throw ApiException(HttpStatusCode.NotFound, "not_found", "No such device.")
        update("UPDATE devices SET revoked_at = COALESCE(revoked_at, now()) WHERE id = ?", deviceId)
        update("UPDATE device_tokens SET revoked_at = now() WHERE device_id = ? AND revoked_at IS NULL", deviceId)
        update(
            "INSERT INTO revoked_devices (device_id, revoked_by, reason) VALUES (?, ?, ?) ON CONFLICT (device_id) DO NOTHING",
            deviceId, userId, reason,
        )
        audit(userId, deviceId, "device_revoke", "ok", ip)
    }

    /** Validates `Authorization: Bearer <device token>` and returns the device. */
    suspend fun requireDevice(call: ApplicationCall): DeviceInfo {
        val header = call.request.header(HttpHeaders.Authorization) ?: throw unauthorized()
        if (!header.startsWith("Bearer ")) throw unauthorized()
        val hash = Secrets.sha256(header.removePrefix("Bearer ").trim())
        return db.tx {
            val device = query(
                """
                SELECT d.* FROM device_tokens t JOIN devices d ON d.id = t.device_id
                WHERE t.token_hash = ? AND t.revoked_at IS NULL AND d.revoked_at IS NULL
                """.trimIndent(),
                hash,
            ) { it.toDeviceInfo() }.singleOrNull() ?: throw unauthorized()
            update("UPDATE device_tokens SET last_used_at = now() WHERE token_hash = ?", hash)
            update("UPDATE devices SET last_seen_at = now() WHERE id = ?", UUID.fromString(device.id))
            device
        }
    }

    private fun java.sql.ResultSet.toDeviceInfo() = DeviceInfo(
        id = uuid("id").toString(),
        name = getString("name"),
        platform = getString("platform"),
        createdAt = time("created_at").toString(),
        lastSeenAt = time("last_seen_at")?.toString(),
        revokedAt = time("revoked_at")?.toString(),
        vaultTrusted = time("vault_trusted_at") != null,
    )
}
