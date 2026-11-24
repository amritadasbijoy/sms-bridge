package app.smsbridge.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.smsbridge.data.cloud.CloudHttpException
import app.smsbridge.data.cloud.CloudRepository
import app.smsbridge.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DeleteAccountState(
    /** null: this phone has no account; true: the account has a password to confirm. */
    val needsPassword: Boolean? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val deleted: Boolean = false,
)

@HiltViewModel
class DeleteAccountViewModel @Inject constructor(private val cloud: CloudRepository) : ViewModel() {
    private val _state = MutableStateFlow(DeleteAccountState())
    val state: StateFlow<DeleteAccountState> = _state.asStateFlow()

    fun load() = viewModelScope.launch {
        _state.value = _state.value.copy(needsPassword = runCatching { cloud.accountNeedsPassword() }.getOrNull())
    }

    fun delete(password: String) = viewModelScope.launch {
        _state.value = _state.value.copy(busy = true, error = null)
        _state.value = try {
            cloud.deleteAccount(password.takeIf { it.isNotEmpty() })
            DeleteAccountState(deleted = true)
        } catch (e: CloudHttpException) {
            _state.value.copy(busy = false, error = if (e.code == "wrong_password") "That password is not right." else e.message)
        } catch (e: Exception) {
            _state.value.copy(busy = false, error = "Could not reach the server. Check the connection and try again.")
        }
    }
}

/**
 * Settings → "Delete my account": deletes the account on the server with every linked phone and
 * browser and all stored messages. The SMS on the phone are not touched.
 */
@Composable
fun DeleteAccountCard(onOpenWeb: (String) -> Unit, viewModel: DeleteAccountViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var asking by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { viewModel.load() }

    SectionCard("Delete my account") {
        Text(
            when {
                state.deleted -> "Your account was deleted from the server. This phone is no longer linked."
                state.needsPassword == null -> "This phone is not linked to an account, so nothing about it is stored on the server."
                else -> "Deletes your account, every linked phone and browser, and all messages stored on the server. " +
                    "The SMS on this phone are not touched."
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (state.needsPassword != null && !state.deleted) {
            OutlinedButton(onClick = { asking = true }, modifier = Modifier.padding(top = 8.dp)) { Text("Delete my account…") }
        }
        TextButton(onClick = { onOpenWeb("https://${app.smsbridge.BuildConfig.DEFAULT_SITE}/delete-account/") }) {
            Text("How account deletion works")
        }
    }

    if (asking && !state.deleted) {
        AlertDialog(
            onDismissRequest = { if (!state.busy) asking = false },
            title = { Text("Delete your account?") },
            text = {
                Column {
                    Text("This cannot be undone. Your computers lose access and the messages stored on the server are deleted.")
                    if (state.needsPassword == true) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Account password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.delete(password) },
                    enabled = !state.busy && (state.needsPassword != true || password.isNotEmpty()),
                ) { Text(if (state.busy) "Deleting…" else "Delete everything", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { asking = false }, enabled = !state.busy) { Text("Cancel") } },
        )
    }
}
