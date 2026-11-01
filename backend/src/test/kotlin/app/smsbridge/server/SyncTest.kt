package app.smsbridge.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
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
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

/** Vault and sync endpoints against real PostgreSQL. Devices are simulated with JDK Ed25519. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncTest {
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
        db.tx { update("TRUNCATE users, user_sessions, devices, pairing_codes, device_tokens, revoked_devices, sync_events, sync_checkpoints, backups, account_settings, audit_events, vaults, vault_keys CASCADE") }
    }

    /** A simulated phone: its own keys, device id and token. */
    private inner class Phone(val name: String) {
        val id = UUID.randomUUID().toString()
        private val signing = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val signPublic: String = b64.encodeToString(signing.public.encoded.takeLast(32).toByteArray())
        val encPublic: String = b64.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
        lateinit var token: String
        private var sequence = 0L
        val signingKey: PrivateKey get() = signing.private

        fun event(ciphertext: ByteArray = ByteArray(80).also(SecureRandom()::nextBytes), eventId: String = UUID.randomUUID().toString(), keyVersion: Int = 1, signWith: PrivateKey = signing.private): String {
            sequence++
            val sig = Signature.getInstance("Ed25519").run {
                initSign(signWith); update(signedMessage(eventId, id, sequence, keyVersion, ciphertext)); sign()
            }
            return """{"eventId":"$eventId","deviceSequence":$sequence,"keyVersion":$keyVersion,"ciphertext":"${b64.encodeToString(ciphertext)}","signature":"${b64.encodeToString(sig)}"}"""
        }
    }

    private val wrapped get() = b64.encodeToString(ByteArray(60).also(SecureRandom()::nextBytes))

    private fun api(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { module(config, db) }
        val browser = createClient { install(HttpCookies); install(WebSockets) }
        browser.post("/api/v1/auth/register") { json("""{"username":"owner","password":"correct horse battery"}""") }
        block(browser)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.json(body: String) {
        contentType(ContentType.Application.Json)
        header(CSRF_HEADER, CSRF_VALUE)
        setBody(body)
    }

    private suspend fun HttpClient.pair(phone: Phone) {
        val code = obj(post("/api/v1/devices/pairing-codes") { json("{}") })["code"]!!.jsonPrimitive.content
        val reg = post("/api/v1/devices/register") {
            json("""{"pairingCode":"$code","deviceId":"${phone.id}","name":"${phone.name}","platform":"android","encPublicKey":"${phone.encPublic}","signPublicKey":"${phone.signPublic}"}""")
        }
        assertEquals(HttpStatusCode.Created, reg.status)
        phone.token = obj(reg)["deviceToken"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.asPhone(phone: Phone, path: String, body: String? = null): HttpResponse =
        if (body == null) get(path) { header(HttpHeaders.Authorization, "Bearer ${phone.token}") }
        else post(path) { header(HttpHeaders.Authorization, "Bearer ${phone.token}"); contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun obj(r: HttpResponse): JsonObject = Json.parseToJsonElement(r.bodyAsText()).jsonObject

    private suspend fun HttpClient.initVault(phone: Phone) {
        val r = asPhone(phone, "/api/v1/vault/init", """{"recoveryKeys":[{"keyVersion":1,"wrappedKey":"$wrapped"}],"selfKeys":[{"keyVersion":1,"wrappedKey":"$wrapped"}]}""")
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
    }

    @Test
    fun firstPhoneCreatesTheVaultOnlyOnce() = api { browser ->
        val a = Phone("A"); val b = Phone("B")
        browser.pair(a); browser.pair(b)
        assertEquals(false, obj(browser.asPhone(a, "/api/v1/vault"))["exists"]!!.jsonPrimitive.content.toBoolean())
        browser.initVault(a)
        val status = obj(browser.asPhone(a, "/api/v1/vault"))
        assertTrue(status["exists"]!!.jsonPrimitive.content.toBoolean() && status["deviceTrusted"]!!.jsonPrimitive.content.toBoolean())
        val again = browser.asPhone(b, "/api/v1/vault/init", """{"recoveryKeys":[{"keyVersion":1,"wrappedKey":"$wrapped"}],"selfKeys":[{"keyVersion":1,"wrappedKey":"$wrapped"}]}""")
        assertEquals(HttpStatusCode.Conflict, again.status)
        // The server holds only wrapped blobs, never anything it could decrypt with.
        assertEquals(2, db.tx { query("SELECT count(*) FROM vault_keys") { it.getInt(1) }.single() })
    }

    @Test
    fun newPhoneWaitsUntilApprovedByATrustedPhone() = api { browser ->
        val a = Phone("A"); val b = Phone("B")
        browser.pair(a); browser.initVault(a); browser.pair(b)

        assertEquals(0, obj(browser.asPhone(b, "/api/v1/vault/keys"))["keys"]!!.jsonArray.size)
        assertEquals(HttpStatusCode.Forbidden, browser.asPhone(b, "/api/v1/vault/requests").status) // untrusted cannot approve
        assertEquals(HttpStatusCode.Forbidden, browser.asPhone(b, "/api/v1/sync/events", """{"events":[${b.event()}]}""").status)

        val requests = Json.parseToJsonElement(browser.asPhone(a, "/api/v1/vault/requests").bodyAsText()).jsonArray
        assertEquals(1, requests.size)
        assertEquals(b.encPublic, requests[0].jsonObject["encPublicKey"]!!.jsonPrimitive.content) // A computes the safety code from this

        assertEquals(HttpStatusCode.BadRequest, browser.asPhone(a, "/api/v1/vault/requests/${b.id}/approve", """{"keys":[]}""").status)
        assertEquals(HttpStatusCode.NoContent, browser.asPhone(a, "/api/v1/vault/requests/${b.id}/approve", """{"keys":[{"keyVersion":1,"wrappedKey":"$wrapped"}]}""").status)
        assertEquals(1, obj(browser.asPhone(b, "/api/v1/vault/keys"))["keys"]!!.jsonArray.size)
        assertTrue(obj(browser.asPhone(b, "/api/v1/vault"))["deviceTrusted"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun rePairingWithNewKeysDropsStaleWrappedKeys() = api { browser ->
        // Found on the emulator: a phone that disconnects and pairs again gets new keys; it must not
        // be handed the copy wrapped for its old key (it cannot decrypt it).
        val a = Phone("A"); val b = Phone("B")
        browser.pair(a); browser.initVault(a); browser.pair(b)
        browser.asPhone(a, "/api/v1/vault/requests/${b.id}/approve", """{"keys":[{"keyVersion":1,"wrappedKey":"$wrapped"}]}""")
        assertEquals(1, obj(browser.asPhone(b, "/api/v1/vault/keys"))["keys"]!!.jsonArray.size)

        val b2 = object { val encPublic = b64.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes)) }
        val code = obj(browser.post("/api/v1/devices/pairing-codes") { json("{}") })["code"]!!.jsonPrimitive.content
        val reg = browser.post("/api/v1/devices/register") {
            json("""{"pairingCode":"$code","deviceId":"${b.id}","name":"B","platform":"android","encPublicKey":"${b2.encPublic}","signPublicKey":"${b.signPublic}"}""")
        }
        b.token = obj(reg)["deviceToken"]!!.jsonPrimitive.content
        assertEquals(0, obj(browser.asPhone(b, "/api/v1/vault/keys"))["keys"]!!.jsonArray.size)
        assertFalse(obj(browser.asPhone(b, "/api/v1/vault"))["deviceTrusted"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun recoveryKeyPath() = api { browser ->
        val a = Phone("A"); val c = Phone("C")
        browser.pair(a); browser.initVault(a); browser.pair(c)
        val recovery = obj(browser.asPhone(c, "/api/v1/vault/recovery"))["keys"]!!.jsonArray
        assertEquals(1, recovery.size) // still useless without the recovery key on paper
        assertEquals(HttpStatusCode.NoContent, browser.asPhone(c, "/api/v1/vault/recovered", """{"selfKeys":[{"keyVersion":1,"wrappedKey":"$wrapped"}]}""").status)
        assertTrue(obj(browser.asPhone(c, "/api/v1/vault"))["deviceTrusted"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun signedUploadsAreStoredOnceAndInOrder() = api { browser ->
        val a = Phone("A")
        browser.pair(a); browser.initVault(a)
        val events = (1..3).map { a.event() }
        val first = obj(browser.asPhone(a, "/api/v1/sync/events", """{"events":[${events.joinToString(",")}]}"""))["acked"]!!.jsonArray
        assertEquals(3, first.size)
        val seqs = first.map { it.jsonObject["serverSequence"]!!.jsonPrimitive.long }
        assertEquals(seqs.sorted(), seqs)

        // Re-sending the same batch (e.g. after a lost response) changes nothing.
        val again = obj(browser.asPhone(a, "/api/v1/sync/events", """{"events":[${events.joinToString(",")}]}"""))["acked"]!!.jsonArray
        assertEquals(seqs, again.map { it.jsonObject["serverSequence"]!!.jsonPrimitive.long })
        assertEquals(3, db.tx { query("SELECT count(*) FROM sync_events") { it.getInt(1) }.single() })
        assertEquals(listOf("opaque"), db.tx { query("SELECT DISTINCT event_type FROM sync_events") { it.getString(1) } })

        // Read back (as a trusted device and as the signed-in browser): ciphertext only.
        val page = obj(browser.asPhone(a, "/api/v1/sync/events?after=0"))
        assertEquals(3, page["events"]!!.jsonArray.size)
        assertEquals(seqs.last(), page["latest"]!!.jsonPrimitive.long)
        val fromBrowser = obj(browser.get("/api/v1/sync/events?after=${seqs[0]}"))
        assertEquals(2, fromBrowser["events"]!!.jsonArray.size)
    }

    @Test
    fun forgedTamperedAndStolenEventsAreRefused() = api { browser ->
        val a = Phone("A"); val b = Phone("B")
        browser.pair(a); browser.initVault(a); browser.pair(b)
        browser.asPhone(a, "/api/v1/vault/requests/${b.id}/approve", """{"keys":[{"keyVersion":1,"wrappedKey":"$wrapped"}]}""")

        // Signed with someone else's key.
        assertEquals(HttpStatusCode.BadRequest, browser.asPhone(a, "/api/v1/sync/events", """{"events":[${a.event(signWith = b.signingKey)}]}""").status)
        // Ciphertext changed after signing.
        val good = Json.parseToJsonElement(a.event()).jsonObject
        val tampered = good.toString().replace(good["ciphertext"]!!.jsonPrimitive.content, b64.encodeToString(ByteArray(80)))
        assertEquals(HttpStatusCode.BadRequest, browser.asPhone(a, "/api/v1/sync/events", """{"events":[$tampered]}""").status)
        // Unknown key version.
        assertEquals(HttpStatusCode.BadRequest, browser.asPhone(a, "/api/v1/sync/events", """{"events":[${a.event(keyVersion = 7)}]}""").status)
        // Another device cannot claim an event id that already exists.
        val shared = UUID.randomUUID().toString()
        assertEquals(HttpStatusCode.OK, browser.asPhone(a, "/api/v1/sync/events", """{"events":[${a.event(eventId = shared)}]}""").status)
        assertEquals(HttpStatusCode.Conflict, browser.asPhone(b, "/api/v1/sync/events", """{"events":[${b.event(eventId = shared)}]}""").status)

        // After revocation the phone can no longer upload.
        browser.post("/api/v1/devices/${a.id}/revoke") { json("{}") }
        assertEquals(HttpStatusCode.Unauthorized, browser.asPhone(a, "/api/v1/sync/events", """{"events":[${a.event()}]}""").status)
    }

    @Test
    fun streamAnnouncesNewEventsWithoutContent() = api { browser ->
        val a = Phone("A")
        browser.pair(a); browser.initVault(a)
        withTimeout(15_000) {
            browser.webSocket("/api/v1/sync/stream") {
                assertEquals("""{"latest":0}""", (incoming.receive() as Frame.Text).readText())
                browser.asPhone(a, "/api/v1/sync/events", """{"events":[${a.event()}]}""")
                val notice = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                assertEquals(setOf("latest"), notice.keys)
                assertTrue(notice["latest"]!!.jsonPrimitive.long > 0)
            }
        }
    }

    @Test
    fun auditTrailRecordsVaultActions() = api { browser ->
        val a = Phone("A"); val b = Phone("B")
        browser.pair(a); browser.initVault(a); browser.pair(b)
        browser.asPhone(a, "/api/v1/vault/requests/${b.id}/approve", """{"keys":[{"keyVersion":1,"wrappedKey":"$wrapped"}]}""")
        val actions = db.tx { query("SELECT action FROM audit_events ORDER BY id") { it.getString(1) } }
        assertTrue(actions.containsAll(listOf("vault_created", "vault_device_approved")), actions.toString())
        assertFalse(actions.any { it.contains("key") && it != "vault_device_approved" })
    }
}
