package com.meshwalk.app.ui.experimental

import android.content.Context
import android.os.BatteryManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshwalk.app.domain.repository.IdentityRepository
import com.meshwalk.app.experimental.SosBroadcast
import com.meshwalk.app.experimental.SosManager
import com.meshwalk.app.util.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SosViewModel @Inject constructor(
    private val sosManager: SosManager,
    private val identityRepo: IdentityRepository
) : ViewModel() {
    data class UiState(
        val history: List<SosBroadcast> = emptyList(),
        val lastSent: Long = 0L
    )
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            sosManager.broadcasts.collect { b ->
                _state.value = _state.value.copy(history = listOf(b) + _state.value.history.take(19))
            }
        }
    }

    fun trigger(note: String, batteryPercent: Int?) {
        viewModelScope.launch {
            val identity = identityRepo.getActiveIdentity() ?: return@launch
            val broadcast = sosManager.build(
                senderNodeId = identity.nodeId,
                senderName = identity.displayName,
                note = note,
                latitude = null,
                longitude = null,
                batteryPercent = batteryPercent
            )
            sosManager.emit(broadcast)
            _state.value = _state.value.copy(lastSent = System.currentTimeMillis())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SosScreen(
    onBack: () -> Unit,
    viewModel: SosViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var note by remember { mutableStateOf("") }
    var showConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SOS Broadcast") },
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
                .fillMaxSize()
        ) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(12.dp)
                ) {
                    Icon(
                        Icons.Filled.Emergency, null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Broadcasts to every reachable node with max TTL. Use only in real emergencies.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Short note (optional)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { showConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.Filled.Emergency, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Send SOS", fontWeight = FontWeight.Bold)
            }

            if (state.lastSent > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Last sent: ${TimeUtils.formatTimestamp(state.lastSent)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (state.history.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                Text("Recent broadcasts", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.history, key = { it.broadcastId }) { b ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    b.senderName ?: "Anonymous",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(b.note, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    TimeUtils.formatTimestamp(b.timestamp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Send SOS?") },
            text = { Text("This will broadcast your message, identity, and battery level to every reachable node on the mesh.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.trigger(note, readBatteryPercent(context))
                    note = ""
                    showConfirm = false
                }) { Text("Send", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

private fun readBatteryPercent(context: Context): Int? {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    return level.takeIf { it in 0..100 }
}
