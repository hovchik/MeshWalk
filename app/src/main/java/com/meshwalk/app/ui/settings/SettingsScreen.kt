package com.meshwalk.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshwalk.app.domain.model.*
import com.meshwalk.app.domain.repository.IdentityRepository
import com.meshwalk.app.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val identityRepo: IdentityRepository
) : ViewModel() {

    data class UiState(
        val settings: AppSettings = AppSettings(),
        val identity: NodeIdentity? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                settingsRepo.observeSettings(),
                identityRepo.observeActiveIdentity()
            ) { settings, identity ->
                UiState(settings = settings, identity = identity)
            }.collect { _state.value = it }
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            val updated = transform(_state.value.settings)
            settingsRepo.updateSettings(updated)
        }
    }

    fun updateProfile(name: String?, type: IdentityType) {
        viewModelScope.launch {
            val identity = _state.value.identity ?: return@launch
            identityRepo.updateProfile(identity.nodeId, name, type)
        }
    }
}

@Composable
fun SettingsScreen(
    onDiagnostics: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var showEditProfileDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState())
    ) {
        // Profile section
        SectionHeader("Profile")
        state.identity?.let { identity ->
            ProfileCard(
                identity = identity,
                onEdit = { showEditProfileDialog = true }
            )
        } ?: run {
            ListItem(
                headlineContent = { Text("No identity configured") },
                supportingContent = { Text("Set up your profile to get started") },
                leadingContent = {
                    Icon(Icons.Filled.PersonOff, null, tint = MaterialTheme.colorScheme.outline)
                }
            )
        }
        HorizontalDivider()

        // Scanning
        SectionHeader("Discovery & Scanning")
        ScanMode.entries.forEach { mode ->
            ListItem(
                modifier = Modifier.clickable {
                    viewModel.updateSettings { it.copy(scanMode = mode) }
                },
                headlineContent = { Text(mode.label) },
                supportingContent = { Text(mode.description) },
                leadingContent = {
                    RadioButton(
                        selected = state.settings.scanMode == mode,
                        onClick = {
                            viewModel.updateSettings { it.copy(scanMode = mode) }
                        }
                    )
                }
            )
        }
        HorizontalDivider()

        // Relay
        SectionHeader("Relay & Routing")
        SwitchItem(
            title = "Act as relay",
            subtitle = "Forward messages for other nodes",
            checked = state.settings.relayEnabled,
            onToggle = { viewModel.updateSettings { it.copy(relayEnabled = !it.relayEnabled) } }
        )
        SwitchItem(
            title = "Store and forward",
            subtitle = "Hold messages for offline recipients",
            checked = state.settings.storeAndForwardEnabled,
            onToggle = { viewModel.updateSettings { it.copy(storeAndForwardEnabled = !it.storeAndForwardEnabled) } }
        )
        HorizontalDivider()

        // Appearance
        SectionHeader("Appearance")
        SwitchItem(
            title = "Show hop count",
            subtitle = "Display message route info in chats",
            checked = state.settings.showHopCount,
            onToggle = { viewModel.updateSettings { it.copy(showHopCount = !it.showHopCount) } }
        )
        SwitchItem(
            title = "Encryption badge",
            subtitle = "Show lock icon on encrypted chats",
            checked = state.settings.showEncryptionBadge,
            onToggle = { viewModel.updateSettings { it.copy(showEncryptionBadge = !it.showEncryptionBadge) } }
        )
        HorizontalDivider()

        // Groups
        SectionHeader("Groups")
        ListItem(
            headlineContent = { Text("Message history on rejoin") },
            supportingContent = {
                Text("Load last ${state.settings.groupMessageHistoryCount} messages when opening a group")
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(
                        onClick = {
                            val current = state.settings.groupMessageHistoryCount
                            if (current > 10) {
                                viewModel.updateSettings { it.copy(groupMessageHistoryCount = current - 10) }
                            }
                        },
                        enabled = state.settings.groupMessageHistoryCount > 10
                    ) {
                        Icon(Icons.Filled.Remove, "Decrease")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${state.settings.groupMessageHistoryCount}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalIconButton(
                        onClick = {
                            val current = state.settings.groupMessageHistoryCount
                            if (current < 500) {
                                viewModel.updateSettings { it.copy(groupMessageHistoryCount = current + 10) }
                            }
                        },
                        enabled = state.settings.groupMessageHistoryCount < 500
                    ) {
                        Icon(Icons.Filled.Add, "Increase")
                    }
                }
            }
        )
        HorizontalDivider()

        // Debug
        SectionHeader("Debug")
        ListItem(
            modifier = Modifier.clickable(onClick = onDiagnostics),
            headlineContent = { Text("Mesh Diagnostics") },
            supportingContent = { Text("View routing tables, queues, and transport state") },
            leadingContent = { Icon(Icons.Filled.BugReport, null) },
            trailingContent = { Icon(Icons.Filled.ChevronRight, null) }
        )

        Spacer(modifier = Modifier.height(32.dp))
    }

    if (showEditProfileDialog) {
        state.identity?.let { identity ->
            EditProfileDialog(
                currentName = identity.displayName,
                currentType = identity.identityType,
                onDismiss = { showEditProfileDialog = false },
                onSave = { name, type ->
                    viewModel.updateProfile(name, type)
                    showEditProfileDialog = false
                }
            )
        }
    }
}

@Composable
private fun ProfileCard(
    identity: NodeIdentity,
    onEdit: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Avatar
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    val icon = when (identity.identityType) {
                        IdentityType.ANONYMOUS -> Icons.Filled.VisibilityOff
                        IdentityType.TEMPORARY -> Icons.Filled.Timer
                        IdentityType.NAMED -> Icons.Filled.Person
                    }
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = identity.displayName ?: "Anonymous",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = when (identity.identityType) {
                            IdentityType.NAMED -> MaterialTheme.colorScheme.primary
                            IdentityType.ANONYMOUS -> MaterialTheme.colorScheme.tertiary
                            IdentityType.TEMPORARY -> MaterialTheme.colorScheme.error
                        }.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = when (identity.identityType) {
                                IdentityType.NAMED -> "Named"
                                IdentityType.ANONYMOUS -> "Anonymous"
                                IdentityType.TEMPORARY -> "Temporary"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when (identity.identityType) {
                                IdentityType.NAMED -> MaterialTheme.colorScheme.primary
                                IdentityType.ANONYMOUS -> MaterialTheme.colorScheme.tertiary
                                IdentityType.TEMPORARY -> MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                FilledTonalIconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, "Edit profile")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(10.dp))

            // Fingerprint
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Fingerprint,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = identity.fingerprint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Node ID
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Tag,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = identity.nodeId.take(16) + "...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EditProfileDialog(
    currentName: String?,
    currentType: IdentityType,
    onDismiss: () -> Unit,
    onSave: (name: String?, type: IdentityType) -> Unit
) {
    var name by remember { mutableStateOf(currentName ?: "") }
    var selectedType by remember { mutableStateOf(currentType) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Person, contentDescription = null) },
        title = { Text("Edit Profile") },
        text = {
            Column {
                // Display name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display Name") },
                    placeholder = { Text("Enter your name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedType != IdentityType.ANONYMOUS
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "Identity Type",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Identity type selection
                IdentityTypeOption(
                    title = "Named",
                    description = "Others see your display name",
                    icon = Icons.Filled.Person,
                    selected = selectedType == IdentityType.NAMED,
                    onClick = { selectedType = IdentityType.NAMED }
                )
                IdentityTypeOption(
                    title = "Anonymous",
                    description = "Others see you as anonymous node",
                    icon = Icons.Filled.VisibilityOff,
                    selected = selectedType == IdentityType.ANONYMOUS,
                    onClick = {
                        selectedType = IdentityType.ANONYMOUS
                        name = ""
                    }
                )
                IdentityTypeOption(
                    title = "Temporary",
                    description = "Identity expires after 24 hours",
                    icon = Icons.Filled.Timer,
                    selected = selectedType == IdentityType.TEMPORARY,
                    onClick = { selectedType = IdentityType.TEMPORARY }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val finalName = if (selectedType == IdentityType.ANONYMOUS) null
                    else name.ifBlank { null }
                    onSave(finalName, selectedType)
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun IdentityTypeOption(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyMedium) },
        supportingContent = {
            Text(description, style = MaterialTheme.typography.bodySmall)
        },
        leadingContent = {
            Icon(icon, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = {
            RadioButton(selected = selected, onClick = onClick)
        }
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun SwitchItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = { onToggle() })
        }
    )
}
