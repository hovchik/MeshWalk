package com.meshwalk.app.transport.api

import com.meshwalk.app.domain.model.ConnectionType
import com.meshwalk.app.domain.model.MeshPacket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Transport-agnostic interface for mesh connectivity.
 * Implementations handle specific technologies (Nearby, BLE, Wi-Fi Direct).
 * The routing layer sits above this and doesn't care about transport details.
 */
interface MeshTransport {
    val transportType: ConnectionType
    val isAvailable: Boolean

    /** Discovered endpoints as a flow */
    val discoveredEndpoints: Flow<TransportEvent>

    /** Start advertising this node's presence */
    suspend fun startAdvertising(nodeInfo: NodeAdvertisement): Result<Unit>

    /** Stop advertising */
    suspend fun stopAdvertising()

    /** Start scanning for nearby nodes */
    suspend fun startDiscovery(): Result<Unit>

    /** Stop scanning */
    suspend fun stopDiscovery()

    /** Send raw data to a connected endpoint */
    suspend fun sendData(endpointId: String, data: ByteArray): Result<Unit>

    /** Accept an incoming connection */
    suspend fun acceptConnection(endpointId: String): Result<Unit>

    /** Reject an incoming connection */
    suspend fun rejectConnection(endpointId: String)

    /** Disconnect from an endpoint */
    suspend fun disconnect(endpointId: String)

    /** Disconnect from all endpoints */
    suspend fun disconnectAll()

    /** Get currently connected endpoint IDs */
    fun getConnectedEndpoints(): Set<String>
}

/**
 * Events emitted by the transport layer.
 */
sealed interface TransportEvent {
    /** A new endpoint was discovered nearby */
    data class EndpointDiscovered(
        val endpointId: String,
        val endpointName: String,
        val nodeId: String?,
        val transportType: ConnectionType,
        val signalStrength: Int? = null
    ) : TransportEvent

    /** An endpoint is no longer visible */
    data class EndpointLost(val endpointId: String) : TransportEvent

    /** Connection request received from a peer */
    data class ConnectionRequested(
        val endpointId: String,
        val endpointName: String,
        val authenticationDigits: String?
    ) : TransportEvent

    /** Connection established */
    data class Connected(val endpointId: String, val nodeId: String?) : TransportEvent

    /** Connection lost */
    data class Disconnected(val endpointId: String) : TransportEvent

    /** Data received from a connected peer */
    data class DataReceived(
        val endpointId: String,
        val data: ByteArray
    ) : TransportEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DataReceived) return false
            return endpointId == other.endpointId && data.contentEquals(other.data)
        }
        override fun hashCode(): Int = endpointId.hashCode()
    }

    /** Transfer progress update */
    data class TransferProgress(
        val endpointId: String,
        val bytesTransferred: Long,
        val totalBytes: Long
    ) : TransportEvent

    /** Error occurred */
    data class Error(
        val endpointId: String?,
        val error: TransportError
    ) : TransportEvent
}

/**
 * Node advertisement data broadcast during discovery.
 */
data class NodeAdvertisement(
    val nodeId: String,
    val displayName: String?,
    val publicExchangeKey: ByteArray,
    val capabilities: Set<String> = setOf("relay", "store-forward"),
    val protocolVersion: Int = 1
) {
    /**
     * Serialize to a compact format for BLE/Nearby advertisement.
     */
    fun serialize(): ByteArray {
        val nameBytes = (displayName ?: "anonymous").toByteArray(Charsets.UTF_8)
        val idBytes = nodeId.toByteArray(Charsets.UTF_8)
        // Simple format: [idLen][id][nameLen][name][keyLen][key]
        val buffer = java.nio.ByteBuffer.allocate(
            4 + idBytes.size + 4 + nameBytes.size + 4 + publicExchangeKey.size
        )
        buffer.putInt(idBytes.size)
        buffer.put(idBytes)
        buffer.putInt(nameBytes.size)
        buffer.put(nameBytes)
        buffer.putInt(publicExchangeKey.size)
        buffer.put(publicExchangeKey)
        return buffer.array()
    }

    companion object {
        fun deserialize(data: ByteArray): NodeAdvertisement? {
            return try {
                val buffer = java.nio.ByteBuffer.wrap(data)
                val idLen = buffer.getInt()
                val idBytes = ByteArray(idLen).also { buffer.get(it) }
                val nameLen = buffer.getInt()
                val nameBytes = ByteArray(nameLen).also { buffer.get(it) }
                val keyLen = buffer.getInt()
                val keyBytes = ByteArray(keyLen).also { buffer.get(it) }

                NodeAdvertisement(
                    nodeId = String(idBytes, Charsets.UTF_8),
                    displayName = String(nameBytes, Charsets.UTF_8).takeIf { it != "anonymous" },
                    publicExchangeKey = keyBytes
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NodeAdvertisement) return false
        return nodeId == other.nodeId
    }

    override fun hashCode(): Int = nodeId.hashCode()
}

sealed class TransportError(val message: String) {
    class ConnectionFailed(msg: String) : TransportError(msg)
    class SendFailed(msg: String) : TransportError(msg)
    class DiscoveryFailed(msg: String) : TransportError(msg)
    class AdvertisingFailed(msg: String) : TransportError(msg)
    class Unavailable(msg: String) : TransportError(msg)
}
