package com.meshwalk.app.ui.experimental

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshwalk.app.experimental.RelayStats
import com.meshwalk.app.experimental.RelayStatsTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RelayStatsViewModel @Inject constructor(
    private val tracker: RelayStatsTracker
) : ViewModel() {
    private val _state = MutableStateFlow(RelayStats.EMPTY)
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            tracker.observe().collect { _state.value = it }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayStatsScreen(
    onBack: () -> Unit,
    viewModel: RelayStatsViewModel = hiltViewModel()
) {
    val stats by viewModel.state.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Relay Stats") },
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
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                icon = Icons.Filled.Hub,
                label = "Packets relayed",
                value = stats.packetsRelayed.toString()
            )
            StatCard(
                icon = Icons.Filled.ArrowDownward,
                label = "Bytes relayed",
                value = formatBytes(stats.bytesRelayed)
            )
            StatCard(
                icon = Icons.Filled.Emergency,
                label = "SOS packets relayed",
                value = stats.sosRelayed.toString()
            )
            if (stats.packetsRelayed == 0L) {
                Text(
                    "You haven't relayed any packets yet. Keep the app running in the background to help others on the mesh.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Text(
                    value,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private fun formatBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024L * 1024 -> "%.1f KB".format(n / 1024.0)
    n < 1024L * 1024 * 1024 -> "%.1f MB".format(n / (1024.0 * 1024))
    else -> "%.1f GB".format(n / (1024.0 * 1024 * 1024))
}
