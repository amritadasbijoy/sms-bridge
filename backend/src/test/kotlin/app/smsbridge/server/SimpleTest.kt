package app.smsbridge.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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

/** Simple mode: typed codes, phone names, stop sharing, auto-unlink, retention and password accounts. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimpleTest {
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
        db.tx { update("TRUNCATE users, user_sessions, devices, pairing_codes, device_tokens, revoked_devices, sync_events, vaults, vault_keys, invites, connect_requests, login_requests, audit_events, account_settings, password_vault CASCADE") }
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

    private suspend fun HttpClient.putJson(path: String, body: String, bearer: String? = null): HttpResponse = put(path) {
        contentType(ContentType.Application.Json)
        if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer") else header(CSRF_HEADER, CSRF_VALUE)
        setBody(body)
    }

    private val oneKey get() = """{"keys":[{"keyVersion":1,"wrappedKey":"${wrapped()}"}]}"""

    /** A phone with its own account and vault. */
    private suspend fun HttpClient.newPhone(): String {
        val phone = obj(postJson("/api/v1/phone/start", phoneBody("Samsung SM-A525F")))["deviceToken"]!!.jsonPrimitive.content
        postJson("/api/v1/vault/init", """{"recoveryKeys":[{"keyVersion":1,"wrappedKey":"${wrapped()}"}],"selfKeys":[{"keyVersion":1,"wrappedKey":"${wrapped()}"}]}""", bearer = phone)
        return phone
    }

    @Test
    fun aTypedCodeLinksABrowserThatThePhoneNamesAndCanStopSharingWith() = api {
        val phoneClient = createClient { }
        val phone = phoneClient.newPhone()
        val browser = createClient { install(HttpCookies) }
        val req = obj(browser.postJson("/api/v1/auth/login-requests", """{"name":"Chrome on Windows","encPublicKey":"${key()}","signPublicKey":"${key()}"}"""))
        val code = req["code"]!!.jsonPrimitive.content
        assertEquals(8, code.length)

        // The phone types the code (spaces are fine) and gets what the QR code would carry.
        assertEquals(HttpStatusCode.NotFound, phoneClient.get("/api/v1/auth/login-codes/00000000").status.takeIf { code != "00000000" } ?: HttpStatusCode.NotFound)
        val found = obj(phoneClient.get("/api/v1/auth/login-codes/${code.take(4)} ${code.drop(4)}"))
        assertEquals(req["requestId"]!!.jsonPrimitive.content, found["requestId"]!!.jsonPrimitive.content)
        assertEquals("Chrome on Windows", found["name"]!!.jsonPrimitive.content)

        // Approved with auto-unlink after 1 day.
        val id = found["requestId"]!!.jsonPrimitive.content
        val approve = """{"keys":[{"keyVersion":1,"wrappedKey":"${wrapped()}"}],"expiresInDays":1}"""
        assertEquals(HttpStatusCode.NoContent, phoneClient.postJson("/api/v1/vault/login-requests/$id/approve", approve, bearer = phone).status)
        val browserId = obj(browser.get("/api/v1/auth/login-requests/$id") { header(REQUEST_SECRET_HEADER, req["secret"]!!.jsonPrimitive.content) })["deviceId"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.NotFound, phoneClient.get("/api/v1/auth/login-codes/$code").status) // used up

        // Names: the phone names itself, the website renames it.
        assertEquals(HttpStatusCode.NoContent, phoneClient.putJson("/api/v1/vault/label", """{"label":"  Office phone  "}""", bearer = phone).status)
        val linked = Json.parseToJsonElement(phoneClient.get("/api/v1/vault/linked") { header(HttpHeaders.Authorization, "Bearer $phone") }.bodyAsText()).jsonArray
        assertEquals(2, linked.size)
        val phoneRow = linked.first { it.jsonObject["platform"]!!.jsonPrimitive.content == "android" }.jsonObject
        assertEquals("Office phone", phoneRow["label"]!!.jsonPrimitive.content)
        val browserRow = linked.first { it.jsonObject["platform"]!!.jsonPrimitive.content == "web" }.jsonObject
        assertEquals(true, browserRow["expiresAt"]!!.jsonPrimitive.content.isNotBlank())
        val phoneId = phoneRow["id"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.NoContent, browser.putJson("/api/v1/devices/$phoneId/label", """{"label":"Wife's phone"}""").status)
        val listed = Json.parseToJsonElement(browser.get("/api/v1/devices").bodyAsText()).jsonArray
        assertEquals("Wife's phone", listed.first { it.jsonObject["id"]!!.jsonPrimitive.content == phoneId }.jsonObject["label"]!!.jsonPrimitive.content)

        // Stop sharing from the phone: the browser loses its keys at once.
        assertEquals(HttpStatusCode.OK, browser.get("/api/v1/browser/devices/$browserId/keys").status)
        assertEquals(HttpStatusCode.NoContent, phoneClient.postJson("/api/v1/vault/linked/$browserId/revoke", "{}", bearer = phone).status)
        assertEquals(HttpStatusCode.NotFound, browser.get("/api/v1/browser/devices/$browserId/keys").status)
    }

    @Test
    fun aSavedPasswordOpensMessagesInANewBrowser() = api {
        val phoneClient = createClient { }
        val phone = phoneClient.newPhone()
        val first = createClient { install(HttpCookies) }
        val req = obj(first.postJson("/api/v1/auth/login-requests", """{"name":"Chrome","encPublicKey":"${key()}","signPublicKey":"${key()}"}"""))
        val id = req["requestId"]!!.jsonPrimitive.content
        phoneClient.postJson("/api/v1/vault/login-requests/$id/approve", oneKey, bearer = phone)
        val firstId = obj(first.get("/api/v1/auth/login-requests/$id") { header(REQUEST_SECRET_HEADER, req["secret"]!!.jsonPrimitive.content) })["deviceId"]!!.jsonPrimitive.content

        // Save the account: username + the browser-derived login secret.
        val loginSecret = bytes(32)
        assertEquals(HttpStatusCode.NoContent, first.postJson("/api/v1/auth/account", """{"username":"amrita","password":"$loginSecret"}""").status)
        assertEquals(HttpStatusCode.Conflict, first.postJson("/api/v1/auth/account", """{"username":"amrita2","password":"$loginSecret"}""").status)
        // The approved browser stores the vault key wrapped for the password.
        val verifier = bytes(32)
        val setVault = """{"deviceId":"$firstId","salt":"${bytes(16)}","iterations":600000,"publicKey":"${key()}","verifier":"$verifier","keys":[{"keyVersion":1,"wrappedKey":"${wrapped()}"}]}"""
        assertEquals(HttpStatusCode.NoContent, first.putJson("/api/v1/vault/password", setVault).status)

        // A new browser signs in with the username and password only.
        val second = createClient { install(HttpCookies) }
        assertEquals(HttpStatusCode.OK, second.postJson("/api/v1/auth/login", """{"username":"amrita","password":"$loginSecret"}""").status)
        val info = obj(second.get("/api/v1/vault/password"))
        assertEquals(600000, info["iterations"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, info["keys"]!!.jsonArray.size)
        val secondId = obj(second.postJson("/api/v1/browser/devices", """{"name":"Firefox","encPublicKey":"${key()}","signPublicKey":"${key()}"}"""))["deviceId"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.Forbidden, second.postJson("/api/v1/browser/devices/$secondId/self-approve", """{"verifier":"${bytes(32)}",${oneKey.drop(1)}""").status)
        assertEquals(HttpStatusCode.NoContent, second.postJson("/api/v1/browser/devices/$secondId/self-approve", """{"verifier":"$verifier",${oneKey.drop(1)}""").status)
        assertEquals("true", obj(second.get("/api/v1/browser/devices/$secondId/keys"))["trusted"]!!.jsonPrimitive.content)
    }

    @Test
    fun cleanupUnlinksExpiredBrowsersAndDropsOldMessagesOnlyWhenAsked() = api {
        val phoneClient = createClient { }
        val phone = phoneClient.newPhone()
        assertEquals(HttpStatusCode.BadRequest, phoneClient.putJson("/api/v1/vault/retention", """{"days":5}""", bearer = phone).status)
        assertEquals(HttpStatusCode.NoContent, phoneClient.putJson("/api/v1/vault/retention", """{"days":7}""", bearer = phone).status)
        val (user, device) = db.tx { query("SELECT user_id, id FROM devices") { it.uuid("user_id") to it.uuid("id") }.single() }
        db.tx {
            listOf(10L, 2L).forEachIndexed { i, age ->
                update(
                    "INSERT INTO sync_events (event_id, user_id, device_id, device_sequence, event_type, key_version, ciphertext, signature, received_at) VALUES (?, ?, ?, ?, 'opaque', 1, ?, ?, now() - make_interval(days => ?))",
                    UUID.randomUUID(), user, device, i + 1L, ByteArray(40), ByteArray(64), age.toInt(),
                )
            }
            update("INSERT INTO devices (id, user_id, name, platform, vault_trusted_at, expires_at) VALUES (?, ?, 'Chrome', 'web', now(), now() - interval '1 minute')", UUID.randomUUID(), user)
        }
        SimpleService(db, Notices()).cleanup()
        assertEquals(1L, db.tx { query("SELECT count(*) FROM sync_events") { it.getLong(1) }.single() })
        assertEquals(1L, db.tx { query("SELECT count(*) FROM devices WHERE revoked_at IS NOT NULL") { it.getLong(1) }.single() })
        // Accounts that never chose a range keep everything.
        phoneClient.putJson("/api/v1/vault/retention", """{"days":null}""", bearer = phone)
        db.tx { update("UPDATE sync_events SET received_at = now() - interval '400 days'") }
        SimpleService(db, Notices()).cleanup()
        assertEquals(1L, db.tx { query("SELECT count(*) FROM sync_events") { it.getLong(1) }.single() })
    }

    @Test
    fun aNewPhoneTypesTheAddPhoneCode() = api {
        val phoneClient = createClient { }
        val phone = phoneClient.newPhone()
        val browser = createClient { install(HttpCookies) }
        val req = obj(browser.postJson("/api/v1/auth/login-requests", """{"name":"Chrome","encPublicKey":"${key()}","signPublicKey":"${key()}"}"""))
        val id = req["requestId"]!!.jsonPrimitive.content
        // A typed sign-in code reads as "login".
        assertEquals("login", obj(phoneClient.get("/api/v1/link-codes/${req["code"]!!.jsonPrimitive.content}"))["kind"]!!.jsonPrimitive.content)
        phoneClient.postJson("/api/v1/vault/login-requests/$id/approve", oneKey, bearer = phone)
        browser.get("/api/v1/auth/login-requests/$id") { header(REQUEST_SECRET_HEADER, req["secret"]!!.jsonPrimitive.content) }

        val pairing = obj(browser.postJson("/api/v1/devices/pairing-codes", "{}"))
        val short = pairing["shortCode"]!!.jsonPrimitive.content
        assertEquals(8, short.length)
        assertEquals("pair", obj(phoneClient.get("/api/v1/link-codes/${short.take(4)} ${short.drop(4)}"))["kind"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.NotFound, phoneClient.get("/api/v1/link-codes/12").status)
        val register = """{"pairingCode":"$short","deviceId":"${UUID.randomUUID()}","name":"Pixel 7","platform":"android","encPublicKey":"${key()}","signPublicKey":"${key()}"}"""
        assertEquals(HttpStatusCode.Created, phoneClient.postJson("/api/v1/devices/register", register).status)
        assertEquals(HttpStatusCode.Unauthorized, phoneClient.postJson("/api/v1/devices/register", register).status) // used up
        assertEquals(HttpStatusCode.NotFound, phoneClient.get("/api/v1/link-codes/$short").status)
    }

    @Test
    fun removingAPhoneDeletesItsMessagesFromTheServer() = api {
        val phoneClient = createClient { }
        val phone = phoneClient.newPhone()
        val browser = createClient { install(HttpCookies) }
        val req = obj(browser.postJson("/api/v1/auth/login-requests", """{"name":"Chrome","encPublicKey":"${key()}","signPublicKey":"${key()}"}"""))
        val id = req["requestId"]!!.jsonPrimitive.content
        phoneClient.postJson("/api/v1/vault/login-requests/$id/approve", oneKey, bearer = phone)
        browser.get("/api/v1/auth/login-requests/$id") { header(REQUEST_SECRET_HEADER, req["secret"]!!.jsonPrimitive.content) }
        val code = obj(browser.postJson("/api/v1/devices/pairing-codes", "{}"))["code"]!!.jsonPrimitive.content
        val second = obj(phoneClient.postJson("/api/v1/devices/register", """{"pairingCode":"$code","deviceId":"${UUID.randomUUID()}","name":"Pixel 7","platform":"android","encPublicKey":"${key()}","signPublicKey":"${key()}"}"""))
        val secondId = UUID.fromString(second["deviceId"]!!.jsonPrimitive.content)
        val (user, firstId) = db.tx { query("SELECT user_id, id FROM devices WHERE name = 'Samsung SM-A525F'") { it.uuid("user_id") to it.uuid("id") }.single() }
        fun events(device: UUID) = runBlocking { db.tx { query("SELECT count(*) FROM sync_events WHERE device_id = ?", device) { it.getLong(1) }.single() } }
        db.tx {
            listOf(firstId, firstId, secondId, secondId, secondId).forEachIndexed { i, d ->
                update(
                    "INSERT INTO sync_events (event_id, user_id, device_id, device_sequence, event_type, key_version, ciphertext, signature) VALUES (?, ?, ?, ?, 'opaque', 1, ?, ?)",
                    UUID.randomUUID(), user, d, i + 1L, ByteArray(40), ByteArray(64),
                )
            }
        }
        // The website removes the second phone: its 3 messages leave the server, the first phone's stay.
        assertEquals(HttpStatusCode.NoContent, browser.postJson("/api/v1/devices/$secondId/revoke", "{}").status)
        assertEquals(0L, events(secondId))
        assertEquals(2L, events(firstId))
        // The first phone unlinks itself: its messages leave the server and its token stops working.
        assertEquals(HttpStatusCode.NoContent, phoneClient.postJson("/api/v1/vault/leave", "{}", bearer = phone).status)
        assertEquals(0L, events(firstId))
        assertEquals(HttpStatusCode.Unauthorized, phoneClient.get("/api/v1/vault") { header(HttpHeaders.Authorization, "Bearer $phone") }.status)
        // A browser that started linking two days ago and never finished is cleaned up.
        db.tx { update("INSERT INTO devices (id, user_id, name, platform, created_at) VALUES (?, ?, 'Old', 'web', now() - interval '2 days')", UUID.randomUUID(), user) }
        SimpleService(db, Notices()).cleanup()
        assertEquals(0L, db.tx { query("SELECT count(*) FROM devices WHERE name = 'Old' AND revoked_at IS NULL") { it.getLong(1) }.single() })
    }
}
