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
    val createdAt: Long = System.currentTimeMillis(),
    val lastMessageAt: Long? = null,
    val lastMessagePreview: String? = null,
    val unreadCount: Int = 0,
    val isEncrypted: Boolean = true
)

enum class ConversationType {
    DIRECT,          // 1:1 chat
    GROUP,           // Persistent group
    BROADCAST,       // One-to-many (sender only sends)
    TEMPORARY_GROUP  // Ad-hoc temporary group that auto-expires
}
