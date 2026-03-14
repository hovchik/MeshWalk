package com.meshwalk.app.transport.nearby

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.meshwalk.app.domain.model.ConnectionType
import com.meshwalk.app.transport.api.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Primary transport using Google Nearby Connections API.
 *
 * Uses P2P_CLUSTER strategy for mesh-like multi-device connectivity.
 * Handles both Bluetooth and Wi-Fi automatically under the hood.
 *
 * Limitations:
 * - Requires Google Play Services
 * - P2P_CLUSTER: each device can connect to multiple peers
 * - Maximum practical connections: ~5-8 simultaneous (hardware dependent)
 * - Range: BT ~10-30m, WiFi ~50-200m (auto-selected by API)
 */
@Singleton
class NearbyConnectionsTransport @Inject constructor(
    @ApplicationContext private val context: Context
) : MeshTransport {

    companion object {
        private const val SERVICE_ID = "com.meshwalk.app.mesh"
        private val STRATEGY = Strategy.P2P_CLUSTER
        private const val NAME_SEPARATOR = "\u001F" // Unit separator for encoding nodeId in name
    }

    override val transportType = ConnectionType.NEARBY_CONNECTIONS
    override var isAvailable: Boolean = true
        private set

    private val _events = MutableSharedFlow<TransportEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val discoveredEndpoints: SharedFlow<TransportEvent> = _events

    private val connectionsClient by lazy {
        Nearby.getConnectionsClient(context)
    }

    // Track endpoint -> nodeId mapping
    private val endpointNodeMap = mutableMapOf<String, String>()
    private val connectedEndpoints = mutableSetOf<String>()
    private var currentAdvertisement: NodeAdvertisement? = null

    // -- Discovery --

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Timber.d("Endpoint found: $endpointId, name=${info.endpointName}")

            // Parse nodeId and display name from encoded advertising name
            val (displayName, nodeId) = parseEncodedName(info.endpointName)
            if (nodeId != null) {
                endpointNodeMap[endpointId] = nodeId
            }

            _events.tryEmit(
                TransportEvent.EndpointDiscovered(
                    endpointId = endpointId,
                    endpointName = displayName,
                    nodeId = nodeId,
                    transportType = ConnectionType.NEARBY_CONNECTIONS
                )
            )

            // Auto-connect to discovered endpoints for mesh operation
            requestConnection(endpointId)
        }

        override fun onEndpointLost(endpointId: String) {
            Timber.d("Endpoint lost: $endpointId")
            endpointNodeMap.remove(endpointId)
            _events.tryEmit(TransportEvent.EndpointLost(endpointId))
        }
    }

    // -- Connection lifecycle --

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Timber.d("Connection initiated: $endpointId, name=${info.endpointName}")

            // Parse nodeId from connecting peer's encoded name
            val (displayName, nodeId) = parseEncodedName(info.endpointName)
            if (nodeId != null && !endpointNodeMap.containsKey(endpointId)) {
                endpointNodeMap[endpointId] = nodeId
            }

            _events.tryEmit(
                TransportEvent.ConnectionRequested(
                    endpointId = endpointId,
                    endpointName = displayName,
                    authenticationDigits = info.authenticationDigits
                )
            )

            // Auto-accept for mesh operation (authenticated via our crypto layer)
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { e ->
                    Timber.e(e, "Failed to accept connection from $endpointId")
                }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Timber.d("Connected to: $endpointId")
                    connectedEndpoints.add(endpointId)
                    _events.tryEmit(
                        TransportEvent.Connected(endpointId, endpointNodeMap[endpointId])
                    )
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Timber.d("Connection rejected: $endpointId")
                    _events.tryEmit(
                        TransportEvent.Error(endpointId, TransportError.ConnectionFailed("Rejected"))
                    )
                }
                else -> {
                    Timber.w("Connection failed: $endpointId, status=${result.status}")
                    _events.tryEmit(
                        TransportEvent.Error(
                            endpointId,
                            TransportError.ConnectionFailed("Status: ${result.status.statusCode}")
                        )
                    )
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Timber.d("Disconnected: $endpointId")
            connectedEndpoints.remove(endpointId)
            _events.tryEmit(TransportEvent.Disconnected(endpointId))
        }
    }

    // -- Payload handling --

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    payload.asBytes()?.let { data ->
                        Timber.d("Data received from $endpointId: ${data.size} bytes")
                        _events.tryEmit(TransportEvent.DataReceived(endpointId, data))
                    }
                }
                else -> {
                    Timber.w("Unsupported payload type from $endpointId: ${payload.type}")
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.IN_PROGRESS) {
                _events.tryEmit(
                    TransportEvent.TransferProgress(
                        endpointId,
                        update.bytesTransferred,
                        update.totalBytes
                    )
                )
            }
        }
    }

    // -- MeshTransport implementation --

    override suspend fun startAdvertising(nodeInfo: NodeAdvertisement): Result<Unit> {
        currentAdvertisement = nodeInfo
        return suspendCancellableCoroutine { cont ->
            val options = AdvertisingOptions.Builder()
                .setStrategy(STRATEGY)
                .build()

            // Encode nodeId in the name so discoverers can identify us
            val encodedName = encodeAdvertisingName(nodeInfo)

            connectionsClient.startAdvertising(
                encodedName,
                SERVICE_ID,
                connectionLifecycleCallback,
                options
            ).addOnSuccessListener {
                Timber.d("Advertising started")
                cont.resume(Result.success(Unit))
            }.addOnFailureListener { e ->
                Timber.e(e, "Advertising failed")
                cont.resume(Result.failure(e))
            }
        }
    }

    override suspend fun stopAdvertising() {
        connectionsClient.stopAdvertising()
        currentAdvertisement = null
        Timber.d("Advertising stopped")
    }

    override suspend fun startDiscovery(): Result<Unit> {
        return suspendCancellableCoroutine { cont ->
            val options = DiscoveryOptions.Builder()
                .setStrategy(STRATEGY)
                .build()

            connectionsClient.startDiscovery(
                SERVICE_ID,
                endpointDiscoveryCallback,
                options
            ).addOnSuccessListener {
                Timber.d("Discovery started")
                cont.resume(Result.success(Unit))
            }.addOnFailureListener { e ->
                Timber.e(e, "Discovery failed")
                cont.resume(Result.failure(e))
            }
        }
    }

    override suspend fun stopDiscovery() {
        connectionsClient.stopDiscovery()
        Timber.d("Discovery stopped")
    }

    override suspend fun sendData(endpointId: String, data: ByteArray): Result<Unit> {
        return try {
            val payload = Payload.fromBytes(data)
            connectionsClient.sendPayload(endpointId, payload)
            Timber.d("Sent ${data.size} bytes to $endpointId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to send data to $endpointId")
            Result.failure(e)
        }
    }

    override suspend fun acceptConnection(endpointId: String): Result<Unit> {
        return try {
            connectionsClient.acceptConnection(endpointId, payloadCallback)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun rejectConnection(endpointId: String) {
        connectionsClient.rejectConnection(endpointId)
    }

    override suspend fun disconnect(endpointId: String) {
        connectionsClient.disconnectFromEndpoint(endpointId)
        connectedEndpoints.remove(endpointId)
    }

    override suspend fun disconnectAll() {
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
        endpointNodeMap.clear()
    }

    override fun getConnectedEndpoints(): Set<String> = connectedEndpoints.toSet()

    // -- Helpers --

    private fun requestConnection(endpointId: String) {
        val name = currentAdvertisement?.let { encodeAdvertisingName(it) } ?: "MeshWalk Node"
        connectionsClient.requestConnection(name, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener {
                Timber.d("Connection requested to $endpointId")
            }
            .addOnFailureListener { e ->
                Timber.w(e, "Failed to request connection to $endpointId")
            }
    }

    /**
     * Resolve an endpointId to a nodeId.
     */
    fun getNodeIdForEndpoint(endpointId: String): String? = endpointNodeMap[endpointId]

    /**
     * Resolve a nodeId to an endpointId (for routing).
     */
    fun getEndpointForNodeId(nodeId: String): String? {
        return endpointNodeMap.entries.firstOrNull { it.value == nodeId }?.key
    }

    /**
     * Encode display name and nodeId into a single string for Nearby advertising.
     * Format: "displayName{SEPARATOR}nodeId"
     */
    private fun encodeAdvertisingName(advert: NodeAdvertisement): String {
        val displayName = advert.displayName ?: "MeshWalk Node"
        return "$displayName$NAME_SEPARATOR${advert.nodeId}"
    }

    /**
     * Parse an encoded advertising name back into (displayName, nodeId).
     */
    private fun parseEncodedName(encodedName: String): Pair<String, String?> {
        val parts = encodedName.split(NAME_SEPARATOR, limit = 2)
        return if (parts.size == 2) {
            Pair(parts[0], parts[1])
        } else {
            Pair(encodedName, null)
        }
    }
}
