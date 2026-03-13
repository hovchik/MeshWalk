package com.meshwalk.app.ui.groups

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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshwalk.app.domain.model.ConversationType
import com.meshwalk.app.domain.model.GroupInfo
import com.meshwalk.app.domain.model.PeerNode
import com.meshwalk.app.domain.repository.GroupRepository
import com.meshwalk.app.domain.repository.PeerRepository
import com.meshwalk.app.domain.usecase.CreateGroupUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GroupListViewModel @Inject constructor(
    private val groupRepo: GroupRepository,
    private val peerRepo: PeerRepository,
    private val createGroup: CreateGroupUseCase
) : ViewModel() {

    data class UiState(
        val groups: List<GroupInfo> = emptyList(),
        val availablePeers: List<PeerNode> = emptyList(),
        val error: String? = null,
        val isCreating: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    // Keep these for backward compat with UI collecting directly
    val groups: StateFlow<List<GroupInfo>> = groupRepo.observeGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val availablePeers: StateFlow<List<PeerNode>> = peerRepo.observeNearbyPeers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupListScreen(
    onGroupClick: (groupId: String) -> Unit = {},
    viewModel: GroupListViewModel = hiltViewModel()
) {
    val groups by viewModel.groups.collectAsState()
    val uiState by viewModel.state.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Show errors
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error, duration = SnackbarDuration.Short)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(title = { Text("Groups") })
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Filled.GroupAdd, "Create Group")
            }
        }
    ) { padding ->
        if (groups.isEmpty()) {
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
                items(groups, key = { it.groupId }) { group ->
                    GroupItem(group = group, onClick = { onGroupClick(group.groupId) })
                }
            }
        }

        if (showCreateDialog) {
            CreateGroupDialog(
                availablePeers = viewModel.availablePeers.collectAsState().value,
                onDismiss = { showCreateDialog = false },
                onCreate = { name, members, temp ->
                    viewModel.createNewGroup(name, members, temp)
                    showCreateDialog = false
                }
            )
        }
    }
}

@Composable
private fun GroupItem(group: GroupInfo, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(group.name) },
        supportingContent = {
            Text(
                "${group.members.size} members" +
                        if (group.isTemporary) " • Temporary" else "",
                style = MaterialTheme.typography.bodySmall
            )
        },
        leadingContent = {
            Icon(
                if (group.groupType == ConversationType.TEMPORARY_GROUP) Icons.Filled.Timer
                else Icons.Filled.Group,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    )
}

@Composable
private fun CreateGroupDialog(
    availablePeers: List<PeerNode>,
    onDismiss: () -> Unit,
    onCreate: (name: String, memberIds: List<String>, temporary: Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var isTemporary by remember { mutableStateOf(false) }
    val selectedPeers = remember { mutableStateListOf<String>() }

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
                Text("Select members:", style = MaterialTheme.typography.labelMedium)
                availablePeers.forEach { peer ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (peer.nodeId in selectedPeers) selectedPeers.remove(peer.nodeId)
                                else selectedPeers.add(peer.nodeId)
                            }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = peer.nodeId in selectedPeers,
                            onCheckedChange = {
                                if (it) selectedPeers.add(peer.nodeId)
                                else selectedPeers.remove(peer.nodeId)
                            }
                        )
                        Text(peer.displayName ?: peer.nodeId.take(8))
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
