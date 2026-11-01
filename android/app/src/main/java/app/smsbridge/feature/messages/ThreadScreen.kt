package app.smsbridge.feature.messages

import android.provider.Telephony
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.smsbridge.core.model.SmsDirection
import app.smsbridge.core.model.SmsMessage
import app.smsbridge.core.model.SmsType
import app.smsbridge.data.notifications.MessageNotifier
import app.smsbridge.data.sms.SendResult
import app.smsbridge.data.sms.SmsPermissionManager
import app.smsbridge.data.sms.SmsPermissionState
import app.smsbridge.data.sms.SmsRepository
import app.smsbridge.data.sms.SmsSender
import app.smsbridge.ui.components.formatTimestamp
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ThreadViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SmsRepository,
    private val sender: SmsSender,
    private val notifier: MessageNotifier,
    private val permissions: SmsPermissionManager,
) : ViewModel() {
    val addressKey: String = savedStateHandle["key"] ?: ""

    val messages: Flow<PagingData<SmsMessage>> = repository.pagedThread(addressKey).cachedIn(viewModelScope)
    val latest: StateFlow<SmsMessage?> =
        repository.latestInThread(addressKey).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val permissionState: StateFlow<SmsPermissionState> = permissions.state

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        notifier.cancelConversation(addressKey)
        // Keep the conversation read while it is open, including messages that arrive meanwhile.
        viewModelScope.launch {
            latest.collect { message ->
                if (permissions.state.value.isDefaultSmsApp && message?.type == SmsType.INBOX && !message.read) {
                    repository.markConversationRead(addressKey)
                    notifier.cancelConversation(addressKey)
                }
            }
        }
    }

    fun send(body: String, onQueued: () -> Unit) {
        val address = latest.value?.address ?: addressKey
        viewModelScope.launch {
            val result = sender.send(address, body, repository.replySubscriptionFor(addressKey))
            _error.value = sendErrorText(result)
            if (result is SendResult.Queued) onQueued()
        }
    }

    fun retry(message: SmsMessage) = send(message.body) {}
}

internal fun sendErrorText(result: SendResult): String? = when (result) {
    is SendResult.Queued -> null
    SendResult.NotDefaultSmsApp -> "SMS Bridge must be your default SMS app to send messages."
    SendResult.InvalidInput -> "Enter a recipient and a message."
    is SendResult.Failed -> "The message could not be sent (${result.reason})."
}

@Composable
fun ThreadScreen(onBack: () -> Unit, viewModel: ThreadViewModel = hiltViewModel()) {
    val latest by viewModel.latest.collectAsStateWithLifecycle()
    val permissions by viewModel.permissionState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val items = viewModel.messages.collectAsLazyPagingItems()
    var draft by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(
                latest?.address ?: "Unknown sender",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
                items[index]?.let { MessageBubble(it, onRetry = { viewModel.retry(it) }, canSend = permissions.canSendSms) }
            }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (permissions.canSendSms) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Text message") },
                    modifier = Modifier.weight(1f),
                    maxLines = 5,
                )
                IconButton(
                    onClick = { viewModel.send(draft) { draft = "" } },
                    enabled = draft.isNotBlank() && latest?.address != null,
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
            }
        } else {
            Text(
                "Archive view. To reply from SMS Bridge, make it your default SMS app in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun MessageBubble(message: SmsMessage, onRetry: () -> Unit, canSend: Boolean) {
    val outgoing = message.type.direction == SmsDirection.OUTGOING
    Box(Modifier.fillMaxWidth(), contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(message.body.ifEmpty { "(empty message)" }, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${formatTimestamp(message.date)} · ${messageStatusLabel(message)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (message.type == SmsType.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (message.type == SmsType.FAILED && canSend) {
                    TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) { Text("Send again") }
                }
            }
        }
    }
}

/** Human-readable state, e.g. "Sending…", "Delivered", "Failed", "Received · SIM 2". */
internal fun messageStatusLabel(message: SmsMessage): String {
    val state = when (message.type) {
        SmsType.INBOX -> if (message.read) "Received" else "Unread"
        SmsType.OUTBOX, SmsType.QUEUED -> "Sending…"
        SmsType.SENT -> when (message.deliveryStatus) {
            Telephony.Sms.STATUS_COMPLETE -> "Delivered"
            Telephony.Sms.STATUS_PENDING -> "Sent, awaiting delivery"
            Telephony.Sms.STATUS_FAILED -> "Not delivered"
            else -> "Sent"
        }
        SmsType.FAILED -> "Failed"
        SmsType.DRAFT -> "Draft"
        SmsType.UNKNOWN -> "Unknown"
    }
    val sim = message.subscriptionId?.let { " · SIM subscription $it" }.orEmpty()
    return state + sim
}
