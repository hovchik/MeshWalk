package com.meshwalk.app.domain.model

import java.util.UUID

/**
 * Represents a conversation - either 1:1 or group.
 */
data class Conversation(
    val conversationId: String = UUID.randomUUID().toString(),
    val type: ConversationType,
    val title: String?,
    val participants: List<String>, // node IDs
    val peerDisplayName: String? = null, // cached peer name for DIRECT conversations
    val createdAt: Long = System.currentTimeMillis(),
    val lastMessageAt: Long? = null,
    val lastMessagePreview: String? = null,
    val unreadCount: Int = 0,
    val isEncrypted: Boolean = true,
    /** User-assigned nickname for the peer (DIRECT conversations only). */
    val nickname: String? = null,
    /** Whether this conversation is marked as a favorite. */
    val isFavorite: Boolean = false
) {
    /** Display title: nickname, group name, cached peer name, or truncated node ID. */
    val displayTitle: String
        get() = nickname
            ?: title
            ?: peerDisplayName
            ?: participants.firstOrNull()?.take(8)
            ?: "Unknown"
}

enum class ConversationType {
    DIRECT,          // 1:1 chat
    GROUP,           // Persistent group
    BROADCAST,       // One-to-many (sender only sends)
    TEMPORARY_GROUP  // Ad-hoc temporary group that auto-expires
}
