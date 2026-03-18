package com.meshwalk.app.domain.model

/**
 * Entry in the routing table. Maps a destination node to the best known next hop.
 */
data class RoutingEntry(
    val destinationNodeId: String,
    val nextHopNodeId: String,
    val hopCount: Int,
    val connectionType: ConnectionType,
    val lastUpdated: Long = System.currentTimeMillis(),
    val reliability: Float = 1.0f,   // 0.0-1.0 based on recent success rate
    val latencyMs: Long? = null
) {
    val isStale: Boolean
        get() = System.currentTimeMillis() - lastUpdated > STALE_THRESHOLD_MS

    companion object {
        const val STALE_THRESHOLD_MS = 60_000L // 1 minute
    }
}

/**
 * Snapshot of the known network topology for visualization.
 */
data class NetworkGraph(
    val selfNodeId: String,
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val timestamp: Long = System.currentTimeMillis()
)

data class GraphNode(
    val nodeId: String,
    val displayName: String?,
    val identityType: IdentityType,
    val isSelf: Boolean = false,
    val isDirect: Boolean = false,
    val isConnected: Boolean = false,
    val hopCount: Int = 0,
    val lastSeen: Long = 0
)

data class GraphEdge(
    val fromNodeId: String,
    val toNodeId: String,
    val connectionType: ConnectionType,
    val signalStrength: Int? = null,
    val isActive: Boolean = true
)
