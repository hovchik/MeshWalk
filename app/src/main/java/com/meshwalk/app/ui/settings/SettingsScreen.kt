package com.meshwalk.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
}

@Composable
fun SettingsScreen(
    onDiagnostics: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
    ) {
            // Identity section
            state.identity?.let { identity ->
                SectionHeader("Identity")
                ListItem(
                    headlineContent = { Text(identity.displayName ?: "Anonymous") },
                    supportingContent = {
                        Text("${identity.identityType.name} • ${identity.nodeId.take(8)}...")
                    },
                    leadingContent = {
                        Icon(
                            when (identity.identityType) {
                                IdentityType.NAMED -> Icons.Filled.Person
                                IdentityType.ANONYMOUS -> Icons.Filled.VisibilityOff
                                IdentityType.TEMPORARY -> Icons.Filled.Timer
                            },
                            contentDescription = null
                        )
                    }
                )
                ListItem(
                    headlineContent = { Text("Fingerprint") },
                    supportingContent = { Text(identity.fingerprint) },
                    leadingContent = { Icon(Icons.Filled.Fingerprint, null) }
                )
                HorizontalDivider()
            }

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
