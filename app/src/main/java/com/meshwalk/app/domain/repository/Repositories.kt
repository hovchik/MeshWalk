package com.meshwalk.app.domain.repository

import com.meshwalk.app.domain.model.*
import kotlinx.coroutines.flow.Flow

interface IdentityRepository {
    suspend fun createIdentity(name: String?, type: IdentityType): NodeIdentity
    suspend fun getActiveIdentity(): NodeIdentity?
    suspend fun setActiveIdentity(nodeId: String)
    suspend fun deleteIdentity(nodeId: String)
    suspend fun updateProfile(nodeId: String, name: String?, type: IdentityType)
    fun observeActiveIdentity(): Flow<NodeIdentity?>
    suspend fun getAllIdentities(): List<NodeIdentity>
}

interface MessageRepository {
    suspend fun saveMessage(message: MeshMessage)
    suspend fun updateDeliveryStatus(messageId: String, status: DeliveryStatus)
    fun observeMessages(conversationId: String): Flow<List<MeshMessage>>
    fun observeRecentMessages(conversationId: String, limit: Int): Flow<List<MeshMessage>>
    suspend fun getUndeliveredMessages(): List<MeshMessage>
    suspend fun deleteMessage(messageId: String)
    suspend fun deleteMessagesByConversation(conversationId: String)
    suspend fun deleteExpiredMessages()
    suspend fun getMessageById(messageId: String): MeshMessage?
    suspend fun getFailedMessagesForPeer(peerNodeId: String): List<MeshMessage>
    suspend fun getStalePendingMessagesForPeer(peerNodeId: String, staleBefore: Long): List<MeshMessage>
    suspend fun updateReactions(messageId: String, reactions: Map<String, String>)
    suspend fun togglePinned(messageId: String, isPinned: Boolean)
    fun observePinnedMessages(conversationId: String): Flow<List<MeshMessage>>
    suspend fun searchMessages(conversationId: String, query: String): List<MeshMessage>
    suspend fun searchAllMessages(query: String): List<MeshMessage>
}

interface ConversationRepository {
    suspend fun createConversation(conversation: Conversation): Conversation
    suspend fun getConversation(conversationId: String): Conversation?
    suspend fun getOrCreateDirectConversation(peerNodeId: String, peerDisplayName: String? = null): Conversation
    fun observeConversations(): Flow<List<Conversation>>
    suspend fun updateLastMessage(conversationId: String, preview: String, timestamp: Long)
    suspend fun updatePeerDisplayName(conversationId: String, displayName: String)
    suspend fun incrementUnread(conversationId: String)
    suspend fun clearUnread(conversationId: String)
    suspend fun deleteConversation(conversationId: String)
    suspend fun updateNickname(conversationId: String, nickname: String?)
    suspend fun toggleFavorite(conversationId: String, isFavorite: Boolean)
    fun observeTotalUnreadCount(): Flow<Int>
}

interface PeerRepository {
    suspend fun upsertPeer(peer: PeerNode)
    suspend fun removePeer(nodeId: String)
    fun observeNearbyPeers(): Flow<List<PeerNode>>
    suspend fun getPeer(nodeId: String): PeerNode?
    suspend fun getDirectPeers(): List<PeerNode>
    suspend fun pruneStale(maxAgeMs: Long)
    suspend fun resetAllConnectionStates()
    suspend fun isBlocked(nodeId: String): Boolean
    suspend fun blockPeer(nodeId: String, displayName: String?)
    suspend fun unblockPeer(nodeId: String)
    fun observeBlockedPeers(): Flow<List<BlockedPeer>>
}

data class BlockedPeer(
    val nodeId: String,
    val displayName: String?,
    val blockedAt: Long
)

interface GroupRepository {
    suspend fun createGroup(group: GroupInfo): GroupInfo
    suspend fun getGroup(groupId: String): GroupInfo?
    suspend fun updateGroup(group: GroupInfo)
    fun observeGroups(): Flow<List<GroupInfo>>
    suspend fun addMember(groupId: String, member: GroupMember)
    suspend fun removeMember(groupId: String, nodeId: String)
    suspend fun deleteGroup(groupId: String)
    suspend fun applyMembershipChange(change: GroupMembershipChange)
    suspend fun updateMemberDisplayName(nodeId: String, displayName: String?)
}

interface RoutingRepository {
    suspend fun updateRoute(entry: RoutingEntry)
    suspend fun getRoute(destinationNodeId: String): RoutingEntry?
    suspend fun getAllRoutes(): List<RoutingEntry>
    suspend fun removeRoute(destinationNodeId: String)
    suspend fun pruneStaleRoutes()
    fun observeNetworkGraph(): Flow<NetworkGraph>
}

interface SettingsRepository {
    fun observeSettings(): Flow<AppSettings>
    suspend fun getSettings(): AppSettings
    suspend fun updateSettings(settings: AppSettings)
    suspend fun isOnboardingComplete(): Boolean
    suspend fun setOnboardingComplete()
}
