package app.smsbridge.feature.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.smsbridge.core.model.AddressKey
import app.smsbridge.data.sms.SendResult
import app.smsbridge.data.sms.SmsPermissionManager
import app.smsbridge.data.sms.SmsPermissionState
import app.smsbridge.data.sms.SmsSender
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ComposeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sender: SmsSender,
    permissions: SmsPermissionManager,
) : ViewModel() {
    val initialRecipient: String = savedStateHandle["to"] ?: ""
    val initialBody: String = savedStateHandle["body"] ?: ""
    val permissionState: StateFlow<SmsPermissionState> = permissions.state

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    /** Sends to each comma-separated recipient individually (SMS has no group messages). */
    fun send(recipients: String, body: String, onSent: (addressKey: String) -> Unit) {
        val targets = recipients.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (targets.isEmpty() || body.isBlank()) {
            _error.value = sendErrorText(SendResult.InvalidInput)
            return
        }
        viewModelScope.launch {
            _sending.value = true
            var firstError: String? = null
            targets.forEach { target -> sendErrorText(sender.send(target, body))?.let { firstError = firstError ?: it } }
            _sending.value = false
            _error.value = firstError
            if (firstError == null) onSent(AddressKey.of(targets.first()))
        }
    }
}

@Composable
fun ComposeScreen(
    onBack: () -> Unit,
    onSent: (addressKey: String) -> Unit,
    viewModel: ComposeViewModel = hiltViewModel(),
) {
    val permissions by viewModel.permissionState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    var to by rememberSaveable { mutableStateOf(viewModel.initialRecipient) }
    var body by rememberSaveable { mutableStateOf(viewModel.initialBody) }

    Column(Modifier.fillMaxSize().imePadding().padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("New message", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = to,
                onValueChange = { to = it },
                label = { Text("To (phone number)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("Message") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (!permissions.canSendSms) {
                Text(
                    "SMS Bridge must be your default SMS app to send. You can change this in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = { viewModel.send(to, body, onSent) },
                enabled = permissions.canSendSms && !sending && to.isNotBlank() && body.isNotBlank(),
            ) { Text(if (sending) "Sending…" else "Send") }
        }
    }
}
