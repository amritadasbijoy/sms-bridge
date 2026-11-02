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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer

/** Pairing without a camera: browsers confirmed by number on a phone, phones confirmed in the browser. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfirmTest {
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
        db.tx { update("TRUNCATE users, user_sessions, devices, pairing_codes, device_tokens, revoked_devices, sync_events, vaults, vault_keys, invites, connect_requests, audit_events, account_settings CASCADE") }
    }

    private fun key() = b64.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    private fun wrapped() = b64.encodeToString(ByteArray(80).also(SecureRandom()::nextBytes))

    private fun api(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { module(config, db) }
        val owner = createClient { install(HttpCookies) }
        owner.postJson("/api/v1/auth/register", """{"username":"owner","password":"correct horse battery"}""")
        block(owner)
    }

    private suspend fun HttpClient.postJson(path: String, body: String, bearer: String? = null, secret: String? = null): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer") else header(CSRF_HEADER, CSRF_VALUE)
        if (secret != null) header(REQUEST_SECRET_HEADER, secret)
        setBody(body)
    }

    private suspend fun obj(r: HttpResponse): JsonObject = Json.parseToJsonElement(r.bodyAsText()).jsonObject

    private suspend fun HttpClient.trustedPhone(): String {
        val code = obj(postJson("/api/v1/devices/pairing-codes", "{}"))["code"]!!.jsonPrimitive.content
        val reg = postJson("/api/v1/devices/register", """{"pairingCode":"$code","deviceId":"${UUID.randomUUID()}","name":"Phone","platform":"android","encPublicKey":"${key()}","signPublicKey":"${key()}"}""")
        val token = obj(reg)["deviceToken"]!!.jsonPrimitive.content
        postJson("/api/v1/vault/init", """{"recoveryKeys":[{"keyVersion":1,"wrappedKey":"${wrapped()}"}],"selfKeys":[{"keyVersion":1,"wrappedKey":"${wrapped()}"}]}""", bearer = token)
        return token
    }

    @Test
    fun browserConfirmedByNumberGetsThePhonesChallenge() = api { owner ->
        val phone = owner.trustedPhone()
        val browser = obj(owner.postJson("/api/v1/browser/devices", """{"name":"Chrome","encPublicKey":"${key()}","signPublicKey":"${key()}"}"""))["deviceId"]!!.jsonPrimitive.content
        val before = obj(owner.get("/api/v1/notices/wait?after=-1&timeout=1") { header(HttpHeaders.Authorization, "Bearer $phone") })["counter"]!!.jsonPrimitive.long

        // The phone is waiting for news; the browser's request wakes it.
        kotlinx.coroutines.coroutineScope {
            val woken = async { obj(owner.get("/api/v1/notices/wait?after=$before&timeout=20") { header(HttpHeaders.Authorization, "Bearer $phone") })["counter"]!!.jsonPrimitive.long }
            delay(300)
            assertEquals(HttpStatusCode.NoContent, owner.postJson("/api/v1/browser/devices/$browser/confirm", "{}").status)
            assertTrue(woken.await() > before)
        }

        val pending = Json.parseToJsonElement(owner.get("/api/v1/vault/requests") { header(HttpHeaders.Authorization, "Bearer $phone") }.bodyAsText()).jsonArray
        assertEquals("true", pending.single().jsonObject["confirmRequested"]!!.jsonPrimitive.content)

        val salt = b64.encodeToString(ByteArray(16) { 7 })
        assertEquals(HttpStatusCode.BadRequest, owner.postJson("/api/v1/vault/requests/$browser/challenge", """{"salt":"short"}""", bearer = phone).status)
        assertEquals(HttpStatusCode.NoContent, owner.postJson("/api/v1/vault/requests/$browser/challenge", """{"salt":"$salt"}""", bearer = phone).status)
        assertEquals(salt, obj(owner.get("/api/v1/browser/devices/$browser/keys"))["challenge"]!!.jsonPrimitive.content)

        // Declining clears it; the browser must ask again before a new challenge is accepted.
        assertEquals(HttpStatusCode.NoContent, owner.postJson("/api/v1/vault/requests/$browser/decline", "{}", bearer = phone).status)
        assertEquals("null", obj(owner.get("/api/v1/browser/devices/$browser/keys"))["challenge"].toString())
        assertEquals(HttpStatusCode.NotFound, owner.postJson("/api/v1/vault/requests/$browser/challenge", """{"salt":"$salt"}""", bearer = phone).status)
    }

    @Test
    fun aPhoneJoinsWhenTheOwnerTapsTheRightNumber() = api { owner ->
        val phone = createClient { }
        val created = obj(phone.postJson("/api/v1/connect-requests", """{"username":"OWNER","name":"Galaxy"}"""))
        val id = created["requestId"]!!.jsonPrimitive.content
        val secret = created["secret"]!!.jsonPrimitive.content
        val number = created["number"]!!.jsonPrimitive.int

        val pending = Json.parseToJsonElement(owner.get("/api/v1/connect-requests").bodyAsText()).jsonArray.single().jsonObject
        val options = pending["options"]!!.jsonArray.map { it.jsonPrimitive.int }
        assertEquals(3, options.toSet().size)
        assertTrue(number in options)

        assertEquals("pending", obj(phone.get("/api/v1/connect-requests/$id") { header(REQUEST_SECRET_HEADER, secret) })["status"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.NotFound, phone.get("/api/v1/connect-requests/$id") { header(REQUEST_SECRET_HEADER, "wrong") }.status)

        assertEquals("true", obj(owner.postJson("/api/v1/connect-requests/$id/confirm", """{"number":$number}"""))["approved"]!!.jsonPrimitive.content)
        val approved = obj(phone.get("/api/v1/connect-requests/$id") { header(REQUEST_SECRET_HEADER, secret) })
        val code = approved["pairingCode"]!!.jsonPrimitive.content
        // The code is handed out once and works like a normal pairing code.
        assertEquals("delivered", obj(phone.get("/api/v1/connect-requests/$id") { header(REQUEST_SECRET_HEADER, secret) })["status"]!!.jsonPrimitive.content)
        val reg = phone.postJson("/api/v1/devices/register", """{"pairingCode":"$code","deviceId":"${UUID.randomUUID()}","name":"Galaxy","platform":"android","encPublicKey":"${key()}","signPublicKey":"${key()}"}""")
        assertEquals(HttpStatusCode.Created, reg.status)
    }

    @Test
    fun aWrongNumberRefusesAndUnknownNamesLookTheSame() = api { owner ->
        val phone = createClient { }
        val created = obj(phone.postJson("/api/v1/connect-requests", """{"username":"owner","name":"Galaxy"}"""))
        val id = created["requestId"]!!.jsonPrimitive.content
        val number = created["number"]!!.jsonPrimitive.int
        val wrong = Json.parseToJsonElement(owner.get("/api/v1/connect-requests").bodyAsText()).jsonArray.single().jsonObject["options"]!!.jsonArray
            .map { it.jsonPrimitive.int }.first { it != number }
        assertEquals("false", obj(owner.postJson("/api/v1/connect-requests/$id/confirm", """{"number":$wrong}"""))["approved"]!!.jsonPrimitive.content)
        assertEquals("rejected", obj(phone.get("/api/v1/connect-requests/$id") { header(REQUEST_SECRET_HEADER, created["secret"]!!.jsonPrimitive.content) })["status"]!!.jsonPrimitive.content)
        // A second try at the same request is not possible.
        assertEquals(HttpStatusCode.NotFound, owner.postJson("/api/v1/connect-requests/$id/confirm", """{"number":$number}""").status)

        // Unknown username: same response shape, never shown to anyone, stays pending until it expires.
        val ghost = phone.postJson("/api/v1/connect-requests", """{"username":"nobody-here","name":"X"}""")
        assertEquals(HttpStatusCode.Created, ghost.status)
        assertEquals(setOf("requestId", "secret", "number", "expiresAt"), obj(ghost).keys)
        assertEquals(0, Json.parseToJsonElement(owner.get("/api/v1/connect-requests").bodyAsText()).jsonArray.size)

        // Strangers cannot list or confirm someone else's requests.
        assertEquals(HttpStatusCode.Unauthorized, createClient { }.get("/api/v1/connect-requests").status)
        assertNotEquals(HttpStatusCode.OK, createClient { }.postJson("/api/v1/connect-requests/$id/confirm", """{"number":$number}""").status)
    }
}
