package com.meshwalk.app.domain.usecase

import com.meshwalk.app.domain.model.*
import com.meshwalk.app.domain.repository.*
import javax.inject.Inject

class CreateIdentityUseCase @Inject constructor(
    private val identityRepo: IdentityRepository,
    private val cryptoManager: CryptoManagerPort
) {
    suspend operator fun invoke(name: String?, type: IdentityType): NodeIdentity {
        val keyPair = cryptoManager.generateIdentityKeyPair()
        val identity = NodeIdentity(
            displayName = name,
            identityType = type,
            publicSigningKey = keyPair.signingPublicKey,
            publicExchangeKey = keyPair.exchangePublicKey,
            expiresAt = if (type == IdentityType.TEMPORARY) {
                System.currentTimeMillis() + 24 * 60 * 60 * 1000 // 24 hours
            } else null
        )
        identityRepo.createIdentity(name, type)
        return identity
    }
}

class SendMessageUseCase @Inject constructor(
    private val messageRepo: MessageRepository,
    private val conversationRepo: ConversationRepository,
    private val meshOutbox: MeshOutboxPort
) {
    suspend operator fun invoke(
        conversationId: String,
        recipientNodeId: String,
        text: String,
        senderNodeId: String
    ): MeshMessage {
        val message = MeshMessage(
            conversationId = conversationId,
            senderNodeId = senderNodeId,
            content = MessageContent.Text(text),
            isIncoming = false,
            deliveryStatus = DeliveryStatus.PENDING
        )
        messageRepo.saveMessage(message)
        conversationRepo.updateLastMessage(conversationId, text, message.timestamp)
        meshOutbox.enqueueMessage(message, recipientNodeId)
        return message
    }
}

class SendGroupMessageUseCase @Inject constructor(
    private val messageRepo: MessageRepository,
    private val conversationRepo: ConversationRepository,
    private val groupRepo: GroupRepository,
    private val meshOutbox: MeshOutboxPort
) {
    suspend operator fun invoke(
        groupId: String,
        text: String,
        senderNodeId: String
    ): MeshMessage {
        val group = groupRepo.getGroup(groupId)
            ?: throw IllegalArgumentException("Group not found: $groupId")

        val message = MeshMessage(
            conversationId = groupId,
            senderNodeId = senderNodeId,
            content = MessageContent.Text(text),
            isIncoming = false,
            deliveryStatus = DeliveryStatus.PENDING
        )
        messageRepo.saveMessage(message)
        conversationRepo.updateLastMessage(groupId, text, message.timestamp)

        // Fan-out: enqueue message for each group member
        group.members
            .filter { it.nodeId != senderNodeId }
            .forEach { member ->
                meshOutbox.enqueueGroupMessage(message, member.nodeId, groupId)
            }

        return message
    }
}

class CreateGroupUseCase @Inject constructor(
    private val groupRepo: GroupRepository,
    private val conversationRepo: ConversationRepository,
    private val identityRepo: IdentityRepository,
    private val groupControlManager: com.meshwalk.app.mesh.group.GroupControlManager
) {
    suspend operator fun invoke(
        name: String,
        memberNodeIds: List<String>,
        temporary: Boolean = false
    ): GroupInfo {
        val self = identityRepo.getActiveIdentity()
            ?: throw IllegalStateException("No active identity")

        val members = memberNodeIds.map { nodeId ->
            GroupMember(
                nodeId = nodeId,
                displayName = null,
                role = if (nodeId == self.nodeId) GroupRole.ADMIN else GroupRole.MEMBER
            )
        } + GroupMember(
            nodeId = self.nodeId,
            displayName = self.displayName,
            role = GroupRole.ADMIN
        )

        val group = GroupInfo(
            name = name,
            creatorNodeId = self.nodeId,
            members = members.distinctBy { it.nodeId },
            groupType = if (temporary) ConversationType.TEMPORARY_GROUP else ConversationType.GROUP,
            expiresAt = if (temporary) System.currentTimeMillis() + 4 * 60 * 60 * 1000 else null
        )

        groupRepo.createGroup(group)
        conversationRepo.createConversation(
            Conversation(
                conversationId = group.groupId,
                type = group.groupType,
                title = name,
                participants = members.map { it.nodeId }
            )
        )

        // Send invitations to all members
        groupControlManager.sendInvitations(group, self.nodeId)

        return group
    }
}

class DiscoverPeersUseCase @Inject constructor(
    private val peerRepo: PeerRepository,
    private val transportManager: TransportManagerPort
) {
    suspend fun startDiscovery() = transportManager.startDiscovery()
    suspend fun stopDiscovery() = transportManager.stopDiscovery()
    fun observePeers() = peerRepo.observeNearbyPeers()
}

class GetNetworkGraphUseCase @Inject constructor(
    private val routingRepo: RoutingRepository
) {
    fun observe() = routingRepo.observeNetworkGraph()
}

// Port interfaces for dependency inversion
interface CryptoManagerPort {
    fun generateIdentityKeyPair(): IdentityKeyPair
}

interface MeshOutboxPort {
    suspend fun enqueueMessage(message: MeshMessage, recipientNodeId: String)
    suspend fun enqueueGroupMessage(message: MeshMessage, recipientNodeId: String, groupId: String)
}

interface TransportManagerPort {
    suspend fun startDiscovery()
    suspend fun stopDiscovery()
    suspend fun startAdvertising()
    suspend fun stopAdvertising()
    suspend fun sendPacket(packet: MeshPacket, targetNodeId: String): Boolean
}

data class IdentityKeyPair(
    val signingPublicKey: ByteArray,
    val signingPrivateKey: ByteArray,
    val exchangePublicKey: ByteArray,
    val exchangePrivateKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentityKeyPair) return false
        return signingPublicKey.contentEquals(other.signingPublicKey)
    }
    override fun hashCode(): Int = signingPublicKey.contentHashCode()
}
