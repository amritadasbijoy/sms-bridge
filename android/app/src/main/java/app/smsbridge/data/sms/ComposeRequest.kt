package app.smsbridge.data.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder
import app.smsbridge.core.common.AppLog
import app.smsbridge.data.background.ReceiverEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

/** Recipients and optional prefilled text from an `sms:`/`smsto:`/`mms:`/`mmsto:` URI. */
data class ComposeRequest(val recipients: List<String>, val body: String?) {
    override fun toString(): String = "ComposeRequest(recipients=${recipients.size}, hasBody=${body != null})"

    companion object {
        private val SCHEMES = setOf("sms", "smsto", "mms", "mmsto")

        /**
         * Parses e.g. `smsto:+15550100`, `sms:+15550100,+15550101?body=Hi%20there`, `sms://+1555`.
         * `+` is kept literally (phone numbers), only %XX escapes are decoded.
         * [extraBody] (the `sms_body` or EXTRA_TEXT extra) wins over a `body=` query.
         */
        fun parse(uri: String?, extraBody: String? = null): ComposeRequest? {
            if (uri == null) return null
            val colon = uri.indexOf(':')
            if (colon <= 0 || uri.substring(0, colon).lowercase() !in SCHEMES) return null
            val rest = uri.substring(colon + 1).removePrefix("//")
            val query = rest.substringAfter('?', "")
            val recipients = percentDecode(rest.substringBefore('?'))
                .split(',', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val queryBody = query.split('&')
                .firstOrNull { it.startsWith("body=") }
                ?.removePrefix("body=")
                ?.let(::percentDecode)
            return ComposeRequest(recipients, extraBody ?: queryBody)
        }

        private fun percentDecode(value: String): String {
            if ('%' !in value) return value
            val bytes = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '%' && i + 2 <= value.lastIndex) {
                    val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        bytes.write(hex)
                        i += 3
                        continue
                    }
                }
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return bytes.toString(Charsets.UTF_8.name())
        }
    }
}

/**
 * "Respond via message" (quick replies to an incoming call). Required for the SMS role.
 * Started by the system with the recipient URI and the reply text in EXTRA_TEXT.
 */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = intent?.let { ComposeRequest.parse(it.dataString, it.getStringExtra(Intent.EXTRA_TEXT)) }
        val body = request?.body
        if (request == null || body.isNullOrEmpty() || request.recipients.isEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, ReceiverEntryPoint::class.java)
        entryPoint.applicationScope().launch {
            try {
                request.recipients.forEach { entryPoint.smsSender().send(it, body) }
                AppLog.i("RespondVia", "quick reply sent to ${request.recipients.size} recipient(s)")
            } catch (e: Exception) {
                AppLog.w("RespondVia", "quick reply failed", e)
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }
}
