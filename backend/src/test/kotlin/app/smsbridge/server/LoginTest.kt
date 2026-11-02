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
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer

/** Accounts without passwords and signing a browser in with a phone. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoginTest {
    private val postgres = PostgreSQLContainer("postgres:16.4-alpine")
    private lateinit var db: Db
    private lateinit var dataSource: com.zaxxer.hikari.HikariDataSource
    private lateinit var config: Config
    private val b64 = Base64.getEncoder()

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
        db.tx { update("TRUNCATE users, user_sessions, devices, pairing_codes, device_tokens, revoked_devices, sync_events, vaults, vault_keys, invites, connect_requests, login_requests, audit_events, account_settings CASCADE") }
    }

    private fun key() = b64.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    private fun wrapped() = b64.encodeToString(ByteArray(80).also(SecureRandom()::nextBytes))

    private fun api(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(config, db) }
        block()
    }

    private suspend fun HttpClient.postJson(path: String, body: String, bearer: String? = null): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer") else header(CSRF_HEADER, CSRF_VALUE)
        setBody(body)
    }

    private suspend fun obj(r: HttpResponse): JsonObject = Json.parseToJsonElement(r.bodyAsText()).jsonObject

    /** "Get started", then a phone connected and approved (vault created). Returns the phone's token. */
    private suspend fun ApplicationTestBuilder.passwordlessOwnerWithPhone(owner: HttpClient): String {
        val me = obj(owner.postJson("/api/v1/auth/start", "{}"))
        assertEquals("false", me["hasPassword"]!!.jsonPrimitive.content)
        assertTrue(me["username"]!!.jsonPrimitive.content.startsWith("user-"))
        val code = obj(owner.postJson("/api/v1/devices/pairing-codes", "{}"))["code"]!!.jsonPrimitive.content
        val token = obj(owner.postJson("/api/v1/devices/register", """{"pairingCode":"$code","deviceId":"${UUID.randomUUID()}","name":"Phone","platform":"android","encPublicKey":"${key()}","signPublicKey":"${key()}"}"""))["deviceToken"]!!.jsonPrimitive.content
        owner.postJson("/api/v1/vault/init", """{"recoveryKeys":[{"keyVersion":1,"wrappedKey":"${wrapped()}"}],"selfKeys":[{"keyVersion":1,"wrappedKey":"${wrapped()}"}]}""", bearer = token)
        return token
    }

    @Test
    fun aPhoneSignsANewBrowserInAndApprovesItInOneStep() = api {
        val owner = createClient { install(HttpCookies) }
        val phone = passwordlessOwnerWithPhone(owner)

        val browser = createClient { install(HttpCookies) }
        val enc = key()
        val sign = key()
        val created = obj(browser.postJson("/api/v1/auth/login-requests", """{"name":"Chrome on macOS","encPublicKey":"$enc","signPublicKey":"$sign"}"""))
        val id = created["requestId"]!!.jsonPrimitive.content
        val secret = created["secret"]!!.jsonPrimitive.content
        assertEquals("pending", obj(browser.get("/api/v1/auth/login-requests/$id") { header(REQUEST_SECRET_HEADER, secret) })["status"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Unauthorized, browser.get("/api/v1/auth/me").status)

        // The phone (scanned the QR) sees the browser's keys and approves with a wrapped vault key.
        val seen = obj(owner.get("/api/v1/vault/login-requests/$id") { header(HttpHeaders.Authorization, "Bearer $phone") })
        assertEquals(enc, seen["encPublicKey"]!!.jsonPrimitive.content)
        val deviceId = seen["deviceId"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.NoContent, owner.postJson("/api/v1/vault/login-requests/$id/approve", """{"keys":[{"keyVersion":1,"wrappedKey":"${wrapped()}"}]}""", bearer = phone).status)

        // The browser collects its session once, and is already an approved device.
        val done = browser.get("/api/v1/auth/login-requests/$id") { header(REQUEST_SECRET_HEADER, secret) }
        assertEquals("approved", obj(done)["status"]!!.jsonPrimitive.content)
        assertEquals(deviceId, obj(done)["deviceId"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, browser.get("/api/v1/auth/me").status)
        val keys = obj(browser.get("/api/v1/browser/devices/$deviceId/keys"))
        assertEquals("true", keys["trusted"]!!.jsonPrimitive.content)
        assertEquals(1, keys["keys"]!!.jsonArray.size)
        val again = createClient { install(HttpCookies) }
        assertEquals("delivered", obj(again.get("/api/v1/auth/login-requests/$id") { header(REQUEST_SECRET_HEADER, secret) })["status"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Unauthorized, again.get("/api/v1/auth/me").status)
        // Cannot be approved twice.
        assertEquals(HttpStatusCode.NotFound, owner.postJson("/api/v1/vault/login-requests/$id/approve", """{"keys":[{"keyVersion":1,"wrappedKey":"${wrapped()}"}]}""", bearer = phone).status)
    }

    @Test
    fun usernameSignInIsConfirmedByNumberAndUnknownNamesLookTheSame() = api {
        val owner = createClient { install(HttpCookies) }
        val phone = passwordlessOwnerWithPhone(owner)
        val username = obj(owner.get("/api/v1/auth/me"))["username"]!!.jsonPrimitive.content

        val browser = createClient { install(HttpCookies) }
        val created = obj(browser.postJson("/api/v1/auth/login-requests", """{"name":"Firefox","encPublicKey":"${key()}","signPublicKey":"${key()}","username":"${username.uppercase()}"}"""))
        val id = created["requestId"]!!.jsonPrimitive.content
        val listed = Json.parseToJsonElement(owner.get("/api/v1/vault/login-requests") { header(HttpHeaders.Authorization, "Bearer $phone") }.bodyAsText()).jsonArray
        assertEquals(id, listed.single().jsonObject["id"]!!.jsonPrimitive.content)

        val salt = b64.encodeToString(ByteArray(16) { 9 })
        owner.postJson("/api/v1/vault/login-requests/$id/challenge", """{"salt":"$salt"}""", bearer = phone)
        assertEquals(salt, obj(browser.get("/api/v1/auth/login-requests/$id") { header(REQUEST_SECRET_HEADER, created["secret"]!!.jsonPrimitive.content) })["challenge"]!!.jsonPrimitive.content)

        // A wrong tap declines it.
        owner.postJson("/api/v1/vault/login-requests/$id/decline", "{}", bearer = phone)
        assertEquals("declined", obj(browser.get("/api/v1/auth/login-requests/$id") { header(REQUEST_SECRET_HEADER, created["secret"]!!.jsonPrimitive.content) })["status"]!!.jsonPrimitive.content)

        val ghost = browser.postJson("/api/v1/auth/login-requests", """{"name":"X","encPublicKey":"${key()}","signPublicKey":"${key()}","username":"nobody"}""")
        assertEquals(HttpStatusCode.Created, ghost.status)
        assertEquals(setOf("requestId", "secret", "expiresAt"), obj(ghost).keys)
        assertEquals(0, Json.parseToJsonElement(owner.get("/api/v1/vault/login-requests") { header(HttpHeaders.Authorization, "Bearer $phone") }.bodyAsText()).jsonArray.size)
    }

    @Test
    fun passwordlessAccountsFollowRegistrationRulesAndCanAddAPassword() = api {
        val owner = createClient { install(HttpCookies) }
        val first = obj(owner.postJson("/api/v1/auth/start", "{}"))
        val name = first["username"]!!.jsonPrimitive.content
        // No password yet: password sign-in is impossible.
        assertEquals(HttpStatusCode.Unauthorized, createClient { }.postJson("/api/v1/auth/login", """{"username":"$name","password":"anything at all 1"}""").status)

        // The second account needs an invite, as with passwords.
        val stranger = createClient { install(HttpCookies) }
        assertEquals("registration_closed", obj(stranger.postJson("/api/v1/auth/start", "{}"))["error"]!!.jsonPrimitive.content)
        val invite = obj(owner.postJson("/api/v1/invites", "{}"))["code"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.Created, stranger.postJson("/api/v1/auth/start", """{"invite":"$invite"}""").status)

        // Adding a password, then changing it needs the current one.
        assertEquals(HttpStatusCode.BadRequest, owner.postJson("/api/v1/auth/password", """{"password":"short"}""").status)
        assertEquals(HttpStatusCode.NoContent, owner.postJson("/api/v1/auth/password", """{"password":"a long new password 1"}""").status)
        assertEquals("true", obj(owner.get("/api/v1/auth/me"))["hasPassword"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, createClient { install(HttpCookies) }.postJson("/api/v1/auth/login", """{"username":"$name","password":"a long new password 1"}""").status)
        assertEquals(HttpStatusCode.Unauthorized, owner.postJson("/api/v1/auth/password", """{"password":"another password 22"}""").status)
        assertEquals(HttpStatusCode.NoContent, owner.postJson("/api/v1/auth/password", """{"password":"another password 22","currentPassword":"a long new password 1"}""").status)
    }
}
