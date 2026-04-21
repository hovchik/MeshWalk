package com.meshwalk.app.ui.experimental

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshwalk.app.domain.model.AppSettings
import com.meshwalk.app.domain.model.ExperimentalFeatures
import com.meshwalk.app.domain.repository.SettingsRepository
import com.meshwalk.app.experimental.DuressManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ExperimentalSettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val duressManager: DuressManager
) : ViewModel() {

    private val _state = MutableStateFlow(AppSettings())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepo.observeSettings().collect { _state.value = it }
        }
    }

    fun update(transform: (ExperimentalFeatures) -> ExperimentalFeatures) {
        viewModelScope.launch {
            val current = settingsRepo.getSettings()
            settingsRepo.updateSettings(
                current.copy(experimental = transform(current.experimental))
            )
        }
    }

    fun setDuressPin(pin: String?) {
        viewModelScope.launch { duressManager.setDuressPin(pin) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExperimentalSettingsScreen(
    onBack: () -> Unit,
    onBulletin: () -> Unit,
    onDropZones: () -> Unit,
    onRelayStats: () -> Unit,
    onSos: () -> Unit,
    viewModel: ExperimentalSettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.state.collectAsState()
    val exp = settings.experimental
    var showDuressDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Experimental Features") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            WarningBanner()

            ToggleRow(
                title = "Drop Zones",
                subtitle = "Geo/beacon-unlocked encrypted messages",
                checked = exp.dropZonesEnabled,
                onToggle = { viewModel.update { it.copy(dropZonesEnabled = it.dropZonesEnabled.not()) } },
                onOpen = if (exp.dropZonesEnabled) onDropZones else null
            )
            ToggleRow(
                title = "Relay Incentives",
                subtitle = "Stats on packets you've relayed",
                checked = exp.relayIncentivesEnabled,
                onToggle = { viewModel.update { it.copy(relayIncentivesEnabled = it.relayIncentivesEnabled.not()) } },
                onOpen = if (exp.relayIncentivesEnabled) onRelayStats else null
            )
            ToggleRow(
                title = "SOS Broadcast",
                subtitle = "One-tap emergency broadcast on the mesh",
                checked = exp.sosBroadcastEnabled,
                onToggle = { viewModel.update { it.copy(sosBroadcastEnabled = it.sosBroadcastEnabled.not()) } },
                onOpen = if (exp.sosBroadcastEnabled) onSos else null
            )
            ToggleRow(
                title = "Anonymous Bulletin Board",
                subtitle = "Proof-of-work posts, no identity",
                checked = exp.bulletinBoardEnabled,
                onToggle = { viewModel.update { it.copy(bulletinBoardEnabled = it.bulletinBoardEnabled.not()) } },
                onOpen = if (exp.bulletinBoardEnabled) onBulletin else null
            )
            ToggleRow(
                title = "Proximity Trust Graph",
                subtitle = "Rank contacts by direct-range encounters",
                checked = exp.trustGraphEnabled,
                onToggle = { viewModel.update { it.copy(trustGraphEnabled = it.trustGraphEnabled.not()) } }
            )
            ToggleRow(
                title = "Double Ratchet Mode",
                subtitle = "Stronger forward secrecy (may break S&F)",
                checked = exp.partialDoubleRatchetEnabled,
                onToggle = { viewModel.update { it.copy(partialDoubleRatchetEnabled = it.partialDoubleRatchetEnabled.not()) } }
            )
            ToggleRow(
                title = "Phrasebook Compression",
                subtitle = "1-byte opcodes for common phrases",
                checked = exp.phrasebookEnabled,
                onToggle = { viewModel.update { it.copy(phrasebookEnabled = it.phrasebookEnabled.not()) } }
            )
            ToggleRow(
                title = "Opportunistic Gossip Sync",
                subtitle = "Bloom-filter sync with passing peers",
                checked = exp.gossipSyncEnabled,
                onToggle = { viewModel.update { it.copy(gossipSyncEnabled = it.gossipSyncEnabled.not()) } }
            )
            ToggleRow(
                title = "Reputation Tokens",
                subtitle = "Signed relay attestations (trust web)",
                checked = exp.reputationTokensEnabled,
                onToggle = { viewModel.update { it.copy(reputationTokensEnabled = it.reputationTokensEnabled.not()) } }
            )
            ToggleRow(
                title = "Signal-Strength Heatmap",
                subtitle = "Sample peer RSSI over time",
                checked = exp.signalHeatmapEnabled,
                onToggle = { viewModel.update { it.copy(signalHeatmapEnabled = it.signalHeatmapEnabled.not()) } }
            )
            ToggleRow(
                title = "Time-to-Reach Estimator",
                subtitle = "Per-peer delivery latency EWMA",
                checked = exp.timeToReachEnabled,
                onToggle = { viewModel.update { it.copy(timeToReachEnabled = it.timeToReachEnabled.not()) } }
            )
            ToggleRow(
                title = "Voice Note Transcripts",
                subtitle = "Send text transcripts, not audio",
                checked = exp.voiceTranscriptsEnabled,
                onToggle = { viewModel.update { it.copy(voiceTranscriptsEnabled = it.voiceTranscriptsEnabled.not()) } }
            )
            ToggleRow(
                title = "Duress PIN",
                subtitle = if (exp.duressPinHash != null) "PIN set — wipes on match"
                           else "Not configured",
                checked = exp.duressPinEnabled,
                onToggle = {
                    if (exp.duressPinEnabled) {
                        viewModel.setDuressPin(null)
                    } else {
                        showDuressDialog = true
                    }
                }
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showDuressDialog) {
        DuressPinDialog(
            onDismiss = { showDuressDialog = false },
            onSave = { pin ->
                viewModel.setDuressPin(pin)
                showDuressDialog = false
            }
        )
    }
}

@Composable
private fun WarningBanner() {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            Icon(
                Icons.Filled.Science,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                "These features are experimental. They may change or behave unpredictably.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
    onOpen: (() -> Unit)? = null
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onOpen != null) {
                    TextButton(onClick = onOpen) { Text("Open") }
                }
                Switch(checked = checked, onCheckedChange = { onToggle() })
            }
        }
    )
    HorizontalDivider()
}

@Composable
private fun DuressPinDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val canSave = pin.length >= 4 && pin == confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Duress PIN") },
        text = {
            Column {
                Text(
                    "Entering this PIN on the lock screen will silently wipe all messages and conversations.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit) },
                    label = { Text("PIN (4+ digits)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it.filter(Char::isDigit) },
                    label = { Text("Confirm PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(enabled = canSave, onClick = { onSave(pin) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
