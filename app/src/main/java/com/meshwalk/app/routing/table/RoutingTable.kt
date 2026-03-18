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
    companion object {
        /** Hard cap on in-memory routing entries to prevent unbounded growth in large meshes. */
        private const val MAX_ROUTES = 500
    }

    private val routes = ConcurrentHashMap<String, RoutingEntry>()
    private val _routeUpdates = MutableStateFlow(0L) // trigger for observers

    /** Observable stream of route-change timestamps for real-time UI updates. */
    val routeUpdates: Flow<Long> get() = _routeUpdates

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
            // Enforce the cap before inserting a brand-new destination.
            if (existing == null && routes.size >= MAX_ROUTES) {
                evictWorstRoute()
            }
            routes[entry.destinationNodeId] = entry
            routingRepository.updateRoute(entry)
            _routeUpdates.value = System.currentTimeMillis()
            Timber.d("Route updated: ${entry.destinationNodeId} via ${entry.nextHopNodeId} (${entry.hopCount} hops)")
        }
    }

    /**
     * Evict the least-useful route to make room for a new one.
     * Priority: stale > most hops > lowest reliability.
     */
    private fun evictWorstRoute() {
        // 1. Prefer removing an already-stale entry
        val staleKey = routes.entries.firstOrNull { it.value.isStale }?.key
        if (staleKey != null) {
            routes.remove(staleKey)
            Timber.d("RoutingTable: evicted stale route for $staleKey (at capacity)")
            return
        }
        // 2. Remove the entry with the highest hop count (furthest, least reliable relay)
        val worstKey = routes.entries.maxByOrNull { it.value.hopCount }?.key
        if (worstKey != null) {
            routes.remove(worstKey)
            Timber.d("RoutingTable: evicted highest-hop route for $worstKey (at capacity)")
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
        _routeUpdates.value = System.currentTimeMillis()
    }

    /**
     * Remove all routes that use a specific node as next hop.
     * Called when that node disconnects.
     */
    suspend fun removeRoutesVia(nextHopNodeId: String) {
        val affected = routes.entries.filter { it.value.nextHopNodeId == nextHopNodeId }
        affected.forEach { (key, _) ->
            routes.remove(key)
            routingRepository.removeRoute(key)
        }
        if (affected.isNotEmpty()) {
            _routeUpdates.value = System.currentTimeMillis()
            Timber.d("Removed ${affected.size} routes via disconnected node ${nextHopNodeId.take(8)}")
        }
    }

    /**
     * Build network graph snapshot for visualization.
     */
    fun buildNetworkGraph(selfNodeId: String, directPeers: List<PeerNode>, activeOnly: Boolean = false): NetworkGraph {
        val nodes = mutableListOf<GraphNode>()
        val edges = mutableListOf<GraphEdge>()

        // Self node
        nodes.add(GraphNode(
            nodeId = selfNodeId,
            displayName = "You",
            identityType = IdentityType.NAMED,
            isSelf = true,
            isDirect = true,
            isConnected = true,
            hopCount = 0
        ))

        // Direct peers
        val now = System.currentTimeMillis()
        directPeers.forEach { peer ->
            // Cross-validate: a peer claiming isConnected but not seen recently
            // is stale (e.g. app was killed before disconnect callback fired).
            val actuallyConnected = peer.isConnected &&
                (now - peer.lastSeen < RoutingEntry.STALE_THRESHOLD_MS)
            nodes.add(GraphNode(
                nodeId = peer.nodeId,
                displayName = peer.displayName,
                identityType = peer.identityType,
                isDirect = true,
                isConnected = actuallyConnected,
                hopCount = 0,
                lastSeen = peer.lastSeen
            ))
            edges.add(GraphEdge(
                fromNodeId = selfNodeId,
                toNodeId = peer.nodeId,
                connectionType = peer.connectionType,
                signalStrength = peer.signalStrength,
                isActive = actuallyConnected
            ))
        }

        // Multi-hop nodes from routing table
        routes.values.forEach { route ->
            if (activeOnly && route.isStale) return@forEach
            if (!nodes.any { it.nodeId == route.destinationNodeId }) {
                nodes.add(GraphNode(
                    nodeId = route.destinationNodeId,
                    displayName = null,
                    identityType = IdentityType.ANONYMOUS,
                    isDirect = false,
                    isConnected = !route.isStale,
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
