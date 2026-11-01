package app.smsbridge.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer

class TotpVectorsTest {
    // RFC 6238 Appendix B (SHA-1), secret "12345678901234567890", last 6 of the 8-digit values.
    private val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test
    fun matchesRfc6238() {
        assertEquals("287082", Totp.code(secret, 59 / 30))
        assertEquals("081804", Totp.code(secret, 1111111109L / 30))
        assertEquals("005924", Totp.code(secret, 1234567890L / 30))
        assertEquals("279037", Totp.code(secret, 2000000000L / 30))
    }

    @Test
    fun acceptsOneStepOfDriftOnly() {
        val now = 1_000_000L
        assertEquals(now - 1, Totp.verify(secret, Totp.code(secret, now - 1), now))
        assertEquals(now + 1, Totp.verify(secret, Totp.code(secret, now + 1), now))
        assertNull(Totp.verify(secret, Totp.code(secret, now - 3), now))
        assertNull(Totp.verify(secret, "12345", now))
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TwoFactorTest {
    private val postgres = PostgreSQLContainer("postgres:16.4-alpine")
    private lateinit var db: Db
    private lateinit var dataSource: com.zaxxer.hikari.HikariDataSource
    private lateinit var config: Config

    @BeforeAll
    fun start() {
        postgres.start()
        config = Config(0, postgres.jdbcUrl, postgres.username, postgres.password, cookieSecure = false, allowRegistration = false, trustProxy = false)
        Db.connect(config).let { (d, ds) -> db = d; dataSource = ds }
    }

    @AfterAll
    fun stop() {
        dataSource.close()
        postgres.stop()
    }

    @BeforeEach
    fun clean() = runBlocking<Unit> {
        db.tx { update("TRUNCATE users, user_sessions, trusted_browsers, devices, vaults, vault_keys, audit_events, account_settings CASCADE") }
    }

    private fun api(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(config, db) }
        block()
    }

    private suspend fun HttpClient.postJson(path: String, json: String): HttpResponse = post(path) {
        contentType(ContentType.Application.Json); header(CSRF_HEADER, CSRF_VALUE); setBody(json)
    }

    private suspend fun obj(r: HttpResponse) = Json.parseToJsonElement(r.bodyAsText()).jsonObject
    private val login = """{"username":"owner","password":"correct horse battery"}"""
    private fun loginWith(code: String) = """{"username":"owner","password":"correct horse battery","totp":"$code"}"""

    /** Registers, sets up and enables TOTP; returns the secret. */
    private suspend fun ApplicationTestBuilder.enable2fa(): String {
        val owner = createClient { install(HttpCookies) }
        owner.postJson("/api/v1/auth/register", login)
        val setup = obj(owner.postJson("/api/v1/auth/totp/setup", "{}"))
        val secret = setup["secret"]!!.jsonPrimitive.content
        assertTrue(setup["otpauthUri"]!!.jsonPrimitive.content.startsWith("otpauth://totp/SMS%20Bridge%3Aowner?secret=$secret"))
        val wrong = if (Totp.code(secret, Totp.step()) == "000000") "111111" else "000000"
        assertEquals(HttpStatusCode.BadRequest, owner.postJson("/api/v1/auth/totp/enable", """{"code":"$wrong"}""").status)
        assertEquals(HttpStatusCode.NoContent, owner.postJson("/api/v1/auth/totp/enable", """{"code":"${Totp.code(secret, Totp.step())}"}""").status)
        assertTrue(obj(owner.get("/api/v1/auth/me"))["totpEnabled"]!!.jsonPrimitive.content.toBoolean())
        return secret
    }

    @Test
    fun newBrowsersNeedTheCodeAndCodesWorkOnce() = api {
        val secret = enable2fa()
        val newBrowser = createClient { install(HttpCookies) }

        val noCode = newBrowser.postJson("/api/v1/auth/login", login)
        assertEquals(HttpStatusCode.Unauthorized, noCode.status)
        assertEquals("totp_required", obj(noCode)["error"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Unauthorized, newBrowser.get("/api/v1/auth/me").status) // no session yet

        assertEquals(HttpStatusCode.Unauthorized, newBrowser.postJson("/api/v1/auth/login", loginWith("000001")).status)

        // A code from the next step (allowed drift) is accepted; the same code cannot be used again.
        val code = Totp.code(secret, Totp.step() + 1)
        val ok = newBrowser.postJson("/api/v1/auth/login", loginWith(code))
        assertEquals(HttpStatusCode.OK, ok.status)
        assertTrue(ok.headers.getAll(HttpHeaders.SetCookie)!!.any { it.startsWith("$BROWSER_COOKIE=") && "HttpOnly" in it })
        val thirdBrowser = createClient { install(HttpCookies) }
        assertEquals(HttpStatusCode.Unauthorized, thirdBrowser.postJson("/api/v1/auth/login", loginWith(code)).status)

        // The remembered browser signs in again with the password alone.
        newBrowser.postJson("/api/v1/auth/logout", "")
        assertEquals(HttpStatusCode.OK, newBrowser.postJson("/api/v1/auth/login", login).status)

        // "Forget all browsers" brings the code back.
        newBrowser.postJson("/api/v1/auth/browsers/forget", "{}")
        newBrowser.postJson("/api/v1/auth/logout", "")
        assertEquals("totp_required", obj(newBrowser.postJson("/api/v1/auth/login", login))["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun wrongPasswordNeverRevealsWhether2faIsOn() = api {
        enable2fa()
        val other = createClient { install(HttpCookies) }
        val wrong = other.postJson("/api/v1/auth/login", """{"username":"owner","password":"wrong password!!"}""")
        assertEquals("invalid_credentials", obj(wrong)["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun disablingNeedsPasswordAndCode() = api {
        val secret = enable2fa()
        val owner = createClient { install(HttpCookies) }
        owner.postJson("/api/v1/auth/login", loginWith(Totp.code(secret, Totp.step() + 1)))
        assertEquals(HttpStatusCode.Unauthorized, owner.postJson("/api/v1/auth/totp/disable", """{"password":"wrong password!!","code":"123456"}""").status)
        // The login consumed step+1, so the same code is refused; rewind the replay marker to use the current step.
        assertEquals(HttpStatusCode.BadRequest, owner.postJson("/api/v1/auth/totp/disable", """{"password":"correct horse battery","code":"${Totp.code(secret, Totp.step() + 1)}"}""").status)
        db.tx { update("UPDATE users SET totp_last_step = ?", Totp.step() - 5) }
        val next = Totp.code(secret, Totp.step())
        assertEquals(HttpStatusCode.NoContent, owner.postJson("/api/v1/auth/totp/disable", """{"password":"correct horse battery","code":"$next"}""").status)
        assertEquals(false, obj(owner.get("/api/v1/auth/me"))["totpEnabled"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun browserVaultReturnsOnlyRecoveryWrappedKeys() = api {
        val owner = createClient { install(HttpCookies) }
        owner.postJson("/api/v1/auth/register", login)
        assertEquals(false, obj(owner.get("/api/v1/vault/browser"))["exists"]!!.jsonPrimitive.content.toBoolean())
        val userId = db.tx { query("SELECT id FROM users") { it.uuid("id") }.single() }
        db.tx {
            update("INSERT INTO devices (id, user_id, name, platform) VALUES (gen_random_uuid(), ?, 'A', 'android')", userId)
            update("INSERT INTO vaults (user_id, current_version) VALUES (?, 1)", userId)
            update("INSERT INTO vault_keys (user_id, key_version, recipient, wrapped_key) VALUES (?, 1, 'recovery', ?)", userId, ByteArray(60) { 1 })
            update("INSERT INTO vault_keys (user_id, key_version, recipient, wrapped_key) VALUES (?, 1, 'some-device', ?)", userId, ByteArray(80) { 2 })
        }
        val vault = obj(owner.get("/api/v1/vault/browser"))
        assertEquals(1, vault["recoveryKeys"]!!.jsonArray.size)
        assertEquals(HttpStatusCode.Unauthorized, createClient { }.get("/api/v1/vault/browser").status)
    }
}
