package com.meshwalk.app.domain.model

import java.util.UUID

/**
 * Represents a decrypted, application-level message.
 * This is what the UI layer works with.
 */
data class MeshMessage(
    val messageId: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val senderNodeId: String,
    val content: MessageContent,
    val timestamp: Long = System.currentTimeMillis(),
    val deliveryStatus: DeliveryStatus = DeliveryStatus.PENDING,
    val isIncoming: Boolean,
    val hopCount: Int = 0,
    val expiresAt: Long? = null,
    /** True when the message was received significantly later than it was sent (offline resend). */
    val isDelayed: Boolean = false,
    /** Emoji reactions mapped by reactor nodeId. */
    val reactions: Map<String, String> = emptyMap(),
    /** If this is a reply, the messageId of the original message. */
    val replyToMessageId: String? = null,
    /** Preview text of the replied-to message (cached for display). */
    val replyToPreview: String? = null,
    /** Whether this message is pinned in the conversation. */
    val isPinned: Boolean = false
)

sealed interface MessageContent {
    data class Text(val text: String) : MessageContent
    data class SystemEvent(val event: String) : MessageContent
}

enum class DeliveryStatus {
    PENDING,        // Created, not yet sent
    SENT,           // Handed to transport layer
    RELAYED,        // Confirmed forwarded by at least one relay
    DELIVERED,      // ACK received from recipient
    READ,           // Read receipt received
    FAILED,         // Delivery failed after retries
    EXPIRED         // TTL expired
}
