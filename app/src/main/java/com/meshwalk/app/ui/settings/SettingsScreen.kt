package com.meshwalk.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
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
import com.meshwalk.app.domain.repository.PeerRepository
import com.meshwalk.app.domain.repository.SettingsRepository
import com.meshwalk.app.domain.usecase.TransportManagerPort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val identityRepo: IdentityRepository,
    private val groupRepo: GroupRepository,
    private val transportManager: TransportManagerPort,
    val featureGate: FeatureGate,
    private val billingManager: BillingManager,
    private val peerRepo: PeerRepository,
    private val backupManager: com.meshwalk.app.data.backup.BackupManager,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context
) : ViewModel() {

    data class UiState(
        val settings: AppSettings = AppSettings(),
        val identity: NodeIdentity? = null,
        val isScanning: Boolean = false,
        val subscriptionState: SubscriptionState = SubscriptionState(),
        val availablePlans: List<PlanDetails> = emptyList(),
        val peerCount: Int = 0,
        val connectedPeerCount: Int = 0,
        val backupMessage: String? = null,
        val backupInProgress: Boolean = false
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

        viewModelScope.launch {
            peerRepo.observeNearbyPeers().collect { peers ->
                _state.update { current ->
                    current.copy(
                        peerCount = peers.size,
                        connectedPeerCount = peers.count { it.isConnected }
                    )
                }
            }
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

    fun getManageSubscriptionIntent() = billingManager.getManageSubscriptionIntent()

    /** Write an encrypted backup to the user-picked [uri]. */
    fun exportBackup(uri: android.net.Uri, passphrase: String) {
        viewModelScope.launch {
            _state.update { it.copy(backupInProgress = true, backupMessage = null) }
            val result = runCatching {
                val blob = backupManager.export(passphrase)
                appContext.contentResolver.openOutputStream(uri)?.use { it.write(blob) }
                    ?: error("Couldn't open the selected file for writing")
            }
            _state.update {
                it.copy(
                    backupInProgress = false,
                    backupMessage = result.fold(
                        onSuccess = { "Backup saved" },
                        onFailure = { e -> "Backup failed: ${e.message}" }
                    )
                )
            }
        }
    }

    /** Restore an encrypted backup from the user-picked [uri]. */
    fun importBackup(uri: android.net.Uri, passphrase: String) {
        viewModelScope.launch {
            _state.update { it.copy(backupInProgress = true, backupMessage = null) }
            val result = runCatching {
                val blob = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Couldn't open the selected file for reading")
                backupManager.import(blob, passphrase)
            }
            _state.update {
                it.copy(
                    backupInProgress = false,
                    backupMessage = result.fold(
                        onSuccess = { "Backup restored — restart the app to load it" },
                        onFailure = { e -> "Restore failed: ${e.message}" }
                    )
                )
            }
        }
    }

    fun clearBackupMessage() {
        _state.update { it.copy(backupMessage = null) }
    }
}

@Composable
fun SettingsScreen(
    onDiagnostics: () -> Unit,
    onSubscription: () -> Unit = {},
    onExperimental: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var backupPassphraseDialog by remember { mutableStateOf<BackupDialogMode?>(null) }
    var pendingRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val context = LocalContext.current

    val createBackupLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) backupPassphraseDialog = BackupDialogMode.Export(uri) }

    val openBackupLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) backupPassphraseDialog = BackupDialogMode.Import(uri) }

    LaunchedEffect(state.backupMessage) {
        state.backupMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.clearBackupMessage()
        }
    }

    backupPassphraseDialog?.let { mode ->
        BackupPassphraseDialog(
            isExport = mode is BackupDialogMode.Export,
            inProgress = state.backupInProgress,
            onConfirm = { passphrase ->
                when (mode) {
                    is BackupDialogMode.Export -> viewModel.exportBackup(mode.uri, passphrase)
                    is BackupDialogMode.Import -> viewModel.importBackup(mode.uri, passphrase)
                }
                backupPassphraseDialog = null
            },
            onDismiss = { backupPassphraseDialog = null }
        )
    }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState())
    ) {
        // Subscription section
        SectionHeader("Subscription")
        SubscriptionInfoCard(
            subscriptionState = state.subscriptionState,
            featureGate = viewModel.featureGate,
            availablePlans = state.availablePlans,
            onManage = onSubscription,
            onManageOnGooglePlay = if (state.subscriptionState.isActive) {
                {
                    val intent = viewModel.getManageSubscriptionIntent()
                    context.startActivity(intent)
                }
            } else null
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

        // Data & Backup section
        SectionHeader("Data & Backup")
        ListItem(
            modifier = Modifier.clickable(enabled = !state.backupInProgress) {
                createBackupLauncher.launch("meshwalk-backup.mwbk")
            },
            headlineContent = { Text("Export encrypted backup") },
            supportingContent = { Text("Save your identity and chats to an encrypted file") },
            leadingContent = { Icon(Icons.Filled.Upload, null) }
        )
        ListItem(
            modifier = Modifier.clickable(enabled = !state.backupInProgress) {
                openBackupLauncher.launch(arrayOf("*/*"))
            },
            headlineContent = { Text("Restore from backup") },
            supportingContent = { Text("Import identity and chats from a backup file") },
            leadingContent = { Icon(Icons.Filled.Download, null) }
        )
        HorizontalDivider()

        // Network Dashboard section (free tier)
        SectionHeader("Network")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    NetworkStatItem(
                        label = "Connected",
                        value = "${state.connectedPeerCount}",
                        icon = Icons.Filled.Link,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    NetworkStatItem(
                        label = "Total Seen",
                        value = "${state.peerCount}",
                        icon = Icons.Filled.People,
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    NetworkStatItem(
                        label = "Scan Mode",
                        value = state.settings.scanMode.label,
                        icon = Icons.Filled.Radar,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
            }
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

        // Battery
        SectionHeader("Battery & Power")
        SwitchItem(
            title = "Run in background",
            subtitle = "Keep mesh service running so messages send and receive even when the app is closed",
            checked = state.settings.autoStartMesh,
            onToggle = {
                val enabling = !state.settings.autoStartMesh
                viewModel.updateSettings { it.copy(autoStartMesh = enabling) }
                if (enabling) {
                    requestIgnoreBatteryOptimizations(context)
                }
            }
        )
        SwitchItem(
            title = "Battery-aware mesh",
            subtitle = "Reduce scan frequency when battery is low",
            checked = state.settings.batteryAwareMeshEnabled,
            onToggle = { viewModel.updateSettings { it.copy(batteryAwareMeshEnabled = !it.batteryAwareMeshEnabled) } }
        )
        if (state.settings.batteryAwareMeshEnabled) {
            ListItem(
                headlineContent = { Text("Low battery threshold") },
                supportingContent = {
                    Column {
                        Text("Switch to Battery Saver below ${state.settings.lowBatteryThresholdPercent}%")
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = state.settings.lowBatteryThresholdPercent.toFloat(),
                            onValueChange = { newValue ->
                                viewModel.updateSettings {
                                    it.copy(lowBatteryThresholdPercent = newValue.roundToInt())
                                }
                            },
                            valueRange = 5f..50f,
                            steps = 8
                        )
                    }
                }
            )
        }
        HorizontalDivider()

        // Messages
        SectionHeader("Messages")
        SwitchItem(
            title = "Auto-retry failed messages",
            subtitle = "Automatically resend failed messages on reconnect",
            checked = state.settings.autoRetryFailedMessages,
            onToggle = { viewModel.updateSettings { it.copy(autoRetryFailedMessages = !it.autoRetryFailedMessages) } }
        )
        SwitchItem(
            title = "Read receipts",
            subtitle = "Send and receive read receipts for messages",
            checked = state.settings.readReceiptsEnabled,
            onToggle = { viewModel.updateSettings { it.copy(readReceiptsEnabled = !it.readReceiptsEnabled) } }
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

        // Theme toggle
        ListItem(
            headlineContent = { Text("Theme") },
            supportingContent = { Text("Choose light, dark, or system theme") },
            trailingContent = {
                SingleChoiceSegmentedButtonRow {
                    DarkModeSetting.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = state.settings.darkMode == mode,
                            onClick = {
                                viewModel.updateSettings { it.copy(darkMode = mode) }
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = DarkModeSetting.entries.size
                            ),
                            icon = {}
                        ) {
                            Text(
                                when (mode) {
                                    DarkModeSetting.LIGHT -> "Light"
                                    DarkModeSetting.DARK -> "Dark"
                                    DarkModeSetting.SYSTEM -> "System"
                                },
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        )
        SwitchItem(
            title = "Haptic feedback",
            subtitle = "Vibrate on key interactions",
            checked = state.settings.hapticFeedbackEnabled,
            onToggle = { viewModel.updateSettings { it.copy(hapticFeedbackEnabled = !it.hapticFeedbackEnabled) } }
        )
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
        SwitchItem(
            title = "Signal strength indicators",
            subtitle = "Show signal strength per peer in lists",
            checked = state.settings.showSignalStrength,
            onToggle = { viewModel.updateSettings { it.copy(showSignalStrength = !it.showSignalStrength) } }
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

        ListItem(
            modifier = Modifier.clickable(onClick = onExperimental),
            headlineContent = { Text("Experimental Features") },
            supportingContent = { Text("Drop zones, SOS, bulletin board, and more") },
            leadingContent = { Icon(Icons.Filled.Science, null) },
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
private fun NetworkStatItem(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = tint
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
    onManage: () -> Unit,
    onManageOnGooglePlay: (() -> Unit)? = null
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
            // Header: plan icon + name + status badge
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
                        if (subscriptionState.isActive) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = when {
                                    subscriptionState.isGracePeriod -> MaterialTheme.colorScheme.error
                                    subscriptionState.isFreeTrial -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.primary
                                }
                            ) {
                                Text(
                                    when {
                                        subscriptionState.isGracePeriod -> "GRACE PERIOD"
                                        subscriptionState.isFreeTrial -> "TRIAL"
                                        else -> "ACTIVE"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when {
                                        subscriptionState.isGracePeriod -> MaterialTheme.colorScheme.onError
                                        subscriptionState.isFreeTrial -> MaterialTheme.colorScheme.onTertiary
                                        else -> MaterialTheme.colorScheme.onPrimary
                                    },
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        when {
                            subscriptionState.isActive -> subscriptionState.planName
                            else -> "Groups up to ${featureGate.maxGroupSize}, ${featureGate.maxQueueSize}-msg queue"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Renewal / expiration info for active subscriptions
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

            // Compact plan summary for free users
            if (!subscriptionState.isActive && availablePlans.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    "Available plans",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                availablePlans.forEach { plan ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            when (plan.billingPeriod) {
                                BillingPeriod.MONTHLY -> Icons.Filled.CalendarMonth
                                BillingPeriod.ANNUAL -> Icons.Filled.CalendarToday
                                BillingPeriod.LIFETIME -> Icons.Filled.AllInclusive
                            },
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            plan.billingPeriod.label.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (plan.billingPeriod == BillingPeriod.ANNUAL) {
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    "SAVE 37%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
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
                if (onManageOnGooglePlay != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = onManageOnGooglePlay,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.OpenInNew, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Manage on Google Play")
                    }
                }
            } else {
                Button(
                    onClick = onManage,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.WorkspacePremium, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Upgrade to Pro")
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

private fun requestIgnoreBatteryOptimizations(context: android.content.Context) {
    val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager
    val packageName = context.packageName
    if (powerManager == null || powerManager.isIgnoringBatteryOptimizations(packageName)) return
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:$packageName")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
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

/** What a pending backup passphrase prompt will do, plus the file it targets. */
private sealed interface BackupDialogMode {
    val uri: android.net.Uri
    data class Export(override val uri: android.net.Uri) : BackupDialogMode
    data class Import(override val uri: android.net.Uri) : BackupDialogMode
}

@Composable
private fun BackupPassphraseDialog(
    isExport: Boolean,
    inProgress: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val tooShort = passphrase.length < 6
    val mismatch = isExport && confirm != passphrase
    AlertDialog(
        onDismissRequest = { if (!inProgress) onDismiss() },
        title = { Text(if (isExport) "Encrypt backup" else "Decrypt backup") },
        text = {
            Column {
                Text(
                    if (isExport)
                        "Choose a passphrase to encrypt your backup. You'll need it to restore — it can't be recovered."
                    else
                        "Enter the passphrase this backup was encrypted with.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (isExport) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = { Text("Confirm passphrase") },
                        singleLine = true,
                        isError = mismatch,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !inProgress && !tooShort && !mismatch,
                onClick = { onConfirm(passphrase) }
            ) { Text(if (isExport) "Export" else "Restore") }
        },
        dismissButton = {
            TextButton(enabled = !inProgress, onClick = onDismiss) { Text("Cancel") }
        }
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
