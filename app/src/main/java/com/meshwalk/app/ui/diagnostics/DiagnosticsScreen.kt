package com.meshwalk.app.ui.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshwalk.app.domain.model.RoutingEntry
import com.meshwalk.app.routing.dedup.PacketDeduplicator
import com.meshwalk.app.routing.engine.MeshRoutingEngine
import com.meshwalk.app.routing.engine.RoutingDiagnosticEvent
import com.meshwalk.app.routing.queue.OfflineQueue
import com.meshwalk.app.routing.table.RoutingTable
import com.meshwalk.app.transport.manager.TransportManager
import com.meshwalk.app.util.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val routingTable: RoutingTable,
    private val offlineQueue: OfflineQueue,
    private val deduplicator: PacketDeduplicator,
    private val transportManager: TransportManager,
    private val routingEngine: MeshRoutingEngine
) : ViewModel() {

    data class UiState(
        val routes: List<RoutingEntry> = emptyList(),
        val connectedPeers: Int = 0,
        val queuedPackets: Int = 0,
        val dedupCacheSize: Int = 0,
        val recentEvents: List<String> = emptyList()
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val eventLog = mutableListOf<String>()

    init {
        // Collect routing diagnostic events
        viewModelScope.launch {
            routingEngine.routingEvents.collect { event ->
                val text = when (event) {
                    is RoutingDiagnosticEvent.PacketDelivered ->
                        "DELIVERED ${event.packetId.take(8)} from ${event.from.take(8)} (${event.hops} hops)"
                    is RoutingDiagnosticEvent.PacketRelayed ->
                        "RELAYED ${event.packetId.take(8)} ${event.from.take(8)}->${event.to.take(8)} (hop ${event.hops})"
                    is RoutingDiagnosticEvent.PacketQueued ->
                        "QUEUED ${event.packetId.take(8)} for ${event.destination.take(8)}"
                    is RoutingDiagnosticEvent.PacketExpired ->
                        "EXPIRED ${event.packetId.take(8)}"
                    is RoutingDiagnosticEvent.DuplicateDropped ->
                        "DEDUP ${event.packetId.take(8)}"
                    is RoutingDiagnosticEvent.RouteMaintenance ->
                        "MAINTENANCE routes=${event.routeCount} queued=${event.queuedPackets}"
                    is RoutingDiagnosticEvent.QueueFlushScheduled ->
                        "QUEUE_FLUSH scheduled attempt ${event.attempt}/${event.maxAttempts} in ${event.delayMs}ms (${event.queuedCount} queued)"
                    is RoutingDiagnosticEvent.QueueFlushCompleted ->
                        "QUEUE_FLUSH attempt ${event.attempt} sent=${event.sentCount} remaining=${event.remainingCount}"
                    is RoutingDiagnosticEvent.QueueDrained ->
                        "QUEUE_DRAINED after ${event.totalAttempts} attempts"
                    is RoutingDiagnosticEvent.QueueFlushGaveUp ->
                        "QUEUE_GAVE_UP after ${event.totalAttempts} attempts (${event.remainingCount} remaining)"
                }
                eventLog.add(0, "${TimeUtils.formatTimestamp(System.currentTimeMillis())} $text")
                if (eventLog.size > 100) eventLog.removeAt(eventLog.lastIndex)
                refresh()
            }
        }

        // Periodic refresh
        viewModelScope.launch {
            while (true) {
                refresh()
                kotlinx.coroutines.delay(5000)
            }
        }
    }

    private fun refresh() {
        _state.value = UiState(
            routes = routingTable.getAllRoutes(),
            connectedPeers = transportManager.getConnectedNodeIds().size,
            queuedPackets = offlineQueue.size(),
            dedupCacheSize = deduplicator.size(),
            recentEvents = eventLog.toList()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mesh Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Stats cards
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCard("Peers", state.connectedPeers.toString(), Icons.Filled.People, Modifier.weight(1f))
                    StatCard("Routes", state.routes.size.toString(), Icons.Filled.Route, Modifier.weight(1f))
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCard("Queued", state.queuedPackets.toString(), Icons.Filled.Queue, Modifier.weight(1f))
                    StatCard("Dedup", state.dedupCacheSize.toString(), Icons.Filled.FilterAlt, Modifier.weight(1f))
                }
            }

            // Routing table
            item {
                Text(
                    "Routing Table",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (state.routes.isEmpty()) {
                item {
                    Text(
                        "No routes yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                items(state.routes) { route ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    "To: ${route.destinationNodeId.take(8)}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "Via: ${route.nextHopNodeId.take(8)} • ${route.hopCount} hops",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                "${"%.0f".format(route.reliability * 100)}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Event log
            item {
                Text(
                    "Event Log",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (state.recentEvents.isEmpty()) {
                item {
                    Text(
                        "No events yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                items(state.recentEvents) { event ->
                    Text(
                        event,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(value, style = MaterialTheme.typography.headlineSmall)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
