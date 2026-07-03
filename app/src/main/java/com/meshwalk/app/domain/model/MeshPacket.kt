package com.meshwalk.app.domain.model

import java.util.UUID

/**
 * Wire-level mesh packet. This is what travels over the transport layer.
 * Relay nodes see only the envelope metadata, not the encrypted payload.
 *
 * Structure:
 * [PacketHeader (cleartext routing metadata)]
 * [EncryptedPayload (opaque to relays)]
 * [Signature (integrity check)]
 */
data class MeshPacket(
    val packetId: String = UUID.randomUUID().toString(),
    val sourceNodeId: String,
    val destinationNodeId: String,    // Target node or group pseudo-ID
    val packetType: PacketType,
    val ttl: Int = DEFAULT_TTL,
    val hopCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val encryptedPayload: ByteArray,
    val nonce: ByteArray,              // Encryption nonce
    val senderSignature: ByteArray,    // Ed25519 signature over header+payload
    val flags: Int = 0
) {
    companion object {
        const val DEFAULT_TTL = 7
        const val MAX_TTL = 15
        // Transport-level fragmentation (PacketFramer) splits packets that
        // exceed the per-payload limit of the underlying transport, so this
        // cap only bounds total packet size (e.g. image attachments).
        const val MAX_PAYLOAD_SIZE = 256_000

        // Flags
        const val FLAG_ACK_REQUESTED = 0x01
        const val FLAG_IS_ACK = 0x02
        const val FLAG_IS_RELAY = 0x04
        const val FLAG_STORE_AND_FORWARD = 0x08
        const val FLAG_GROUP_MESSAGE = 0x10
        const val FLAG_KEY_EXCHANGE = 0x20
        const val FLAG_READ_RECEIPT = 0x40
        /** Experimental: emergency SOS broadcast — bypass normal TTL limits. */
        const val FLAG_SOS = 0x80
        /** Experimental: anonymous bulletin post (no verified sender identity). */
        const val FLAG_ANONYMOUS = 0x100
    }

    val isAckRequested: Boolean get() = flags and FLAG_ACK_REQUESTED != 0
    val isAck: Boolean get() = flags and FLAG_IS_ACK != 0
    val isRelay: Boolean get() = flags and FLAG_IS_RELAY != 0
    val isStoreAndForward: Boolean get() = flags and FLAG_STORE_AND_FORWARD != 0
    val isGroupMessage: Boolean get() = flags and FLAG_GROUP_MESSAGE != 0
    val isKeyExchange: Boolean get() = flags and FLAG_KEY_EXCHANGE != 0
    val isReadReceipt: Boolean get() = flags and FLAG_READ_RECEIPT != 0
    val isSos: Boolean get() = flags and FLAG_SOS != 0
    val isAnonymous: Boolean get() = flags and FLAG_ANONYMOUS != 0
    val isExpired: Boolean get() = ttl <= 0

    /**
     * Creates a new packet with decremented TTL and incremented hop count for forwarding.
     */
    fun forRelay(): MeshPacket = copy(
        ttl = ttl - 1,
        hopCount = hopCount + 1,
        flags = flags or FLAG_IS_RELAY
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeshPacket) return false
        return packetId == other.packetId
    }

    override fun hashCode(): Int = packetId.hashCode()
}

enum class PacketType {
    MESSAGE,          // Chat message
    ACK,              // Delivery acknowledgement
    KEY_EXCHANGE,     // Key agreement handshake
    DISCOVERY,        // Node announcement/discovery
    ROUTE_UPDATE,     // Routing table update
    GROUP_CONTROL,    // Group membership changes
    PING,             // Keepalive / reachability probe
    STORE_FORWARD,    // Store-and-forward delivery
    READ_RECEIPT,     // Read receipt notification
    REACTION,         // Message reaction
    // Experimental packet types:
    SOS,              // Emergency SOS broadcast (flag-bypassed TTL)
    BULLETIN,         // Anonymous bulletin-board post (broadcast)
    GOSSIP_DIGEST,    // Bloom-filter digest for opportunistic sync
    REPUTATION_TOKEN, // Signed "I saw this peer relay my packet" attestation
    DROP_ZONE         // Geofenced drop-zone announcement
}
