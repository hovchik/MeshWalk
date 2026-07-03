package com.meshwalk.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "identities")
data class IdentityEntity(
    @PrimaryKey val nodeId: String,
    val displayName: String?,
    val identityType: String,
    val publicSigningKey: ByteArray,
    val publicExchangeKey: ByteArray,
    val createdAt: Long,
    val expiresAt: Long?,
    val isActive: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentityEntity) return false
        return nodeId == other.nodeId
    }
    override fun hashCode(): Int = nodeId.hashCode()
}

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val senderNodeId: String,
    val contentType: String,   // "text", "system"
    val contentText: String,
    val timestamp: Long,
    val deliveryStatus: String,
    val isIncoming: Boolean,
    val hopCount: Int,
    val expiresAt: Long?,
    @ColumnInfo(defaultValue = "0")
    val isDelayed: Boolean = false,
    @ColumnInfo(defaultValue = "")
    val reactionsJson: String = "",
    @ColumnInfo(defaultValue = "")
    val replyToMessageId: String? = null,
    @ColumnInfo(defaultValue = "")
    val replyToPreview: String? = null,
    @ColumnInfo(defaultValue = "0")
    val isPinned: Boolean = false
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val conversationId: String,
    val type: String,
    val title: String?,
    val participants: String,  // JSON list of node IDs
    val peerDisplayName: String?,  // cached peer name for direct conversations
    val createdAt: Long,
    val lastMessageAt: Long?,
    val lastMessagePreview: String?,
    val unreadCount: Int,
    val isEncrypted: Boolean,
    @ColumnInfo(defaultValue = "")
    val nickname: String? = null,
    @ColumnInfo(defaultValue = "0")
    val isFavorite: Boolean = false,
    val messageTtlMs: Long? = null
)

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val nodeId: String,
    val displayName: String?,
    val identityType: String,
    val publicSigningKey: ByteArray?,
    val publicExchangeKey: ByteArray?,
    val connectionType: String,
    val hopCount: Int,
    val signalStrength: Int?,
    val lastSeen: Long,
    val isConnected: Boolean,
    val relayCapable: Boolean,
    val fingerprint: String?,
    @ColumnInfo(defaultValue = "0")
    val isVerified: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerEntity) return false
        return nodeId == other.nodeId
    }
    override fun hashCode(): Int = nodeId.hashCode()
}

@Entity(tableName = "routing_entries")
data class RoutingEntryEntity(
    @PrimaryKey val destinationNodeId: String,
    val nextHopNodeId: String,
    val hopCount: Int,
    val connectionType: String,
    val lastUpdated: Long,
    val reliability: Float,
    val latencyMs: Long?
)

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val groupId: String,
    val name: String,
    val creatorNodeId: String,
    val membersJson: String,   // JSON serialized member list
    val groupType: String,
    val createdAt: Long,
    val expiresAt: Long?,
    val version: Long,
    val maxMembers: Int
)

@Entity(
    tableName = "sender_keys",
    primaryKeys = ["groupId", "senderNodeId"]
)
data class SenderKeyEntity(
    val groupId: String,
    val senderNodeId: String,
    val chainKey: ByteArray,
    val iteration: Int,
    val createdAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SenderKeyEntity) return false
        return groupId == other.groupId && senderNodeId == other.senderNodeId
    }
    override fun hashCode(): Int = 31 * groupId.hashCode() + senderNodeId.hashCode()
}

@Entity(tableName = "blocked_peers")
data class BlockedPeerEntity(
    @PrimaryKey val nodeId: String,
    val displayName: String?,
    val blockedAt: Long
)

/**
 * Encrypted archive of a temporary group after its lifetime expired.
 * The archive is encrypted using the admin's public key (ECIES: ECDH + AES-GCM).
 * Only the admin can decrypt it using their private exchange key.
 */
@Entity(tableName = "group_archives")
data class GroupArchiveEntity(
    @PrimaryKey val groupId: String,
    val groupName: String,
    val adminNodeId: String,
    val encryptedData: ByteArray,       // ECIES-encrypted JSON of messages
    val ephemeralPublicKey: ByteArray,   // Ephemeral public key for ECIES decryption
    val nonce: ByteArray,               // AES-GCM nonce
    val memberCount: Int,
    val messageCount: Int,
    val createdAt: Long,                // When the group was created
    val archivedAt: Long,               // When the group was archived
    val expiresAt: Long                 // When the group expired
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupArchiveEntity) return false
        return groupId == other.groupId
    }
    override fun hashCode(): Int = groupId.hashCode()
}
