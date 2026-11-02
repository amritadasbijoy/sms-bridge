package app.smsbridge.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.smsbridge.core.database.dao.ConversationSummary
import app.smsbridge.feature.permissions.SmsPermissionCard
import app.smsbridge.ui.components.SectionCard
import app.smsbridge.ui.components.formatTimestamp

@Composable
fun DashboardScreen(
    onOpenMessages: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val permissions = state.permissions

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text("SMS Bridge", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Your private, on-device SMS archive",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { app.smsbridge.feature.update.UpdateBanner() }

        if (!permissions.hasTelephony) {
            item {
                SectionCard("No mobile network on this device") {
                    Text(
                        "This device cannot receive SMS. SMS Bridge can still open archives, " +
                            "but there is nothing to capture here.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (!permissions.canReadSms || !permissions.canReceiveSms) {
            item { SmsPermissionCard(permissions, onPermissionResult = viewModel::onPermissionResult) }
        }

        item { SyncStatusCard(state.sync) }

        if (state.scan.running) {
            item {
                SectionCard("Scanning messages") {
                    Text("${state.scan.scanned} messages processed", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Total", state.stats.total, Modifier.weight(1f))
                StatTile("Incoming", state.stats.incoming, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Outgoing", state.stats.outgoing, Modifier.weight(1f))
                StatTile("Conversations", state.stats.conversations, Modifier.weight(1f))
            }
        }

        if (permissions.isDefaultSmsApp) {
            item {
                SectionCard("Default SMS app mode") {
                    Text(
                        "SMS Bridge receives, stores and sends your text messages. MMS (picture and group " +
                            "messages) is not supported.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!permissions.canPostNotifications) {
                        Text(
                            "Notifications are off: you will not be alerted to new messages. Turn them on in Settings.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        } else if (permissions.canReadSms) {
            item {
                SectionCard("Backup-only mode") {
                    Text(
                        "SMS Bridge works alongside your usual messaging app. New incoming messages are " +
                            "saved as they arrive. Messages you send from another app are picked up by " +
                            "background scans, which Android may delay, so they can appear here later. " +
                            "To send from SMS Bridge, make it your default SMS app in Settings.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        item {
            Text("Recent conversations", style = MaterialTheme.typography.titleMedium)
        }
        if (state.conversations.isEmpty()) {
            item {
                val message = when {
                    !state.loaded -> "Loading…"
                    !permissions.canReadSms -> "Allow \"Read existing messages\" to see your conversations here."
                    state.scan.running -> "Your messages are being imported…"
                    else -> "No messages found on this device yet."
                }
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(state.conversations, key = { it.addressKey }) { ConversationRow(it) }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                Button(onClick = viewModel::scanNow, enabled = permissions.canReadSms) { Text("Scan now") }
                OutlinedButton(onClick = onOpenMessages) { Text("All messages") }
            }
        }
    }
}

@Composable
private fun SyncStatusCard(sync: SyncSummary) {
    val (label, detail) = when (sync.state) {
        SyncDisplayState.PAUSED ->
            "Paused" to "Synchronization is paused. New messages are still saved on this phone."
        SyncDisplayState.SYNCHRONIZING ->
            "Synchronizing" to "Uploading ${sync.pendingEvents} change(s)."
        SyncDisplayState.PENDING -> "Pending" to if (sync.cloudConfigured) {
            "${sync.pendingEvents} change(s) waiting for a network connection."
        } else {
            "${sync.pendingEvents} change(s) queued on this phone. Cloud sync is not set up yet, so nothing is uploaded."
        }
        SyncDisplayState.UP_TO_DATE -> "Up to date" to if (sync.cloudConfigured) {
            "Everything has been uploaded."
        } else {
            "All captured messages are stored on this phone. Cloud sync is not set up."
        }
    }
    SectionCard("Sync: $label") {
        Text(detail, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        sync.lastSyncedAt?.let {
            Text("Last synchronized ${formatTimestamp(it)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatTile(label: String, value: Int, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(value.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConversationRow(conversation: ConversationSummary) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                conversation.address ?: "Unknown sender",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(formatTimestamp(conversation.lastDate), style = MaterialTheme.typography.labelSmall)
        }
        Text(
            conversation.body,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("${conversation.messageCount} messages", style = MaterialTheme.typography.labelSmall)
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}
