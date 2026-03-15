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
    val isDelayed: Boolean = false
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
    val isEncrypted: Boolean
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
    val fingerprint: String?
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
