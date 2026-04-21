package com.meshwalk.app.ui.experimental

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshwalk.app.crypto.keys.KeyStorage
import com.meshwalk.app.experimental.DropZone
import com.meshwalk.app.experimental.DropZoneManager
import com.meshwalk.app.util.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DropZonesViewModel @Inject constructor(
    private val manager: DropZoneManager,
    private val keyStorage: KeyStorage
) : ViewModel() {
    data class UiState(
        val zones: List<DropZone> = emptyList(),
        val unlockedText: Map<String, String> = emptyMap()
    )
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            manager.observeZones().collect { zones ->
                _state.value = _state.value.copy(zones = zones)
            }
        }
    }

    fun createLocationZone(body: String, lat: Double, lng: Double, radius: Double) {
        viewModelScope.launch {
            val self = keyStorage.getDeviceNodeId()
            manager.createLocationZone(
                creatorNodeId = self,
                recipientNodeId = null,
                body = body,
                latitude = lat,
                longitude = lng,
                radiusMeters = radius,
                ttlMs = 24L * 60 * 60 * 1000
            )
        }
    }

    fun createBeaconZone(body: String, beaconNodeId: String) {
        viewModelScope.launch {
            val self = keyStorage.getDeviceNodeId()
            manager.createBeaconZone(
                creatorNodeId = self,
                recipientNodeId = null,
                body = body,
                beaconNodeId = beaconNodeId,
                ttlMs = 24L * 60 * 60 * 1000
            )
        }
    }

    fun tryUnlock(zone: DropZone, lat: Double?, lng: Double?, nearbyBeacons: Set<String>) {
        viewModelScope.launch {
            val plaintext = manager.tryUnlock(zone.zoneId, lat, lng, nearbyBeacons)
            if (plaintext != null) {
                _state.value = _state.value.copy(
                    unlockedText = _state.value.unlockedText + (zone.zoneId to plaintext)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropZonesScreen(
    onBack: () -> Unit,
    viewModel: DropZonesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var showComposer by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Drop Zones") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showComposer = true }) {
                Icon(Icons.Filled.Add, "New zone")
            }
        }
    ) { padding ->
        if (state.zones.isEmpty()) {
            Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No drop zones. Leave a message tied to a location or beacon.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.zones, key = { it.zoneId }) { zone ->
                    DropZoneCard(
                        zone = zone,
                        unlockedText = state.unlockedText[zone.zoneId],
                        onTryUnlock = { viewModel.tryUnlock(zone, null, null, emptySet()) }
                    )
                }
            }
        }
    }

    if (showComposer) {
        DropZoneComposer(
            onDismiss = { showComposer = false },
            onCreateLocation = { body, lat, lng, radius ->
                viewModel.createLocationZone(body, lat, lng, radius)
                showComposer = false
            },
            onCreateBeacon = { body, beacon ->
                viewModel.createBeaconZone(body, beacon)
                showComposer = false
            }
        )
    }
}

@Composable
private fun DropZoneCard(
    zone: DropZone,
    unlockedText: String?,
    onTryUnlock: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (zone.isUnlocked || unlockedText != null) Icons.Filled.LockOpen else Icons.Filled.Lock,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    when {
                        zone.beaconNodeId != null -> "Beacon zone"
                        zone.latitude != null -> "Location zone"
                        else -> "Zone"
                    },
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    TimeUtils.formatTimestamp(zone.createdAt),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            when {
                unlockedText != null -> Text(unlockedText, style = MaterialTheme.typography.bodyMedium)
                zone.beaconNodeId != null -> Text(
                    "Unlocks near beacon ${zone.beaconNodeId.take(8)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                zone.latitude != null && zone.longitude != null -> Text(
                    "Unlocks within ${zone.radiusMeters?.toInt() ?: 0}m of %.5f, %.5f".format(
                        zone.latitude, zone.longitude
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (unlockedText == null && !zone.isUnlocked) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onTryUnlock) {
                    Icon(Icons.Filled.LocationOn, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Try to unlock here")
                }
            }
        }
    }
}

@Composable
private fun DropZoneComposer(
    onDismiss: () -> Unit,
    onCreateLocation: (String, Double, Double, Double) -> Unit,
    onCreateBeacon: (String, String) -> Unit
) {
    var body by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(0) } // 0 location, 1 beacon
    var lat by remember { mutableStateOf("") }
    var lng by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf("100") }
    var beaconId by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Drop Zone") },
        text = {
            Column {
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Hidden message") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                Spacer(modifier = Modifier.height(12.dp))
                TabRow(selectedTabIndex = mode) {
                    Tab(selected = mode == 0, onClick = { mode = 0 }, text = { Text("Location") })
                    Tab(selected = mode == 1, onClick = { mode = 1 }, text = { Text("Beacon") })
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (mode == 0) {
                    OutlinedTextField(
                        value = lat, onValueChange = { lat = it },
                        label = { Text("Latitude") }, singleLine = true
                    )
                    OutlinedTextField(
                        value = lng, onValueChange = { lng = it },
                        label = { Text("Longitude") }, singleLine = true
                    )
                    OutlinedTextField(
                        value = radius, onValueChange = { radius = it.filter(Char::isDigit) },
                        label = { Text("Radius (meters)") }, singleLine = true
                    )
                } else {
                    OutlinedTextField(
                        value = beaconId, onValueChange = { beaconId = it },
                        label = { Text("Beacon nodeId") }, singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = body.isNotBlank() && (
                    (mode == 0 && lat.toDoubleOrNull() != null && lng.toDoubleOrNull() != null) ||
                    (mode == 1 && beaconId.isNotBlank())
                ),
                onClick = {
                    if (mode == 0) {
                        onCreateLocation(
                            body,
                            lat.toDouble(),
                            lng.toDouble(),
                            radius.toDoubleOrNull() ?: 100.0
                        )
                    } else {
                        onCreateBeacon(body, beaconId)
                    }
                }
            ) { Text("Drop") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
