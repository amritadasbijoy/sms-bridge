package app.smsbridge.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.smsbridge.core.database.entity.DeviceEntity
import app.smsbridge.data.background.SmsCaptureCoordinator
import app.smsbridge.data.settings.AppSettingsRepository
import app.smsbridge.feature.permissions.DefaultSmsAppCard
import app.smsbridge.data.settings.LocalDeviceRepository
import app.smsbridge.data.sms.SmsPermissionManager
import app.smsbridge.data.sms.SmsPermissionState
import app.smsbridge.data.sync.SyncScheduler
import app.smsbridge.ui.components.SectionCard
import app.smsbridge.ui.components.openAppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val syncPaused: Boolean = false,
    val permissions: SmsPermissionState = SmsPermissionState(),
    val device: DeviceEntity? = null,
    val deliveryReports: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: AppSettingsRepository,
    private val scheduler: SyncScheduler,
    val permissions: SmsPermissionManager,
    private val coordinator: SmsCaptureCoordinator,
    devices: LocalDeviceRepository,
) : ViewModel() {

    init {
        // Make sure the local device row exists so it can be shown.
        viewModelScope.launch { devices.localDeviceId() }
    }

    val uiState: StateFlow<SettingsUiState> =
        combine(
            settings.syncPaused,
            permissions.state,
            devices.localDevice,
            settings.deliveryReportsEnabled,
        ) { paused, perms, device, deliveryReports ->
            SettingsUiState(paused, perms, device, deliveryReports)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun onPermissionsChanged() = coordinator.start()

    fun setDeliveryReports(enabled: Boolean) {
        viewModelScope.launch { settings.setDeliveryReportsEnabled(enabled) }
    }

    fun setSyncPaused(paused: Boolean) {
        viewModelScope.launch {
            settings.setSyncPaused(paused)
            if (!paused) scheduler.scheduleSync()
        }
    }

    fun rescanAll() = scheduler.requestFullRescan()
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmRescan by rememberSaveable { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall) }

        item {
            SectionCard("Synchronization") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Pause synchronization", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = state.syncPaused, onCheckedChange = viewModel::setSyncPaused)
                }
                Text(
                    "While paused, nothing is uploaded. Messages are still captured and kept on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item {
            SectionCard("Permissions") {
                PermissionLine("Read existing messages", state.permissions.canReadSms)
                PermissionLine("Capture new incoming messages", state.permissions.canReceiveSms)
                OutlinedButton(onClick = { context.openAppSettings() }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Open app settings")
                }
            }
        }

        item {
            DefaultSmsAppCard(
                state = state.permissions,
                createRequestIntent = viewModel.permissions::createDefaultSmsRequestIntent,
                openDefaultAppsSettings = { context.startActivity(viewModel.permissions.defaultAppsSettingsIntent()) },
                onChanged = viewModel::onPermissionsChanged,
            )
        }

        if (state.permissions.isDefaultSmsApp) {
            item {
                SectionCard("Sending") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Request delivery reports", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(checked = state.deliveryReports, onCheckedChange = viewModel::setDeliveryReports)
                    }
                    Text(
                        "Shows \"Delivered\" when the recipient's phone confirms receipt. Some carriers " +
                            "charge for or do not support delivery reports. Messages use your default SMS SIM; " +
                            "replies use the SIM the conversation last arrived on.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            SectionCard("Background operation") {
                Text(
                    "New messages are saved as soon as Android delivers them. Other checks run in the " +
                        "background about once an hour. Battery saving (Doze), some phone makers' " +
                        "battery managers, and force-stopping the app can delay or stop these checks " +
                        "until you open SMS Bridge again.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        item {
            SectionCard("Archive") {
                Text(
                    "Re-read every message on this phone and add anything that is missing. " +
                        "Existing archive entries are kept; nothing is deleted.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                OutlinedButton(
                    onClick = { confirmRescan = true },
                    enabled = state.permissions.canReadSms,
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Rescan all messages") }
            }
        }

        item {
            SectionCard("This device") {
                Text(state.device?.name ?: "—", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
                state.device?.let {
                    Text("Device ID ${it.id.take(8)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            SectionCard("Privacy") {
                Text(
                    "Messages are stored only in SMS Bridge's private storage on this phone and are " +
                        "excluded from Android cloud backup. App logs never contain message text or " +
                        "phone numbers. Cloud sync, when it arrives, will be end-to-end encrypted and optional.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }

    if (confirmRescan) {
        AlertDialog(
            onDismissRequest = { confirmRescan = false },
            title = { Text("Rescan all messages?") },
            text = { Text("This can take a minute on phones with many messages. Nothing will be deleted.") },
            confirmButton = {
                TextButton(onClick = { confirmRescan = false; viewModel.rescanAll() }) { Text("Rescan") }
            },
            dismissButton = { TextButton(onClick = { confirmRescan = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PermissionLine(label: String, granted: Boolean) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            if (granted) "Allowed" else "Not allowed",
            style = MaterialTheme.typography.labelLarge,
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}
