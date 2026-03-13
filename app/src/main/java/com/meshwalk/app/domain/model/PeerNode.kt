package com.meshwalk.app.domain.model

/**
 * Represents a discovered peer node in the mesh network.
 */
data class PeerNode(
    val nodeId: String,
    val displayName: String?,
    val identityType: IdentityType,
    val publicSigningKey: ByteArray?,
    val publicExchangeKey: ByteArray?,
    val connectionType: ConnectionType,
    val hopCount: Int,              // 0 = direct, 1+ = via relay
    val signalStrength: Int? = null, // RSSI if available
    val lastSeen: Long = System.currentTimeMillis(),
    val isConnected: Boolean = false,
    val relayCapable: Boolean = true,
    val fingerprint: String? = null
) {
    val isDirect: Boolean get() = hopCount == 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerNode) return false
        return nodeId == other.nodeId
    }

    override fun hashCode(): Int = nodeId.hashCode()
}

enum class ConnectionType {
    BLE,
    BLUETOOTH_CLASSIC,
    WIFI_DIRECT,
    NEARBY_CONNECTIONS,
    UNKNOWN
}
