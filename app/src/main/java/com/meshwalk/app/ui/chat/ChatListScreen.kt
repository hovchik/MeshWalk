package com.meshwalk.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshwalk.app.domain.model.Conversation
import com.meshwalk.app.domain.model.ConversationType
import com.meshwalk.app.domain.repository.ConversationRepository
import com.meshwalk.app.domain.repository.IdentityRepository
import com.meshwalk.app.domain.repository.SettingsRepository
import com.meshwalk.app.util.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val conversationRepo: ConversationRepository,
    private val identityRepo: IdentityRepository,
    private val settingsRepo: SettingsRepository
) : ViewModel() {

    data class UiState(
        val conversations: List<Conversation> = emptyList(),
        val needsSetup: Boolean = false,
        val isLoading: Boolean = true
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val hasIdentity = identityRepo.getActiveIdentity() != null
            val onboarded = settingsRepo.isOnboardingComplete()

            if (!hasIdentity || !onboarded) {
                _state.value = UiState(needsSetup = true, isLoading = false)
                return@launch
            }

            conversationRepo.observeConversations().collect { conversations ->
                _state.value = UiState(
                    conversations = conversations.filter { it.type == ConversationType.DIRECT },
                    isLoading = false
                )
            }
        }
    }
}

@Composable
fun ChatListScreen(
    onChatClick: (conversationId: String, peerNodeId: String) -> Unit,
    onNeedSetup: () -> Unit,
    viewModel: ChatListViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.needsSetup) {
        if (state.needsSetup) onNeedSetup()
    }

    if (state.isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    } else if (state.conversations.isEmpty()) {
        EmptyChatState()
    } else {
        LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(state.conversations, key = { it.conversationId }) { conversation ->
                ConversationItem(
                    conversation = conversation,
                    onClick = {
                        onChatClick(
                            conversation.conversationId,
                            conversation.participants.firstOrNull() ?: ""
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(
                text = conversation.title
                    ?: conversation.participants.firstOrNull()?.take(8)
                    ?: "Unknown",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            conversation.lastMessagePreview?.let { preview ->
                Text(
                    text = preview,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        leadingContent = {
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                conversation.lastMessageAt?.let { timestamp ->
                    Text(
                        text = TimeUtils.formatTimestamp(timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (conversation.unreadCount > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Badge {
                        Text(conversation.unreadCount.toString())
                    }
                }
                if (conversation.isEncrypted) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = "Encrypted",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    )
}

@Composable
private fun EmptyChatState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.Chat,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No conversations yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Discover nearby peers to start chatting",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )
    }
}
