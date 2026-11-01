package app.smsbridge.feature.backup

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
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
import androidx.work.WorkInfo
import app.smsbridge.core.database.entity.BackupHistoryEntity
import app.smsbridge.data.background.SmsCaptureCoordinator
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.remember
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.smsbridge.data.backup.BackupInfo
import app.smsbridge.data.backup.BackupProbe
import app.smsbridge.data.backup.EncryptedBackup
import app.smsbridge.data.backup.EphemeralKeys
import app.smsbridge.data.backup.InvalidBackupException
import app.smsbridge.data.backup.WrongPassphraseException
import app.smsbridge.data.backup.BackupKind
import app.smsbridge.data.backup.BackupRepository
import app.smsbridge.data.backup.BackupScheduler
import app.smsbridge.data.backup.ExportFormat
import app.smsbridge.data.backup.HistoryKind
import app.smsbridge.data.backup.RestoreOptions
import app.smsbridge.data.backup.RestoreTarget
import app.smsbridge.data.sms.SmsPermissionManager
import app.smsbridge.data.sms.SmsPermissionState
import app.smsbridge.feature.permissions.DefaultSmsAppCard
import app.smsbridge.ui.components.SectionCard
import app.smsbridge.ui.components.formatTimestamp
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Inspection {
    data object None : Inspection
    data object Checking : Inspection
    data class NeedsPassphrase(val uri: Uri, val header: EncryptedBackup.Header, val error: String? = null) : Inspection
    /** [keyId] points to the in-memory key for an encrypted backup (see [EphemeralKeys]). */
    data class Ready(val uri: Uri, val info: BackupInfo, val keyId: String? = null) : Inspection
    data class Error(val message: String) : Inspection
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backups: BackupRepository,
    private val scheduler: BackupScheduler,
    val permissions: SmsPermissionManager,
    private val coordinator: SmsCaptureCoordinator,
    private val keys: EphemeralKeys,
) : ViewModel() {
    val work: StateFlow<WorkInfo?> = scheduler.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val history: StateFlow<List<BackupHistoryEntity>> =
        backups.recentHistory().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val permissionState: StateFlow<SmsPermissionState> = permissions.state

    private val _inspection = MutableStateFlow<Inspection>(Inspection.None)
    val inspection: StateFlow<Inspection> = _inspection.asStateFlow()

    private val _deriving = MutableStateFlow(false)
    /** True while a passphrase is being turned into a key (deliberately slow). */
    val deriving: StateFlow<Boolean> = _deriving.asStateFlow()

    /** Passphrase chosen for the next encrypted export, held only until the file picker returns. */
    private var pendingPassphrase: CharArray? = null

    fun export(uri: Uri, format: ExportFormat) = scheduler.export(uri, format)

    fun setPendingPassphrase(passphrase: CharArray) {
        pendingPassphrase?.fill('\u0000')
        pendingPassphrase = passphrase
    }

    /** Called with the file the user picked, or null if they cancelled the picker. */
    fun exportEncrypted(uri: Uri?) {
        val passphrase = pendingPassphrase ?: return
        pendingPassphrase = null
        if (uri == null) {
            passphrase.fill('\u0000')
            return
        }
        viewModelScope.launch {
            _deriving.value = true
            val key = withContext(Dispatchers.Default) { EncryptedBackup.deriveKey(passphrase, EncryptedBackup.newHeader()) }
            _deriving.value = false
            scheduler.export(uri, ExportFormat.ENCRYPTED, keys.put(key))
        }
    }

    fun inspect(uri: Uri) {
        clearInspection()
        _inspection.value = Inspection.Checking
        viewModelScope.launch {
            _inspection.value = try {
                when (val probe = withContext(Dispatchers.IO) { backups.probe(uri) }) {
                    is BackupProbe.Encrypted -> Inspection.NeedsPassphrase(uri, probe.header)
                    BackupProbe.Plain -> Inspection.Ready(uri, withContext(Dispatchers.IO) { backups.inspect(uri) })
                }
            } catch (e: InvalidBackupException) {
                Inspection.Error(e.message ?: "This is not a usable backup.")
            } catch (e: Exception) {
                Inspection.Error("The file could not be read (${e.javaClass.simpleName}).")
            }
        }
    }

    fun unlock(passphrase: CharArray) {
        val current = _inspection.value as? Inspection.NeedsPassphrase ?: return
        viewModelScope.launch {
            _deriving.value = true
            val key = withContext(Dispatchers.Default) { EncryptedBackup.deriveKey(passphrase, current.header) }
            _inspection.value = try {
                val info = withContext(Dispatchers.IO) { backups.inspect(current.uri, key) }
                Inspection.Ready(current.uri, info, keys.put(key))
            } catch (e: WrongPassphraseException) {
                key.wipe()
                current.copy(error = e.message)
            } catch (e: Exception) {
                key.wipe()
                Inspection.Error("The file could not be read (${e.javaClass.simpleName}).")
            } finally {
                _deriving.value = false
            }
        }
    }

    fun restore(uri: Uri, kind: BackupKind, options: RestoreOptions, keyId: String?) {
        scheduler.restore(uri, kind, options, keyId)
        // The worker now owns the key and wipes it when done.
        _inspection.value = Inspection.None
    }

    fun clearInspection() {
        (_inspection.value as? Inspection.Ready)?.keyId?.let(keys::remove)
        _inspection.value = Inspection.None
    }

    override fun onCleared() {
        pendingPassphrase?.fill('\u0000')
        (_inspection.value as? Inspection.Ready)?.keyId?.let(keys::remove)
    }

    fun onPermissionsChanged() = coordinator.start()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(viewModel: BackupViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val work by viewModel.work.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val inspection by viewModel.inspection.collectAsStateWithLifecycle()
    val permissions by viewModel.permissionState.collectAsStateWithLifecycle()
    val busy = work?.state?.isFinished == false

    fun persist(uri: Uri, flags: Int) = runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }

    val deriving by viewModel.deriving.collectAsStateWithLifecycle()
    var askExportPassphrase by rememberSaveable { mutableStateOf(false) }

    val exportLaunchers = ExportFormat.entries.associateWith { format ->
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mimeType)) { uri ->
            if (uri != null) persist(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            when {
                format == ExportFormat.ENCRYPTED -> viewModel.exportEncrypted(uri)
                uri != null -> viewModel.export(uri, format)
            }
        }
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            persist(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            viewModel.inspect(uri)
        }
    }

    // imePadding: keep the passphrase card reachable above the on-screen keyboard.
    LazyColumn(
        modifier = Modifier.imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Backup & restore", style = MaterialTheme.typography.headlineSmall) }

        item {
            SectionCard("Create a backup") {
                Text(
                    "Saves every archived message to a file you choose (phone storage, SD card, or a " +
                        "cloud drive app). The backup package contains messages.xml, metadata, a manifest " +
                        "and SHA-256 checksums, and can be restored by SMS Bridge on any phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "Encrypted backups are protected with a passphrase only you know (AES-256). " +
                        "Unencrypted backups, JSON and CSV can be read by anyone who gets the file.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Button(
                    onClick = { askExportPassphrase = true },
                    enabled = !busy && !deriving,
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(if (deriving) "Preparing key…" else "Create encrypted backup…") }
                OutlinedButton(
                    onClick = { exportLaunchers.getValue(ExportFormat.PACKAGE).launch(fileName(ExportFormat.PACKAGE)) },
                    enabled = !busy,
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text("Unencrypted backup…") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    OutlinedButton(onClick = { exportLaunchers.getValue(ExportFormat.JSON).launch(fileName(ExportFormat.JSON)) }, enabled = !busy) {
                        Text("Export JSON…")
                    }
                    OutlinedButton(onClick = { exportLaunchers.getValue(ExportFormat.CSV).launch(fileName(ExportFormat.CSV)) }, enabled = !busy) {
                        Text("Export CSV…")
                    }
                }
                Text(
                    "JSON and CSV are for reading in other tools; restore uses the backup package or an SMS XML file.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        work?.let { info -> item { WorkStatusCard(info, permissions, onSwitchBack = { context.startActivity(viewModel.permissions.defaultAppsSettingsIntent()) }) } }

        item {
            SectionCard("Restore") {
                Text(
                    "Choose an SMS Bridge backup (.zip) or an SMS backup XML file. It is checked first; " +
                        "nothing changes until you confirm. Messages already present are skipped.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                OutlinedButton(
                    onClick = { openLauncher.launch(arrayOf("application/zip", "text/xml", "application/xml", "application/octet-stream", "*/*")) },
                    enabled = !busy,
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Choose backup file…") }
            }
        }

        when (val current = inspection) {
            Inspection.None -> Unit
            Inspection.Checking -> item {
                SectionCard("Checking backup…") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp)) }
            }
            is Inspection.NeedsPassphrase -> item {
                UnlockCard(
                    error = current.error,
                    working = deriving,
                    onUnlock = viewModel::unlock,
                    onCancel = viewModel::clearInspection,
                )
            }
            is Inspection.Error -> item {
                SectionCard("Cannot use this file") {
                    Text(current.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                }
            }
            is Inspection.Ready -> item {
                RestorePlanner(
                    info = current.info,
                    permissions = permissions,
                    busy = busy,
                    onRestore = { options -> viewModel.restore(current.uri, current.info.kind, options, current.keyId) },
                    onCancel = viewModel::clearInspection,
                    defaultAppCard = {
                        DefaultSmsAppCard(
                            state = permissions,
                            createRequestIntent = viewModel.permissions::createDefaultSmsRequestIntent,
                            openDefaultAppsSettings = { context.startActivity(viewModel.permissions.defaultAppsSettingsIntent()) },
                            onChanged = viewModel::onPermissionsChanged,
                        )
                    },
                )
            }
        }

        if (history.isNotEmpty()) {
            item { Text("History", style = MaterialTheme.typography.titleMedium) }
            items(history, key = { it.id }) { HistoryRow(it) }
        }
    }

    if (askExportPassphrase) {
        NewPassphraseDialog(
            onDismiss = { askExportPassphrase = false },
            onConfirm = { passphrase ->
                askExportPassphrase = false
                viewModel.setPendingPassphrase(passphrase)
                exportLaunchers.getValue(ExportFormat.ENCRYPTED).launch(fileName(ExportFormat.ENCRYPTED))
            },
        )
    }
}

/** Asks for a new passphrase twice. Never stored; hands a CharArray the caller must wipe. */
@Composable
private fun NewPassphraseDialog(onDismiss: () -> Unit, onConfirm: (CharArray) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val tooShort = first.length < EncryptedBackup.MIN_PASSPHRASE_LENGTH
    val mismatch = second.isNotEmpty() && first != second
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a backup passphrase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "You will need it to restore this backup. SMS Bridge does not store it and cannot recover it: " +
                        "if you forget it, the backup cannot be opened.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                PassphraseField(first, { first = it }, "Passphrase (at least ${EncryptedBackup.MIN_PASSPHRASE_LENGTH} characters)")
                PassphraseField(second, { second = it }, "Repeat passphrase")
                if (mismatch) Text("The passphrases do not match.", color = MaterialTheme.colorScheme.error)
                Text("Tip: several unrelated words are easier to remember and harder to guess.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(first.toCharArray()); first = ""; second = "" },
                enabled = !tooShort && first == second,
            ) { Text("Continue") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun UnlockCard(error: String?, working: Boolean, onUnlock: (CharArray) -> Unit, onCancel: () -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    val submit = {
        if (passphrase.isNotEmpty() && !working) {
            onUnlock(passphrase.toCharArray())
            passphrase = ""
        }
    }
    SectionCard("Encrypted backup") {
        Text("Enter the passphrase that was chosen when this backup was created.", modifier = Modifier.padding(top = 4.dp))
        PassphraseField(passphrase, { passphrase = it }, "Passphrase", Modifier.padding(top = 8.dp), onDone = submit)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = submit, enabled = passphrase.isNotEmpty() && !working) {
                Text(if (working) "Checking…" else "Unlock")
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun PassphraseField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    onDone: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrect = false,
            imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = modifier.fillMaxWidth(),
    )
}

private fun fileName(format: ExportFormat): String {
    val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"))
    val base = if (format == ExportFormat.PACKAGE || format == ExportFormat.ENCRYPTED) "sms-bridge-backup" else "sms-bridge-export"
    return "$base-$stamp.${format.extension}"
}

@Composable
private fun WorkStatusCard(info: WorkInfo, permissions: SmsPermissionState, onSwitchBack: () -> Unit) {
    val data = if (info.state.isFinished) info.outputData else info.progress
    val operation = data.getString(BackupScheduler.KEY_OPERATION)
    val isRestore = operation == BackupScheduler.OP_RESTORE
    val done = data.getInt(BackupScheduler.KEY_DONE, 0)
    val total = data.getInt(BackupScheduler.KEY_TOTAL, 0)
    when (info.state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> SectionCard(if (isRestore) "Restoring…" else "Backing up…") {
            val restored = data.getInt(BackupScheduler.KEY_RESTORED, 0)
            Text(
                if (isRestore) "$done messages read, $restored restored" else if (total > 0) "$done of $total messages" else "Starting…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (total > 0) {
                LinearProgressIndicator(progress = { done / total.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            Text("You can leave this screen; it continues in the background.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
        WorkInfo.State.SUCCEEDED -> SectionCard(if (isRestore) "Restore finished" else "Backup finished") {
            if (isRestore) {
                val restored = data.getInt(BackupScheduler.KEY_RESTORED, 0)
                val duplicates = data.getInt(BackupScheduler.KEY_DUPLICATES, 0)
                val selected = data.getInt(BackupScheduler.KEY_SELECTED, 0)
                Text(
                    "$restored restored, $duplicates already present, ${done - selected} not selected ($done in file).",
                    modifier = Modifier.padding(top = 4.dp),
                )
                val drafts = data.getInt(BackupScheduler.KEY_DRAFTS_SKIPPED, 0)
                if (drafts > 0) {
                    Text(
                        "$drafts drafts were not restored into the phone: Android keeps only one draft per " +
                            "conversation. Restore into the SMS Bridge archive to keep them.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (data.getString(BackupScheduler.KEY_TARGET) == RestoreTarget.PHONE.name && permissions.isDefaultSmsApp) {
                    Text(
                        "If you made SMS Bridge the default SMS app only for this restore, you can switch back now.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    OutlinedButton(onClick = onSwitchBack, modifier = Modifier.padding(top = 4.dp)) { Text("Switch back to my messaging app") }
                }
            } else {
                Text("$done messages saved.", modifier = Modifier.padding(top = 4.dp))
                data.getString(BackupScheduler.KEY_SHA256)?.let {
                    Text("messages.xml SHA-256 ${it.take(16)}…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> SectionCard(if (isRestore) "Restore failed" else "Backup failed") {
            Text(
                data.getString(BackupScheduler.KEY_ERROR) ?: "Stopped before finishing. Nothing already saved was removed; you can run it again.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RestorePlanner(
    info: BackupInfo,
    permissions: SmsPermissionState,
    busy: Boolean,
    onRestore: (RestoreOptions) -> Unit,
    onCancel: () -> Unit,
    defaultAppCard: @Composable () -> Unit,
) {
    var target by rememberSaveable { mutableStateOf(RestoreTarget.ARCHIVE) }
    var incoming by rememberSaveable { mutableStateOf(true) }
    var outgoing by rememberSaveable { mutableStateOf(true) }
    var fromDate by rememberSaveable { mutableStateOf<Long?>(null) }
    var toDate by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickDates by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }

    SectionCard(if (info.isUsable) "Backup checked" else "Backup has problems") {
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                when {
                    info.encrypted -> "Encrypted SMS Bridge backup (passphrase accepted)"
                    info.kind == BackupKind.SMS_BRIDGE_PACKAGE -> "SMS Bridge backup package"
                    else -> "SMS backup XML file"
                },
            )
            Text("${info.messageCount} messages: ${info.incoming} received, ${info.outgoing} sent")
            if (info.oldestDate != null && info.newestDate != null) {
                Text("From ${formatTimestamp(info.oldestDate)} to ${formatTimestamp(info.newestDate)}")
            }
            info.createdAt?.let { Text("Created ${formatTimestamp(it)}${info.sourceDeviceName?.let { n -> " on $n" }.orEmpty()}") }
            when (info.checksumsValid) {
                true -> Text("Checksums verified", color = MaterialTheme.colorScheme.primary)
                false -> Text("Checksums do NOT match", color = MaterialTheme.colorScheme.error)
                null -> Text("No checksums in this file format", style = MaterialTheme.typography.bodySmall)
            }
            if (info.skippedInvalid + info.skippedUnsupported > 0) {
                Text(
                    "${info.skippedInvalid} unreadable entries and ${info.skippedUnsupported} MMS/other entries will be skipped.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            info.problems.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        }

        if (info.isUsable) {
            Text("Restore into", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            TargetOption(
                selected = target == RestoreTarget.ARCHIVE,
                title = "SMS Bridge archive",
                detail = "Adds the messages to SMS Bridge only. Your messaging app is not changed.",
                onSelect = { target = RestoreTarget.ARCHIVE },
            )
            TargetOption(
                selected = target == RestoreTarget.PHONE,
                title = "This phone's messages",
                detail = "Writes them into Android's SMS database so every messaging app shows them. " +
                    "Android only allows this for the default SMS app. Android assigns new internal and " +
                    "conversation ids; SIM assignment and drafts are not restored.",
                onSelect = { target = RestoreTarget.PHONE },
            )

            Text("Messages", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Label and box toggle together.
                Row(
                    Modifier.toggleable(value = incoming, role = Role.Checkbox, onValueChange = { incoming = it }).padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = incoming, onCheckedChange = null)
                    Text("Received", modifier = Modifier.padding(start = 4.dp))
                }
                Row(
                    Modifier.toggleable(value = outgoing, role = Role.Checkbox, onValueChange = { outgoing = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = outgoing, onCheckedChange = null)
                    Text("Sent", modifier = Modifier.padding(start = 4.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (fromDate == null) "All dates" else "${formatDay(fromDate!!)} – ${toDate?.let(::formatDay) ?: "…"}",
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { pickDates = true }) { Text("Choose dates") }
                if (fromDate != null) TextButton(onClick = { fromDate = null; toDate = null }) { Text("Clear") }
            }

            val needsRole = target == RestoreTarget.PHONE && !permissions.isDefaultSmsApp
            if (needsRole) {
                Text(
                    "To restore into the phone, temporarily make SMS Bridge the default SMS app, then " +
                        "switch back after the restore.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                defaultAppCard()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { confirm = true }, enabled = !busy && !needsRole && (incoming || outgoing)) { Text("Restore…") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        } else {
            TextButton(onClick = onCancel, modifier = Modifier.padding(top = 8.dp)) { Text("Close") }
        }
    }

    if (pickDates) {
        val state = rememberDateRangePickerState(initialSelectedStartDateMillis = fromDate, initialSelectedEndDateMillis = toDate)
        DatePickerDialog(
            onDismissRequest = { pickDates = false },
            confirmButton = {
                TextButton(onClick = {
                    // The picker returns UTC midnight; convert to the local day's full range.
                    fromDate = state.selectedStartDateMillis?.let { startOfLocalDay(it) }
                    toDate = state.selectedEndDateMillis?.let { endOfLocalDay(it) }
                    pickDates = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDates = false }) { Text("Cancel") } },
        ) { DateRangePicker(state = state, modifier = Modifier.weight(1f)) }
    }

    if (confirm) {
        val where = if (target == RestoreTarget.PHONE) "this phone's messages" else "the SMS Bridge archive"
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Restore into $where?") },
            text = {
                Text(
                    "Matching messages from the backup will be added. Messages already present are skipped; " +
                        "nothing is deleted or overwritten. Pending outgoing messages are restored as failed " +
                        "so they are never sent again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onRestore(RestoreOptions(target, incoming, outgoing, fromDate, toDate))
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TargetOption(selected: Boolean, title: String, detail: String, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect).padding(vertical = 4.dp)) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HistoryRow(entry: BackupHistoryEntity) {
    val what = when (entry.format) {
        ExportFormat.ENCRYPTED.name -> "Encrypted backup"
        ExportFormat.PACKAGE.name -> "Unencrypted backup"
        ExportFormat.JSON.name -> "JSON export"
        ExportFormat.CSV.name -> "CSV export"
        HistoryKind.RESTORE_ARCHIVE -> "Restore to archive"
        HistoryKind.RESTORE_PHONE -> "Restore to phone"
        else -> entry.format
    }
    val status = when (entry.status) {
        HistoryKind.COMPLETED -> "${entry.messageCount} messages"
        HistoryKind.RUNNING -> "in progress"
        HistoryKind.INTERRUPTED -> "interrupted, run it again to finish"
        else -> "failed"
    }
    Column(Modifier.fillMaxWidth()) {
        Text("$what · ${formatTimestamp(entry.createdAt)}", style = MaterialTheme.typography.bodyMedium)
        Text(status, style = MaterialTheme.typography.bodySmall, color = if (entry.status == HistoryKind.COMPLETED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
    }
}

private fun formatDay(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

internal fun startOfLocalDay(utcMidnight: Long): Long =
    LocalDate.ofEpochDay(utcMidnight / 86_400_000L).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

internal fun endOfLocalDay(utcMidnight: Long): Long =
    LocalDate.ofEpochDay(utcMidnight / 86_400_000L).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
