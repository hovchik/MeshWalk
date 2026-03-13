package com.meshwalk.app.domain.model

import java.util.UUID

/**
 * Group information for mesh group communication.
 */
data class GroupInfo(
    val groupId: String = UUID.randomUUID().toString(),
    val name: String,
    val creatorNodeId: String,
    val members: List<GroupMember>,
    val groupType: ConversationType,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null,        // For temporary groups
    val version: Long = 1,               // Membership version for sync
    val maxMembers: Int = 50
) {
    val isTemporary: Boolean get() = groupType == ConversationType.TEMPORARY_GROUP
    val isFull: Boolean get() = members.size >= maxMembers
}

data class GroupMember(
    val nodeId: String,
    val displayName: String?,
    val role: GroupRole,
    val joinedAt: Long = System.currentTimeMillis(),
    val senderKeyDistributed: Boolean = false
)

enum class GroupRole {
    ADMIN,
    MEMBER
}

/**
 * Group membership change event for synchronization.
 */
data class GroupMembershipChange(
    val groupId: String,
    val changeType: MembershipChangeType,
    val nodeId: String,
    val initiatedBy: String,
    val timestamp: Long = System.currentTimeMillis(),
    val version: Long
)

enum class MembershipChangeType {
    JOINED,
    LEFT,
    REMOVED,
    PROMOTED,
    GROUP_CREATED,
    GROUP_DISSOLVED
}
