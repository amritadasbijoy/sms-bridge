package app.smsbridge.feature.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.smsbridge.core.database.SmsBridgeDatabase
import app.smsbridge.core.database.entity.RemoteMessageEntity
import app.smsbridge.data.cloud.CloudRepository
import app.smsbridge.ui.components.SectionCard
import app.smsbridge.ui.components.formatTimestamp
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class RemoteMessagesViewModel @Inject constructor(db: SmsBridgeDatabase, private val cloud: CloudRepository) : ViewModel() {
    val messages: StateFlow<List<RemoteMessageEntity>> =
        db.remoteMessageDao().observeNewest(500).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Catch up immediately when the screen opens (the background connection keeps it current). */
    fun refresh() = viewModelScope.launch { runCatching { cloud.pullRemote() } }
}

/** Read-only list of SMS from the account's other phones (listener mode), newest first. */
@Composable
fun RemoteMessagesScreen(viewModel: RemoteMessagesViewModel = hiltViewModel()) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Messages from your other phones", style = MaterialTheme.typography.headlineSmall) }
        if (messages.isEmpty()) {
            item {
                SectionCard("Nothing yet") {
                    Text("New SMS received by your other phones appear here as they arrive.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        items(messages, key = { it.key }) { m ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(
                    (if (m.box == "INBOX") "From " else "To ") + (m.address ?: "unknown"),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(m.body, style = MaterialTheme.typography.bodyMedium)
                Text(formatTimestamp(m.date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
