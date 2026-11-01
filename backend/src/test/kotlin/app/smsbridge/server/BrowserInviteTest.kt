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

/** Browser devices approved by a phone, and invite-only registration, against real PostgreSQL. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BrowserInviteTest {
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
        db.tx { update("TRUNCATE users, user_sessions, devices, pairing_codes, device_tokens, revoked_devices, sync_events, vaults, vault_keys, invites, audit_events, account_settings CASCADE") }
    }

    private fun key() = b64.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    private fun wrapped() = b64.encodeToString(ByteArray(80).also(SecureRandom()::nextBytes))

    private fun api(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { module(config, db) }
        val owner = createClient { install(HttpCookies) }
        assertEquals(HttpStatusCode.Created, owner.postJson("/api/v1/auth/register", """{"username":"owner","password":"correct horse battery"}""").status)
        block(owner)
    }

    private suspend fun HttpClient.postJson(path: String, body: String, bearer: String? = null): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer") else header(CSRF_HEADER, CSRF_VALUE)
        setBody(body)
    }

    private suspend fun obj(r: HttpResponse): JsonObject = Json.parseToJsonElement(r.bodyAsText()).jsonObject

    /** Pairs a phone and creates the vault, so it is trusted. Returns its token. */
    private suspend fun HttpClient.trustedPhone(): String {
        val code = obj(postJson("/api/v1/devices/pairing-codes", "{}"))["code"]!!.jsonPrimitive.content
        val id = UUID.randomUUID().toString()
        val reg = postJson("/api/v1/devices/register", """{"pairingCode":"$code","deviceId":"$id","name":"Phone","platform":"android","encPublicKey":"${key()}","signPublicKey":"${key()}"}""")
        val token = obj(reg)["deviceToken"]!!.jsonPrimitive.content
        val init = postJson("/api/v1/vault/init", """{"recoveryKeys":[{"keyVersion":1,"wrappedKey":"${wrapped()}"}],"selfKeys":[{"keyVersion":1,"wrappedKey":"${wrapped()}"}]}""", bearer = token)
        assertEquals(HttpStatusCode.Created, init.status)
        return token
    }

    private suspend fun HttpClient.registerBrowser(name: String = "Chrome on macOS"): String {
        val r = postJson("/api/v1/browser/devices", """{"name":"$name","encPublicKey":"${key()}","signPublicKey":"${key()}"}""")
        assertEquals(HttpStatusCode.Created, r.status)
        return obj(r)["deviceId"]!!.jsonPrimitive.content
    }

    @Test
    fun aPhoneApprovesABrowserWhichThenGetsItsWrappedKey() = api { owner ->
        val phone = owner.trustedPhone()
        val browserId = owner.registerBrowser()

        val before = obj(owner.get("/api/v1/browser/devices/$browserId/keys"))
        assertEquals("false", before["trusted"]!!.jsonPrimitive.content)
        assertEquals(0, before["keys"]!!.jsonArray.size)

        // The phone sees it as a pending device, like a second phone.
        val pending = Json.parseToJsonElement(owner.get("/api/v1/vault/requests") { header(HttpHeaders.Authorization, "Bearer $phone") }.bodyAsText()).jsonArray
        assertEquals(1, pending.size)
        assertEquals("web", pending[0].jsonObject["platform"]!!.jsonPrimitive.content)

        val blob = wrapped()
        assertEquals(HttpStatusCode.NoContent, owner.postJson("/api/v1/vault/requests/$browserId/approve", """{"keys":[{"keyVersion":1,"wrappedKey":"$blob"}]}""", bearer = phone).status)
        val after = obj(owner.get("/api/v1/browser/devices/$browserId/keys"))
        assertEquals("true", after["trusted"]!!.jsonPrimitive.content)
        assertEquals(blob, after["keys"]!!.jsonArray.single().jsonObject["wrappedKey"]!!.jsonPrimitive.content)

        // Revoking the browser stops it at once.
        assertEquals(HttpStatusCode.NoContent, owner.postJson("/api/v1/devices/$browserId/revoke", "{}").status)
        assertEquals(HttpStatusCode.NotFound, owner.get("/api/v1/browser/devices/$browserId/keys").status)
    }

    @Test
    fun browserEndpointsNeedTheOwnSessionAndCsrfAndValidKeys() = api { owner ->
        val browserId = owner.registerBrowser()
        val stranger = createClient { install(HttpCookies) }
        assertEquals(HttpStatusCode.Unauthorized, stranger.get("/api/v1/browser/devices/$browserId/keys").status)
        val noCsrf = owner.post("/api/v1/browser/devices") {
            contentType(ContentType.Application.Json); setBody("""{"name":"x","encPublicKey":"${key()}","signPublicKey":"${key()}"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, noCsrf.status)
        assertEquals(HttpStatusCode.BadRequest, owner.postJson("/api/v1/browser/devices", """{"name":"x","encPublicKey":"short","signPublicKey":"${key()}"}""").status)

        // Another account cannot read this browser's keys, even with the id.
        val invite = obj(owner.postJson("/api/v1/invites", "{}"))["code"]!!.jsonPrimitive.content
        val friend = createClient { install(HttpCookies) }
        friend.postJson("/api/v1/auth/register", """{"username":"friend","password":"another long passphrase","invite":"$invite"}""")
        assertEquals(HttpStatusCode.NotFound, friend.get("/api/v1/browser/devices/$browserId/keys").status)
    }

    @Test
    fun pendingBrowsersAreCapped() = api { owner ->
        repeat(5) { owner.registerBrowser("B$it") }
        val sixth = owner.postJson("/api/v1/browser/devices", """{"name":"B6","encPublicKey":"${key()}","signPublicKey":"${key()}"}""")
        assertEquals(HttpStatusCode.Conflict, sixth.status)
    }

    @Test
    fun invitesAdmitExactlyOnePersonEach() = api { owner ->
        val friend = createClient { install(HttpCookies) }
        val closed = friend.postJson("/api/v1/auth/register", """{"username":"friend","password":"another long passphrase"}""")
        assertEquals("registration_closed", obj(closed)["error"]!!.jsonPrimitive.content)
        val bad = friend.postJson("/api/v1/auth/register", """{"username":"friend","password":"another long passphrase","invite":"AAAAA-BBBBB"}""")
        assertEquals("invalid_invite", obj(bad)["error"]!!.jsonPrimitive.content)

        val invite = obj(owner.postJson("/api/v1/invites", "{}"))["code"]!!.jsonPrimitive.content
        val ok = friend.postJson("/api/v1/auth/register", """{"username":"friend","password":"another long passphrase","invite":"${invite.lowercase()}"}""")
        assertEquals(HttpStatusCode.Created, ok.status)
        // Separate account: none of the owner's devices are visible.
        assertEquals("[]", friend.get("/api/v1/devices").bodyAsText())

        val third = createClient { install(HttpCookies) }
        val reused = third.postJson("/api/v1/auth/register", """{"username":"third","password":"yet another passphrase","invite":"$invite"}""")
        assertEquals(HttpStatusCode.Forbidden, reused.status)
        val list = Json.parseToJsonElement(owner.get("/api/v1/invites").bodyAsText()).jsonArray
        assertEquals("true", list.single().jsonObject["used"]!!.jsonPrimitive.content)

        // Expired invites do not work either; a failed attempt leaves no account behind.
        val old = obj(owner.postJson("/api/v1/invites", "{}"))["code"]!!.jsonPrimitive.content
        db.tx { update("UPDATE invites SET expires_at = now() - interval '1 minute' WHERE used_at IS NULL") }
        assertEquals(HttpStatusCode.Forbidden, third.postJson("/api/v1/auth/register", """{"username":"third","password":"yet another passphrase","invite":"$old"}""").status)
        assertTrue(db.tx { query("SELECT count(*) FROM users WHERE username = 'third'") { it.getLong(1) }.single() } == 0L)
    }
}
