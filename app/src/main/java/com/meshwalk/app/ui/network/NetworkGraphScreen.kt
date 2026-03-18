package com.meshwalk.app.ui.network

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshwalk.app.domain.model.*
import com.meshwalk.app.domain.repository.IdentityRepository
import com.meshwalk.app.domain.repository.PeerRepository
import com.meshwalk.app.domain.repository.SettingsRepository
import com.meshwalk.app.domain.usecase.TransportManagerPort
import com.meshwalk.app.routing.table.RoutingTable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.*

@HiltViewModel
class NetworkGraphViewModel @Inject constructor(
    private val peerRepo: PeerRepository,
    private val routingTable: RoutingTable,
    private val identityRepo: IdentityRepository,
    private val settingsRepo: SettingsRepository,
    private val transportManager: TransportManagerPort
) : ViewModel() {

    data class UiState(
        val graph: NetworkGraph? = null,
        val selfNodeId: String = "",
        val peerCount: Int = 0,
        val activePeerCount: Int = 0,
        val routeCount: Int = 0,
        val showOnlyActive: Boolean = true,
        val lastUpdated: Long = 0L
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Reset stale connection states left over from a previous session
            // (e.g. app was killed before disconnect callbacks could fire).
            peerRepo.resetAllConnectionStates()

            val identity = identityRepo.getActiveIdentity()
            val selfId = identity?.nodeId ?: ""

            // Periodic ticker driven by the user-configurable refresh interval.
            // Pauses when scanning is stopped so we don't poll unnecessarily.
            val ticker = combine(
                settingsRepo.observeSettings()
                    .map { it.networkGraphRefreshSeconds }
                    .distinctUntilChanged(),
                transportManager.isScanning
            ) { seconds, scanning -> seconds to scanning }
                .flatMapLatest { (seconds, scanning) ->
                    if (scanning) {
                        flow {
                            while (true) {
                                emit(Unit)
                                delay(seconds * 1_000L)
                            }
                        }
                    } else {
                        // Emit once so the graph shows current state, then stop polling
                        flowOf(Unit)
                    }
                }

            // Combine peer changes, route changes, and periodic ticker
            combine(
                peerRepo.observeNearbyPeers(),
                routingTable.routeUpdates,
                ticker
            ) { peers, _, _ ->
                peers
            }.collect { peers ->
                rebuildGraph(selfId, peers)
            }
        }
    }

    private fun rebuildGraph(selfId: String, peers: List<PeerNode>) {
        val showActive = _state.value.showOnlyActive
        val now = System.currentTimeMillis()

        // Cross-validate: peer must both claim isConnected AND have been seen
        // recently to be considered truly active.
        val trulyActivePeers = peers.filter {
            it.isConnected && (now - it.lastSeen < RoutingEntry.STALE_THRESHOLD_MS)
        }

        val filteredPeers = if (showActive) trulyActivePeers else peers

        val graph = routingTable.buildNetworkGraph(selfId, filteredPeers, activeOnly = showActive)
        _state.value = _state.value.copy(
            graph = graph,
            selfNodeId = selfId,
            peerCount = peers.size,
            activePeerCount = trulyActivePeers.size,
            routeCount = routingTable.size(),
            lastUpdated = now
        )
    }

    fun toggleActiveFilter() {
        _state.update { it.copy(showOnlyActive = !it.showOnlyActive) }
        // Trigger immediate rebuild
        viewModelScope.launch {
            val identity = identityRepo.getActiveIdentity()
            val selfId = identity?.nodeId ?: ""
            val peers = peerRepo.observeNearbyPeers().first()
            rebuildGraph(selfId, peers)
        }
    }

}

@Composable
fun NetworkGraphScreen(viewModel: NetworkGraphViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()

    Column {
        // Status bar with peer counts and filter toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "${state.activePeerCount} active • ${state.peerCount} total • ${state.routeCount} routes",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.lastUpdated > 0) {
                    val elapsed = remember(state.lastUpdated) {
                        formatElapsed(state.lastUpdated)
                    }
                    // Re-compose periodically to update relative time
                    var currentElapsed by remember { mutableStateOf(elapsed) }
                    LaunchedEffect(state.lastUpdated) {
                        while (true) {
                            currentElapsed = formatElapsed(state.lastUpdated)
                            delay(1_000L)
                        }
                    }
                    Text(
                        "Updated $currentElapsed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            FilterChip(
                selected = state.showOnlyActive,
                onClick = { viewModel.toggleActiveFilter() },
                label = { Text("Active only") },
                leadingIcon = if (state.showOnlyActive) {
                    { Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null
            )
        }

        // Legend
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            LegendItem(color = Color(0xFF00BFA5), label = "You")
            LegendItem(color = Color(0xFF2196F3), label = "Direct")
            LegendItem(color = Color(0xFFFF9800), label = "Relay")
            LegendItem(color = Color(0xFFE53935), label = "Inactive")
        }

        // Graph canvas
        val graph = state.graph
        if (graph != null && graph.nodes.size > 1) {
            NetworkCanvas(
                graph = graph,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Scanning animation
                    val infiniteTransition = rememberInfiniteTransition(label = "scan")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1200, easing = EaseInOut),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulse"
                    )
                    Icon(
                        Icons.Filled.Hub,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline.copy(alpha = pulseAlpha)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        if (state.showOnlyActive) "No active peers connected"
                        else "No network nodes discovered",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.showOnlyActive && state.peerCount > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "${state.peerCount} peers discovered but not connected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(12.dp)) {
            drawCircle(color = color)
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun NetworkCanvas(
    graph: NetworkGraph,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val selfColor = Color(0xFF00BFA5)
    val directColor = Color(0xFF2196F3)
    val relayColor = Color(0xFFFF9800)
    val inactiveColor = Color(0xFFE53935)
    val edgeColor = Color(0xFF546E7A)
    val edgeActiveColor = Color(0xFF00BFA5)
    val textMeasurer = rememberTextMeasurer()

    // Pulse animation for active nodes
    val infiniteTransition = rememberInfiniteTransition(label = "nodePulse")
    val pulseRadius by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseOut),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse"
    )

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 3f)
                    offset += pan
                }
            }
    ) {
        val centerX = size.width / 2 + offset.x
        val centerY = size.height / 2 + offset.y
        val radius = minOf(size.width, size.height) * 0.35f * scale

        // Calculate node positions
        val positions = calculateNodePositions(graph, centerX, centerY, radius)

        // Draw edges
        graph.edges.forEach { edge ->
            val fromPos = positions[edge.fromNodeId]
            val toPos = positions[edge.toNodeId]
            if (fromPos != null && toPos != null) {
                val lineColor = if (edge.isActive) edgeActiveColor else edgeColor
                val strokeWidth = if (edge.isActive) 2.5f * scale else 1f * scale

                drawLine(
                    color = lineColor,
                    start = fromPos,
                    end = toPos,
                    strokeWidth = strokeWidth,
                    pathEffect = if (!edge.isActive) PathEffect.dashPathEffect(
                        floatArrayOf(10f, 10f)
                    ) else null
                )

                // Signal strength indicator on active edges
                if (edge.isActive && edge.signalStrength != null) {
                    val midX = (fromPos.x + toPos.x) / 2
                    val midY = (fromPos.y + toPos.y) / 2
                    val bars = signalBars(edge.signalStrength)
                    val barLabel = textMeasurer.measure(
                        text = AnnotatedString(bars),
                        style = TextStyle(
                            fontSize = (8 * scale).sp,
                            color = edgeActiveColor
                        )
                    )
                    drawText(
                        textLayoutResult = barLabel,
                        topLeft = Offset(midX - barLabel.size.width / 2, midY - barLabel.size.height / 2)
                    )
                }
            }
        }

        // Draw nodes
        graph.nodes.forEach { node ->
            val pos = positions[node.nodeId] ?: return@forEach
            val nodeRadius = if (node.isSelf) 24f * scale else 16f * scale
            val color = when {
                node.isSelf -> selfColor
                !node.isConnected -> inactiveColor
                node.isDirect -> directColor
                node.hopCount > 0 -> relayColor
                else -> inactiveColor
            }

            // Active pulse ring for connected nodes only
            if (node.isConnected && !node.isSelf) {
                val expandedRadius = nodeRadius + 12f * scale * pulseRadius
                val alpha = (1f - pulseRadius) * 0.4f
                drawCircle(
                    color = color.copy(alpha = alpha),
                    radius = expandedRadius,
                    center = pos
                )
            }

            // Self node gets a subtle constant glow
            if (node.isSelf) {
                drawCircle(
                    color = selfColor.copy(alpha = 0.15f),
                    radius = nodeRadius + 10f * scale,
                    center = pos
                )
            }

            // Node circle with border
            drawCircle(color = color, radius = nodeRadius, center = pos)
            drawCircle(
                color = Color.White.copy(alpha = 0.3f),
                radius = nodeRadius - 3f * scale,
                center = pos
            )

            // Connection type icon ring for non-self nodes
            if (!node.isSelf) {
                drawCircle(
                    color = color,
                    radius = nodeRadius,
                    center = pos,
                    style = Stroke(width = 2f * scale)
                )
            }

            // Node label
            val label = when {
                node.isSelf -> "You"
                node.displayName != null -> node.displayName.take(8)
                else -> node.nodeId.take(6)
            }

            val textResult = textMeasurer.measure(
                text = AnnotatedString(label),
                style = TextStyle(
                    fontSize = (10 * scale).sp,
                    color = Color.White
                )
            )

            drawText(
                textLayoutResult = textResult,
                topLeft = Offset(
                    pos.x - textResult.size.width / 2,
                    pos.y + nodeRadius + 4f * scale
                )
            )

            // Hop count badge for relay nodes
            if (node.hopCount > 0) {
                val hopText = textMeasurer.measure(
                    text = AnnotatedString("${node.hopCount}h"),
                    style = TextStyle(
                        fontSize = (8 * scale).sp,
                        color = Color.White
                    )
                )
                drawText(
                    textLayoutResult = hopText,
                    topLeft = Offset(
                        pos.x - hopText.size.width / 2,
                        pos.y - 3f * scale
                    )
                )
            }
        }
    }
}

/**
 * Simple circular layout with self at center.
 * Direct peers in inner ring, relay peers in outer ring.
 */
private fun calculateNodePositions(
    graph: NetworkGraph,
    centerX: Float,
    centerY: Float,
    radius: Float
): Map<String, Offset> {
    val positions = mutableMapOf<String, Offset>()

    // Self at center
    val selfNode = graph.nodes.find { it.isSelf }
    if (selfNode != null) {
        positions[selfNode.nodeId] = Offset(centerX, centerY)
    }

    // Direct peers in inner ring
    val directNodes = graph.nodes.filter { it.isDirect && !it.isSelf }
    val innerRadius = radius * 0.5f
    directNodes.forEachIndexed { index, node ->
        val angle = 2 * PI * index / maxOf(directNodes.size, 1) - PI / 2
        positions[node.nodeId] = Offset(
            centerX + (innerRadius * cos(angle)).toFloat(),
            centerY + (innerRadius * sin(angle)).toFloat()
        )
    }

    // Relay peers in outer ring
    val relayNodes = graph.nodes.filter { !it.isDirect && !it.isSelf }
    relayNodes.forEachIndexed { index, node ->
        val angle = 2 * PI * index / maxOf(relayNodes.size, 1)
        positions[node.nodeId] = Offset(
            centerX + (radius * cos(angle)).toFloat(),
            centerY + (radius * sin(angle)).toFloat()
        )
    }

    return positions
}

/** Map RSSI to a visual signal bar string. */
private fun signalBars(rssi: Int): String = when {
    rssi >= -50 -> "||||"
    rssi >= -65 -> "|||"
    rssi >= -80 -> "||"
    else -> "|"
}

/** Format millisecond timestamp as relative elapsed time. */
private fun formatElapsed(timestampMs: Long): String {
    val elapsed = System.currentTimeMillis() - timestampMs
    return when {
        elapsed < 2_000 -> "just now"
        elapsed < 60_000 -> "${elapsed / 1000}s ago"
        elapsed < 3_600_000 -> "${elapsed / 60_000}m ago"
        else -> "${elapsed / 3_600_000}h ago"
    }
}
