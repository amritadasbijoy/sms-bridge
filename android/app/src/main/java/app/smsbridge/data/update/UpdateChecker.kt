package app.smsbridge.data.update

import app.smsbridge.BuildConfig
import app.smsbridge.core.common.AppLog
import app.smsbridge.core.common.TimeSource
import app.smsbridge.data.settings.AppSettingsRepository
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A newer app version published on the user's server. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val sha256: String = "",
    /** Older versions should update before going on (the dialog cannot be dismissed). */
    val required: Boolean = false,
    val notes: String = "",
)

/**
 * The app is installed from an APK, so no store tells people about new versions. The server
 * publishes releases (GET /api/v1/app/latest; /download/latest.json for older apps) and wakes
 * phones when one appears; otherwise the app checks at most every 12 hours.
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val settings: AppSettingsRepository,
    private val time: TimeSource,
) {
    /** The newer version, or null if this one is current or the check failed. */
    suspend fun check(force: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        // Never linked yet: ask the site the app was downloaded from.
        val server = settings.getString(K_SERVER) ?: "https://${BuildConfig.DEFAULT_SITE}"
        val now = time.nowMillis()
        val last = settings.getString(K_LAST_CHECK)?.toLongOrNull() ?: 0L
        val cached = settings.getString(K_LATEST)
        val json = if (!force && cached != null && now - last < CHECK_INTERVAL_MS) {
            cached
        } else {
            runCatching { runCatching { fetch("$server/api/v1/app/latest") }.getOrElse { fetch("$server/download/latest.json") } }
                .onFailure { AppLog.i(TAG, "update check failed (${it.javaClass.simpleName})") }
                .getOrNull()
                ?.also { settings.putString(K_LATEST, it); settings.putString(K_LAST_CHECK, now.toString()) }
                ?: cached
        } ?: return@withContext null
        parse(json, server)?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
    }

    private fun fetch(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }.take(8_192)
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val TAG = "UpdateCheck"
        private const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000
        // Written by CloudRepository on connect; survives a disconnect.
        private const val K_SERVER = "cloudnotice.last_server"
        private const val K_LAST_CHECK = "update.last_check"
        private const val K_LATEST = "update.latest"

        /** Parses a release; a relative APK url is resolved against [server]. Only http(s) links are accepted. */
        fun parse(json: String, server: String): UpdateInfo? = runCatching {
            val o = JSONObject(json)
            val raw = o.getString("url")
            val url = if (raw.startsWith("/")) server.trimEnd('/') + raw else raw
            require(url.startsWith("https://") || url.startsWith("http://"))
            UpdateInfo(
                o.getInt("versionCode"), o.getString("versionName"), url,
                o.optString("sha256"), o.optBoolean("required", false), o.optString("notes"),
            )
        }.getOrNull()
    }
}
