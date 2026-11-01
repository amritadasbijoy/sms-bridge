package app.smsbridge.feature.sync

import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.smsbridge.data.cloud.CloudHttpException
import app.smsbridge.data.cloud.CloudRepository
import app.smsbridge.data.cloud.CloudState
import app.smsbridge.data.cloud.PendingApproval
import app.smsbridge.data.settings.AppSettingsRepository
import app.smsbridge.data.sync.PendingSyncRepository
import app.smsbridge.data.sync.SyncScheduler
import app.smsbridge.ui.components.SectionCard
import app.smsbridge.ui.components.findActivity
import app.smsbridge.ui.components.formatTimestamp
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val cloud: CloudRepository,
    pendingSync: PendingSyncRepository,
    private val settings: AppSettingsRepository,
    private val scheduler: SyncScheduler,
) : ViewModel() {
    val state: StateFlow<CloudState> = cloud.state
    val pending: StateFlow<Int> = pendingSync.pendingCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val lastSynced: StateFlow<Long?> = pendingSync.lastSyncedAt.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val paused: StateFlow<Boolean> = settings.syncPaused.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _approvals = MutableStateFlow<List<PendingApproval>>(emptyList())
    val approvals: StateFlow<List<PendingApproval>> = _approvals.asStateFlow()

    private val _wasRevoked = MutableStateFlow(false)
    val wasRevoked: StateFlow<Boolean> = _wasRevoked.asStateFlow()
    private val _lastServer = MutableStateFlow("")
    val lastServer: StateFlow<String> = _lastServer.asStateFlow()

    init {
        viewModelScope.launch {
            cloud.refresh()
            _wasRevoked.value = cloud.wasRevoked()
            _lastServer.value = cloud.lastServer().orEmpty()
        }
    }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            try {
                block()
            } catch (e: CloudHttpException) {
                _error.value = e.message
            } catch (e: IllegalArgumentException) {
                _error.value = e.message
            } catch (e: IOException) {
                _error.value = "Could not reach the server. Check the address and your connection."
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never crash the screen; report the class only (messages may come from crypto libraries).
                app.smsbridge.core.common.AppLog.w("Sync", "sync action failed", e)
                _error.value = "Something went wrong (${e.javaClass.simpleName}). Try again."
            } finally {
                _busy.value = false
            }
        }
    }

    fun connect(server: String, code: String) = run {
        cloud.connect(server, code)
        _wasRevoked.value = false
    }
    fun confirmRecoverySaved() = run { cloud.confirmRecoverySaved() }
    fun checkApproval() = run { cloud.checkApproval() }
    fun startOver() = run { cloud.startOver() }
    fun recover(text: String) = run { cloud.recover(text) }
    fun loadApprovals() = run { _approvals.value = cloud.pendingApprovals() }
    fun approve(request: PendingApproval) = run {
        cloud.approve(request)
        _approvals.value = cloud.pendingApprovals()
    }
    fun disconnect() = run { cloud.disconnect() }
    fun syncNow() = scheduler.scheduleSync()
    fun setPaused(paused: Boolean) {
        viewModelScope.launch {
            settings.setSyncPaused(paused)
            if (!paused) scheduler.scheduleSync()
        }
    }
}

@Composable
fun SyncScreen(viewModel: SyncViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    LazyColumn(Modifier.imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Cloud sync", style = MaterialTheme.typography.headlineSmall) }
        item {
            Text(
                "Optional. Copies your archive to your own SMS Bridge server, end-to-end encrypted: the " +
                    "server stores only data it cannot read. Everything keeps working without it.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        when (val s = state) {
            CloudState.NotConnected -> item {
                val revoked by viewModel.wasRevoked.collectAsStateWithLifecycle()
                val lastServer by viewModel.lastServer.collectAsStateWithLifecycle()
                ConnectCard(busy, revoked, lastServer, viewModel::connect)
            }
            is CloudState.NeedsRecoverySetup -> item { RecoveryKeyCard(s.recoveryKey, busy, viewModel::confirmRecoverySaved) }
            is CloudState.WaitingForApproval -> item { WaitingCard(s, busy, viewModel) }
            is CloudState.Ready -> {
                item { ConnectedCard(s, viewModel) }
                item { ApprovalsCard(viewModel, busy) }
            }
        }
    }
}

@Composable
private fun ConnectCard(busy: Boolean, wasRevoked: Boolean, lastServer: String, onConnect: (String, String) -> Unit) {
    var server by rememberSaveable(lastServer) { mutableStateOf(lastServer) }
    var code by rememberSaveable { mutableStateOf("") }
    SectionCard("Connect to your server") {
        if (wasRevoked) {
            Text(
                "This phone was removed from the server (revoked on the Devices page), so it stopped syncing. " +
                    "Create a new pairing code on the Devices page to connect it again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text(
            "Sign in to your server in a browser, create a pairing code (valid 10 minutes), then enter it here. " +
                "No password is typed on the phone.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        OutlinedTextField(
            value = server, onValueChange = { server = it }, label = { Text("Server address") },
            placeholder = { Text("https://sms.example.org") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = code, onValueChange = { code = it }, label = { Text("Pairing code") },
            placeholder = { Text("XXXXX-XXXXX") }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Button(onClick = { onConnect(server, code) }, enabled = !busy && server.isNotBlank() && code.isNotBlank(), modifier = Modifier.padding(top = 8.dp)) {
            Text(if (busy) "Connecting…" else "Connect")
        }
    }
}

/** Shown once. The window is marked secure so the key cannot be captured in screenshots or recents. */
@Composable
private fun RecoveryKeyCard(recoveryKey: String, busy: Boolean, onConfirm: () -> Unit) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    var saved by rememberSaveable { mutableStateOf(false) }
    SectionCard("Write down your recovery key") {
        Text(
            "If you lose every phone, this key is the only way to read your server copy. SMS Bridge and your " +
                "server cannot recover it. Write it on paper and keep it safe; do not store it in a photo or note app.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(recoveryKey, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 12.dp))
        // The whole row toggles (not just the small box), as accessibility guidelines require.
        Row(
            Modifier.fillMaxWidth().toggleable(value = saved, role = Role.Checkbox, onValueChange = { saved = it }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = saved, onCheckedChange = null)
            Text("I have written down the recovery key")
        }
        Button(onClick = onConfirm, enabled = saved && !busy) { Text(if (busy) "Setting up…" else "Continue") }
    }
}

@Composable
private fun WaitingCard(state: CloudState.WaitingForApproval, busy: Boolean, viewModel: SyncViewModel) {
    var recovery by rememberSaveable { mutableStateOf("") }
    var confirmStartOver by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.checkApproval() }
    if (state.canStartOver) {
        SectionCard("No approved device is left") {
            Text(
                "Nothing on this account can approve this phone any more (the approved devices were disconnected or revoked). " +
                    "Start over to make this phone the approved one again.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Button(onClick = { confirmStartOver = true }, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) { Text("Start over with this phone") }
        }
    }
    if (confirmStartOver) {
        AlertDialog(
            onDismissRequest = { confirmStartOver = false },
            title = { Text("Start over with this phone?") },
            text = {
                Text(
                    "The server's encrypted copy of your messages is deleted and replaced with this phone's messages, which are " +
                        "uploaded again. Messages on this phone are not touched. You get a new recovery key, and browsers and " +
                        "other phones must be approved again.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmStartOver = false; viewModel.startOver() }) { Text("Start over") } },
            dismissButton = { TextButton(onClick = { confirmStartOver = false }) { Text("Cancel") } },
        )
    }
    SectionCard("Waiting for approval") {
        Text(
            "This account already syncs from another phone. On that phone, open Sync → Devices waiting for approval, " +
                "and approve this phone only if it shows the same code:",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(state.safetyCode, style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 8.dp))
        Button(onClick = viewModel::checkApproval, enabled = !busy) { Text(if (busy) "Checking…" else "I approved it, check now") }
        Text("No other phone? Use your recovery key instead:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        OutlinedTextField(
            value = recovery, onValueChange = { recovery = it }, label = { Text("Recovery key") }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrect = false),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { viewModel.recover(recovery); recovery = "" }, enabled = !busy && recovery.isNotBlank(), modifier = Modifier.padding(top = 4.dp)) {
            Text("Use recovery key")
        }
        TextButton(onClick = viewModel::disconnect) { Text("Cancel and disconnect") }
    }
}

@Composable
private fun ConnectedCard(state: CloudState.Ready, viewModel: SyncViewModel) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val lastSynced by viewModel.lastSynced.collectAsStateWithLifecycle()
    val paused by viewModel.paused.collectAsStateWithLifecycle()
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
    SectionCard(
        when {
            paused -> "Sync: Paused"
            pending > 0 -> "Sync: Pending"
            else -> "Sync: Up to date"
        },
    ) {
        Text("Connected to ${state.server}", modifier = Modifier.padding(top = 4.dp))
        Text(if (pending > 0) "$pending changes waiting to upload." else "Everything is uploaded.", style = MaterialTheme.typography.bodyMedium)
        lastSynced?.let { Text("Last upload ${formatTimestamp(it)}", style = MaterialTheme.typography.bodySmall) }
        Text("This phone's safety code: ${state.safetyCode}", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Pause sync")
            Switch(checked = paused, onCheckedChange = viewModel::setPaused)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::syncNow, enabled = !paused) { Text("Sync now") }
            TextButton(onClick = { confirmDisconnect = true }) { Text("Disconnect") }
        }
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect this phone?") },
            text = {
                Text(
                    "This phone forgets the server and its keys. Your messages on this phone and on the server are not deleted.\n\n" +
                        "If this is your only approved phone, nothing can approve it again afterwards: you would need the " +
                        "recovery key, or to start over (the server copy is replaced with this phone's messages).",
                )
            },
            confirmButton = { TextButton(onClick = { confirmDisconnect = false; viewModel.disconnect() }) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ApprovalsCard(viewModel: SyncViewModel, busy: Boolean) {
    val approvals by viewModel.approvals.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { viewModel.loadApprovals() }
    SectionCard("Devices waiting for approval") {
        if (approvals.isEmpty()) {
            Text("None. When you connect another phone or open the web dashboard in a new browser, it appears here.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
        approvals.forEach { request ->
            Column(Modifier.padding(top = 8.dp)) {
                Text(request.name, style = MaterialTheme.typography.titleSmall)
                Text("Safety code: ${request.safetyCode}", fontFamily = FontFamily.Monospace)
                Button(onClick = { confirm = request.deviceId }, enabled = !busy, modifier = Modifier.padding(top = 4.dp)) { Text("Approve…") }
            }
        }
        OutlinedButton(onClick = viewModel::loadApprovals, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) { Text("Refresh") }
    }
    approvals.firstOrNull { it.deviceId == confirm }?.let { request ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Approve ${request.name}?") },
            text = {
                Text(
                    "Approve only if ${request.name} shows exactly ${request.safetyCode}. If the codes differ, someone may be " +
                        "trying to intercept your messages: tap Cancel.",
                )
            },
            confirmButton = { TextButton(onClick = { confirm = null; viewModel.approve(request) }) { Text("Codes match, approve") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}
