package app.smsbridge.feature.messages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.smsbridge.core.database.dao.ConversationSummary
import app.smsbridge.core.model.MessageFilter
import app.smsbridge.core.model.SmsDirection
import app.smsbridge.core.model.SmsMessage
import app.smsbridge.data.sms.SmsPermissionManager
import app.smsbridge.data.sms.SmsPermissionState
import app.smsbridge.data.sms.SmsRepository
import app.smsbridge.ui.components.formatTimestamp
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest

/** What the Messages tab shows: grouped conversations, or a flat filtered list. */
enum class MessagesMode(val label: String, val filter: MessageFilter?) {
    CONVERSATIONS("Conversations", null),
    ALL("All", MessageFilter.ALL),
    INCOMING("Incoming", MessageFilter.INCOMING),
    OUTGOING("Outgoing", MessageFilter.OUTGOING),
}

@HiltViewModel
class MessagesViewModel @Inject constructor(
    repository: SmsRepository,
    permissions: SmsPermissionManager,
) : ViewModel() {
    private val _mode = MutableStateFlow(MessagesMode.CONVERSATIONS)
    val mode: StateFlow<MessagesMode> = _mode.asStateFlow()
    val permissionState: StateFlow<SmsPermissionState> = permissions.state

    val conversations: Flow<PagingData<ConversationSummary>> =
        repository.pagedConversations().cachedIn(viewModelScope)

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: Flow<PagingData<SmsMessage>> =
        _mode.flatMapLatest { repository.pagedMessages(it.filter ?: MessageFilter.ALL) }.cachedIn(viewModelScope)

    fun setMode(mode: MessagesMode) {
        _mode.value = mode
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    onOpenThread: (addressKey: String) -> Unit,
    onCompose: () -> Unit,
    viewModel: MessagesViewModel = hiltViewModel(),
) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val permissions by viewModel.permissionState.collectAsStateWithLifecycle()
    val conversations = viewModel.conversations.collectAsLazyPagingItems()
    val messages = viewModel.messages.collectAsLazyPagingItems()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Text("Messages", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MessagesMode.entries.forEach { option ->
                    FilterChip(
                        selected = mode == option,
                        onClick = { viewModel.setMode(option) },
                        label = { Text(option.label) },
                    )
                }
            }
            if (mode == MessagesMode.CONVERSATIONS) {
                PagedList(conversations, emptyText = "No conversations yet.", key = { it.addressKey }) {
                    ConversationRow(it, onClick = { onOpenThread(it.addressKey) })
                }
            } else {
                PagedList(messages, emptyText = "No messages in the archive yet.", key = { it.id }) {
                    MessageRow(it, onClick = { onOpenThread(it.addressKey) })
                }
            }
        }
        if (permissions.canSendSms) {
            ExtendedFloatingActionButton(
                onClick = onCompose,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New message") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

@Composable
private fun <T : Any> PagedList(
    items: LazyPagingItems<T>,
    emptyText: String,
    key: (T) -> Any,
    row: @Composable (T) -> Unit,
) {
    when {
        items.loadState.refresh is LoadState.Loading && items.itemCount == 0 ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        items.itemCount == 0 ->
            Text(
                emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        else -> LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp)) {
            items(count = items.itemCount, key = items.itemKey(key)) { index ->
                items[index]?.let { row(it) }
            }
        }
    }
}

@Composable
private fun ConversationRow(conversation: ConversationSummary, onClick: () -> Unit) {
    val unread = conversation.unreadCount > 0
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                conversation.address ?: "Unknown sender",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (unread) FontWeight.Bold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(formatTimestamp(conversation.lastDate), style = MaterialTheme.typography.labelSmall)
        }
        Text(
            conversation.body.ifEmpty { "(empty message)" },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (unread) FontWeight.SemiBold else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            buildString {
                append("${conversation.messageCount} messages")
                if (unread) append(" · ${conversation.unreadCount} unread")
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun MessageRow(message: SmsMessage, onClick: () -> Unit) {
    val direction = when (message.type.direction) {
        SmsDirection.INCOMING -> "From"
        SmsDirection.OUTGOING -> "To"
        SmsDirection.UNKNOWN -> "With"
    }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "$direction ${message.address ?: "unknown"}",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(formatTimestamp(message.date), style = MaterialTheme.typography.labelSmall)
        }
        Text(
            message.body.ifEmpty { "(empty message)" },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            messageStatusLabel(message),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}
