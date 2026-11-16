package app.smsbridge.feature.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.smsbridge.BuildConfig
import app.smsbridge.data.cloud.CloudState
import app.smsbridge.data.cloud.LinkedDeviceDto
import app.smsbridge.feature.dashboard.DashboardViewModel
import app.smsbridge.feature.permissions.SmsPermissionCard
import app.smsbridge.feature.sync.StayReachableCard
import app.smsbridge.feature.sync.SyncViewModel
import app.smsbridge.feature.sync.rememberQrScanner
import app.smsbridge.ui.components.SectionCard
import app.smsbridge.ui.components.formatTimestamp
import java.time.OffsetDateTime

/** Share range choices: label → days (null: everything). */
private val RANGES = listOf("Last 7 days" to 7, "Last 30 days" to 30, "Everything" to null)
/** Auto-unlink choices: label → days (null: never). */
private val UNLINK = listOf("Never" to null, "After 1 day" to 1, "After 30 days" to 30)

private fun rangeLabel(days: Int?) = RANGES.firstOrNull { it.second == days }?.first ?: "Last $days days"
private fun millis(iso: String?): Long? = iso?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

/**
 * Home: link a computer (scan or type its code), see who can read your SMS and stop sharing, name
 * this phone and choose how much is shared. Everything else lives under Settings → Advanced.
 */
@Composable
fun HomeScreen(viewModel: SyncViewModel = hiltViewModel(), dashboard: DashboardViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val info by viewModel.info.collectAsStateWithLifecycle()
    val linking by viewModel.linking.collectAsStateWithLifecycle()
    val linked by viewModel.linked.collectAsStateWithLifecycle()
    val phoneName by viewModel.phoneName.collectAsStateWithLifecycle()
    val shareDays by viewModel.shareDays.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val dash by dashboard.uiState.collectAsStateWithLifecycle()
    val scan = rememberQrScanner(viewModel::onScanned)
    var typing by rememberSaveable { mutableStateOf(false) }
    var stopping by remember { mutableStateOf<LinkedDeviceDto?>(null) }
    var choosingRange by rememberSaveable { mutableStateOf(false) }
    var unlinking by rememberSaveable { mutableStateOf(false) }
    val myId by viewModel.myId.collectAsStateWithLifecycle()
    val ready = state is CloudState.Ready

    LaunchedEffect(state) { viewModel.loadLinked() }

    LazyColumn(Modifier.imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("SMS Bridge", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, bottom = 3.dp),
                )
            }
        }
        item { app.smsbridge.feature.update.UpdateBanner() }
        if (!dash.permissions.canReadSms || !dash.permissions.canReceiveSms) {
            item { SmsPermissionCard(dash.permissions, onPermissionResult = dashboard::onPermissionResult) }
        }

        item {
            SectionCard("Link a computer") {
                if (state is CloudState.WaitingForApproval || linking) {
                    Text(
                        "Almost done: keep the page on your computer open. This phone is added within a few seconds.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    val site = viewModel.lastServer.collectAsStateWithLifecycle().value.substringAfter("://").ifBlank { BuildConfig.DEFAULT_SITE }
                    Text(
                        "On your computer, open $site. Then scan the QR code, or type the 8-digit code shown under it.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Button(
                        onClick = { scan("Scan the QR code on your computer") },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) { Text("Scan QR code") }
                    OutlinedButton(onClick = { typing = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text("Type code")
                    }
                }
                info?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
        }

        if (ready) {
            val browsers = linked.filter { it.platform == "web" && it.trusted }
            val otherPhones = linked.filter { it.platform != "web" && it.id != myId }
            item {
                SectionCard("Who can see your SMS") {
                    if (browsers.isEmpty()) {
                        Text("No computer yet. Link one above.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            items(browsers, key = { it.id }) { b ->
                SectionCard(b.label ?: b.name) {
                    val seen = millis(b.lastSeenAt)?.let { "Last opened ${formatTimestamp(it)}" } ?: "Not opened yet"
                    val until = millis(b.expiresAt)?.let { " · Unlinks ${formatTimestamp(it)}" }.orEmpty()
                    Text("$seen$until", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                    OutlinedButton(onClick = { stopping = b }, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) { Text("Stop sharing") }
                }
            }
            if (otherPhones.isNotEmpty()) {
                item { Text("Other phones on this account", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp)) }
                items(otherPhones, key = { it.id }) { p ->
                    SectionCard(p.label ?: p.name) {
                        val seen = millis(p.lastSeenAt)?.let { "Last seen ${formatTimestamp(it)}" } ?: "Not seen yet"
                        Text(if (p.label != null) "${p.name} · $seen" else seen, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                        OutlinedButton(onClick = { stopping = p }, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) { Text("Remove") }
                    }
                }
            }
            item {
                SectionCard("This phone") {
                    Text(phoneName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                    Text(
                        "Sharing: ${rangeLabel(shareDays)}" + if (pending > 0) " · $pending waiting to upload" else " · up to date",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedButton(onClick = viewModel::renamePhone, enabled = !busy) { Text("Rename") }
                        OutlinedButton(onClick = { choosingRange = true }, enabled = !busy) { Text("Change range") }
                    }
                    TextButton(onClick = { unlinking = true }, enabled = !busy, modifier = Modifier.padding(top = 4.dp)) {
                        Text("Unlink this phone", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            item { SimLabelsCard() }
            item { StayReachableCard() }
        }
    }

    if (typing) CodeDialog(onDismiss = { typing = false }, onSubmit = { typing = false; viewModel.submitCode(it) })
    viewModel.consent.collectAsStateWithLifecycle().value?.let { c ->
        ConsentDialog(c.browserName, shareDays, onAllow = viewModel::allow, onCancel = viewModel::decline)
    }
    viewModel.askName.collectAsStateWithLifecycle().value?.let { suggested ->
        NameDialog(suggested, onSave = viewModel::nameThisPhone, onSkip = viewModel::skipName)
    }
    viewModel.move.collectAsStateWithLifecycle().value?.let {
        AlertDialog(
            onDismissRequest = viewModel::cancelMove,
            title = { Text("Move this phone here?") },
            text = {
                Text(
                    "This phone is linked to another SMS Bridge account. Move it to the account on your computer? " +
                        "The messages on this phone are not touched. Computers linked to the old account stop getting its new SMS.",
                )
            },
            confirmButton = { Button(onClick = viewModel::moveHere) { Text("Move here") } },
            dismissButton = { TextButton(onClick = viewModel::cancelMove) { Text("Cancel") } },
        )
    }
    stopping?.let { b ->
        val phone = b.platform != "web"
        AlertDialog(
            onDismissRequest = { stopping = null },
            title = { Text(if (phone) "Remove ${b.label ?: b.name}?" else "Stop sharing with ${b.label ?: b.name}?") },
            text = {
                Text(
                    if (phone) "It stops sending SMS to your computer, and all of its messages are deleted from the server. The messages on that phone are not touched."
                    else "It can no longer see your SMS. To use it again, link it again with the QR code.",
                )
            },
            confirmButton = {
                TextButton(onClick = { stopping = null; viewModel.stopSharing(b.id, isPhone = phone) }) {
                    Text(if (phone) "Remove and delete its messages" else "Stop sharing")
                }
            },
            dismissButton = { TextButton(onClick = { stopping = null }) { Text("Cancel") } },
        )
    }
    if (unlinking) {
        AlertDialog(
            onDismissRequest = { unlinking = false },
            title = { Text("Unlink this phone?") },
            text = {
                Text(
                    "Your computers stop getting this phone's SMS, and all of its messages are deleted from the server. " +
                        "The messages on this phone stay. You can link it again any time.",
                )
            },
            confirmButton = { TextButton(onClick = { unlinking = false; viewModel.unlinkThisPhone() }) { Text("Unlink and delete from server") } },
            dismissButton = { TextButton(onClick = { unlinking = false }) { Text("Cancel") } },
        )
    }
    if (choosingRange) {
        var picked by remember { mutableStateOf(shareDays) }
        AlertDialog(
            onDismissRequest = { choosingRange = false },
            title = { Text("What to share") },
            text = { Choices(RANGES, picked) { picked = it } },
            confirmButton = { TextButton(onClick = { choosingRange = false; viewModel.changeShareDays(picked) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { choosingRange = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Choices(options: List<Pair<String, Int?>>, selected: Int?, onPick: (Int?) -> Unit) {
    Column {
        options.forEach { (label, value) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().selectable(selected = value == selected, role = Role.RadioButton) { onPick(value) },
            ) {
                RadioButton(selected = value == selected, onClick = null)
                Text(label, modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 10.dp))
            }
        }
    }
}

/** "Allow Chrome on Windows to see your SMS?" with what to share and when to unlink. */
@Composable
private fun ConsentDialog(browser: String, current: Int?, onAllow: (Int?, Int?) -> Unit, onCancel: () -> Unit) {
    var days by remember { mutableStateOf(current) }
    var unlink by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Allow $browser to see your SMS?") },
        text = {
            Column {
                Text("Share", style = MaterialTheme.typography.titleSmall)
                Choices(RANGES, days) { days = it }
                Text("Unlink this computer", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Choices(UNLINK, unlink) { unlink = it }
                Text("New SMS show up live. You can stop sharing any time on this screen.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = { onAllow(days, unlink) }) { Text("Allow") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun CodeDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val digits = code.filter(Char::isDigit)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Type the code") },
        text = {
            Column {
                Text("The 8 digits shown under the QR code on your computer.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter { it.isDigit() || it == ' ' }.take(9) },
                    singleLine = true,
                    placeholder = { Text("1234 5678") },
                    textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center, letterSpacing = 4.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = { Button(onClick = { onSubmit(digits) }, enabled = digits.length == 8) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NameDialog(suggested: String, onSave: (String?) -> Unit, onSkip: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(suggested) }
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("Name this phone") },
        text = {
            Column {
                Text("Shown on your computer, for example \"Office phone\" or \"My Samsung\".", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        },
        confirmButton = { Button(onClick = { onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onSkip) { Text("Skip") } },
    )
}

/** Optional "Phone" permission: lets the browser show SIM 1 / SIM 2 on each message. */
@Composable
private fun SimLabelsCard() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val granted = remember(refresh) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    if (granted) return
    SectionCard("Show SIM 1 / SIM 2") {
        Text(
            "On phones with two SIMs, your computer can show which SIM each message used. Android calls this permission \"Phone\". SMS Bridge never makes calls.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        OutlinedButton(onClick = { ask.launch(Manifest.permission.READ_PHONE_STATE) }, modifier = Modifier.padding(top = 8.dp)) { Text("Allow") }
    }
}
