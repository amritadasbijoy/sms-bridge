package app.smsbridge.server

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReleaseTest {
    private val postgres = PostgreSQLContainer("postgres:16.4-alpine")
    private lateinit var db: Db
    private lateinit var dataSource: com.zaxxer.hikari.HikariDataSource
    private lateinit var config: Config
    private val token = "t".repeat(40)
    private val sha = "a".repeat(64)

    @BeforeAll
    fun start() {
        postgres.start()
        config = Config(0, postgres.jdbcUrl, postgres.username, postgres.password, cookieSecure = false, allowRegistration = false, trustProxy = false, adminToken = token)
        Db.connect(config).let { (d, ds) -> db = d; dataSource = ds }
    }

    @AfterAll
    fun stop() {
        dataSource.close()
        postgres.stop()
    }

    @BeforeEach
    fun clean() = runBlocking<Unit> {
        db.tx { update("TRUNCATE users, user_sessions, devices, pairing_codes, device_tokens, app_releases, audit_events, account_settings CASCADE") }
    }

    private fun release(code: Int, url: String = "/download/sms-bridge-0.$code.apk") =
        """{"versionCode":$code,"versionName":"0.$code","url":"$url","sha256":"$sha","required":false,"notes":"Fixes"}"""

    @Test
    fun publishingNeedsTheAdminTokenAndWakesPhones() = testApplication {
        application { module(config, db) }
        val owner = createClient { install(HttpCookies) }
        owner.post("/api/v1/auth/register") { contentType(ContentType.Application.Json); header(CSRF_HEADER, CSRF_VALUE); setBody("""{"username":"owner","password":"correct horse battery"}""") }
        val code = Json.parseToJsonElement(owner.post("/api/v1/devices/pairing-codes") { contentType(ContentType.Application.Json); header(CSRF_HEADER, CSRF_VALUE); setBody("{}") }.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content
        val phone = Json.parseToJsonElement(owner.post("/api/v1/devices/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"pairingCode":"$code","deviceId":"${UUID.randomUUID()}","name":"P","platform":"android"}""")
        }.bodyAsText()).jsonObject["deviceToken"]!!.jsonPrimitive.content
        val anyone = createClient { }
        assertEquals(HttpStatusCode.NotFound, anyone.get("/api/v1/app/latest").status)

        suspend fun publish(body: String, bearer: String?) = anyone.post("/api/v1/admin/releases") {
            contentType(ContentType.Application.Json); if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer"); setBody(body)
        }
        assertEquals(HttpStatusCode.Unauthorized, publish(release(10), null).status)
        assertEquals(HttpStatusCode.Unauthorized, publish(release(10), "wrong-token-wrong-token-wrong-token-xx").status)
        assertEquals(HttpStatusCode.BadRequest, publish(release(10, "http://evil.example/a.apk"), token).status)

        val before = Json.parseToJsonElement(anyone.get("/api/v1/notices/wait?after=-1&timeout=1") { header(HttpHeaders.Authorization, "Bearer $phone") }.bodyAsText()).jsonObject
        assertEquals("0", before["appVersion"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Created, publish(release(10), token).status)
        val after = Json.parseToJsonElement(anyone.get("/api/v1/notices/wait?after=${before["counter"]!!.jsonPrimitive.content}&timeout=1") { header(HttpHeaders.Authorization, "Bearer $phone") }.bodyAsText()).jsonObject
        assertEquals("10", after["appVersion"]!!.jsonPrimitive.content)
        assertTrue(after["counter"]!!.jsonPrimitive.content.toLong() > before["counter"]!!.jsonPrimitive.content.toLong())

        val latest = Json.parseToJsonElement(anyone.get("/api/v1/app/latest").bodyAsText()).jsonObject
        assertEquals("0.10", latest["versionName"]!!.jsonPrimitive.content)
        // Versions only move forward.
        assertEquals(HttpStatusCode.Conflict, publish(release(10), token).status)
        assertEquals(HttpStatusCode.Conflict, publish(release(9), token).status)
    }

    @Test
    fun publishingIsOffWithoutAToken() = testApplication {
        application { module(config.copy(adminToken = null), db) }
        val r = createClient { }.post("/api/v1/admin/releases") { contentType(ContentType.Application.Json); header(HttpHeaders.Authorization, "Bearer $token"); setBody(release(10)) }
        assertEquals(HttpStatusCode.NotFound, r.status)
    }
}
