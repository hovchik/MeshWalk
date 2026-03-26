package com.meshwalk.app.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshwalk.app.domain.model.Conversation
import com.meshwalk.app.domain.model.ConversationType
import com.meshwalk.app.domain.repository.ConversationRepository
import com.meshwalk.app.domain.repository.IdentityRepository
import com.meshwalk.app.domain.repository.MessageRepository
import com.meshwalk.app.domain.repository.SettingsRepository
import com.meshwalk.app.util.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val conversationRepo: ConversationRepository,
    private val messageRepo: MessageRepository,
    private val identityRepo: IdentityRepository,
    private val settingsRepo: SettingsRepository
) : ViewModel() {

    data class UiState(
        val conversations: List<Conversation> = emptyList(),
        val favoriteConversations: List<Conversation> = emptyList(),
        val regularConversations: List<Conversation> = emptyList(),
        val totalUnreadCount: Int = 0,
        val needsSetup: Boolean = false,
        val isLoading: Boolean = true,
        val showEncryptionBadge: Boolean = true
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

            combine(
                conversationRepo.observeConversations(),
                settingsRepo.observeSettings(),
                conversationRepo.observeTotalUnreadCount()
            ) { conversations, settings, totalUnread ->
                val directConversations = conversations.filter { it.type == ConversationType.DIRECT }
                val favorites = directConversations
                    .filter { it.isFavorite }
                    .sortedByDescending { it.lastMessageAt }
                val regular = directConversations
                    .filter { !it.isFavorite }
                    .sortedByDescending { it.lastMessageAt }
                UiState(
                    conversations = directConversations,
                    favoriteConversations = favorites,
                    regularConversations = regular,
                    totalUnreadCount = totalUnread,
                    isLoading = false,
                    showEncryptionBadge = settings.showEncryptionBadge
                )
            }.collect { _state.value = it }
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            messageRepo.deleteMessagesByConversation(conversationId)
            conversationRepo.deleteConversation(conversationId)
        }
    }

    fun toggleFavorite(conversationId: String) {
        viewModelScope.launch {
            val conv = conversationRepo.getConversation(conversationId) ?: return@launch
            conversationRepo.toggleFavorite(conversationId, !conv.isFavorite)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    onChatClick: (conversationId: String, peerNodeId: String) -> Unit,
    onNeedSetup: () -> Unit,
    viewModel: ChatListViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }

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
            // Total unread count header
            if (state.totalUnreadCount > 0) {
                item(key = "unread_header") {
                    TotalUnreadHeader(totalUnreadCount = state.totalUnreadCount)
                }
            }

            // Favorites section
            if (state.favoriteConversations.isNotEmpty()) {
                item(key = "favorites_header") {
                    SectionHeader(title = "Favorites")
                }
                items(
                    state.favoriteConversations,
                    key = { "fav_${it.conversationId}" }
                ) { conversation ->
                    SwipeableConversationItem(
                        conversation = conversation,
                        showEncryptionBadge = state.showEncryptionBadge,
                        onClick = {
                            onChatClick(
                                conversation.conversationId,
                                conversation.participants.firstOrNull() ?: ""
                            )
                        },
                        onDelete = { deleteTarget = conversation },
                        onToggleFavorite = {
                            viewModel.toggleFavorite(conversation.conversationId)
                        }
                    )
                }
            }

            // Regular conversations section
            if (state.regularConversations.isNotEmpty()) {
                if (state.favoriteConversations.isNotEmpty()) {
                    item(key = "conversations_header") {
                        SectionHeader(title = "Conversations")
                    }
                }
                items(
                    state.regularConversations,
                    key = { "reg_${it.conversationId}" }
                ) { conversation ->
                    SwipeableConversationItem(
                        conversation = conversation,
                        showEncryptionBadge = state.showEncryptionBadge,
                        onClick = {
                            onChatClick(
                                conversation.conversationId,
                                conversation.participants.firstOrNull() ?: ""
                            )
                        },
                        onDelete = { deleteTarget = conversation },
                        onToggleFavorite = {
                            viewModel.toggleFavorite(conversation.conversationId)
                        }
                    )
                }
            }
        }
    }

    deleteTarget?.let { conversation ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Chat") },
            text = {
                Text("Delete this conversation and all its messages? This cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteConversation(conversation.conversationId)
                    deleteTarget = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun TotalUnreadHeader(totalUnreadCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Email,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$totalUnreadCount unread message${if (totalUnreadCount != 1) "s" else ""}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableConversationItem(
    conversation: Conversation,
    showEncryptionBadge: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            when (dismissValue) {
                SwipeToDismissBoxValue.EndToStart -> {
                    onDelete()
                    false // Don't settle; let the dialog handle it
                }
                SwipeToDismissBoxValue.StartToEnd -> {
                    onToggleFavorite()
                    false // Snap back after toggling
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val color by animateColorAsState(
                when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                    SwipeToDismissBoxValue.StartToEnd -> Color(0xFFFFF3E0) // light orange/gold
                    else -> Color.Transparent
                },
                label = "swipe_bg_color"
            )
            val icon = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> Icons.Filled.Delete
                SwipeToDismissBoxValue.StartToEnd -> Icons.Filled.Star
                else -> null
            }
            val iconTint = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error
                SwipeToDismissBoxValue.StartToEnd -> Color(0xFFFFA000) // amber
                else -> Color.Transparent
            }
            val alignment = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                else -> Alignment.CenterStart
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 24.dp),
                contentAlignment = alignment
            ) {
                icon?.let {
                    Icon(
                        it,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        },
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true
    ) {
        ConversationItem(
            conversation = conversation,
            showEncryptionBadge = showEncryptionBadge,
            onClick = onClick,
            onDelete = onDelete
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationItem(
    conversation: Conversation,
    showEncryptionBadge: Boolean = true,
    onClick: () -> Unit,
    onDelete: () -> Unit = {}
) {
    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onDelete
        ),
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conversation.displayTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (conversation.isFavorite) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = "Favorite",
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFFFFA000) // amber
                    )
                }
            }
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
                    val badgeColor = if (conversation.unreadCount > 5) {
                        Color(0xFFD32F2F) // red for high count
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                    Badge(
                        containerColor = badgeColor,
                        contentColor = Color.White
                    ) {
                        Text(
                            text = conversation.unreadCount.toString(),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (conversation.isEncrypted && showEncryptionBadge) {
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
