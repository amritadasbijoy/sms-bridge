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
    /** A dashboard pairing link opened on the phone (smsbridge://pair?...): handled by the Sync screen. */
    data object OpenSync : NavRequest
    /** Listener mode notification: the messages from the account's other phones. */
    data object OpenRemote : NavRequest
}

/** Hands navigation requests from Activity intents (notifications, sms: links) to the Compose nav graph. */
@Singleton
class NavigationRequests @Inject constructor() {
    private val _pending = MutableStateFlow<NavRequest?>(null)
    val pending: StateFlow<NavRequest?> = _pending.asStateFlow()

    private val _pairLink = MutableStateFlow<String?>(null)
    /** The last opened pairing link, until the Sync screen takes it. */
    val pairLink: StateFlow<String?> = _pairLink.asStateFlow()

    fun takePairLink(): String? = _pairLink.value.also { _pairLink.value = null }

    private val _confirmDevice = MutableStateFlow<String?>(null)
    /** A browser to confirm by number, from the approval notification. */
    val confirmDevice: StateFlow<String?> = _confirmDevice.asStateFlow()

    fun takeConfirmDevice(): String? = _confirmDevice.value.also { _confirmDevice.value = null }

    fun handle(intent: Intent?) {
        intent ?: return
        _pending.value = when (intent.action) {
            MessageNotifier.ACTION_OPEN_THREAD ->
                intent.getStringExtra(MessageNotifier.EXTRA_ADDRESS_KEY)?.let { NavRequest.OpenThread(it) }
            Intent.ACTION_SENDTO -> ComposeRequest.parse(
                intent.dataString,
                intent.getStringExtra("sms_body") ?: intent.getStringExtra(Intent.EXTRA_TEXT),
            )?.let { NavRequest.Compose(it.recipients.joinToString(", ").ifEmpty { null }, it.body) }
            app.smsbridge.data.cloud.CloudConnectionService.ACTION_CONFIRM_DEVICE ->
                intent.getStringExtra(app.smsbridge.data.cloud.CloudConnectionService.EXTRA_DEVICE_ID)?.let {
                    _confirmDevice.value = it
                    NavRequest.OpenSync
                }
            app.smsbridge.data.cloud.CloudConnectionService.ACTION_OPEN_REMOTE -> NavRequest.OpenRemote
            Intent.ACTION_VIEW -> intent.dataString?.takeIf { it.startsWith("smsbridge://pair?") || it.startsWith("smsbridge://login?") }?.let {
                _pairLink.value = it
                NavRequest.OpenSync
            }
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
    const val BACKUP = "backup"
    const val TRANSFER = "transfer"
    const val SYNC = "sync"
    const val SETTINGS = "settings"
    const val REMOTE = "remote"
    const val THREAD = "thread?key={key}"
    const val COMPOSE = "compose?to={to}&body={body}"

    fun thread(addressKey: String) = "thread?key=${Uri.encode(addressKey)}"

    fun compose(to: String? = null, body: String? = null) =
        "compose?to=${Uri.encode(to.orEmpty())}&body=${Uri.encode(body.orEmpty())}"
}
