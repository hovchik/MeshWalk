package com.meshwalk.app.transport.manager

import com.meshwalk.app.domain.model.ConnectionType
import com.meshwalk.app.domain.model.MeshPacket
import com.meshwalk.app.domain.model.PacketType
import com.meshwalk.app.domain.model.PeerNode
import com.meshwalk.app.domain.repository.PeerRepository
import com.meshwalk.app.domain.usecase.TransportManagerPort
import com.meshwalk.app.transport.api.*
import com.meshwalk.app.transport.nearby.NearbyConnectionsTransport
import com.meshwalk.app.transport.ble.BleDiscoveryTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates multiple transport implementations.
 *
 * Strategy:
 * 1. BLE for lightweight, passive discovery (always-on when mesh is active)
 * 2. Nearby Connections for full bidirectional communication
 *
 * The TransportManager:
 * - Merges discovery events from all transports
 * - Routes outgoing data through the best available transport
 * - Maintains the endpoint-to-nodeId mapping
 * - Provides a unified event stream to the routing layer
 */
@Singleton
class TransportManager @Inject constructor(
    private val nearbyTransport: NearbyConnectionsTransport,
    private val bleTransport: BleDiscoveryTransport,
    private val peerRepository: PeerRepository
) : TransportManagerPort {

    companion object {
        /** Magic byte prefix to distinguish advertisement payloads from regular mesh packets. */
        const val ADVERTISEMENT_MAGIC: Byte = 0x4D // 'M' for MeshWalk advertisement
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentAdvertisement: NodeAdvertisement? = null

    // Unified event stream from all transports
    private val _transportEvents = MutableSharedFlow<TransportEvent>(
        extraBufferCapacity = 128
    )
    val transportEvents: SharedFlow<TransportEvent> = _transportEvents

    // Endpoint to transport mapping (accessed from multiple coroutines)
    private val endpointTransportMap = java.util.concurrent.ConcurrentHashMap<String, MeshTransport>()

    // NodeId to endpointId mapping (across transports)
    private val nodeEndpointMap = java.util.concurrent.ConcurrentHashMap<String, String>()

    init {
        // Merge events from all transports
        scope.launch {
            merge(
                nearbyTransport.discoveredEndpoints,
                bleTransport.discoveredEndpoints
            ).collect { event ->
                val consumed = handleTransportEvent(event)
                if (!consumed) {
                    _transportEvents.emit(event)
                }
            }
        }
    }

    /**
     * Start the mesh: begin advertising and discovering on all available transports.
     */
    suspend fun startMesh(advertisement: NodeAdvertisement) {
        currentAdvertisement = advertisement
        startAdvertising()
        startDiscovery()
    }

    /**
     * Stop all mesh activity.
     */
    suspend fun stopMesh() {
        stopAdvertising()
        stopDiscovery()
        nearbyTransport.disconnectAll()
        bleTransport.disconnectAll()
        endpointTransportMap.clear()
        nodeEndpointMap.clear()
    }

    override suspend fun startDiscovery() {
        nearbyTransport.startDiscovery().onFailure {
            Timber.w(it, "Nearby discovery failed, falling back to BLE only")
        }

        if (bleTransport.isAvailable) {
            bleTransport.startDiscovery().onFailure {
                Timber.w(it, "BLE discovery failed")
            }
        }
    }

    override suspend fun stopDiscovery() {
        nearbyTransport.stopDiscovery()
        bleTransport.stopDiscovery()
    }

    override suspend fun startAdvertising() {
        val advert = currentAdvertisement ?: return

        nearbyTransport.startAdvertising(advert).onFailure {
            Timber.w(it, "Nearby advertising failed")
        }

        if (bleTransport.isAvailable) {
            bleTransport.startAdvertising(advert).onFailure {
                Timber.w(it, "BLE advertising failed")
            }
        }
    }

    override suspend fun stopAdvertising() {
        nearbyTransport.stopAdvertising()
        bleTransport.stopAdvertising()
    }

    /**
     * Send a mesh packet to a specific node.
     * Selects the best transport automatically.
     */
    override suspend fun sendPacket(packet: MeshPacket, targetNodeId: String): Boolean {
        val serialized = serializePacket(packet)
        val endpointId = nodeEndpointMap[targetNodeId]

        if (endpointId != null) {
            // We have a direct connection
            val transport = endpointTransportMap[endpointId]
            if (transport != null) {
                val result = transport.sendData(endpointId, serialized)
                if (result.isSuccess) {
                    Timber.d("Packet ${packet.packetId} sent to $targetNodeId via ${transport.transportType}")
                    return true
                }
            }
        }

        // Try Nearby directly by nodeId
        val nearbyEndpoint = nearbyTransport.getEndpointForNodeId(targetNodeId)
        if (nearbyEndpoint != null) {
            val result = nearbyTransport.sendData(nearbyEndpoint, serialized)
            if (result.isSuccess) {
                Timber.d("Packet ${packet.packetId} sent to $targetNodeId via Nearby")
                return true
            }
        }

        Timber.w("No route to $targetNodeId for packet ${packet.packetId}")
        return false
    }

    /**
     * Broadcast a packet to all directly connected peers.
     * Used for flooding-based routing or discovery announcements.
     */
    suspend fun broadcastPacket(packet: MeshPacket, excludeNodeId: String? = null) {
        val serialized = serializePacket(packet)
        val connected = nearbyTransport.getConnectedEndpoints()

        connected.forEach { endpointId ->
            val nodeId = nearbyTransport.getNodeIdForEndpoint(endpointId)
            if (nodeId != excludeNodeId) {
                nearbyTransport.sendData(endpointId, serialized).onFailure {
                    Timber.w("Failed to broadcast to $endpointId")
                }
            }
        }
    }

    /**
     * Get all currently connected node IDs.
     */
    fun getConnectedNodeIds(): Set<String> {
        return nodeEndpointMap.keys.toSet()
    }

    // -- Event handling --

    /**
     * Handle a transport event. Returns true if the event was consumed
     * and should NOT be forwarded to the routing layer.
     */
    private suspend fun handleTransportEvent(event: TransportEvent): Boolean {
        when (event) {
            is TransportEvent.EndpointDiscovered -> {
                event.nodeId?.let { nodeId ->
                    nodeEndpointMap[nodeId] = event.endpointId
                    endpointTransportMap[event.endpointId] = when (event.transportType) {
                        ConnectionType.BLE -> bleTransport
                        else -> nearbyTransport
                    }

                    // Update peer repository
                    peerRepository.upsertPeer(
                        PeerNode(
                            nodeId = nodeId,
                            displayName = event.endpointName.takeIf { it != "MeshWalk Node" },
                            identityType = com.meshwalk.app.domain.model.IdentityType.NAMED,
                            publicSigningKey = null,
                            publicExchangeKey = null,
                            connectionType = event.transportType,
                            hopCount = 0,
                            signalStrength = event.signalStrength,
                            isConnected = false
                        )
                    )
                }
            }

            is TransportEvent.Connected -> {
                event.nodeId?.let { nodeId ->
                    // For incoming connections, EndpointDiscovered may not have fired,
                    // so ensure the endpoint mapping exists before sending the advertisement.
                    if (nodeId !in nodeEndpointMap) {
                        nodeEndpointMap[nodeId] = event.endpointId
                        endpointTransportMap[event.endpointId] = nearbyTransport
                    }

                    // Ensure the peer exists in the repository (may be missing for
                    // incoming connections where EndpointDiscovered was never emitted).
                    val peer = peerRepository.getPeer(nodeId)
                    if (peer != null) {
                        peerRepository.upsertPeer(peer.copy(isConnected = true))
                    } else {
                        peerRepository.upsertPeer(
                            PeerNode(
                                nodeId = nodeId,
                                displayName = null,
                                identityType = com.meshwalk.app.domain.model.IdentityType.NAMED,
                                publicSigningKey = null,
                                publicExchangeKey = null,
                                connectionType = ConnectionType.NEARBY_CONNECTIONS,
                                hopCount = 0,
                                signalStrength = null,
                                isConnected = true
                            )
                        )
                    }

                    // Send our advertisement to the newly connected peer so they get
                    // our publicExchangeKey (needed for session establishment).
                    currentAdvertisement?.let { advert ->
                        val endpointId = nodeEndpointMap[nodeId] ?: return@let
                        val transport = endpointTransportMap[endpointId] ?: return@let
                        val advertData = advert.serialize()
                        // Prefix with a magic byte to distinguish from regular packets
                        val payload = ByteArray(1 + advertData.size)
                        payload[0] = ADVERTISEMENT_MAGIC
                        advertData.copyInto(payload, 1)
                        transport.sendData(endpointId, payload).onFailure {
                            Timber.w("Failed to send advertisement to $nodeId")
                        }
                        Timber.d("Sent advertisement to $nodeId (exchangeKey included)")
                    }
                }
            }

            is TransportEvent.Disconnected -> {
                val nodeId = nearbyTransport.getNodeIdForEndpoint(event.endpointId)
                nodeId?.let {
                    nodeEndpointMap.remove(it)
                    peerRepository.getPeer(it)?.let { peer ->
                        peerRepository.upsertPeer(peer.copy(isConnected = false))
                    }
                }
                endpointTransportMap.remove(event.endpointId)
            }

            is TransportEvent.EndpointLost -> {
                val nodeId = nearbyTransport.getNodeIdForEndpoint(event.endpointId)
                nodeId?.let { nodeEndpointMap.remove(it) }
                endpointTransportMap.remove(event.endpointId)
            }

            is TransportEvent.DataReceived -> {
                // Check if this is an advertisement payload (magic byte prefix)
                if (event.data.isNotEmpty() && event.data[0] == ADVERTISEMENT_MAGIC) {
                    val advertData = event.data.copyOfRange(1, event.data.size)
                    try {
                        val advert = NodeAdvertisement.deserialize(advertData)
                        if (advert != null) {
                            val existingPeer = peerRepository.getPeer(advert.nodeId)
                            if (existingPeer != null) {
                                peerRepository.upsertPeer(
                                    existingPeer.copy(
                                        publicExchangeKey = advert.publicExchangeKey,
                                        displayName = advert.displayName ?: existingPeer.displayName
                                    )
                                )
                            } else {
                                // Peer record may be missing for incoming connections
                                // where EndpointDiscovered was never emitted.
                                peerRepository.upsertPeer(
                                    PeerNode(
                                        nodeId = advert.nodeId,
                                        displayName = advert.displayName,
                                        identityType = com.meshwalk.app.domain.model.IdentityType.NAMED,
                                        publicSigningKey = null,
                                        publicExchangeKey = advert.publicExchangeKey,
                                        connectionType = ConnectionType.NEARBY_CONNECTIONS,
                                        hopCount = 0,
                                        signalStrength = null,
                                        isConnected = true
                                    )
                                )
                            }
                            Timber.d("Updated peer ${advert.nodeId.take(8)} with exchange key from advertisement")
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to parse advertisement from ${event.endpointId}")
                    }
                    return true // Consumed: don't forward advertisement to routing layer
                }
            }

            else -> { /* handled by routing layer */ }
        }
        return false
    }

    // -- Packet serialization --

    fun serializePacket(packet: MeshPacket): ByteArray {
        val idBytes = packet.packetId.toByteArray(Charsets.UTF_8)
        val srcBytes = packet.sourceNodeId.toByteArray(Charsets.UTF_8)
        val dstBytes = packet.destinationNodeId.toByteArray(Charsets.UTF_8)

        val headerSize = 4 + idBytes.size +
                4 + srcBytes.size +
                4 + dstBytes.size +
                4 + 4 + 4 + 8 + 4 +  // type, ttl, hop, timestamp, flags
                4 + packet.nonce.size +
                4 + packet.senderSignature.size +
                4 + packet.encryptedPayload.size

        val buffer = ByteBuffer.allocate(headerSize)
        buffer.putInt(idBytes.size).put(idBytes)
        buffer.putInt(srcBytes.size).put(srcBytes)
        buffer.putInt(dstBytes.size).put(dstBytes)
        buffer.putInt(packet.packetType.ordinal)
        buffer.putInt(packet.ttl)
        buffer.putInt(packet.hopCount)
        buffer.putLong(packet.timestamp)
        buffer.putInt(packet.flags)
        buffer.putInt(packet.nonce.size).put(packet.nonce)
        buffer.putInt(packet.senderSignature.size).put(packet.senderSignature)
        buffer.putInt(packet.encryptedPayload.size).put(packet.encryptedPayload)

        return buffer.array()
    }

    fun deserializePacket(data: ByteArray): MeshPacket? {
        return try {
            val buffer = ByteBuffer.wrap(data)

            fun readBytes(): ByteArray {
                val len = buffer.getInt()
                return ByteArray(len).also { buffer.get(it) }
            }

            val id = String(readBytes(), Charsets.UTF_8)
            val src = String(readBytes(), Charsets.UTF_8)
            val dst = String(readBytes(), Charsets.UTF_8)
            val type = PacketType.entries[buffer.getInt()]
            val ttl = buffer.getInt()
            val hop = buffer.getInt()
            val timestamp = buffer.getLong()
            val flags = buffer.getInt()
            val nonce = readBytes()
            val signature = readBytes()
            val payload = readBytes()

            MeshPacket(
                packetId = id,
                sourceNodeId = src,
                destinationNodeId = dst,
                packetType = type,
                ttl = ttl,
                hopCount = hop,
                timestamp = timestamp,
                encryptedPayload = payload,
                nonce = nonce,
                senderSignature = signature,
                flags = flags
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to deserialize packet")
            null
        }
    }
}
