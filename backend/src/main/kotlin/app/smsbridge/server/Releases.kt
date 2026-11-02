package app.smsbridge.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable

// ---- App releases -------------------------------------------------------------------------------
//
// The operator publishes a new APK with an admin token (tools/publish-release.sh); every phone is
// woken through the notice channel and offers the update. Phones read the newest release here.

@Serializable data class AppRelease(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val sha256: String,
    val required: Boolean = false,
    val notes: String = "",
)

class ReleaseService(private val db: Db, private val notices: Notices) {
    private val latest = AtomicReference<AppRelease?>(null)
    @Volatile private var loaded = false

    suspend fun latest(): AppRelease? {
        if (!loaded) {
            latest.set(db.tx { query("SELECT * FROM app_releases ORDER BY version_code DESC LIMIT 1") { it.toRelease() }.singleOrNull() })
            loaded = true
        }
        return latest.get()
    }

    /** The newest version code, for the wake-up response (0 when nothing is published). */
    suspend fun latestCode(): Int = latest()?.versionCode ?: 0

    suspend fun publish(r: AppRelease) {
        if (r.versionCode <= 0 || r.versionName.isBlank() || !Regex("[0-9a-f]{64}").matches(r.sha256)) {
            throw ApiException(HttpStatusCode.BadRequest, "invalid_release", "versionCode, versionName and a lowercase hex sha256 are required.")
        }
        if (!(r.url.startsWith("/download/") || r.url.startsWith("https://"))) {
            throw ApiException(HttpStatusCode.BadRequest, "invalid_release", "The url must be /download/… or https://….")
        }
        val users = db.tx {
            if ((query("SELECT max(version_code) FROM app_releases") { it.getInt(1) }.singleOrNull() ?: 0) >= r.versionCode) {
                throw ApiException(HttpStatusCode.Conflict, "not_newer", "A release with this or a newer version code already exists.")
            }
            update(
                "INSERT INTO app_releases (version_code, version_name, url, sha256, required, notes) VALUES (?, ?, ?, ?, ?, ?)",
                r.versionCode, r.versionName.take(32), r.url.take(300), r.sha256, r.required, r.notes.take(2000),
            )
            audit(null, null, "app_release_published", "v${r.versionCode}", "admin")
            query("SELECT id FROM users") { it.uuid("id") }
        }
        latest.set(r)
        loaded = true
        // Wake every phone: their next wait returns with the new version code.
        users.forEach(notices::bump)
    }

    private fun java.sql.ResultSet.toRelease() = AppRelease(
        getInt("version_code"), getString("version_name"), getString("url"), getString("sha256"), getBoolean("required"), getString("notes"),
    )
}

fun Route.releaseRoutes(config: Config, releases: ReleaseService) {
    get("/app/latest") {
        call.respond(releases.latest() ?: throw ApiException(HttpStatusCode.NotFound, "no_release", "Nothing published yet."))
    }
    post("/admin/releases") {
        // Disabled unless the server has an admin token; compared in constant time.
        val expected = config.adminToken ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "Not found.")
        val given = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim().orEmpty()
        if (!MessageDigest.isEqual(Secrets.sha256(given), Secrets.sha256(expected))) {
            throw ApiException(HttpStatusCode.Unauthorized, "unauthorized", "Not allowed.")
        }
        releases.publish(call.receive())
        call.respond(HttpStatusCode.Created, releases.latest()!!)
    }
}
