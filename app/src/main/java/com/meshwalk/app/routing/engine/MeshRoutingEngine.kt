package com.meshwalk.app.routing.engine

import com.meshwalk.app.domain.model.*
import com.meshwalk.app.domain.repository.PeerRepository
import com.meshwalk.app.domain.repository.RoutingRepository
import com.meshwalk.app.routing.dedup.PacketDeduplicator
import com.meshwalk.app.routing.queue.OfflineQueue
import com.meshwalk.app.routing.queue.QueuedMessageHandler
import com.meshwalk.app.routing.queue.QueueFlushEvent
import com.meshwalk.app.routing.table.RoutingTable
import com.meshwalk.app.transport.api.TransportEvent
import com.meshwalk.app.transport.manager.TransportManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Core mesh routing engine.
 *
 * Routing strategy: Hybrid flooding + distance-vector.
 *
 * 1. FLOODING (for initial delivery and small networks):
 *    - Packet broadcast to all connected peers (except source)
 *    - TTL prevents infinite loops
 *    - Deduplication prevents processing duplicates
 *
 * 2. DISTANCE-VECTOR (optimization for established routes):
 *    - Routing table built from discovery and ACK observations
 *    - Once a route is known, prefer targeted forwarding over flooding
 *    - Route entries have reliability scores based on recent success
 *
 * Packet lifecycle:
 * 1. Application creates message → encrypted into MeshPacket
 * 2. Routing engine attempts direct delivery or routes via next hop
 * 3. If no route available, packet goes to offline queue (store-and-forward)
 * 4. When new peers connect, offline queue is flushed
 * 5. ACKs flow back along the reverse path
 * 6. Deduplicator prevents relay loops
 */
@Singleton
class MeshRoutingEngine @Inject constructor(
    private val transportManager: TransportManager,
    private val routingTable: RoutingTable,
    private val deduplicator: PacketDeduplicator,
    private val offlineQueue: OfflineQueue,
    private val queuedMessageHandler: QueuedMessageHandler,
    private val peerRepository: PeerRepository,
    private val routingRepository: RoutingRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Packets destined for this node (after routing/decryption)
    private val _incomingPackets = MutableSharedFlow<MeshPacket>(extraBufferCapacity = 64)
    val incomingPackets: SharedFlow<MeshPacket> = _incomingPackets

    // Routing diagnostics
    private val _routingEvents = MutableSharedFlow<RoutingDiagnosticEvent>(extraBufferCapacity = 32)
    val routingEvents: SharedFlow<RoutingDiagnosticEvent> = _routingEvents

    private var selfNodeId: String? = null
    private var isRunning = false

    /**
     * Start the routing engine. Begins processing transport events.
     */
    fun start(nodeId: String) {
        if (isRunning) return
        selfNodeId = nodeId
        isRunning = true

        // Wire up queued message handler callbacks
        queuedMessageHandler.onReconnectForQueue = {
            transportManager.startDiscovery()
            transportManager.startAdvertising()
        }
        queuedMessageHandler.onFlushQueue = {
            flushAllQueued()
        }

        // Listen for incoming data from transport layer
        scope.launch {
            transportManager.transportEvents.collect { event ->
                when (event) {
                    is TransportEvent.DataReceived -> {
                        val packet = transportManager.deserializePacket(event.data)
                        if (packet != null) {
                            handleIncomingPacket(packet, event.endpointId)
                        }
                    }
                    is TransportEvent.Connected -> {
                        // New peer connected — flush any queued messages for reachable nodes
                        event.nodeId?.let { flushOfflineQueue(it) }
                        // Notify handler that connectivity changed
                        queuedMessageHandler.onConnectivityRestored()
                    }
                    is TransportEvent.Disconnected -> {
                        // Update routing table
                        val nodeId = transportManager.let {
                            // Endpoint disconnected
                            null // Will be resolved by transport manager
                        }
                    }
                    else -> {} // Other events handled by transport manager
                }
            }
        }

        // Forward queued message handler events as routing diagnostics
        scope.launch {
            queuedMessageHandler.events.collect { event ->
                when (event) {
                    is QueueFlushEvent.FlushScheduled ->
                        emitDiagnostic(RoutingDiagnosticEvent.QueueFlushScheduled(
                            event.attempt, event.maxAttempts, event.delayMs, event.queuedCount
                        ))
                    is QueueFlushEvent.FlushCompleted ->
                        emitDiagnostic(RoutingDiagnosticEvent.QueueFlushCompleted(
                            event.attempt, event.sentCount, event.remainingCount
                        ))
                    is QueueFlushEvent.QueueDrained ->
                        emitDiagnostic(RoutingDiagnosticEvent.QueueDrained(event.totalAttempts))
                    is QueueFlushEvent.GaveUp ->
                        emitDiagnostic(RoutingDiagnosticEvent.QueueFlushGaveUp(
                            event.totalAttempts, event.remainingCount
                        ))
                    else -> {}
                }
            }
        }

        // Periodic route maintenance
        scope.launch {
            while (isActive) {
                delay(30_000) // Every 30 seconds
                routingTable.pruneStaleEntries()
                deduplicator.pruneExpired()
                emitDiagnostic(RoutingDiagnosticEvent.RouteMaintenance(
                    routeCount = routingTable.size(),
                    queuedPackets = offlineQueue.size()
                ))
            }
        }

        Timber.d("Routing engine started for node $nodeId")
    }

    /**
     * Stop the routing engine.
     */
    fun stop() {
        isRunning = false
        queuedMessageHandler.cancel()
        scope.coroutineContext.cancelChildren()
        Timber.d("Routing engine stopped")
    }

    /**
     * Send a packet into the mesh.
     * The routing engine decides how to deliver it.
     */
    suspend fun sendPacket(packet: MeshPacket): Boolean {
        if (deduplicator.isDuplicate(packet.packetId)) {
            Timber.d("Dropping duplicate outgoing packet: ${packet.packetId}")
            return false
        }
        deduplicator.markSeen(packet.packetId)

        return routePacket(packet)
    }

    /**
     * Handle a packet received from the transport layer.
     */
    private suspend fun handleIncomingPacket(packet: MeshPacket, fromEndpointId: String) {
        // 1. Deduplication check
        if (deduplicator.isDuplicate(packet.packetId)) {
            Timber.d("Dropping duplicate packet: ${packet.packetId}")
            emitDiagnostic(RoutingDiagnosticEvent.DuplicateDropped(packet.packetId))
            return
        }
        deduplicator.markSeen(packet.packetId)

        // 2. TTL check
        if (packet.isExpired) {
            Timber.d("Dropping expired packet: ${packet.packetId}, TTL=${packet.ttl}")
            emitDiagnostic(RoutingDiagnosticEvent.PacketExpired(packet.packetId))
            return
        }

        // 3. Update routing table (we learned that source is reachable via this endpoint)
        updateRoutingFromPacket(packet, fromEndpointId)

        // 4. Is this packet for us?
        val myNodeId = selfNodeId ?: return
        if (packet.destinationNodeId == myNodeId) {
            Timber.d("Packet ${packet.packetId} delivered to us from ${packet.sourceNodeId}")
            _incomingPackets.emit(packet)
            emitDiagnostic(RoutingDiagnosticEvent.PacketDelivered(
                packet.packetId, packet.sourceNodeId, packet.hopCount
            ))

            // Send ACK if requested
            if (packet.isAckRequested) {
                sendAck(packet)
            }
            return
        }

        // 5. Not for us — relay if TTL allows
        relayPacket(packet, fromEndpointId)
    }

    /**
     * Relay a packet to the next hop toward its destination.
     */
    private suspend fun relayPacket(packet: MeshPacket, fromEndpointId: String) {
        val relayed = packet.forRelay()

        if (relayed.isExpired) {
            Timber.d("Relay packet expired after decrement: ${packet.packetId}")
            return
        }

        emitDiagnostic(RoutingDiagnosticEvent.PacketRelayed(
            packet.packetId, packet.sourceNodeId, packet.destinationNodeId, relayed.hopCount
        ))

        // Try targeted routing first
        val route = routingTable.getRoute(packet.destinationNodeId)
        if (route != null) {
            val sent = transportManager.sendPacket(relayed, route.nextHopNodeId)
            if (sent) {
                Timber.d("Relayed ${packet.packetId} via targeted route to ${route.nextHopNodeId}")
                return
            }
        }

        // Fall back to flooding (broadcast to all except sender)
        val fromNodeId = transportManager.let {
            // Resolve endpoint to nodeId to exclude
            null
        }
        transportManager.broadcastPacket(relayed, packet.sourceNodeId)
        Timber.d("Relayed ${packet.packetId} via flooding")
    }

    /**
     * Route a locally-originated packet to its destination.
     */
    private suspend fun routePacket(packet: MeshPacket): Boolean {
        val destNodeId = packet.destinationNodeId

        // 1. Check if destination is directly connected
        val directPeers = transportManager.getConnectedNodeIds()
        if (destNodeId in directPeers) {
            val sent = transportManager.sendPacket(packet, destNodeId)
            if (sent) {
                Timber.d("Sent ${packet.packetId} directly to $destNodeId")
                return true
            }
        }

        // 2. Check routing table for known path
        val route = routingTable.getRoute(destNodeId)
        if (route != null && route.nextHopNodeId in directPeers) {
            val sent = transportManager.sendPacket(packet, route.nextHopNodeId)
            if (sent) {
                Timber.d("Sent ${packet.packetId} via route to ${route.nextHopNodeId}")
                return true
            }
        }

        // 3. Flood to all connected peers
        if (directPeers.isNotEmpty()) {
            transportManager.broadcastPacket(packet)
            Timber.d("Flooded ${packet.packetId} to ${directPeers.size} peers")
            return true
        }

        // 4. No peers available — queue for later delivery
        offlineQueue.enqueue(packet)
        Timber.d("Queued ${packet.packetId} for offline delivery")
        emitDiagnostic(RoutingDiagnosticEvent.PacketQueued(packet.packetId, destNodeId))
        // Start Fibonacci retry cycle to reconnect and drain the queue
        queuedMessageHandler.onMessageQueued()
        return false
    }

    /**
     * When a new peer connects, try to deliver queued packets.
     */
    private suspend fun flushOfflineQueue(newPeerNodeId: String) {
        val packets = offlineQueue.getPacketsForDestination(newPeerNodeId)
        packets.forEach { packet ->
            val sent = transportManager.sendPacket(packet, newPeerNodeId)
            if (sent) {
                offlineQueue.remove(packet.packetId)
                Timber.d("Flushed queued packet ${packet.packetId} to $newPeerNodeId")
            }
        }

        // Also try packets with no known route (flood to new peer)
        val unroutable = offlineQueue.getAll()
        unroutable.forEach { packet ->
            if (packet.destinationNodeId != newPeerNodeId) {
                // This new peer might be closer to the destination
                val sent = transportManager.sendPacket(packet, newPeerNodeId)
                if (sent) {
                    Timber.d("Forwarded queued ${packet.packetId} via new peer $newPeerNodeId")
                }
            }
        }
    }

    /**
     * Attempt to flush all queued messages to any connected peers.
     * Returns the number of messages still remaining in the queue.
     */
    private suspend fun flushAllQueued(): Int {
        val connectedPeers = transportManager.getConnectedNodeIds()
        if (connectedPeers.isEmpty()) return offlineQueue.size()

        val allQueued = offlineQueue.getAll()
        var sentCount = 0

        allQueued.forEach { packet ->
            val destNodeId = packet.destinationNodeId

            // Try direct send if destination is connected
            if (destNodeId in connectedPeers) {
                val sent = transportManager.sendPacket(packet, destNodeId)
                if (sent) {
                    offlineQueue.remove(packet.packetId)
                    sentCount++
                    Timber.d("Queue flush: sent ${packet.packetId} directly to $destNodeId")
                    return@forEach
                }
            }

            // Try routing table
            val route = routingTable.getRoute(destNodeId)
            if (route != null && route.nextHopNodeId in connectedPeers) {
                val sent = transportManager.sendPacket(packet, route.nextHopNodeId)
                if (sent) {
                    offlineQueue.remove(packet.packetId)
                    sentCount++
                    Timber.d("Queue flush: sent ${packet.packetId} via route to ${route.nextHopNodeId}")
                    return@forEach
                }
            }

            // Try flooding to any connected peer
            connectedPeers.firstOrNull()?.let { peerId ->
                val sent = transportManager.sendPacket(packet, peerId)
                if (sent) {
                    offlineQueue.markRetry(packet.packetId)
                    sentCount++
                    Timber.d("Queue flush: flooded ${packet.packetId} via $peerId")
                }
            }
        }

        Timber.d("Queue flush: sent $sentCount, remaining ${offlineQueue.size()}")
        return offlineQueue.size()
    }

    /**
     * Send an acknowledgement packet back to the sender.
     */
    private suspend fun sendAck(originalPacket: MeshPacket) {
        val myNodeId = selfNodeId ?: return
        val ack = MeshPacket(
            sourceNodeId = myNodeId,
            destinationNodeId = originalPacket.sourceNodeId,
            packetType = PacketType.ACK,
            encryptedPayload = originalPacket.packetId.toByteArray(), // Reference to original
            nonce = ByteArray(12),
            senderSignature = ByteArray(0), // ACKs don't need signatures
            flags = MeshPacket.FLAG_IS_ACK,
            ttl = originalPacket.hopCount + 2 // Enough TTL to get back
        )
        routePacket(ack)
    }

    /**
     * Learn routes from observed traffic.
     */
    private suspend fun updateRoutingFromPacket(packet: MeshPacket, fromEndpointId: String) {
        val fromNodeId = transportManager.let {
            // Get nodeId for the endpoint that sent us this packet
            it.getConnectedNodeIds().firstOrNull() // Simplified
        } ?: return

        // The source of this packet is reachable via the node that forwarded it
        routingTable.updateRoute(
            RoutingEntry(
                destinationNodeId = packet.sourceNodeId,
                nextHopNodeId = fromNodeId,
                hopCount = packet.hopCount,
                connectionType = ConnectionType.NEARBY_CONNECTIONS
            )
        )
    }

    private suspend fun emitDiagnostic(event: RoutingDiagnosticEvent) {
        _routingEvents.emit(event)
    }
}

/**
 * Diagnostic events for the mesh debugging UI.
 */
sealed interface RoutingDiagnosticEvent {
    data class PacketDelivered(val packetId: String, val from: String, val hops: Int) : RoutingDiagnosticEvent
    data class PacketRelayed(val packetId: String, val from: String, val to: String, val hops: Int) : RoutingDiagnosticEvent
    data class PacketQueued(val packetId: String, val destination: String) : RoutingDiagnosticEvent
    data class PacketExpired(val packetId: String) : RoutingDiagnosticEvent
    data class DuplicateDropped(val packetId: String) : RoutingDiagnosticEvent
    data class RouteMaintenance(val routeCount: Int, val queuedPackets: Int) : RoutingDiagnosticEvent
    data class QueueFlushScheduled(val attempt: Int, val maxAttempts: Int, val delayMs: Long, val queuedCount: Int) : RoutingDiagnosticEvent
    data class QueueFlushCompleted(val attempt: Int, val sentCount: Int, val remainingCount: Int) : RoutingDiagnosticEvent
    data class QueueDrained(val totalAttempts: Int) : RoutingDiagnosticEvent
    data class QueueFlushGaveUp(val totalAttempts: Int, val remainingCount: Int) : RoutingDiagnosticEvent
}
