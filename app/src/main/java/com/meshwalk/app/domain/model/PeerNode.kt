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
    val fingerprint: String? = null,
    /** True once the user has manually verified this peer's key fingerprint. */
    val isVerified: Boolean = false
) {
    val isDirect: Boolean get() = hopCount == 0

    /**
     * Human-comparable safety number derived from the peer's exchange key.
     * Both sides display the same blocks when the keys match (no MITM).
     */
    val safetyNumber: String?
        get() = publicExchangeKey?.let { key ->
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key)
            digest.take(12).chunked(2).joinToString(" ") { pair ->
                "%02X%02X".format(pair[0], pair[1])
            }
        }

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
