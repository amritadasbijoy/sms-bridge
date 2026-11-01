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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

/** Runs the real API against a real PostgreSQL 16 (Testcontainers) with the real migrations. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiTest {
    private val postgres = PostgreSQLContainer("postgres:16.4-alpine")
    private lateinit var db: Db
    private lateinit var dataSource: com.zaxxer.hikari.HikariDataSource
    private lateinit var config: Config

    @BeforeAll
    fun start() {
        postgres.start()
        config = Config(
            port = 0, dbUrl = postgres.jdbcUrl, dbUser = postgres.username, dbPassword = postgres.password,
            cookieSecure = false, allowRegistration = false, trustProxy = false,
        )
        Db.connect(config).let { (d, ds) -> db = d; dataSource = ds }
    }

    @AfterAll
    fun stop() {
        dataSource.close()
        postgres.stop()
    }

    @BeforeEach
    fun clean() = runBlocking<Unit> {
        db.tx { update("TRUNCATE users, user_sessions, devices, pairing_codes, device_tokens, revoked_devices, sync_events, sync_checkpoints, backups, account_settings, audit_events CASCADE") }
    }

    private fun api(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { module(config, db) }
        block(createClient { install(HttpCookies) })
    }

    private suspend fun HttpClient.postJson(path: String, json: String, csrf: Boolean = true, bearer: String? = null): HttpResponse =
        post(path) {
            contentType(ContentType.Application.Json)
            if (csrf) header(CSRF_HEADER, CSRF_VALUE)
            bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(json)
        }

    private fun creds(user: String = "owner", password: String = "correct horse battery") =
        """{"username":"$user","password":"$password"}"""

    private fun json(response: String) = Json.parseToJsonElement(response).jsonObject

    @Test
    fun healthReportsDatabase() = api { client ->
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", json(response.bodyAsText())["database"]!!.jsonPrimitive.content)
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("no-store", response.headers["Cache-Control"])
    }

    @Test
    fun firstAccountOnlyThenRegistrationCloses() = api { client ->
        assertTrue(json(client.get("/api/v1/auth/status").bodyAsText())["registrationOpen"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(HttpStatusCode.Created, client.postJson("/api/v1/auth/register", creds()).status)
        assertFalse(json(client.get("/api/v1/auth/status").bodyAsText())["registrationOpen"]!!.jsonPrimitive.content.toBoolean())
        val second = client.postJson("/api/v1/auth/register", creds("intruder"))
        assertEquals(HttpStatusCode.Forbidden, second.status)
        assertEquals("registration_closed", json(second.bodyAsText())["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun weakPasswordsAndBadUsernamesAreRejected() = api { client ->
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/v1/auth/register", creds(password = "short")).status)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/v1/auth/register", creds(user = "a b<script>")).status)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/v1/auth/register", "not json").status)
    }

    @Test
    fun passwordsAndTokensAreStoredOnlyAsHashes() = api { client ->
        client.postJson("/api/v1/auth/register", creds(password = "correct horse battery"))
        val stored = db.tx { query("SELECT password_hash FROM users") { it.getString(1) }.single() }
        assertTrue(stored.startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$"))
        assertFalse(stored.contains("correct horse"))
        val code = json(client.postJson("/api/v1/devices/pairing-codes", "{}").bodyAsText())["code"]!!.jsonPrimitive.content
        val token = json(
            client.postJson("/api/v1/devices/register", """{"pairingCode":"$code","deviceId":"${UUID.randomUUID()}","name":"Pixel","platform":"android"}""", csrf = false).bodyAsText(),
        )["deviceToken"]!!.jsonPrimitive.content
        val tokenHashes = db.tx { query("SELECT token_hash FROM device_tokens") { it.getBytes(1) } }
        assertEquals(1, tokenHashes.size)
        assertTrue(tokenHashes.single().contentEquals(Secrets.sha256(token)))
        val audit = db.tx { query("SELECT action || ':' || outcome FROM audit_events ORDER BY id") { it.getString(1) } }
        assertEquals(listOf("register:ok", "pairing_code_created:ok", "device_register:ok"), audit)
    }

    @Test
    fun loginSessionCookieAndLogout() = api { client ->
        client.postJson("/api/v1/auth/register", creds())
        client.postJson("/api/v1/auth/logout", "")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/auth/me").status)

        val wrong = client.postJson("/api/v1/auth/login", creds(password = "wrong password!!"))
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        val unknown = client.postJson("/api/v1/auth/login", creds(user = "nobody"))
        assertEquals(wrong.bodyAsText(), unknown.bodyAsText()) // no username enumeration

        val ok = client.postJson("/api/v1/auth/login", creds())
        assertEquals(HttpStatusCode.OK, ok.status)
        val cookie = ok.headers.getAll(HttpHeaders.SetCookie)!!.single { it.startsWith("$SESSION_COOKIE=") }
        assertTrue("HttpOnly" in cookie && "SameSite=Strict" in cookie && "Path=/api" in cookie, cookie)
        assertEquals("owner", json(client.get("/api/v1/auth/me").bodyAsText())["username"]!!.jsonPrimitive.content)

        assertEquals(HttpStatusCode.NoContent, client.postJson("/api/v1/auth/logout", "").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/auth/me").status)
    }

    @Test
    fun cookieAuthenticatedChangesNeedTheCsrfHeader() = api { client ->
        client.postJson("/api/v1/auth/register", creds())
        val noHeader = client.postJson("/api/v1/devices/pairing-codes", "{}", csrf = false)
        assertEquals(HttpStatusCode.Forbidden, noHeader.status)
        assertEquals(HttpStatusCode.Created, client.postJson("/api/v1/devices/pairing-codes", "{}").status)
    }

    @Test
    fun devicePairingTokenUseAndRevocation() = api { client ->
        client.postJson("/api/v1/auth/register", creds())
        val code = json(client.postJson("/api/v1/devices/pairing-codes", "{}").bodyAsText())["code"]!!.jsonPrimitive.content
        assertTrue(Regex("[0-9A-Z]{5}-[0-9A-Z]{5}").matches(code))
        val deviceId = UUID.randomUUID().toString()
        // The phone has no cookie: it presents only the code (typed in lower case with spaces here).
        val phone = createClient { }
        val reg = phone.postJson("/api/v1/devices/register", """{"pairingCode":"${code.lowercase().replace("-", " ")}","deviceId":"$deviceId","name":"Galaxy","platform":"android"}""", csrf = false)
        assertEquals(HttpStatusCode.Created, reg.status)
        val token = json(reg.bodyAsText())["deviceToken"]!!.jsonPrimitive.content

        val me = phone.get("/api/v1/device") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals(deviceId, json(me.bodyAsText())["id"]!!.jsonPrimitive.content)
        assertNotNull(db.tx { query("SELECT last_seen_at FROM devices") { it.time("last_seen_at") }.single() })

        // A code works exactly once.
        val reuse = phone.postJson("/api/v1/devices/register", """{"pairingCode":"$code","deviceId":"${UUID.randomUUID()}","name":"Other","platform":"android"}""", csrf = false)
        assertEquals(HttpStatusCode.Unauthorized, reuse.status)

        val list = Json.parseToJsonElement(client.get("/api/v1/devices").bodyAsText()).jsonArray
        assertEquals(1, list.size)

        assertEquals(HttpStatusCode.NoContent, client.postJson("/api/v1/devices/$deviceId/revoke", """{"reason":"lost phone"}""").status)
        assertEquals(HttpStatusCode.Unauthorized, phone.get("/api/v1/device") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
        assertEquals(1, db.tx { query("SELECT count(*) FROM revoked_devices") { it.getInt(1) }.single() })

        // A revoked device id cannot sneak back in with a new code.
        val code2 = json(client.postJson("/api/v1/devices/pairing-codes", "{}").bodyAsText())["code"]!!.jsonPrimitive.content
        val back = phone.postJson("/api/v1/devices/register", """{"pairingCode":"$code2","deviceId":"$deviceId","name":"Galaxy","platform":"android"}""", csrf = false)
        assertEquals(HttpStatusCode.Conflict, back.status)
    }

    @Test
    fun expiredPairingCodesAndBadTokensAreRefused() = api { client ->
        client.postJson("/api/v1/auth/register", creds())
        val code = json(client.postJson("/api/v1/devices/pairing-codes", "{}").bodyAsText())["code"]!!.jsonPrimitive.content
        db.tx { update("UPDATE pairing_codes SET expires_at = now() - interval '1 minute'") }
        val late = client.postJson("/api/v1/devices/register", """{"pairingCode":"$code","deviceId":"${UUID.randomUUID()}","name":"X","platform":"android"}""", csrf = false)
        assertEquals(HttpStatusCode.Unauthorized, late.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/device") { header(HttpHeaders.Authorization, "Bearer not-a-token") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/device").status)
    }

    @Test
    fun idleAndExpiredSessionsEnd() = api { client ->
        client.postJson("/api/v1/auth/register", creds())
        db.tx { update("UPDATE user_sessions SET last_used_at = now() - interval '25 hours'") }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/auth/me").status)
    }

    @Test
    fun loginIsRateLimited() = api { client ->
        client.postJson("/api/v1/auth/register", creds())
        val statuses = (1..12).map { client.postJson("/api/v1/auth/login", creds(password = "wrong password!!")).status }
        assertTrue(HttpStatusCode.TooManyRequests in statuses, statuses.toString())
    }

    @Test
    fun oversizedBodiesAreRefused() = api { client ->
        val big = "x".repeat(70_000)
        assertEquals(HttpStatusCode.PayloadTooLarge, client.postJson("/api/v1/auth/login", """{"username":"a","password":"$big"}""").status)
    }

    @Test
    fun usersCannotSeeOrRevokeOthersDevices() = api { client ->
        // Second account via a server that allows registration.
        val open = config.copy(allowRegistration = true)
        testApplication {
            application { module(open, db) }
            val alice = createClient { install(HttpCookies) }
            val bob = createClient { install(HttpCookies) }
            alice.postJson("/api/v1/auth/register", creds("alice"))
            bob.postJson("/api/v1/auth/register", creds("bob"))
            val code = json(alice.postJson("/api/v1/devices/pairing-codes", "{}").bodyAsText())["code"]!!.jsonPrimitive.content
            val id = UUID.randomUUID().toString()
            alice.postJson("/api/v1/devices/register", """{"pairingCode":"$code","deviceId":"$id","name":"A","platform":"android"}""", csrf = false)
            assertEquals(0, Json.parseToJsonElement(bob.get("/api/v1/devices").bodyAsText()).jsonArray.size)
            assertEquals(HttpStatusCode.NotFound, bob.postJson("/api/v1/devices/$id/revoke", "{}").status)
        }
    }

    @Test
    fun openApiSpecIsServed() = api { client ->
        val spec = client.get("/api/openapi.yaml")
        assertEquals(HttpStatusCode.OK, spec.status)
        assertTrue(spec.bodyAsText().startsWith("openapi: 3.0.3"))
    }
}
