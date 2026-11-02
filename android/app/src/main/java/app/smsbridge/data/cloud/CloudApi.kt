package app.smsbridge.data.cloud

import app.smsbridge.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** A server answer that is not 2xx. [code] is the server's machine-readable error code. */
class CloudHttpException(val status: Int, val code: String, message: String) : IOException(message)

data class WrappedKeyDto(val keyVersion: Int, val wrappedKey: ByteArray)
data class VaultStatusDto(val exists: Boolean, val currentVersion: Int?, val trusted: Boolean, val approvedDevices: Int)
data class PendingDeviceDto(val id: String, val name: String, val encPublic: ByteArray, val signPublic: ByteArray, val confirmRequested: Boolean = false)
data class ConnectRequestDto(val requestId: String, val secret: String, val number: Int, val expiresAt: String)
data class UploadEventDto(val eventId: String, val deviceSequence: Long, val keyVersion: Int, val ciphertext: ByteArray, val signature: ByteArray)

/**
 * Minimal JSON-over-HTTPS client for the SMS Bridge server (backend/src/main/resources/openapi.yaml).
 * Uses the platform HTTP stack; no request or response bodies are ever logged.
 */
class CloudApi(baseUrl: String, private val token: String? = null) {
    private val base = normalize(baseUrl)

    fun register(pairingCode: String, deviceId: String, name: String, encPublic: ByteArray, signPublic: ByteArray): String =
        call(
            "POST", "/api/v1/devices/register",
            JSONObject()
                .put("pairingCode", pairingCode).put("deviceId", deviceId).put("name", name).put("platform", "android")
                .put("encPublicKey", b64(encPublic)).put("signPublicKey", b64(signPublic)),
        ).getString("deviceToken")

    fun vaultStatus(): VaultStatusDto = call("GET", "/api/v1/vault").let {
        VaultStatusDto(
            it.getBoolean("exists"), if (it.isNull("currentVersion")) null else it.getInt("currentVersion"), it.getBoolean("deviceTrusted"),
            it.optInt("approvedDevices", -1),
        )
    }

    fun vaultInit(recovery: WrappedKeyDto, self: WrappedKeyDto) {
        call("POST", "/api/v1/vault/init", JSONObject().put("recoveryKeys", keys(listOf(recovery))).put("selfKeys", keys(listOf(self))))
    }

    /** Replaces the server's encrypted copy with a new vault; the server allows it only when no device is approved. */
    fun vaultReset(recovery: WrappedKeyDto, self: WrappedKeyDto) {
        call("POST", "/api/v1/vault/reset", JSONObject().put("recoveryKeys", keys(listOf(recovery))).put("selfKeys", keys(listOf(self))))
    }

    fun myKeys(): List<WrappedKeyDto> = parseKeys(call("GET", "/api/v1/vault/keys"))

    fun recoveryKeys(): List<WrappedKeyDto> = parseKeys(call("GET", "/api/v1/vault/recovery"))

    fun recovered(selfKeys: List<WrappedKeyDto>) {
        call("POST", "/api/v1/vault/recovered", JSONObject().put("selfKeys", keys(selfKeys)))
    }

    fun pendingDevices(): List<PendingDeviceDto> {
        val array = callArray("GET", "/api/v1/vault/requests")
        return List(array.length()) { i ->
            val o = array.getJSONObject(i)
            PendingDeviceDto(
                o.getString("id"), o.getString("name"), unb64(o.getString("encPublicKey")), unb64(o.getString("signPublicKey")),
                o.optBoolean("confirmRequested", false),
            )
        }
    }

    fun approve(deviceId: String, keys: List<WrappedKeyDto>) {
        call("POST", "/api/v1/vault/requests/$deviceId/approve", JSONObject().put("keys", keys(keys)))
    }

    /** Number matching: the random challenge the browser turns into its number. */
    fun setChallenge(deviceId: String, salt: ByteArray) {
        call("POST", "/api/v1/vault/requests/$deviceId/challenge", JSONObject().put("salt", b64(salt)))
    }

    fun decline(deviceId: String) {
        call("POST", "/api/v1/vault/requests/$deviceId/decline", JSONObject())
    }

    /** Long-polls the server's content-free change counter; returns it (possibly unchanged after the timeout). */
    fun waitForNews(after: Long, timeoutSeconds: Int): Long =
        call("GET", "/api/v1/notices/wait?after=$after&timeout=$timeoutSeconds").getLong("counter")

    /** Asks to join [username]'s account (no session, no code); the owner confirms the number in the browser. */
    fun createConnectRequest(username: String, name: String): ConnectRequestDto =
        call("POST", "/api/v1/connect-requests", JSONObject().put("username", username).put("name", name)).let {
            ConnectRequestDto(it.getString("requestId"), it.getString("secret"), it.getInt("number"), it.getString("expiresAt"))
        }

    /** "pending", "approved" (with the one-time pairing code), "rejected", "expired" or "delivered". */
    fun connectRequestStatus(id: String, secret: String): Pair<String, String?> =
        call("GET", "/api/v1/connect-requests/$id", headers = mapOf("X-Request-Secret" to secret)).let {
            it.getString("status") to it.optString("pairingCode").takeIf { c -> c.isNotEmpty() }
        }

    /** Returns the acknowledged event ids. */
    fun upload(events: List<UploadEventDto>): Set<String> {
        val body = JSONObject().put(
            "events",
            JSONArray().apply {
                events.forEach {
                    put(
                        JSONObject().put("eventId", it.eventId).put("deviceSequence", it.deviceSequence).put("keyVersion", it.keyVersion)
                            .put("ciphertext", b64(it.ciphertext)).put("signature", b64(it.signature)),
                    )
                }
            },
        )
        val acked = call("POST", "/api/v1/sync/events", body).getJSONArray("acked")
        return (0 until acked.length()).map { acked.getJSONObject(it).getString("eventId") }.toSet()
    }

    private fun keys(list: List<WrappedKeyDto>) = JSONArray().apply {
        list.forEach { put(JSONObject().put("keyVersion", it.keyVersion).put("wrappedKey", b64(it.wrappedKey))) }
    }

    private fun parseKeys(o: JSONObject): List<WrappedKeyDto> {
        val array = o.getJSONArray("keys")
        return List(array.length()) { i -> array.getJSONObject(i).let { WrappedKeyDto(it.getInt("keyVersion"), unb64(it.getString("wrappedKey"))) } }
    }

    private fun call(method: String, path: String, body: JSONObject? = null, headers: Map<String, String> = emptyMap()): JSONObject =
        raw(method, path, body, headers).let { if (it.isBlank()) JSONObject() else JSONObject(it) }

    private fun callArray(method: String, path: String): JSONArray = JSONArray(raw(method, path, null))

    private fun raw(method: String, path: String, body: JSONObject?, headers: Map<String, String> = emptyMap()): String {
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            body?.let { connection.outputStream.use { out -> out.write(it.toString().toByteArray()) } }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val error = runCatching { JSONObject(text) }.getOrNull()
                throw CloudHttpException(status, error?.optString("error") ?: "http_$status", error?.optString("message") ?: "Server error ($status).")
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private val b64e = Base64.getEncoder()
        private val b64d = Base64.getDecoder()
        fun b64(bytes: ByteArray): String = b64e.encodeToString(bytes)
        fun unb64(text: String): ByteArray = b64d.decode(text)

        /**
         * Release builds require HTTPS. Debug builds may also use plain HTTP to the emulator's view
         * of the development machine (10.0.2.2) or localhost; see network_security_config.xml.
         */
        fun normalize(url: String): String {
            val trimmed = url.trim().trimEnd('/')
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val parsed = URL(withScheme)
            val localDev = parsed.host in setOf("10.0.2.2", "localhost", "127.0.0.1")
            require(parsed.protocol == "https" || (parsed.protocol == "http" && BuildConfig.DEBUG && localDev)) {
                "The server address must start with https://"
            }
            return withScheme
        }
    }
}
