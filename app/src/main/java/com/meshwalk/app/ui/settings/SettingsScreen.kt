package com.meshwalk.app.ui.settings

import androidx.biometric.BiometricManager
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.shape.RoundedCornerShape
import com.meshwalk.app.billing.BillingManager
import com.meshwalk.app.billing.BillingPeriod
import com.meshwalk.app.billing.FeatureGate
import com.meshwalk.app.billing.PlanDetails
import com.meshwalk.app.billing.SubscriptionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.meshwalk.app.domain.model.*
import com.meshwalk.app.domain.repository.GroupRepository
import com.meshwalk.app.domain.repository.IdentityRepository
import com.meshwalk.app.domain.repository.SettingsRepository
import com.meshwalk.app.domain.usecase.TransportManagerPort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val identityRepo: IdentityRepository,
    private val groupRepo: GroupRepository,
    private val transportManager: TransportManagerPort,
    val featureGate: FeatureGate,
    private val billingManager: BillingManager
) : ViewModel() {

    data class UiState(
        val settings: AppSettings = AppSettings(),
        val identity: NodeIdentity? = null,
        val isScanning: Boolean = false,
        val subscriptionState: SubscriptionState = SubscriptionState(),
        val availablePlans: List<PlanDetails> = emptyList()
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                settingsRepo.observeSettings(),
                identityRepo.observeActiveIdentity(),
                transportManager.isScanning,
                featureGate.subscriptionState,
                billingManager.availablePlans
            ) { settings, identity, scanning, subscription, plans ->
                UiState(
                    settings = settings,
                    identity = identity,
                    isScanning = scanning,
                    subscriptionState = subscription,
                    availablePlans = plans
                )
            }.collect { _state.value = it }
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            val updated = transform(_state.value.settings)
            settingsRepo.updateSettings(updated)
        }
    }

    fun startScanning() {
        viewModelScope.launch {
            transportManager.startDiscovery()
            transportManager.startAdvertising()
        }
    }

    fun stopScanning() {
        viewModelScope.launch {
            transportManager.stopDiscovery()
            transportManager.stopAdvertising()
        }
    }

    fun updateProfile(name: String?, type: IdentityType) {
        viewModelScope.launch {
            val identity = _state.value.identity ?: return@launch
            identityRepo.updateProfile(identity.nodeId, name, type)
            // Propagate the new display name to all group member lists
            groupRepo.updateMemberDisplayName(identity.nodeId, name)
        }
    }
}

@Composable
fun SettingsScreen(
    onDiagnostics: () -> Unit,
    onSubscription: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var showEditProfileDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState())
    ) {
        // Subscription section
        SectionHeader("Subscription")
        SubscriptionInfoCard(
            subscriptionState = state.subscriptionState,
            featureGate = viewModel.featureGate,
            availablePlans = state.availablePlans,
            onManage = onSubscription
        )
        HorizontalDivider()

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
        ListItem(
            headlineContent = { Text("Network scanning") },
            supportingContent = {
                Text(
                    if (state.isScanning) "Scanning for nearby peers..."
                    else "Scanning is stopped"
                )
            },
            leadingContent = {
                Icon(
                    if (state.isScanning) Icons.Filled.Radar else Icons.Filled.WifiFind,
                    contentDescription = null,
                    tint = if (state.isScanning) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    Switch(
                        checked = state.isScanning,
                        onCheckedChange = {
                            if (state.isScanning) viewModel.stopScanning()
                            else viewModel.startScanning()
                        }
                    )
                }
            }
        )
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

        // Security
        SectionHeader("Security")
        val context = LocalContext.current
        val biometricAvailable = remember {
            val biometricManager = BiometricManager.from(context)
            biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                    BiometricManager.BIOMETRIC_SUCCESS
        }
        SwitchItem(
            title = "Fingerprint lock",
            subtitle = if (biometricAvailable) {
                "Require fingerprint to unlock the app"
            } else {
                "No fingerprint enrolled on this device"
            },
            checked = state.settings.fingerprintLockEnabled,
            enabled = biometricAvailable,
            onToggle = {
                viewModel.updateSettings { it.copy(fingerprintLockEnabled = !it.fingerprintLockEnabled) }
            }
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
        ListItem(
            headlineContent = { Text("Network graph refresh") },
            supportingContent = {
                Text("Refresh every ${state.settings.networkGraphRefreshSeconds}s")
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(
                        onClick = {
                            val current = state.settings.networkGraphRefreshSeconds
                            if (current > 1) {
                                viewModel.updateSettings { it.copy(networkGraphRefreshSeconds = current - 1) }
                            }
                        },
                        enabled = state.settings.networkGraphRefreshSeconds > 1
                    ) {
                        Icon(Icons.Filled.Remove, "Decrease")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${state.settings.networkGraphRefreshSeconds}s",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalIconButton(
                        onClick = {
                            val current = state.settings.networkGraphRefreshSeconds
                            if (current < 30) {
                                viewModel.updateSettings { it.copy(networkGraphRefreshSeconds = current + 1) }
                            }
                        },
                        enabled = state.settings.networkGraphRefreshSeconds < 30
                    ) {
                        Icon(Icons.Filled.Add, "Increase")
                    }
                }
            }
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
        val canAccessDiagnostics = viewModel.featureGate.canAccessDiagnostics
        ListItem(
            modifier = Modifier.clickable(onClick = {
                if (canAccessDiagnostics) onDiagnostics() else onSubscription()
            }),
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Mesh Diagnostics")
                    if (!canAccessDiagnostics) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        ) {
                            Text(
                                "PRO",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            },
            supportingContent = {
                Text(
                    if (canAccessDiagnostics) "View routing tables, queues, and transport state"
                    else "Upgrade to Pro for full diagnostics access"
                )
            },
            leadingContent = { Icon(Icons.Filled.BugReport, null) },
            trailingContent = {
                Icon(
                    if (canAccessDiagnostics) Icons.Filled.ChevronRight else Icons.Filled.Lock,
                    null
                )
            }
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
private fun SubscriptionInfoCard(
    subscriptionState: SubscriptionState,
    featureGate: FeatureGate,
    availablePlans: List<PlanDetails>,
    onManage: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (subscriptionState.isActive)
                MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: plan name + status badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    when {
                        subscriptionState.isFreeTrial -> Icons.Filled.Timer
                        subscriptionState.isActive -> Icons.Filled.Verified
                        else -> Icons.Filled.WorkspacePremium
                    },
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = when {
                        subscriptionState.isFreeTrial -> MaterialTheme.colorScheme.tertiary
                        subscriptionState.isActive -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outline
                    }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                subscriptionState.isFreeTrial -> "Free Trial"
                                subscriptionState.isActive -> "MeshWalk Pro"
                                else -> "Free Plan"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        if (subscriptionState.isActive) {
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = if (subscriptionState.isFreeTrial)
                                    MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    if (subscriptionState.isFreeTrial) "TRIAL" else "ACTIVE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (subscriptionState.isFreeTrial)
                                        MaterialTheme.colorScheme.onTertiary
                                    else MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    if (subscriptionState.isActive) {
                        Text(
                            subscriptionState.planName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Renewal / expiration info
            if (subscriptionState.isActive && subscriptionState.expiresAt != null) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(12.dp))

                val daysLeft = ((subscriptionState.expiresAt - System.currentTimeMillis()) /
                        (24 * 60 * 60 * 1000)).toInt().coerceAtLeast(0)
                val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
                val expiryDate = remember(subscriptionState.expiresAt) {
                    dateFormat.format(Date(subscriptionState.expiresAt))
                }

                SubscriptionDetailRow(
                    icon = Icons.Filled.CalendarMonth,
                    label = if (subscriptionState.isFreeTrial) "Trial ends" else "Renews on",
                    value = expiryDate
                )
                SubscriptionDetailRow(
                    icon = Icons.Filled.Schedule,
                    label = "Days remaining",
                    value = "$daysLeft days"
                )
            } else if (subscriptionState.isActive && subscriptionState.expiresAt == null) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(12.dp))
                SubscriptionDetailRow(
                    icon = Icons.Filled.AllInclusive,
                    label = "Duration",
                    value = "Lifetime access"
                )
            }

            // Available packages
            if (availablePlans.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    if (subscriptionState.isActive) "All plans" else "Available packages",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                availablePlans.forEach { plan ->
                    val isCurrentPlan = subscriptionState.isActive &&
                            subscriptionState.planName.contains(plan.name, ignoreCase = true)
                    PlanSummaryRow(
                        plan = plan,
                        isCurrent = isCurrentPlan
                    )
                }
            }

            // Feature limits
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                "Your limits",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            SubscriptionDetailRow(
                icon = Icons.Filled.Group,
                label = "Max group size",
                value = "${featureGate.maxGroupSize} members"
            )
            SubscriptionDetailRow(
                icon = Icons.Filled.Inbox,
                label = "Message queue",
                value = "${featureGate.maxQueueSize} messages"
            )
            SubscriptionDetailRow(
                icon = Icons.Filled.History,
                label = "Message retention",
                value = "${featureGate.messageRetentionDays} days"
            )
            SubscriptionDetailRow(
                icon = Icons.Filled.Speed,
                label = "Priority relay",
                value = if (featureGate.hasPriorityRelay) "Enabled" else "Standard"
            )
            SubscriptionDetailRow(
                icon = Icons.Filled.Badge,
                label = "Multiple identities",
                value = if (featureGate.canCreateMultipleIdentities) "Enabled" else "Disabled"
            )
            SubscriptionDetailRow(
                icon = Icons.Filled.BugReport,
                label = "Diagnostics",
                value = if (featureGate.canAccessDiagnostics) "Full access" else "Basic"
            )
            SubscriptionDetailRow(
                icon = Icons.Filled.FileDownload,
                label = "Chat export",
                value = if (featureGate.canExportChat) "Available" else "Locked"
            )

            // Action button
            Spacer(modifier = Modifier.height(16.dp))
            if (subscriptionState.isActive) {
                OutlinedButton(
                    onClick = onManage,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Settings, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Manage Subscription")
                }
            } else {
                Button(
                    onClick = onManage,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.WorkspacePremium, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start 7-Day Free Trial")
                }
            }
        }
    }
}

@Composable
private fun SubscriptionDetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun PlanSummaryRow(
    plan: PlanDetails,
    isCurrent: Boolean
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (isCurrent)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        tonalElevation = if (isCurrent) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                when (plan.billingPeriod) {
                    BillingPeriod.MONTHLY -> Icons.Filled.CalendarMonth
                    BillingPeriod.ANNUAL -> Icons.Filled.CalendarToday
                    BillingPeriod.LIFETIME -> Icons.Filled.AllInclusive
                },
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        plan.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (isCurrent) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                "CURRENT",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    if (plan.hasFreeTrial && !isCurrent) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        ) {
                            Text(
                                "${plan.freeTrialDays}-DAY TRIAL",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiary,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    if (plan.billingPeriod == BillingPeriod.ANNUAL && !isCurrent) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        ) {
                            Text(
                                "BEST VALUE",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Text(
                    plan.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "${plan.formattedPrice}/${plan.billingPeriod.label}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
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
    enabled: Boolean = true,
    onToggle: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                title,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        },
        supportingContent = {
            Text(
                subtitle,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            )
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
        }
    )
}
