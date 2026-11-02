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

/** WhatsApp-style linking: a phone starts its own account, and an approved browser adds more phones. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LinkTest {
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

    private fun bytes(n: Int) = b64.encodeToString(ByteArray(n).also(SecureRandom()::nextBytes))
    private fun key() = bytes(32)
    private fun wrapped() = bytes(80)

    private fun api(cfg: Config = config, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(cfg, db) }
        block()
    }

    private suspend fun HttpClient.postJson(path: String, body: String, bearer: String? = null): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer") else header(CSRF_HEADER, CSRF_VALUE)
        setBody(body)
    }

    private suspend fun obj(r: HttpResponse): JsonObject = Json.parseToJsonElement(r.bodyAsText()).jsonObject

    private fun phoneBody(name: String) = """{"deviceId":"${UUID.randomUUID()}","name":"$name","encPublicKey":"${key()}","signPublicKey":"${key()}"}"""

    @Test
    fun aPhoneStartsItsOwnAccountSignsABrowserInAndTheBrowserAddsASecondPhone() = api {
        val client = createClient { install(HttpCookies) }
        // Registration is closed, yet a phone may start its own account (more than one, too).
        client.postJson("/api/v1/phone/start", phoneBody("Someone else"))
        val started = client.postJson("/api/v1/phone/start", phoneBody("Galaxy A52"))
        assertEquals(HttpStatusCode.Created, started.status)
        val phone = obj(started)["deviceToken"]!!.jsonPrimitive.content
        assertEquals("false", obj(client.get("/api/v1/vault") { header(HttpHeaders.Authorization, "Bearer $phone") })["exists"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Created, client.postJson("/api/v1/vault/init", """{"recoveryKeys":[{"keyVersion":1,"wrappedKey":"${wrapped()}"}],"selfKeys":[{"keyVersion":1,"wrappedKey":"${wrapped()}"}]}""", bearer = phone).status)

        // The browser's QR code: the phone approves the sign-in.
        val browser = createClient { install(HttpCookies) }
        val req = obj(browser.postJson("/api/v1/auth/login-requests", """{"name":"Chrome","encPublicKey":"${key()}","signPublicKey":"${key()}"}"""))
        val id = req["requestId"]!!.jsonPrimitive.content
        client.postJson("/api/v1/vault/login-requests/$id/approve", """{"keys":[{"keyVersion":1,"wrappedKey":"${wrapped()}"}]}""", bearer = phone)
        val browserId = obj(browser.get("/api/v1/auth/login-requests/$id") { header(REQUEST_SECRET_HEADER, req["secret"]!!.jsonPrimitive.content) })["deviceId"]!!.jsonPrimitive.content

        // "Add another phone": the second phone joins with the QR's pairing code and leaves its proof.
        val code = obj(browser.postJson("/api/v1/devices/pairing-codes", "{}"))["code"]!!.jsonPrimitive.content
        val second = obj(client.postJson("/api/v1/devices/register", """{"pairingCode":"$code","deviceId":"${UUID.randomUUID()}","name":"Pixel 7","platform":"android","encPublicKey":"${key()}","signPublicKey":"${key()}"}"""))
        val secondToken = second["deviceToken"]!!.jsonPrimitive.content
        val secondId = second["deviceId"]!!.jsonPrimitive.content
        val proof = bytes(32)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/v1/vault/link-proof", """{"proof":"${bytes(8)}"}""", bearer = secondToken).status)
        assertEquals(HttpStatusCode.NoContent, client.postJson("/api/v1/vault/link-proof", """{"proof":"$proof"}""", bearer = secondToken).status)
        assertEquals(HttpStatusCode.Conflict, client.postJson("/api/v1/vault/link-proof", """{"proof":"$proof"}""", bearer = phone).status)

        // The browser sees it with the proof, and approves it.
        val pending = browser.get("/api/v1/browser/devices/$browserId/pending").let { Json.parseToJsonElement(it.bodyAsText()).jsonArray }
        assertEquals(1, pending.size)
        assertEquals(proof, pending[0].jsonObject["linkProof"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.BadRequest, browser.postJson("/api/v1/browser/devices/$browserId/approve/$secondId", """{"keys":[]}""").status)
        assertEquals(HttpStatusCode.NoContent, browser.postJson("/api/v1/browser/devices/$browserId/approve/$secondId", """{"keys":[{"keyVersion":1,"wrappedKey":"${wrapped()}"}]}""").status)
        val status = obj(client.get("/api/v1/vault") { header(HttpHeaders.Authorization, "Bearer $secondToken") })
        assertEquals("true", status["deviceTrusted"]!!.jsonPrimitive.content)
        assertEquals(1, obj(client.get("/api/v1/vault/keys") { header(HttpHeaders.Authorization, "Bearer $secondToken") })["keys"]!!.jsonArray.size)
        assertEquals(0, Json.parseToJsonElement(browser.get("/api/v1/browser/devices/$browserId/pending").bodyAsText()).jsonArray.size)

        // Another account's browser cannot use this browser's id.
        val stranger = createClient { install(HttpCookies) }
        stranger.postJson("/api/v1/phone/start", phoneBody("x"))
        assertEquals(HttpStatusCode.Unauthorized, stranger.get("/api/v1/browser/devices/$browserId/pending").status)
    }

    @Test
    fun anUnapprovedBrowserCannotApprovePhones() = api {
        val owner = createClient { install(HttpCookies) }
        owner.postJson("/api/v1/auth/start", "{}") // first account, open
        val browserId = obj(owner.postJson("/api/v1/browser/devices", """{"name":"Chrome","encPublicKey":"${key()}","signPublicKey":"${key()}"}"""))["deviceId"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.Forbidden, owner.get("/api/v1/browser/devices/$browserId/pending").status)
        assertEquals(HttpStatusCode.Forbidden, owner.postJson("/api/v1/browser/devices/$browserId/approve/${UUID.randomUUID()}", """{"keys":[]}""").status)
    }

    @Test
    fun theOperatorCanTurnPhoneSignupOff() = api(config.copy(phoneSignup = false)) {
        val client = createClient { }
        assertEquals(HttpStatusCode.Created, client.postJson("/api/v1/phone/start", phoneBody("First")).status) // the first account always works
        val refused = client.postJson("/api/v1/phone/start", phoneBody("Second"))
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertEquals("registration_closed", obj(refused)["error"]!!.jsonPrimitive.content)
        assertEquals(1L, db.tx { query("SELECT count(*) FROM users") { it.getLong(1) }.single() })
    }
}
