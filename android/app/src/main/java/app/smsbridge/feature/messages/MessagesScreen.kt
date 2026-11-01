package app.smsbridge.feature.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.smsbridge.core.model.MessageFilter
import app.smsbridge.core.model.SmsDirection
import app.smsbridge.core.model.SmsMessage
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

@HiltViewModel
class MessagesViewModel @Inject constructor(repository: SmsRepository) : ViewModel() {
    private val _filter = MutableStateFlow(MessageFilter.ALL)
    val filter: StateFlow<MessageFilter> = _filter.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: Flow<PagingData<SmsMessage>> =
        _filter.flatMapLatest { repository.pagedMessages(it) }.cachedIn(viewModelScope)

    fun setFilter(filter: MessageFilter) {
        _filter.value = filter
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(viewModel: MessagesViewModel = hiltViewModel()) {
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val items = viewModel.messages.collectAsLazyPagingItems()

    Column(Modifier.fillMaxSize()) {
        Text("Messages", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MessageFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { viewModel.setFilter(option) },
                    label = { Text(option.label()) },
                )
            }
        }

        when {
            items.loadState.refresh is LoadState.Loading && items.itemCount == 0 ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            items.itemCount == 0 ->
                Text(
                    "No messages in the archive yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            else -> LazyColumn(contentPadding = PaddingValues(16.dp)) {
                items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
                    items[index]?.let { MessageRow(it) }
                }
            }
        }
    }
}

private fun MessageFilter.label() = when (this) {
    MessageFilter.ALL -> "All"
    MessageFilter.INCOMING -> "Incoming"
    MessageFilter.OUTGOING -> "Outgoing"
}

@Composable
private fun MessageRow(message: SmsMessage) {
    val direction = when (message.type.direction) {
        SmsDirection.INCOMING -> "From"
        SmsDirection.OUTGOING -> "To"
        SmsDirection.UNKNOWN -> "With"
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
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
        val details = buildList {
            add(message.type.name.lowercase().replaceFirstChar { it.uppercase() })
            if (!message.read) add("Unread")
            message.subscriptionId?.let { add("SIM subscription $it") }
        }
        Text(
            details.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}
