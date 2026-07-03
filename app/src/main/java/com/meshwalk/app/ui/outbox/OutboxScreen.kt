package com.meshwalk.app.ui.outbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshwalk.app.domain.model.DeliveryStatus
import com.meshwalk.app.domain.model.MeshMessage
import com.meshwalk.app.domain.repository.ConversationRepository
import com.meshwalk.app.domain.repository.MessageRepository
import com.meshwalk.app.routing.queue.OfflineQueue
import com.meshwalk.app.util.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Makes store-and-forward legible: lists outgoing messages that haven't been
 * delivered yet (PENDING or FAILED), so the user can see what's waiting to go
 * out and how many packets are sitting in the offline relay queue.
 */
@HiltViewModel
class OutboxViewModel @Inject constructor(
    messageRepo: MessageRepository,
    private val conversationRepo: ConversationRepository,
    private val offlineQueue: OfflineQueue
) : ViewModel() {

    data class OutboxEntry(
        val message: MeshMessage,
        val conversationTitle: String
    )

    val entries = messageRepo.observeOutbox()
        .map { messages ->
            messages.map { msg ->
                val title = conversationRepo.getConversation(msg.conversationId)?.displayTitle
                    ?: msg.conversationId.take(8)
                OutboxEntry(msg, title)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun queuedPacketCount(): Int = offlineQueue.size()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutboxScreen(
    onBack: () -> Unit,
    onOpenChat: (conversationId: String, peerNodeId: String) -> Unit,
    viewModel: OutboxViewModel = hiltViewModel()
) {
    val entries by viewModel.entries.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Outbox") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Column(
                modifier = Modifier.padding(padding).fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Filled.MarkEmailRead,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Nothing waiting to send", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Undelivered messages appear here until a peer is in range.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(entries, key = { it.message.messageId }) { entry ->
                    OutboxRow(entry, onOpenChat)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun OutboxRow(
    entry: OutboxViewModel.OutboxEntry,
    onOpenChat: (conversationId: String, peerNodeId: String) -> Unit
) {
    val msg = entry.message
    val (icon, tint) = when (msg.deliveryStatus) {
        DeliveryStatus.FAILED -> Icons.Filled.ErrorOutline to MaterialTheme.colorScheme.error
        else -> Icons.Filled.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
    }
    ListItem(
        modifier = Modifier.clickable {
            // For direct chats the peer is the sole participant; pass conversationId
            // as a best-effort peerNodeId fallback for group chats.
            onOpenChat(msg.conversationId, msg.conversationId)
        },
        headlineContent = { Text(entry.conversationTitle, maxLines = 1) },
        supportingContent = {
            Text(msg.content.previewText, maxLines = 1)
        },
        leadingContent = { Icon(icon, contentDescription = msg.deliveryStatus.name, tint = tint) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (msg.deliveryStatus == DeliveryStatus.FAILED) "Failed" else "Pending",
                    style = MaterialTheme.typography.labelSmall,
                    color = tint
                )
                Text(
                    TimeUtils.formatTimestamp(msg.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    )
}
