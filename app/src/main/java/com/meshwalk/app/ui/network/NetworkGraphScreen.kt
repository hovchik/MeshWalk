package com.meshwalk.app.ui.network

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
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import com.meshwalk.app.routing.table.RoutingTable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.*

@HiltViewModel
class NetworkGraphViewModel @Inject constructor(
    private val peerRepo: PeerRepository,
    private val routingTable: RoutingTable,
    private val identityRepo: IdentityRepository
) : ViewModel() {

    data class UiState(
        val graph: NetworkGraph? = null,
        val selfNodeId: String = "",
        val peerCount: Int = 0,
        val routeCount: Int = 0
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val identity = identityRepo.getActiveIdentity()
            val selfId = identity?.nodeId ?: ""

            peerRepo.observeNearbyPeers().collect { peers ->
                val graph = routingTable.buildNetworkGraph(selfId, peers)
                _state.value = UiState(
                    graph = graph,
                    selfNodeId = selfId,
                    peerCount = peers.size,
                    routeCount = routingTable.size()
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkGraphScreen(viewModel: NetworkGraphViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network Map") },
                actions = {
                    Text(
                        "${state.peerCount} peers • ${state.routeCount} routes",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // Legend
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                LegendItem(color = Color(0xFF00BFA5), label = "You")
                LegendItem(color = Color(0xFF2196F3), label = "Direct")
                LegendItem(color = Color(0xFFFF9800), label = "Relay")
                LegendItem(color = Color(0xFF9E9E9E), label = "Offline")
            }

            // Graph canvas
            val graph = state.graph
            if (graph != null && graph.nodes.isNotEmpty()) {
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
                        Icon(
                            Icons.Filled.Hub,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "No network nodes discovered",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
    val offlineColor = Color(0xFF9E9E9E)
    val edgeColor = Color(0xFF546E7A)
    val edgeActiveColor = Color(0xFF00BFA5)
    val textMeasurer = rememberTextMeasurer()

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

        // Calculate node positions using force-directed-like layout
        val positions = calculateNodePositions(graph, centerX, centerY, radius)

        // Draw edges first
        graph.edges.forEach { edge ->
            val fromPos = positions[edge.fromNodeId]
            val toPos = positions[edge.toNodeId]
            if (fromPos != null && toPos != null) {
                val lineColor = if (edge.isActive) edgeActiveColor else edgeColor
                val strokeWidth = if (edge.isActive) 2f * scale else 1f * scale

                drawLine(
                    color = lineColor,
                    start = fromPos,
                    end = toPos,
                    strokeWidth = strokeWidth,
                    pathEffect = if (!edge.isActive) PathEffect.dashPathEffect(
                        floatArrayOf(10f, 10f)
                    ) else null
                )
            }
        }

        // Draw nodes
        graph.nodes.forEach { node ->
            val pos = positions[node.nodeId] ?: return@forEach
            val nodeRadius = if (node.isSelf) 24f * scale else 16f * scale
            val color = when {
                node.isSelf -> selfColor
                node.isDirect -> directColor
                node.hopCount > 0 -> relayColor
                else -> offlineColor
            }

            // Node circle
            drawCircle(color = color, radius = nodeRadius, center = pos)
            drawCircle(
                color = Color.White.copy(alpha = 0.3f),
                radius = nodeRadius - 3f * scale,
                center = pos
            )

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
