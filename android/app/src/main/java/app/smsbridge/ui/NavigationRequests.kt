package app.smsbridge.ui

import android.content.Intent
import android.net.Uri
import app.smsbridge.data.notifications.MessageNotifier
import app.smsbridge.data.sms.ComposeRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface NavRequest {
    data class OpenThread(val addressKey: String) : NavRequest
    data class Compose(val recipient: String?, val body: String?) : NavRequest
}

/** Hands navigation requests from Activity intents (notifications, sms: links) to the Compose nav graph. */
@Singleton
class NavigationRequests @Inject constructor() {
    private val _pending = MutableStateFlow<NavRequest?>(null)
    val pending: StateFlow<NavRequest?> = _pending.asStateFlow()

    fun handle(intent: Intent?) {
        intent ?: return
        _pending.value = when (intent.action) {
            MessageNotifier.ACTION_OPEN_THREAD ->
                intent.getStringExtra(MessageNotifier.EXTRA_ADDRESS_KEY)?.let { NavRequest.OpenThread(it) }
            Intent.ACTION_SENDTO -> ComposeRequest.parse(
                intent.dataString,
                intent.getStringExtra("sms_body") ?: intent.getStringExtra(Intent.EXTRA_TEXT),
            )?.let { NavRequest.Compose(it.recipients.joinToString(", ").ifEmpty { null }, it.body) }
            else -> null
        } ?: _pending.value
    }

    fun consume() {
        _pending.value = null
    }
}

object Routes {
    const val HOME = "home"
    const val MESSAGES = "messages"
    const val SETTINGS = "settings"
    const val THREAD = "thread?key={key}"
    const val COMPOSE = "compose?to={to}&body={body}"

    fun thread(addressKey: String) = "thread?key=${Uri.encode(addressKey)}"

    fun compose(to: String? = null, body: String? = null) =
        "compose?to=${Uri.encode(to.orEmpty())}&body=${Uri.encode(body.orEmpty())}"
}
