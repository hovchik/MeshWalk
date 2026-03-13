package com.meshwalk.app.routing.table

import com.meshwalk.app.domain.model.*
import com.meshwalk.app.domain.repository.RoutingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory routing table with persistence backing.
 * Maintains best-known routes to all discovered nodes.
 *
 * Route selection criteria (priority order):
 * 1. Lowest hop count
 * 2. Highest reliability
 * 3. Most recently updated
 */
@Singleton
class RoutingTable @Inject constructor(
    private val routingRepository: RoutingRepository
) {
    private val routes = ConcurrentHashMap<String, RoutingEntry>()
    private val _routeUpdates = MutableStateFlow(0L) // trigger for observers

    fun getRoute(destinationNodeId: String): RoutingEntry? {
        val entry = routes[destinationNodeId]
        if (entry != null && entry.isStale) {
            routes.remove(destinationNodeId)
            return null
        }
        return entry
    }

    suspend fun updateRoute(entry: RoutingEntry) {
        val existing = routes[entry.destinationNodeId]

        // Only update if new route is better
        val shouldUpdate = existing == null ||
                existing.isStale ||
                entry.hopCount < existing.hopCount ||
                (entry.hopCount == existing.hopCount && entry.reliability > existing.reliability)

        if (shouldUpdate) {
            routes[entry.destinationNodeId] = entry
            routingRepository.updateRoute(entry)
            _routeUpdates.value = System.currentTimeMillis()
            Timber.d("Route updated: ${entry.destinationNodeId} via ${entry.nextHopNodeId} (${entry.hopCount} hops)")
        }
    }

    fun getAllRoutes(): List<RoutingEntry> = routes.values.toList()

    fun getReachableNodeIds(): Set<String> = routes.keys.toSet()

    fun size(): Int = routes.size

    suspend fun pruneStaleEntries() {
        val stale = routes.entries.filter { it.value.isStale }
        stale.forEach { (key, _) ->
            routes.remove(key)
            routingRepository.removeRoute(key)
        }
        if (stale.isNotEmpty()) {
            _routeUpdates.value = System.currentTimeMillis()
            Timber.d("Pruned ${stale.size} stale routes")
        }
    }

    suspend fun removeRoute(destinationNodeId: String) {
        routes.remove(destinationNodeId)
        routingRepository.removeRoute(destinationNodeId)
    }

    /**
     * Build network graph snapshot for visualization.
     */
    fun buildNetworkGraph(selfNodeId: String, directPeers: List<PeerNode>): NetworkGraph {
        val nodes = mutableListOf<GraphNode>()
        val edges = mutableListOf<GraphEdge>()

        // Self node
        nodes.add(GraphNode(
            nodeId = selfNodeId,
            displayName = "You",
            identityType = IdentityType.NAMED,
            isSelf = true,
            isDirect = true,
            hopCount = 0
        ))

        // Direct peers
        directPeers.forEach { peer ->
            nodes.add(GraphNode(
                nodeId = peer.nodeId,
                displayName = peer.displayName,
                identityType = peer.identityType,
                isDirect = true,
                hopCount = 0,
                lastSeen = peer.lastSeen
            ))
            edges.add(GraphEdge(
                fromNodeId = selfNodeId,
                toNodeId = peer.nodeId,
                connectionType = peer.connectionType,
                signalStrength = peer.signalStrength,
                isActive = peer.isConnected
            ))
        }

        // Multi-hop nodes from routing table
        routes.values.forEach { route ->
            if (!nodes.any { it.nodeId == route.destinationNodeId }) {
                nodes.add(GraphNode(
                    nodeId = route.destinationNodeId,
                    displayName = null,
                    identityType = IdentityType.ANONYMOUS,
                    isDirect = false,
                    hopCount = route.hopCount,
                    lastSeen = route.lastUpdated
                ))
                edges.add(GraphEdge(
                    fromNodeId = route.nextHopNodeId,
                    toNodeId = route.destinationNodeId,
                    connectionType = route.connectionType,
                    isActive = !route.isStale
                ))
            }
        }

        return NetworkGraph(
            selfNodeId = selfNodeId,
            nodes = nodes,
            edges = edges
        )
    }
}
