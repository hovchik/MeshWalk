package com.meshwalk.app.ui.identity

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshwalk.app.domain.model.IdentityType
import com.meshwalk.app.domain.model.NodeIdentity
import com.meshwalk.app.domain.repository.IdentityRepository
import com.meshwalk.app.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class IdentityViewModel @Inject constructor(
    private val identityRepository: IdentityRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = false,
        val error: String? = null,
        val identity: NodeIdentity? = null,
        val isComplete: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    fun createIdentity(name: String?, type: IdentityType) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val identity = identityRepository.createIdentity(name, type)
                settingsRepository.setOnboardingComplete()
                _state.value = _state.value.copy(
                    isLoading = false,
                    identity = identity,
                    isComplete = true
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to create identity"
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentitySetupScreen(
    onComplete: () -> Unit,
    viewModel: IdentityViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var displayName by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(IdentityType.NAMED) }

    LaunchedEffect(state.isComplete) {
        if (state.isComplete) onComplete()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "Create Your Identity",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Choose how you appear on the mesh network",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Identity type selection
        IdentityTypeCard(
            icon = Icons.Filled.Person,
            title = "Named",
            description = "Others see your chosen display name",
            selected = selectedType == IdentityType.NAMED,
            onClick = { selectedType = IdentityType.NAMED }
        )

        Spacer(modifier = Modifier.height(12.dp))

        IdentityTypeCard(
            icon = Icons.Filled.VisibilityOff,
            title = "Anonymous",
            description = "Others see you as an anonymous node",
            selected = selectedType == IdentityType.ANONYMOUS,
            onClick = { selectedType = IdentityType.ANONYMOUS }
        )

        Spacer(modifier = Modifier.height(12.dp))

        IdentityTypeCard(
            icon = Icons.Filled.Timer,
            title = "Temporary",
            description = "Identity expires after 24 hours",
            selected = selectedType == IdentityType.TEMPORARY,
            onClick = { selectedType = IdentityType.TEMPORARY }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Name input (only for NAMED type)
        if (selectedType == IdentityType.NAMED) {
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Display Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        Spacer(modifier = Modifier.weight(1f))

        state.error?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Button(
            onClick = {
                val name = when (selectedType) {
                    IdentityType.NAMED -> displayName.ifBlank { null }
                    else -> null
                }
                viewModel.createIdentity(name, selectedType)
            },
            enabled = !state.isLoading && (selectedType != IdentityType.NAMED || displayName.isNotBlank()),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("Create Identity")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun IdentityTypeCard(
    icon: ImageVector,
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}
