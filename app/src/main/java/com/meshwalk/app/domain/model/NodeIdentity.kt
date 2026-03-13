package com.meshwalk.app.domain.model

import java.util.UUID

/**
 * Represents a node's identity in the mesh network.
 * Every device running MeshWalk has exactly one active identity at a time.
 */
data class NodeIdentity(
    val nodeId: String = UUID.randomUUID().toString(),
    val displayName: String?,
    val identityType: IdentityType,
    val publicSigningKey: ByteArray,
    val publicExchangeKey: ByteArray,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null
) {
    val isAnonymous: Boolean get() = identityType == IdentityType.ANONYMOUS
    val isTemporary: Boolean get() = identityType == IdentityType.TEMPORARY
    val isExpired: Boolean get() = expiresAt != null && System.currentTimeMillis() > expiresAt

    /**
     * Short fingerprint for contact verification (safety number).
     * Uses first 8 bytes of signing key hash for human-readable verification.
     */
    val fingerprint: String
        get() = publicSigningKey.take(8)
            .joinToString(":") { "%02X".format(it) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NodeIdentity) return false
        return nodeId == other.nodeId
    }

    override fun hashCode(): Int = nodeId.hashCode()
}

enum class IdentityType {
    NAMED,
    ANONYMOUS,
    TEMPORARY
}
