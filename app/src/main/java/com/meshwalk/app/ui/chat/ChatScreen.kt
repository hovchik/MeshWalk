package com.meshwalk.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshwalk.app.domain.model.*
import com.meshwalk.app.domain.repository.*
import com.meshwalk.app.domain.usecase.MeshOutboxPort
import com.meshwalk.app.domain.usecase.SendGroupMessageUseCase
import com.meshwalk.app.domain.usecase.SendMessageUseCase
import com.meshwalk.app.mesh.group.GroupControlManager
import com.meshwalk.app.util.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messageRepo: MessageRepository,
    private val conversationRepo: ConversationRepository,
    private val identityRepo: IdentityRepository,
    private val peerRepo: PeerRepository,
    private val settingsRepo: SettingsRepository,
    private val groupRepo: GroupRepository,
    private val sendMessage: SendMessageUseCase,
    private val sendGroupMessage: SendGroupMessageUseCase,
    private val groupControlManager: GroupControlManager,
    private val meshOutbox: MeshOutboxPort
) : ViewModel() {

    private val conversationId: String = savedStateHandle["conversationId"] ?: ""
    private val peerNodeId: String = savedStateHandle["peerNodeId"] ?: ""

    data class UiState(
        val messages: List<MeshMessage> = emptyList(),
        val peerName: String? = null,
        val isEncrypted: Boolean = true,
        val peerOnline: Boolean = false,
        val selfNodeId: String = "",
        val sendError: String? = null,
        val showHopCount: Boolean = false,
        val onlineMemberCount: Int = 0,
        val totalMemberCount: Int = 0,
        val showEncryptionBadge: Boolean = true,
        val isGroupChat: Boolean = false,
        val groupMembers: List<GroupMember> = emptyList(),
        val availablePeers: List<PeerNode> = emptyList(),
        val pinnedMessages: List<MeshMessage> = emptyList(),
        val searchResults: List<MeshMessage> = emptyList(),
        val isSearching: Boolean = false,
        val replyToMessage: MeshMessage? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val identity = identityRepo.getActiveIdentity()

            // Check if this is a group conversation
            val group = groupRepo.getGroup(conversationId)
            if (group != null) {
                // For group chats, check how many members are actually online
                val onlineMembers = group.members
                    .filter { it.nodeId != identity?.nodeId }
                    .mapNotNull { peerRepo.getPeer(it.nodeId) }
                    .count { it.isConnected }
                val totalMembers = group.members.filter { it.nodeId != identity?.nodeId }.size
                _state.value = _state.value.copy(
                    peerName = group.name,
                    peerOnline = onlineMembers > 0,
                    selfNodeId = identity?.nodeId ?: "",
                    isGroupChat = true,
                    onlineMemberCount = onlineMembers,
                    totalMemberCount = totalMembers,
                    groupMembers = group.members
                )
            } else {
                // For direct chats: use peer display name, fall back to conversation's cached name
                val conversation = conversationRepo.getConversation(conversationId)
                val peer = peerRepo.getPeer(peerNodeId)
                val displayName = peer?.displayName
                    ?: conversation?.peerDisplayName
                    ?: peerNodeId.take(8)

                _state.value = _state.value.copy(
                    peerName = displayName,
                    peerOnline = peer?.isConnected == true,
                    selfNodeId = identity?.nodeId ?: ""
                )

                // Update the cached peer name if we have a fresh one from the peer
                if (peer?.displayName != null && conversation?.peerDisplayName != peer.displayName) {
                    conversationRepo.updatePeerDisplayName(conversationId, peer.displayName)
                }
            }

            // Clear unread
            conversationRepo.clearUnread(conversationId)
        }

        // Observe settings
        viewModelScope.launch {
            settingsRepo.observeSettings().collect { settings ->
                _state.value = _state.value.copy(
                    showHopCount = settings.showHopCount,
                    showEncryptionBadge = settings.showEncryptionBadge
                )
            }
        }

        // Observe pinned messages
        viewModelScope.launch {
            messageRepo.observePinnedMessages(conversationId).collect { pinned ->
                _state.value = _state.value.copy(pinnedMessages = pinned)
            }
        }

        // Observe messages — for group chats, limit to the configured history count
        viewModelScope.launch {
            val settings = settingsRepo.getSettings()
            val group = groupRepo.getGroup(conversationId)
            val messagesFlow = if (group != null) {
                messageRepo.observeRecentMessages(conversationId, settings.groupMessageHistoryCount)
            } else {
                messageRepo.observeMessages(conversationId)
            }
            messagesFlow.collect { messages ->
                _state.value = _state.value.copy(messages = messages)
            }
        }
    }

    fun send(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(sendError = null)
            try {
                if (_state.value.isGroupChat) {
                    sendGroupMessage(
                        groupId = conversationId,
                        text = text.trim(),
                        senderNodeId = _state.value.selfNodeId
                    )
                } else {
                    sendMessage(
                        conversationId = conversationId,
                        recipientNodeId = peerNodeId,
                        text = text.trim(),
                        senderNodeId = _state.value.selfNodeId
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    sendError = e.message ?: "Failed to send message"
                )
            }
        }
    }

    fun addMember(memberNodeId: String) {
        viewModelScope.launch {
            try {
                groupControlManager.addMemberToGroup(
                    groupId = conversationId,
                    newMemberNodeId = memberNodeId,
                    ourNodeId = _state.value.selfNodeId
                )
                // Refresh group state
                val group = groupRepo.getGroup(conversationId)
                if (group != null) {
                    _state.value = _state.value.copy(
                        groupMembers = group.members,
                        totalMemberCount = group.members.filter { it.nodeId != _state.value.selfNodeId }.size
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(sendError = "Failed to add member: ${e.message}")
            }
        }
    }

    fun updateGroupName(newName: String) {
        viewModelScope.launch {
            try {
                groupControlManager.updateGroupName(conversationId, newName)
                _state.value = _state.value.copy(peerName = newName)
            } catch (e: Exception) {
                _state.value = _state.value.copy(sendError = "Failed to rename group: ${e.message}")
            }
        }
    }

    private var loadPeersJob: Job? = null

    fun loadAvailablePeers() {
        // Cancel any previous collection to prevent leaking coroutines
        loadPeersJob?.cancel()
        loadPeersJob = viewModelScope.launch {
            peerRepo.observeNearbyPeers().collect { peerList ->
                val nonMembers = peerList.filter { peer ->
                    _state.value.groupMembers.none { it.nodeId == peer.nodeId }
                }
                _state.value = _state.value.copy(availablePeers = nonMembers)
            }
        }
    }

    fun resendMessage(message: MeshMessage) {
        viewModelScope.launch {
            _state.value = _state.value.copy(sendError = null)
            try {
                // Reset status to PENDING before retrying
                messageRepo.updateDeliveryStatus(message.messageId, DeliveryStatus.PENDING)

                val text = when (val content = message.content) {
                    is MessageContent.Text -> content.text
                    is MessageContent.SystemEvent -> return@launch // Don't resend system events
                }

                if (_state.value.isGroupChat) {
                    val group = groupRepo.getGroup(message.conversationId) ?: return@launch
                    val recipientNodeIds = group.members
                        .filter { it.nodeId != _state.value.selfNodeId }
                        .map { it.nodeId }
                    meshOutbox.enqueueGroupMessage(message, recipientNodeIds, group.groupId)
                } else {
                    meshOutbox.enqueueMessage(message, peerNodeId)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    sendError = e.message ?: "Failed to resend message"
                )
            }
        }
    }

    fun clearSendError() {
        _state.value = _state.value.copy(sendError = null)
    }

    fun addReaction(message: MeshMessage, emoji: String) {
        viewModelScope.launch {
            val selfId = _state.value.selfNodeId
            val updated = message.reactions.toMutableMap()
            val isRemoval = updated[selfId] == emoji
            if (isRemoval) updated.remove(selfId) else updated[selfId] = emoji
            messageRepo.updateReactions(message.messageId, updated)

            // Notify the original message sender about our reaction so it
            // reaches them even when they're not currently viewing the chat.
            // No packet is sent when reacting to our own message.
            if (message.senderNodeId.isNotBlank() && message.senderNodeId != selfId) {
                try {
                    meshOutbox.enqueueReaction(
                        reaction = ReactionEnvelope(
                            messageId = message.messageId,
                            conversationId = message.conversationId,
                            emoji = emoji,
                            isRemoval = isRemoval
                        ),
                        recipientNodeId = message.senderNodeId,
                        senderNodeId = selfId
                    )
                } catch (e: Exception) {
                    _state.value = _state.value.copy(
                        sendError = "Couldn't deliver reaction: ${e.message ?: "unknown error"}"
                    )
                }
            }
        }
    }

    fun togglePin(message: MeshMessage) {
        viewModelScope.launch {
            messageRepo.togglePinned(message.messageId, !message.isPinned)
        }
    }

    fun setReplyTo(message: MeshMessage?) {
        _state.value = _state.value.copy(replyToMessage = message)
    }

    fun sendWithReply(text: String) {
        if (text.isBlank()) return
        val replyTo = _state.value.replyToMessage
        viewModelScope.launch {
            _state.value = _state.value.copy(sendError = null, replyToMessage = null)
            try {
                val sentMsg = if (_state.value.isGroupChat) {
                    sendGroupMessage(conversationId, text.trim(), _state.value.selfNodeId)
                } else {
                    sendMessage(conversationId, peerNodeId, text.trim(), _state.value.selfNodeId)
                }
                // If replying, update the sent message with reply-to fields
                if (replyTo != null) {
                    val preview = when (val c = replyTo.content) {
                        is MessageContent.Text -> c.text.take(80)
                        is MessageContent.SystemEvent -> c.event.take(80)
                    }
                    val updated = sentMsg.copy(
                        replyToMessageId = replyTo.messageId,
                        replyToPreview = preview
                    )
                    messageRepo.saveMessage(updated)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(sendError = e.message ?: "Failed to send")
            }
        }
    }

    fun searchMessages(query: String) {
        if (query.isBlank()) {
            _state.value = _state.value.copy(searchResults = emptyList(), isSearching = false)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isSearching = true)
            val results = messageRepo.searchMessages(conversationId, query)
            _state.value = _state.value.copy(searchResults = results, isSearching = false)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    peerNodeId: String,
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var messageText by remember { mutableStateOf(TextFieldValue("")) }
    var showGroupSettings by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showPinnedBanner by remember { mutableStateOf(false) }
    var contextMenuMessage by remember { mutableStateOf<MeshMessage?>(null) }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // Auto-scroll to bottom on new messages
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    // Show send errors
    LaunchedEffect(state.sendError) {
        state.sendError?.let { error ->
            snackbarHostState.showSnackbar(
                message = error,
                duration = SnackbarDuration.Short
            )
            viewModel.clearSendError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.peerName ?: "Chat")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state.isGroupChat) {
                                Icon(
                                    Icons.Filled.Group,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${state.onlineMemberCount}/${state.totalMemberCount} online",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            if (state.peerOnline) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outline
                                        )
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (state.peerOnline) "Online" else "Offline",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (state.isEncrypted && state.showEncryptionBadge) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    Icons.Filled.Lock,
                                    contentDescription = "End-to-end encrypted",
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Filled.Search, "Search messages")
                    }
                    if (state.isGroupChat) {
                        IconButton(onClick = {
                            viewModel.loadAvailablePeers()
                            showGroupSettings = true
                        }) {
                            Icon(Icons.Filled.Settings, "Group settings")
                        }
                    }
                }
            )
        }
    ) { padding ->
        // Use a Column with imePadding so both the message list and input bar
        // resize correctly when the keyboard opens. The message list takes all
        // available space (weight 1f) and the input bar sits at the bottom.
        // consumeWindowInsets(padding) prevents imePadding from double-counting
        // the insets already consumed by Scaffold's content padding.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .navigationBarsPadding()
                .imePadding()
        ) {
            // Search bar
            AnimatedVisibility(visible = showSearch) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        viewModel.searchMessages(it)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    placeholder = { Text("Search messages...") },
                    leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(20.dp)) },
                    trailingIcon = {
                        IconButton(onClick = {
                            showSearch = false
                            searchQuery = ""
                            viewModel.searchMessages("")
                        }) { Icon(Icons.Filled.Close, "Close search", modifier = Modifier.size(20.dp)) }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp)
                )
            }

            // Search results
            if (showSearch && state.searchResults.isNotEmpty()) {
                Surface(
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("${state.searchResults.size} results", style = MaterialTheme.typography.labelSmall)
                        state.searchResults.take(5).forEach { msg ->
                            val preview = when (val c = msg.content) {
                                is MessageContent.Text -> c.text.take(60)
                                is MessageContent.SystemEvent -> c.event.take(60)
                            }
                            Text(
                                text = "${TimeUtils.formatTimestamp(msg.timestamp)}: $preview",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            // Pinned messages banner
            if (state.pinnedMessages.isNotEmpty()) {
                Surface(
                    tonalElevation = 2.dp,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPinnedBanner = !showPinnedBanner }
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PushPin, null, modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "${state.pinnedMessages.size} pinned message${if (state.pinnedMessages.size > 1) "s" else ""}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        AnimatedVisibility(visible = showPinnedBanner) {
                            Column(modifier = Modifier.padding(top = 4.dp)) {
                                state.pinnedMessages.forEach { pinned ->
                                    val preview = when (val c = pinned.content) {
                                        is MessageContent.Text -> c.text.take(80)
                                        is MessageContent.SystemEvent -> c.event.take(80)
                                    }
                                    Text(
                                        text = preview,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        maxLines = 1,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Message list — fills available space above input bar
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(state.messages, key = { it.messageId }) { message ->
                    val senderName = if (state.isGroupChat && message.isIncoming) {
                        state.groupMembers.find { it.nodeId == message.senderNodeId }?.displayName
                            ?: message.senderNodeId.take(8)
                    } else null

                    MessageBubble(
                        message = message,
                        isOutgoing = !message.isIncoming,
                        showHopCount = state.showHopCount,
                        senderName = senderName,
                        onResend = if (!message.isIncoming && (message.deliveryStatus == DeliveryStatus.FAILED || message.deliveryStatus == DeliveryStatus.EXPIRED)) {
                            { viewModel.resendMessage(message) }
                        } else null,
                        onRetry = if (!message.isIncoming && message.deliveryStatus == DeliveryStatus.PENDING &&
                            System.currentTimeMillis() - message.timestamp > 10_000L) {
                            { viewModel.resendMessage(message) }
                        } else null,
                        onLongPress = { contextMenuMessage = message }
                    )
                }
            }

            // Quick message chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("I'm OK", "Need help", "On my way", "Yes", "No").forEach { quick ->
                    SuggestionChip(
                        onClick = {
                            viewModel.send(quick)
                        },
                        label = { Text(quick, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            // Reply-to preview bar
            state.replyToMessage?.let { replyMsg ->
                Surface(
                    tonalElevation = 2.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(32.dp)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Reply to", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                            val preview = when (val c = replyMsg.content) {
                                is MessageContent.Text -> c.text.take(60)
                                is MessageContent.SystemEvent -> c.event.take(60)
                            }
                            Text(preview, style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { viewModel.setReplyTo(null) }) {
                            Icon(Icons.Filled.Close, "Cancel reply", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // Input bar — stays above keyboard
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            showEmojiPicker = !showEmojiPicker
                            if (showEmojiPicker) keyboardController?.hide()
                        }) {
                            Icon(
                                Icons.Filled.EmojiEmotions,
                                contentDescription = "Emoji",
                                tint = if (showEmojiPicker) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedTextField(
                            value = messageText,
                            onValueChange = {
                                messageText = it
                                if (showEmojiPicker) showEmojiPicker = false
                            },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Type a message...") },
                            maxLines = 4,
                            shape = RoundedCornerShape(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilledIconButton(
                            onClick = {
                                if (state.replyToMessage != null) {
                                    viewModel.sendWithReply(messageText.text)
                                } else {
                                    viewModel.send(messageText.text)
                                }
                                messageText = TextFieldValue("")
                                showEmojiPicker = false
                            },
                            enabled = messageText.text.isNotBlank()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Send")
                        }
                    }
                    AnimatedVisibility(visible = showEmojiPicker) {
                        EmojiPickerGrid(onEmojiSelected = { emoji ->
                            val current = messageText
                            val before = current.text.substring(0, current.selection.start)
                            val after = current.text.substring(current.selection.end)
                            val newText = before + emoji + after
                            val newCursor = before.length + emoji.length
                            messageText = TextFieldValue(
                                text = newText,
                                selection = TextRange(newCursor)
                            )
                        })
                    }
                }
            }
        }
    }

    if (showGroupSettings) {
        GroupSettingsDialog(
            groupName = state.peerName ?: "",
            members = state.groupMembers,
            availablePeers = state.availablePeers,
            onDismiss = { showGroupSettings = false },
            onRename = { newName ->
                viewModel.updateGroupName(newName)
                showGroupSettings = false
            },
            onAddMember = { nodeId ->
                viewModel.addMember(nodeId)
            }
        )
    }

    // Message context menu (long-press)
    contextMenuMessage?.let { msg ->
        val reactionEmojis = listOf("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDE22", "\uD83D\uDE4F")
        AlertDialog(
            onDismissRequest = { contextMenuMessage = null },
            title = { Text("Message Actions") },
            text = {
                Column {
                    // Reaction row
                    Text("React", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        reactionEmojis.forEach { emoji ->
                            Surface(
                                onClick = {
                                    viewModel.addReaction(msg, emoji)
                                    contextMenuMessage = null
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = if (msg.reactions[state.selfNodeId] == emoji)
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    emoji,
                                    fontSize = 24.sp,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    // Copy
                    val copyableText = when (val c = msg.content) {
                        is MessageContent.Text -> c.text
                        is MessageContent.SystemEvent -> c.event
                    }
                    TextButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(copyableText))
                            contextMenuMessage = null
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Message copied",
                                    duration = SnackbarDuration.Short
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = copyableText.isNotEmpty()
                    ) {
                        Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Copy")
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    // Reply
                    TextButton(
                        onClick = {
                            viewModel.setReplyTo(msg)
                            contextMenuMessage = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Reply, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Reply")
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    // Pin/Unpin
                    TextButton(
                        onClick = {
                            viewModel.togglePin(msg)
                            contextMenuMessage = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.PushPin, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (msg.isPinned) "Unpin" else "Pin")
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { contextMenuMessage = null }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun GroupSettingsDialog(
    groupName: String,
    members: List<GroupMember>,
    availablePeers: List<PeerNode>,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onAddMember: (String) -> Unit
) {
    var editedName by remember { mutableStateOf(groupName) }
    var showAddMember by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Group Settings") },
        text = {
            Column {
                OutlinedTextField(
                    value = editedName,
                    onValueChange = { editedName = it },
                    label = { Text("Group Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (editedName != groupName && editedName.isNotBlank()) {
                    TextButton(onClick = { onRename(editedName) }) {
                        Text("Save Name")
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Members (${members.size})",
                    style = MaterialTheme.typography.labelMedium
                )
                members.forEach { member ->
                    ListItem(
                        headlineContent = {
                            Text(member.displayName ?: member.nodeId.take(8))
                        },
                        supportingContent = {
                            Text(
                                member.role.name,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Person,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = { showAddMember = !showAddMember }) {
                    Icon(Icons.Filled.PersonAdd, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Member")
                }
                if (showAddMember) {
                    if (availablePeers.isEmpty()) {
                        Text(
                            "No available peers to add",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    } else {
                        availablePeers.forEach { peer ->
                            ListItem(
                                modifier = Modifier.clickable { onAddMember(peer.nodeId) },
                                headlineContent = {
                                    Text(peer.displayName ?: peer.nodeId.take(8))
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Filled.PersonAdd,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

private val EMOJI_LIST = listOf(
    // Smileys
    "\uD83D\uDE00", "\uD83D\uDE03", "\uD83D\uDE04", "\uD83D\uDE01", "\uD83D\uDE06",
    "\uD83D\uDE05", "\uD83D\uDE02", "\uD83E\uDD23", "\uD83D\uDE0A", "\uD83D\uDE07",
    "\uD83D\uDE42", "\uD83D\uDE43", "\uD83D\uDE09", "\uD83D\uDE0C", "\uD83D\uDE0D",
    "\uD83E\uDD70", "\uD83D\uDE18", "\uD83D\uDE17", "\uD83D\uDE1A", "\uD83D\uDE19",
    "\uD83D\uDE0B", "\uD83D\uDE1B", "\uD83D\uDE1D", "\uD83D\uDE1C", "\uD83E\uDD2A",
    "\uD83E\uDD28", "\uD83E\uDDD0", "\uD83E\uDD13", "\uD83D\uDE0E", "\uD83E\uDD29",
    // Gestures
    "\uD83D\uDC4D", "\uD83D\uDC4E", "\uD83D\uDC4B", "\uD83D\uDC4F", "\uD83D\uDE4F",
    "\uD83D\uDC4C", "\u270C\uFE0F", "\uD83E\uDD1E", "\uD83E\uDD1F", "\uD83E\uDD18",
    // Hearts
    "\u2764\uFE0F", "\uD83E\uDDE1", "\uD83D\uDC9B", "\uD83D\uDC9A", "\uD83D\uDC99",
    "\uD83D\uDC9C", "\uD83D\uDDA4", "\uD83D\uDC94", "\u2763\uFE0F", "\uD83D\uDC95",
    // Faces
    "\uD83D\uDE14", "\uD83D\uDE1E", "\uD83D\uDE22", "\uD83D\uDE2D", "\uD83D\uDE29",
    "\uD83D\uDE21", "\uD83E\uDD2C", "\uD83D\uDE31", "\uD83D\uDE28", "\uD83D\uDE30",
    "\uD83E\uDD14", "\uD83E\uDD2F", "\uD83D\uDE34", "\uD83E\uDD75", "\uD83E\uDD76",
    "\uD83D\uDE2E", "\uD83D\uDE32", "\uD83E\uDD2D", "\uD83E\uDD2B", "\uD83E\uDD25",
    // Objects
    "\uD83D\uDD25", "\uD83C\uDF1F", "\u2728", "\uD83C\uDF08", "\u2600\uFE0F",
    "\uD83C\uDF19", "\uD83C\uDF1A", "\u26A1", "\uD83D\uDCA5", "\uD83C\uDF89"
)

@Composable
private fun EmojiPickerGrid(onEmojiSelected: (String) -> Unit) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            modifier = Modifier
                .height(200.dp)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            contentPadding = PaddingValues(4.dp)
        ) {
            items(EMOJI_LIST) { emoji ->
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onEmojiSelected(emoji) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = emoji, fontSize = 24.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: MeshMessage,
    isOutgoing: Boolean,
    showHopCount: Boolean = false,
    senderName: String? = null,
    onResend: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null
) {
    val alignment = if (isOutgoing) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (message.isDelayed && message.isIncoming) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else if (isOutgoing) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (message.isDelayed && message.isIncoming) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else if (isOutgoing) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val bubbleShape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (isOutgoing) 16.dp else 4.dp,
        bottomEnd = if (isOutgoing) 4.dp else 16.dp
    )

    Column {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (isOutgoing) 48.dp else 0.dp,
                end = if (isOutgoing) 0.dp else 48.dp
            )
            .let { mod ->
                if (onLongPress != null) mod.combinedClickable(
                    onClick = {},
                    onLongClick = onLongPress
                ) else mod
            },
        contentAlignment = alignment
    ) {
        Surface(
            shape = bubbleShape,
            color = bubbleColor,
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                // Reply preview
                if (message.replyToPreview != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isOutgoing) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                               else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Box(modifier = Modifier.width(2.dp).height(20.dp)
                                .background(MaterialTheme.colorScheme.primary))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = message.replyToPreview,
                                style = MaterialTheme.typography.bodySmall,
                                color = textColor.copy(alpha = 0.7f),
                                maxLines = 1
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
                // Pinned indicator
                if (message.isPinned) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PushPin, null, modifier = Modifier.size(10.dp),
                            tint = textColor.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Pinned", style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.5f))
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                }
                if (message.isDelayed && message.isIncoming) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Schedule,
                            contentDescription = "Delayed message",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Delayed",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                }
                if (senderName != null) {
                    Text(
                        text = senderName,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (message.isDelayed && message.isIncoming)
                            MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                when (val content = message.content) {
                    is MessageContent.Text -> {
                        Text(
                            text = content.text,
                            color = textColor,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    is MessageContent.SystemEvent -> {
                        Text(
                            text = content.event,
                            color = textColor.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = TimeUtils.formatTimestamp(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.6f)
                    )

                    if (isOutgoing) {
                        Spacer(modifier = Modifier.width(4.dp))
                        DeliveryStatusIcon(
                            status = message.deliveryStatus,
                            tint = textColor.copy(alpha = 0.6f)
                        )
                        if (onRetry != null) {
                            Spacer(modifier = Modifier.width(2.dp))
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "Retry",
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable(onClick = onRetry),
                                tint = textColor.copy(alpha = 0.8f)
                            )
                        }
                    }

                    if (showHopCount && message.hopCount > 0) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${message.hopCount}h",
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.4f)
                        )
                    }
                }

                if (onResend != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        onClick = onResend,
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "Resend",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Tap to resend",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }
    }
    // Reactions display below bubble
    if (message.reactions.isNotEmpty()) {
        Row(
            modifier = Modifier
                .padding(
                    start = if (isOutgoing) 48.dp else 4.dp,
                    end = if (isOutgoing) 4.dp else 48.dp
                ),
            horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
        ) {
            val grouped = message.reactions.values.groupingBy { it }.eachCount()
            grouped.forEach { (emoji, count) ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Text(
                        text = if (count > 1) "$emoji $count" else emoji,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
    } // close outer Column
}

@Composable
private fun DeliveryStatusIcon(status: DeliveryStatus, tint: androidx.compose.ui.graphics.Color) {
    val icon = when (status) {
        DeliveryStatus.PENDING -> Icons.Filled.Schedule
        DeliveryStatus.SENT -> Icons.Filled.Check
        DeliveryStatus.RELAYED -> Icons.Filled.SwapHoriz
        DeliveryStatus.DELIVERED -> Icons.Filled.DoneAll
        DeliveryStatus.READ -> Icons.Filled.DoneAll
        DeliveryStatus.FAILED -> Icons.Filled.ErrorOutline
        DeliveryStatus.EXPIRED -> Icons.Filled.TimerOff
    }
    Icon(
        imageVector = icon,
        contentDescription = status.name,
        modifier = Modifier.size(14.dp),
        tint = if (status == DeliveryStatus.READ) MaterialTheme.colorScheme.primary
        else tint
    )
}
