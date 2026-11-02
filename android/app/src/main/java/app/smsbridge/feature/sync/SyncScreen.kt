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
import androidx.compose.runtime.remember
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
import app.smsbridge.data.cloud.PairLinks
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val cloud: CloudRepository,
    pendingSync: PendingSyncRepository,
    private val settings: AppSettingsRepository,
    private val scheduler: SyncScheduler,
    private val navigationRequests: app.smsbridge.ui.NavigationRequests,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val db: app.smsbridge.core.database.SmsBridgeDatabase,
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
                if (e.status == 401 && cloud.state.value !is CloudState.NotConnected) {
                    // Revoked on the Devices page: show the reconnect screen, not "connected" plus an error.
                    cloud.handleRevoked()
                    _wasRevoked.value = true
                } else {
                    _error.value = e.message
                }
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

    private val _info = MutableStateFlow<String?>(null)
    val info: StateFlow<String?> = _info.asStateFlow()
    private val _recoveryReminder = MutableStateFlow<String?>(null)
    val recoveryReminder: StateFlow<String?> = _recoveryReminder.asStateFlow()

    fun connect(server: String, code: String) {
        // A pasted dashboard link works like a scan.
        if (server.trim().startsWith("smsbridge://")) return onScanned(server)
        run {
            cloud.connect(server, code)
            _wasRevoked.value = false
        }
    }

    /** A QR code from the dashboard: connect and/or approve the browser it names. */
    fun onScanned(text: String) = run {
        _info.value = null
        PairLinks.parseLogin(text)?.let { login ->
            _info.value = "Signed in ${cloud.approveScannedLogin(login)}."
            return@run
        }
        val link = PairLinks.parse(text) ?: throw IllegalArgumentException("That is not an SMS Bridge QR code. Scan the one shown on your dashboard.")
        _info.value = cloud.handleLink(link)
        _wasRevoked.value = false
        _recoveryReminder.value = cloud.recoveryReminder()
    }

    // ---- Number matching ----
    private val _confirm = MutableStateFlow<app.smsbridge.data.cloud.ConfirmPrompt?>(null)
    val confirm: StateFlow<app.smsbridge.data.cloud.ConfirmPrompt?> = _confirm.asStateFlow()

    fun startConfirm(request: PendingApproval) = run {
        _confirm.value = if (request.loginRequestId != null) cloud.startLoginConfirm(request) else cloud.startConfirm(request)
    }

    /** [picked] null = "none of these" (declines). */
    fun pickNumber(picked: Int?) = run {
        val prompt = _confirm.value ?: return@run
        _confirm.value = null
        _info.value = if (cloud.finishConfirm(prompt, picked)) "Approved ${prompt.request.name}." else "Not approved: the numbers did not match. Ask again from the browser."
        _approvals.value = cloud.pendingApprovals()
    }

    private val _connectWait = MutableStateFlow<app.smsbridge.data.cloud.ConnectWait?>(null)
    val connectWait: StateFlow<app.smsbridge.data.cloud.ConnectWait?> = _connectWait.asStateFlow()

    /** Connect without a QR code: the owner taps the number this phone shows in the browser. */
    fun connectByNumber(server: String, username: String) = run {
        val wait = cloud.requestConnect(server, username)
        _connectWait.value = wait
        viewModelScope.launch {
            while (_connectWait.value == wait) {
                kotlinx.coroutines.delay(2_000)
                try {
                    if (cloud.checkConnect(wait)) {
                        _connectWait.value = null
                        _info.value = "Connected."
                    }
                } catch (e: IllegalArgumentException) {
                    _connectWait.value = null
                    _error.value = e.message
                } catch (e: IOException) {
                    // keep waiting through brief network trouble
                }
            }
        }
    }

    fun cancelConnectWait() { _connectWait.value = null }

    // ---- Listener mode ----
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()
    val remoteCount: StateFlow<Int> = db.remoteMessageDao().observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun loadListening() = viewModelScope.launch { _listening.value = cloud.isListening() }
    fun setListening(on: Boolean) = run {
        cloud.setListening(on)
        _listening.value = cloud.isListening()
    }

    /** null = not asked; 0 = asked, waiting for the approving phone; otherwise the number to show. */
    private val _selfConfirm = MutableStateFlow<Int?>(null)
    val selfConfirm: StateFlow<Int?> = _selfConfirm.asStateFlow()

    /** This waiting phone asks to be confirmed by number, then shows its number and waits for approval. */
    fun confirmSelfByNumber() = run {
        cloud.requestConfirmSelf()
        _selfConfirm.value = 0
        viewModelScope.launch {
            while (_selfConfirm.value != null && cloud.state.value is CloudState.WaitingForApproval) {
                kotlinx.coroutines.delay(2_000)
                runCatching {
                    _selfConfirm.value = cloud.myConfirmNumber() ?: 0
                    cloud.checkApproval()
                }
            }
            _selfConfirm.value = null
        }
    }

    fun loadRecoveryReminder() = viewModelScope.launch { _recoveryReminder.value = cloud.recoveryReminder() }
    fun dismissRecoveryReminder() = viewModelScope.launch {
        cloud.dismissRecoveryReminder()
        _recoveryReminder.value = null
    }
    fun confirmRecoveryLater() = run {
        cloud.confirmRecoverySaved(remindLater = true)
        _recoveryReminder.value = cloud.recoveryReminder()
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
    private val _history = MutableStateFlow<app.smsbridge.data.cloud.HistoryStatus?>(null)
    val history: StateFlow<app.smsbridge.data.cloud.HistoryStatus?> = _history.asStateFlow()
    fun loadHistory() = viewModelScope.launch { _history.value = cloud.historyStatus() }
    fun uploadFullHistory() = run {
        cloud.uploadFullHistory()
        _history.value = cloud.historyStatus()
    }
    fun setPaused(paused: Boolean) {
        viewModelScope.launch {
            settings.setSyncPaused(paused)
            if (!paused) scheduler.scheduleSync()
        }
    }

    // Last, so every property above is initialised: a pairing link opened on this phone acts like a scan,
    // the approval notification opens the number prompt, and a connected phone keeps its connection.
    init {
        viewModelScope.launch {
            navigationRequests.pairLink.collect { if (it != null) navigationRequests.takePairLink()?.let(::onScanned) }
        }
        viewModelScope.launch {
            navigationRequests.confirmDevice.collect { id ->
                if (id == null) return@collect
                navigationRequests.takeConfirmDevice()
                run {
                    val all = cloud.pendingApprovals()
                    _approvals.value = all
                    val logins = cloud.loginAsks()
                    _confirm.value = logins.firstOrNull { it.loginRequestId == id }?.let { cloud.startLoginConfirm(it) }
                        ?: all.firstOrNull { it.deviceId == id }?.let { cloud.startConfirm(it) }
                        ?: throw IllegalArgumentException("That request is no longer waiting.")
                }
            }
        }
        viewModelScope.launch {
            cloud.state.collect { if (it is CloudState.Ready) app.smsbridge.data.cloud.CloudConnectionService.start(appContext) }
        }
    }
}

@Composable
fun SyncScreen(viewModel: SyncViewModel = hiltViewModel(), onOpenRemote: () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val info by viewModel.info.collectAsStateWithLifecycle()

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
        info?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        item { ConfirmNumberDialog(viewModel) }
        item { app.smsbridge.feature.update.UpdateBanner() }
        when (val s = state) {
            CloudState.NotConnected -> {
                item {
                    val revoked by viewModel.wasRevoked.collectAsStateWithLifecycle()
                    val lastServer by viewModel.lastServer.collectAsStateWithLifecycle()
                    ConnectCard(busy, revoked, lastServer, viewModel::connect, viewModel::onScanned)
                }
                item {
                    val lastServer by viewModel.lastServer.collectAsStateWithLifecycle()
                    ConnectByNumberCard(viewModel, busy, lastServer)
                }
            }
            is CloudState.NeedsRecoverySetup -> item { RecoveryKeyCard(s.recoveryKey, busy, viewModel::confirmRecoverySaved, viewModel::confirmRecoveryLater) }
            is CloudState.WaitingForApproval -> item { WaitingCard(s, busy, viewModel) }
            is CloudState.Ready -> {
                item { RecoveryReminderCard(viewModel) }
                item { StayReachableCard() }
                item { ConnectedCard(s, viewModel) }
                item { ListenCard(viewModel, onOpenRemote) }
                item { ApprovalsCard(viewModel, busy) }
            }
        }
    }
}

@Composable
private fun ConnectCard(busy: Boolean, wasRevoked: Boolean, lastServer: String, onConnect: (String, String) -> Unit, onScanned: (String) -> Unit) {
    var server by rememberSaveable(lastServer) { mutableStateOf(lastServer) }
    var code by rememberSaveable { mutableStateOf("") }
    var manual by rememberSaveable { mutableStateOf(false) }
    val scan = rememberQrScanner(onScanned)
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
            "Open your SMS Bridge dashboard in a browser, then scan the QR code it shows.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Button(onClick = { scan("Scan the QR code on your SMS Bridge dashboard") }, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) {
            Text(if (busy) "Connecting…" else "Scan QR code")
        }
        if (!manual) {
            TextButton(onClick = { manual = true }) { Text("Enter the address and code instead") }
        } else {
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
            OutlinedButton(
                onClick = { onConnect(server, code) },
                enabled = !busy && server.isNotBlank() && (code.isNotBlank() || server.trim().startsWith("smsbridge://")),
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Connect") }
        }
    }
}

/** Listener mode: show SMS from the account's other phones on this one, live. */
@Composable
private fun ListenCard(viewModel: SyncViewModel, onOpenRemote: () -> Unit) {
    val listening by viewModel.listening.collectAsStateWithLifecycle()
    val count by viewModel.remoteCount.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadListening() }
    SectionCard("Messages from your other phones") {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp).toggleable(value = listening, role = Role.Switch, enabled = !busy, onValueChange = viewModel::setListening),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Receive SMS from my other phones", modifier = Modifier.weight(1f))
            Switch(checked = listening, onCheckedChange = null)
        }
        Text(
            if (listening) "New SMS on your other phones appear here and as notifications, even when SMS Bridge is closed."
            else "Turn on for a phone you use to read messages from your other phones.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (listening) {
            Button(onClick = onOpenRemote, modifier = Modifier.padding(top = 8.dp)) { Text("Open ($count)") }
        }
    }
}

/** Connect without a camera: this phone shows a number, the owner taps it in the browser. */
@Composable
private fun ConnectByNumberCard(viewModel: SyncViewModel, busy: Boolean, lastServer: String) {
    val wait by viewModel.connectWait.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    var server by rememberSaveable(lastServer) { mutableStateOf(lastServer) }
    var username by rememberSaveable { mutableStateOf("") }
    val w = wait
    if (w != null) {
        SectionCard("Confirm in your browser") {
            Text("Sign in to your dashboard in a browser. It asks which number this phone shows. Tap:", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            Text("${w.number}", style = MaterialTheme.typography.displayLarge, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 12.dp))
            Text("Waiting… this expires in 10 minutes.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = viewModel::cancelConnectWait) { Text("Cancel") }
        }
        return
    }
    if (!open) {
        TextButton(onClick = { open = true }) { Text("No camera? Confirm with a number instead") }
        return
    }
    SectionCard("Connect with a number") {
        OutlinedTextField(
            value = server, onValueChange = { server = it }, label = { Text("Server address") },
            placeholder = { Text("https://sms.example.org") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = username, onValueChange = { username = it }, label = { Text("Your username") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Button(
            onClick = { viewModel.connectByNumber(server, username) },
            enabled = !busy && server.isNotBlank() && username.isNotBlank(),
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("Continue") }
    }
}

/** "Which number does the browser show?" Three choices; anything but the right one declines. */
@Composable
private fun ConfirmNumberDialog(viewModel: SyncViewModel) {
    val prompt by viewModel.confirm.collectAsStateWithLifecycle()
    val p = prompt ?: return
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Which number does ${p.request.name} show?") },
        text = {
            Column {
                Text("Tap the number shown in the browser. If none matches, someone else may be trying to sign in.", style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    p.options.forEach { n ->
                        Button(onClick = { viewModel.pickNumber(n) }) { Text("$n", style = MaterialTheme.typography.headlineSmall) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { viewModel.pickNumber(null) }) { Text("None of these") } },
    )
}

/** Notifications and battery settings the background connection needs. Hidden once both are fine. */
@Composable
private fun StayReachableCard() {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val notificationsOk = remember(refresh) { androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val batteryOk = remember(refresh) {
        context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { refresh++ }
    if (notificationsOk && batteryOk) return
    SectionCard("Stay reachable") {
        Text(
            "So browsers can be confirmed and SMS arrive instantly even when SMS Bridge is closed:",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (!notificationsOk) {
            Button(onClick = {
                if (android.os.Build.VERSION.SDK_INT >= 33) askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
            }, modifier = Modifier.padding(top = 8.dp)) { Text("Allow notifications") }
        }
        if (!batteryOk) {
            OutlinedButton(onClick = {
                @android.annotation.SuppressLint("BatteryLife")
                val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(intent) }
            }, modifier = Modifier.padding(top = 8.dp)) { Text("Allow running in the background") }
        }
    }
}

/** Opens the QR scanner; the scanned text goes to [onResult]. */
@Composable
private fun rememberQrScanner(onResult: (String) -> Unit): (String) -> Unit {
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(onResult)
    }
    return { prompt ->
        launcher.launch(
            ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(prompt).setBeepEnabled(false).setOrientationLocked(false),
        )
    }
}

/** Shown until the user saves a recovery key they postponed. */
@Composable
private fun RecoveryReminderCard(viewModel: SyncViewModel) {
    val key by viewModel.recoveryReminder.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadRecoveryReminder() }
    val recovery = key ?: return
    var show by rememberSaveable { mutableStateOf(false) }
    SectionCard("Save your recovery key") {
        Text(
            "If you lose every phone, this key is the only way to read your server copy. Write it on paper.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (show) {
            SecureWindow()
            Text(recovery, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 12.dp))
            Button(onClick = viewModel::dismissRecoveryReminder) { Text("I have written it down") }
        } else {
            OutlinedButton(onClick = { show = true }, modifier = Modifier.padding(top = 8.dp)) { Text("Show recovery key") }
        }
    }
}

/** Blocks screenshots and the recents thumbnail while composed. */
@Composable
private fun SecureWindow() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** Shown once. The window is marked secure so the key cannot be captured in screenshots or recents. */
@Composable
private fun RecoveryKeyCard(recoveryKey: String, busy: Boolean, onConfirm: () -> Unit, onLater: () -> Unit) {
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
        TextButton(onClick = onLater, enabled = !busy) { Text("Remind me later") }
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
        val selfNumber by viewModel.selfConfirm.collectAsStateWithLifecycle()
        when (val n = selfNumber) {
            null -> OutlinedButton(onClick = viewModel::confirmSelfByNumber, enabled = !busy, modifier = Modifier.padding(top = 4.dp)) {
                Text("Confirm with a number instead")
            }
            0 -> Text("Your approved phone got a notification. Open it there…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            else -> {
                Text("On your approved phone, tap:", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                Text("$n", style = MaterialTheme.typography.displayLarge, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 8.dp))
            }
        }
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
        HistoryRow(viewModel)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Pause sync")
            Switch(checked = paused, onCheckedChange = viewModel::setPaused)
        }
        val scan = rememberQrScanner(viewModel::onScanned)
        Button(onClick = { scan("Scan the QR code your browser shows") }, modifier = Modifier.padding(top = 8.dp)) { Text("Scan a browser's QR code") }
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
private fun HistoryRow(viewModel: SyncViewModel) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.loadHistory() }
    val h = history ?: return
    when {
        h.fullHistoryQueued || h.uploadedFrom == null ->
            Text("Uploading new messages instantly, plus your full history.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        else -> {
            Text(
                "New messages upload instantly. Messages since ${formatTimestamp(h.uploadedFrom)} are on the server; " +
                    "older ones stay only on this phone.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(onClick = { confirm = true }) { Text("Upload full history…") }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Upload your full history?") },
            text = { Text("Every older message in this phone's archive is encrypted and uploaded, newest to oldest. New messages keep going first. This can take a while for large archives.") },
            confirmButton = { TextButton(onClick = { confirm = false; viewModel.uploadFullHistory() }) { Text("Upload") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
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
                if (request.confirmRequested) {
                    Button(onClick = { viewModel.startConfirm(request) }, enabled = !busy, modifier = Modifier.padding(top = 4.dp)) { Text("Confirm with a number") }
                    OutlinedButton(onClick = { confirm = request.deviceId }, enabled = !busy) { Text("Compare codes instead…") }
                } else {
                    Button(onClick = { confirm = request.deviceId }, enabled = !busy, modifier = Modifier.padding(top = 4.dp)) { Text("Approve…") }
                }
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
