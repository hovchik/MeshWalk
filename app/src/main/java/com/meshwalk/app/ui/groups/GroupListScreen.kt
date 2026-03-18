package com.meshwalk.app.ui.groups

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshwalk.app.domain.model.Conversation
import com.meshwalk.app.domain.model.ConversationType
import com.meshwalk.app.domain.model.GroupInfo
import com.meshwalk.app.domain.model.GroupInvitation
import com.meshwalk.app.domain.model.PeerNode
import com.meshwalk.app.domain.repository.ConversationRepository
import com.meshwalk.app.domain.repository.GroupRepository
import com.meshwalk.app.domain.repository.IdentityRepository
import com.meshwalk.app.domain.repository.MessageRepository
import com.meshwalk.app.domain.repository.PeerRepository
import com.meshwalk.app.billing.FeatureGate
import com.meshwalk.app.domain.usecase.CreateGroupUseCase
import com.meshwalk.app.mesh.group.GroupControlManager
import com.meshwalk.app.util.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GroupListViewModel @Inject constructor(
    private val groupRepo: GroupRepository,
    private val peerRepo: PeerRepository,
    private val identityRepo: IdentityRepository,
    private val conversationRepo: ConversationRepository,
    private val messageRepo: MessageRepository,
    private val createGroup: CreateGroupUseCase,
    private val groupControlManager: GroupControlManager,
    val featureGate: FeatureGate
) : ViewModel() {

    data class GroupWithConversation(
        val group: GroupInfo,
        val conversation: Conversation? = null
    ) {
        val lastMessagePreview: String? get() = conversation?.lastMessagePreview
        val lastMessageAt: Long? get() = conversation?.lastMessageAt
        val unreadCount: Int get() = conversation?.unreadCount ?: 0
    }

    data class UiState(
        val groups: List<GroupWithConversation> = emptyList(),
        val availablePeers: List<PeerNode> = emptyList(),
        val pendingInvitations: List<GroupInvitation> = emptyList(),
        val error: String? = null,
        val isCreating: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    val groups: StateFlow<List<GroupWithConversation>> = combine(
        groupRepo.observeGroups(),
        conversationRepo.observeConversations()
    ) { groupList, conversationList ->
        val conversationMap = conversationList.associateBy { it.conversationId }
        groupList.map { group ->
            GroupWithConversation(
                group = group,
                conversation = conversationMap[group.groupId]
            )
        }.sortedByDescending { it.lastMessageAt ?: it.group.createdAt }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val availablePeers: StateFlow<List<PeerNode>> = peerRepo.observeNearbyPeers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingInvitations: StateFlow<List<GroupInvitation>> = groupControlManager.pendingInvitations

    fun createNewGroup(name: String, memberIds: List<String>, temporary: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isCreating = true, error = null)
            try {
                createGroup(name, memberIds, temporary)
                _state.value = _state.value.copy(isCreating = false)
            } catch (e: IllegalStateException) {
                _state.value = _state.value.copy(
                    isCreating = false,
                    error = "No active identity. Please set up your identity first."
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isCreating = false,
                    error = e.message ?: "Failed to create group"
                )
            }
        }
    }

    fun acceptInvitation(invitation: GroupInvitation) {
        viewModelScope.launch {
            try {
                val identity = identityRepo.getActiveIdentity() ?: return@launch
                groupControlManager.acceptInvitation(invitation, identity.nodeId, identity.displayName)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "Failed to accept: ${e.message}")
            }
        }
    }

    fun rejectInvitation(invitation: GroupInvitation) {
        viewModelScope.launch {
            try {
                val identity = identityRepo.getActiveIdentity() ?: return@launch
                groupControlManager.rejectInvitation(invitation, identity.nodeId)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "Failed to reject: ${e.message}")
            }
        }
    }

    fun deleteGroup(groupId: String) {
        viewModelScope.launch {
            try {
                messageRepo.deleteMessagesByConversation(groupId)
                conversationRepo.deleteConversation(groupId)
                groupRepo.deleteGroup(groupId)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "Failed to delete group: ${e.message}")
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}

@Composable
fun GroupListScreen(
    onGroupClick: (groupId: String) -> Unit = {},
    viewModel: GroupListViewModel = hiltViewModel()
) {
    val groups by viewModel.groups.collectAsState()
    val invitations by viewModel.pendingInvitations.collectAsState()
    val uiState by viewModel.state.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<GroupInfo?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error, duration = SnackbarDuration.Short)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Filled.GroupAdd, "Create Group")
            }
        }
    ) { padding ->
        if (groups.isEmpty() && invitations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Group,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No groups yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Create a group to chat with multiple peers",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                // Pending invitations section
                if (invitations.isNotEmpty()) {
                    item {
                        Text(
                            "Pending Invitations",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(invitations, key = { it.groupId }) { invitation ->
                        InvitationItem(
                            invitation = invitation,
                            onAccept = { viewModel.acceptInvitation(invitation) },
                            onReject = { viewModel.rejectInvitation(invitation) }
                        )
                    }
                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }
                }

                // Groups section
                if (groups.isNotEmpty()) {
                    item {
                        Text(
                            "My Groups",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(groups, key = { it.group.groupId }) { groupWithConvo ->
                        GroupItem(
                            groupWithConvo = groupWithConvo,
                            onClick = { onGroupClick(groupWithConvo.group.groupId) },
                            onDelete = { deleteTarget = groupWithConvo.group }
                        )
                    }
                }
            }
        }

        if (showCreateDialog) {
            CreateGroupDialog(
                availablePeers = viewModel.availablePeers.collectAsState().value,
                maxMembers = viewModel.featureGate.maxGroupSize,
                isPremium = viewModel.featureGate.isPremium,
                onDismiss = { showCreateDialog = false },
                onCreate = { name, members, temp ->
                    viewModel.createNewGroup(name, members, temp)
                    showCreateDialog = false
                }
            )
        }

        deleteTarget?.let { group ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("Delete Group") },
                text = {
                    Text("Delete \"${group.name}\" and all its messages? This cannot be undone.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.deleteGroup(group.groupId)
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
}

@Composable
private fun InvitationItem(
    invitation: GroupInvitation,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    ListItem(
        headlineContent = { Text(invitation.groupName) },
        supportingContent = {
            Text(
                "From ${invitation.inviterName ?: invitation.inviterNodeId.take(8)} • ${invitation.memberCount} members",
                style = MaterialTheme.typography.bodySmall
            )
        },
        leadingContent = {
            Icon(
                Icons.Filled.Mail,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        },
        trailingContent = {
            Row {
                FilledTonalIconButton(
                    onClick = onReject,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Icon(Icons.Filled.Close, "Reject")
                }
                Spacer(modifier = Modifier.width(4.dp))
                FilledIconButton(onClick = onAccept) {
                    Icon(Icons.Filled.Check, "Accept")
                }
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupItem(
    groupWithConvo: GroupListViewModel.GroupWithConversation,
    onClick: () -> Unit,
    onDelete: () -> Unit = {}
) {
    val group = groupWithConvo.group
    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onDelete
        ),
        headlineContent = { Text(group.name) },
        supportingContent = {
            val preview = groupWithConvo.lastMessagePreview
            if (preview != null) {
                Text(
                    text = preview,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "${group.members.size} members" +
                            if (group.isTemporary) " • Temporary" else "",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        leadingContent = {
            Icon(
                if (group.groupType == ConversationType.TEMPORARY_GROUP) Icons.Filled.Timer
                else Icons.Filled.Group,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                groupWithConvo.lastMessageAt?.let { timestamp ->
                    Text(
                        text = TimeUtils.formatTimestamp(timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (groupWithConvo.unreadCount > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Badge {
                        Text(groupWithConvo.unreadCount.toString())
                    }
                }
            }
        }
    )
}

@Composable
private fun CreateGroupDialog(
    availablePeers: List<PeerNode>,
    maxMembers: Int,
    isPremium: Boolean,
    onDismiss: () -> Unit,
    onCreate: (name: String, memberIds: List<String>, temporary: Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var isTemporary by remember { mutableStateOf(false) }
    val selectedPeers = remember { mutableStateListOf<String>() }

    // Account for the creator being a member (maxMembers includes self)
    val maxSelectable = maxMembers - 1
    val atLimit = selectedPeers.size >= maxSelectable

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Group") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Group Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Temporary group", modifier = Modifier.weight(1f))
                    Switch(checked = isTemporary, onCheckedChange = { isTemporary = it })
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Select members:", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "${selectedPeers.size}/$maxSelectable",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (atLimit) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (atLimit && !isPremium) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Upgrade to Pro for groups up to 50 members",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                availablePeers.forEach { peer ->
                    val isSelected = peer.nodeId in selectedPeers
                    val canSelect = isSelected || !atLimit
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = canSelect) {
                                if (isSelected) selectedPeers.remove(peer.nodeId)
                                else if (!atLimit) selectedPeers.add(peer.nodeId)
                            }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { checked ->
                                if (checked && !atLimit) selectedPeers.add(peer.nodeId)
                                else selectedPeers.remove(peer.nodeId)
                            },
                            enabled = canSelect
                        )
                        Text(
                            peer.displayName ?: peer.nodeId.take(8),
                            color = if (canSelect) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, selectedPeers.toList(), isTemporary) },
                enabled = name.isNotBlank() && selectedPeers.isNotEmpty()
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
