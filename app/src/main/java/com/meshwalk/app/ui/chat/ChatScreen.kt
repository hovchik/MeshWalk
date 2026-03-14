package com.meshwalk.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshwalk.app.domain.model.*
import com.meshwalk.app.domain.repository.*
import com.meshwalk.app.domain.usecase.SendGroupMessageUseCase
import com.meshwalk.app.domain.usecase.SendMessageUseCase
import com.meshwalk.app.mesh.group.GroupControlManager
import com.meshwalk.app.util.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val groupControlManager: GroupControlManager
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
        val availablePeers: List<PeerNode> = emptyList()
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
                val peer = peerRepo.getPeer(peerNodeId)
                _state.value = _state.value.copy(
                    peerName = peer?.displayName ?: peerNodeId.take(8),
                    peerOnline = peer?.isConnected == true,
                    selfNodeId = identity?.nodeId ?: ""
                )
            }

            // Clear unread
            conversationRepo.clearUnread(conversationId)
        }

        // Observe settings first, then messages (need settings for group history limit)
        viewModelScope.launch {
            settingsRepo.observeSettings().collect { settings ->
                _state.value = _state.value.copy(
                    showHopCount = settings.showHopCount,
                    showEncryptionBadge = settings.showEncryptionBadge
                )
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

    fun loadAvailablePeers() {
        viewModelScope.launch {
            val peers = peerRepo.observeNearbyPeers()
            peers.collect { peerList ->
                val nonMembers = peerList.filter { peer ->
                    _state.value.groupMembers.none { it.nodeId == peer.nodeId }
                }
                _state.value = _state.value.copy(availablePeers = nonMembers)
            }
        }
    }

    fun clearSendError() {
        _state.value = _state.value.copy(sendError = null)
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
    var messageText by remember { mutableStateOf("") }
    var showGroupSettings by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

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
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .imePadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Type a message...") },
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            viewModel.send(messageText)
                            messageText = ""
                        },
                        enabled = messageText.isNotBlank()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send")
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(state.messages, key = { it.messageId }) { message ->
                MessageBubble(
                    message = message,
                    isOutgoing = !message.isIncoming,
                    showHopCount = state.showHopCount
                )
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

@Composable
private fun MessageBubble(
    message: MeshMessage,
    isOutgoing: Boolean,
    showHopCount: Boolean = false
) {
    val alignment = if (isOutgoing) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (isOutgoing) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isOutgoing) {
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (isOutgoing) 48.dp else 0.dp,
                end = if (isOutgoing) 0.dp else 48.dp
            ),
        contentAlignment = alignment
    ) {
        Surface(
            shape = bubbleShape,
            color = bubbleColor,
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
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
            }
        }
    }
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
