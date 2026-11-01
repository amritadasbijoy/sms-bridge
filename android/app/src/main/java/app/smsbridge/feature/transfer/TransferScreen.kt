package app.smsbridge.feature.transfer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.smsbridge.data.background.SmsCaptureCoordinator
import app.smsbridge.data.backup.RestoreTarget
import app.smsbridge.data.sms.SmsPermissionManager
import app.smsbridge.data.sms.SmsPermissionState
import app.smsbridge.data.transfer.ReceiverState
import app.smsbridge.data.transfer.SenderState
import app.smsbridge.data.transfer.TransferManager
import app.smsbridge.feature.permissions.DefaultSmsAppCard
import app.smsbridge.ui.components.SectionCard
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val manager: TransferManager,
    val permissions: SmsPermissionManager,
    private val coordinator: SmsCaptureCoordinator,
) : ViewModel() {
    val receiver: StateFlow<ReceiverState> = manager.receiver
    val sender: StateFlow<SenderState> = manager.sender
    val permissionState: StateFlow<SmsPermissionState> = permissions.state

    fun startReceiving(target: RestoreTarget) = manager.startReceiving(target)
    fun answerOffer(accept: Boolean) = manager.answerOffer(accept)
    fun stopReceiving() = manager.stopReceiving()
    fun startSending(link: String) = manager.startSending(link)
    fun answerSend(confirm: Boolean) = manager.answerSend(confirm)
    fun resumeSending() = manager.resumeSending()
    fun cancelSending() = manager.cancelSending()
    fun onPermissionsChanged() = coordinator.start()
}

@Composable
fun TransferScreen(viewModel: TransferViewModel = hiltViewModel()) {
    val receiver by viewModel.receiver.collectAsStateWithLifecycle()
    val sender by viewModel.sender.collectAsStateWithLifecycle()
    val permissions by viewModel.permissionState.collectAsStateWithLifecycle()

    // Keep the screen on while anything is in flight, so Android does not suspend the network.
    val active = receiver !is ReceiverState.Idle && receiver !is ReceiverState.Finished && receiver !is ReceiverState.Failed ||
        sender is SenderState.Connecting || sender is SenderState.WaitingForReceiver || sender is SenderState.Sending
    val view = LocalView.current
    DisposableEffect(active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }

    LazyColumn(
        modifier = Modifier.imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Phone-to-phone transfer", style = MaterialTheme.typography.headlineSmall) }
        item {
            Text(
                "Copy your SMS Bridge archive to another phone over your own Wi-Fi. Nothing goes over the " +
                    "internet. Both phones must be on the same Wi-Fi network and have SMS Bridge open.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item { ReceiveCard(receiver, permissions, viewModel) }
        item { SendCard(sender, viewModel) }
    }
}

@Composable
private fun ReceiveCard(state: ReceiverState, permissions: SmsPermissionState, viewModel: TransferViewModel) {
    val context = LocalContext.current
    var target by rememberSaveable { mutableStateOf(RestoreTarget.ARCHIVE) }
    SectionCard("Receive on this phone") {
        when (state) {
            ReceiverState.Idle, is ReceiverState.Failed -> {
                if (state is ReceiverState.Failed) {
                    Text(state.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                }
                TargetChoice(target == RestoreTarget.ARCHIVE, "Into the SMS Bridge archive", "Your messaging app is not changed.") { target = RestoreTarget.ARCHIVE }
                TargetChoice(
                    target == RestoreTarget.PHONE,
                    "Into this phone's messages",
                    "Shows them in every messaging app. Needs SMS Bridge as the default SMS app during the transfer; drafts are not copied.",
                ) { target = RestoreTarget.PHONE }
                if (target == RestoreTarget.PHONE && !permissions.isDefaultSmsApp) {
                    DefaultSmsAppCard(
                        state = permissions,
                        createRequestIntent = viewModel.permissions::createDefaultSmsRequestIntent,
                        openDefaultAppsSettings = { context.startActivity(viewModel.permissions.defaultAppsSettingsIntent()) },
                        onChanged = viewModel::onPermissionsChanged,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Button(
                    onClick = { viewModel.startReceiving(target) },
                    enabled = target == RestoreTarget.ARCHIVE || permissions.isDefaultSmsApp,
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Start receiving") }
            }
            ReceiverState.Starting -> Text("Preparing a secure connection…", modifier = Modifier.padding(top = 4.dp))
            is ReceiverState.Waiting -> {
                Text("On the other phone, open SMS Bridge → Transfer → Scan QR code.", modifier = Modifier.padding(top = 4.dp))
                val qr = remember(state.link) { qrBitmap(state.link) }
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "Pairing QR code",
                    // No smoothing: blurred module edges make the code harder for cameras to read.
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.size(260.dp).padding(top = 8.dp),
                )
                Text(
                    "Listening on ${state.address}. The code works only for this session and only on this Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = { copyToClipboard(context, state.link) }) { Text("Copy pairing link") }
                    TextButton(onClick = viewModel::stopReceiving) { Text("Stop") }
                }
                Text(
                    "Only share the link with your own phone: it lets that phone send messages to this one.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            is ReceiverState.Offer -> {
                Text(
                    "${state.senderName} wants to send ${state.total} messages to this phone.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "Messages already here are skipped. Nothing on this phone is deleted or overwritten.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = { viewModel.answerOffer(true) }) { Text("Accept") }
                    TextButton(onClick = { viewModel.answerOffer(false) }) { Text("Decline") }
                }
            }
            is ReceiverState.Receiving -> Progress("Receiving from ${state.senderName}", state.received, state.total) {
                TextButton(onClick = viewModel::stopReceiving) { Text("Stop") }
            }
            is ReceiverState.Finished -> {
                val r = state.result
                Text("Transfer from ${state.senderName} finished.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
                Text("${r.restored} added, ${r.duplicates} already here (${r.read} received).")
                if (r.draftsSkipped > 0) {
                    Text("${r.draftsSkipped} drafts were not put into the phone's messages.", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = viewModel::stopReceiving, modifier = Modifier.padding(top = 4.dp)) { Text("Done") }
            }
        }
    }
}

@Composable
private fun SendCard(state: SenderState, viewModel: TransferViewModel) {
    var linkText by rememberSaveable { mutableStateOf("") }
    var linkError by rememberSaveable { mutableStateOf(false) }
    val scanner = androidx.activity.compose.rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { scanned ->
            linkError = !viewModel.startSending(scanned)
        }
    }
    SectionCard("Send from this phone") {
        when (state) {
            SenderState.Idle, is SenderState.Failed -> {
                if (state is SenderState.Failed) {
                    Text(state.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                    if (state.canResume) {
                        Button(onClick = viewModel::resumeSending, modifier = Modifier.padding(top = 4.dp)) { Text("Resume") }
                    }
                }
                Text(
                    "Sends every message in this phone's SMS Bridge archive. The other phone decides where they go.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Button(
                    onClick = {
                        scanner.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("Scan the QR code shown on the receiving phone")
                                .setBeepEnabled(false)
                                .setOrientationLocked(false),
                        )
                    },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Scan QR code") }
                OutlinedTextField(
                    value = linkText,
                    onValueChange = { linkText = it; linkError = false },
                    label = { Text("…or paste the pairing link") },
                    singleLine = true,
                    isError = linkError,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (linkError) Text("That is not a valid SMS Bridge pairing link.", color = MaterialTheme.colorScheme.error)
                OutlinedButton(
                    onClick = { linkError = !viewModel.startSending(linkText); if (!linkError) linkText = "" },
                    enabled = linkText.isNotBlank(),
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text("Connect") }
            }
            SenderState.Connecting -> Text("Connecting securely…", modifier = Modifier.padding(top = 4.dp))
            is SenderState.Confirm -> {
                Text(
                    "Connected to ${state.receiverName}. Send ${state.total} messages?",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text("The connection is encrypted and verified with the QR code.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = { viewModel.answerSend(true) }) { Text("Send") }
                    TextButton(onClick = viewModel::cancelSending) { Text("Cancel") }
                }
            }
            SenderState.WaitingForReceiver -> {
                Text("Waiting for the other phone to accept…", modifier = Modifier.padding(top = 4.dp))
                TextButton(onClick = viewModel::cancelSending) { Text("Cancel") }
            }
            is SenderState.Sending -> Progress("Sending", state.sent, state.total) {
                TextButton(onClick = viewModel::cancelSending) { Text("Cancel") }
            }
            is SenderState.Finished -> {
                val o = state.outcome
                Text("Transfer finished.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
                Text("The other phone added ${o.restored} messages; ${o.duplicates} were already there.")
                TextButton(onClick = viewModel::cancelSending, modifier = Modifier.padding(top = 4.dp)) { Text("Done") }
            }
        }
    }
}

@Composable
private fun Progress(title: String, done: Int, total: Int, actions: @Composable () -> Unit) {
    Column(Modifier.padding(top = 4.dp)) {
        Text("$title: $done of $total messages")
        LinearProgressIndicator(
            progress = { if (total > 0) done / total.toFloat() else 0f },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Text("Keep both phones on this screen and on the same Wi-Fi.", style = MaterialTheme.typography.bodySmall)
        actions()
    }
}

@Composable
private fun TargetChoice(selected: Boolean, title: String, detail: String, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect).padding(vertical = 4.dp)) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun qrBitmap(text: String): Bitmap = BarcodeEncoder().encodeBitmap(text, BarcodeFormat.QR_CODE, 720, 720)

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("SMS Bridge pairing link", text)
    // Mark as sensitive so Android 13+ does not show it in the clipboard preview.
    clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    clipboard.setPrimaryClip(clip)
}
