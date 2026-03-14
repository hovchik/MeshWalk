package com.meshwalk.app.mesh.group

import com.meshwalk.app.crypto.group.GroupKeyManager
import com.meshwalk.app.crypto.keys.KeyStorage
import com.meshwalk.app.domain.model.*
import com.meshwalk.app.domain.repository.ConversationRepository
import com.meshwalk.app.domain.repository.GroupRepository
import com.meshwalk.app.routing.engine.MeshRoutingEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages group control packets: invitations, acceptance, key distribution.
 *
 * GROUP_CONTROL packet payload format:
 * [actionByte][groupIdLen][groupId][groupNameLen][groupName][senderNameLen][senderName]
 * [groupTypeByte][memberCount (4 bytes)]
 * Optional for KEY_DISTRIBUTION: [senderKeyLen][senderKeyBytes]
 */
@Singleton
class GroupControlManager @Inject constructor(
    private val groupRepo: GroupRepository,
    private val conversationRepo: ConversationRepository,
    private val groupKeyManager: GroupKeyManager,
    private val keyStorage: KeyStorage,
    private val routingEngine: MeshRoutingEngine
) {
    companion object {
        const val ACTION_INVITE: Byte = 0x01
        const val ACTION_ACCEPT: Byte = 0x02
        const val ACTION_REJECT: Byte = 0x03
        const val ACTION_KEY_DISTRIBUTION: Byte = 0x04
    }

    private val _pendingInvitations = MutableStateFlow<List<GroupInvitation>>(emptyList())
    val pendingInvitations: StateFlow<List<GroupInvitation>> = _pendingInvitations.asStateFlow()

    /**
     * Send group invitations to all members after group creation.
     * Also generates our sender key and distributes it.
     */
    suspend fun sendInvitations(group: GroupInfo, ourNodeId: String) {
        // Generate our sender key for this group
        groupKeyManager.generateSenderKey(group.groupId, ourNodeId)

        val signingKey = keyStorage.getSigningPrivateKey(ourNodeId) ?: run {
            Timber.e("No signing key for $ourNodeId, cannot send invitations")
            return
        }

        val members = group.members.filter { it.nodeId != ourNodeId }
        for (member in members) {
            val payload = serializeInvitePayload(
                action = ACTION_INVITE,
                groupId = group.groupId,
                groupName = group.name,
                senderName = group.members.find { it.nodeId == ourNodeId }?.displayName,
                groupType = group.groupType,
                memberCount = group.members.size
            )

            val packet = MeshPacket(
                sourceNodeId = ourNodeId,
                destinationNodeId = member.nodeId,
                packetType = PacketType.GROUP_CONTROL,
                encryptedPayload = payload,
                nonce = ByteArray(12),
                senderSignature = ByteArray(0),
                flags = MeshPacket.FLAG_ACK_REQUESTED
            )
            routingEngine.sendPacket(packet)
            Timber.d("Sent group invite for ${group.name} to ${member.nodeId.take(8)}")
        }
    }

    /**
     * Handle an incoming GROUP_CONTROL packet.
     */
    suspend fun handleGroupControl(packet: MeshPacket) {
        val payload = packet.encryptedPayload
        if (payload.isEmpty()) return

        when (payload[0]) {
            ACTION_INVITE -> handleInvite(packet)
            ACTION_ACCEPT -> handleAccept(packet)
            ACTION_REJECT -> handleReject(packet)
            ACTION_KEY_DISTRIBUTION -> handleKeyDistribution(packet)
            else -> Timber.w("Unknown group control action: ${payload[0]}")
        }
    }

    private fun handleInvite(packet: MeshPacket) {
        val parsed = deserializeInvitePayload(packet.encryptedPayload) ?: return
        val invitation = GroupInvitation(
            groupId = parsed.groupId,
            groupName = parsed.groupName,
            inviterNodeId = packet.sourceNodeId,
            inviterName = parsed.senderName,
            groupType = parsed.groupType,
            memberCount = parsed.memberCount
        )

        val current = _pendingInvitations.value.toMutableList()
        // Replace existing invite for same group
        current.removeAll { it.groupId == invitation.groupId }
        current.add(invitation)
        _pendingInvitations.value = current

        Timber.d("Received group invite: ${invitation.groupName} from ${packet.sourceNodeId.take(8)}")
    }

    /**
     * Accept a pending invitation. Creates the group locally and notifies the inviter.
     */
    suspend fun acceptInvitation(invitation: GroupInvitation, ourNodeId: String, ourName: String?) {
        // Create group locally
        val group = GroupInfo(
            groupId = invitation.groupId,
            name = invitation.groupName,
            creatorNodeId = invitation.inviterNodeId,
            members = listOf(
                GroupMember(
                    nodeId = invitation.inviterNodeId,
                    displayName = invitation.inviterName,
                    role = GroupRole.ADMIN
                ),
                GroupMember(
                    nodeId = ourNodeId,
                    displayName = ourName,
                    role = GroupRole.MEMBER
                )
            ),
            groupType = invitation.groupType
        )
        groupRepo.createGroup(group)
        conversationRepo.createConversation(
            Conversation(
                conversationId = group.groupId,
                type = group.groupType,
                title = group.name,
                participants = group.members.map { it.nodeId }
            )
        )

        // Generate our sender key
        groupKeyManager.generateSenderKey(invitation.groupId, ourNodeId)

        // Send accept + our sender key to inviter
        val senderKeyBytes = groupKeyManager.serializeSenderKey(invitation.groupId, ourNodeId)
        val payload = serializeInvitePayload(
            action = ACTION_ACCEPT,
            groupId = invitation.groupId,
            groupName = invitation.groupName,
            senderName = ourName,
            groupType = invitation.groupType,
            memberCount = 0,
            senderKey = senderKeyBytes
        )

        val packet = MeshPacket(
            sourceNodeId = ourNodeId,
            destinationNodeId = invitation.inviterNodeId,
            packetType = PacketType.GROUP_CONTROL,
            encryptedPayload = payload,
            nonce = ByteArray(12),
            senderSignature = ByteArray(0),
            flags = MeshPacket.FLAG_ACK_REQUESTED
        )
        routingEngine.sendPacket(packet)

        // Remove from pending
        _pendingInvitations.value = _pendingInvitations.value.filter { it.groupId != invitation.groupId }

        Timber.d("Accepted group invite: ${invitation.groupName}")
    }

    /**
     * Reject a pending invitation. Notifies the inviter.
     */
    suspend fun rejectInvitation(invitation: GroupInvitation, ourNodeId: String) {
        val payload = serializeInvitePayload(
            action = ACTION_REJECT,
            groupId = invitation.groupId,
            groupName = invitation.groupName,
            senderName = null,
            groupType = invitation.groupType,
            memberCount = 0
        )

        val packet = MeshPacket(
            sourceNodeId = ourNodeId,
            destinationNodeId = invitation.inviterNodeId,
            packetType = PacketType.GROUP_CONTROL,
            encryptedPayload = payload,
            nonce = ByteArray(12),
            senderSignature = ByteArray(0),
            flags = 0
        )
        routingEngine.sendPacket(packet)

        _pendingInvitations.value = _pendingInvitations.value.filter { it.groupId != invitation.groupId }

        Timber.d("Rejected group invite: ${invitation.groupName}")
    }

    private suspend fun handleAccept(packet: MeshPacket) {
        val parsed = deserializeInvitePayload(packet.encryptedPayload) ?: return

        // Store their sender key if provided
        if (parsed.senderKey != null) {
            groupKeyManager.storeSenderKey(parsed.groupId, packet.sourceNodeId, parsed.senderKey)
        }

        // Send our sender key back to the acceptor
        val ourNodeId = packet.destinationNodeId // This packet was sent TO us
        val senderKeyBytes = groupKeyManager.serializeSenderKey(parsed.groupId, ourNodeId)
        if (senderKeyBytes != null) {
            val keyPayload = serializeInvitePayload(
                action = ACTION_KEY_DISTRIBUTION,
                groupId = parsed.groupId,
                groupName = parsed.groupName,
                senderName = null,
                groupType = parsed.groupType,
                memberCount = 0,
                senderKey = senderKeyBytes
            )
            val keyPacket = MeshPacket(
                sourceNodeId = ourNodeId,
                destinationNodeId = packet.sourceNodeId,
                packetType = PacketType.GROUP_CONTROL,
                encryptedPayload = keyPayload,
                nonce = ByteArray(12),
                senderSignature = ByteArray(0),
                flags = 0
            )
            routingEngine.sendPacket(keyPacket)
        }

        Timber.d("${packet.sourceNodeId.take(8)} accepted invite for group ${parsed.groupId.take(8)}")
    }

    private fun handleReject(packet: MeshPacket) {
        val parsed = deserializeInvitePayload(packet.encryptedPayload) ?: return
        Timber.d("${packet.sourceNodeId.take(8)} rejected invite for group ${parsed.groupId.take(8)}")
    }

    private fun handleKeyDistribution(packet: MeshPacket) {
        val parsed = deserializeInvitePayload(packet.encryptedPayload) ?: return
        if (parsed.senderKey != null) {
            groupKeyManager.storeSenderKey(parsed.groupId, packet.sourceNodeId, parsed.senderKey)
            Timber.d("Received sender key from ${packet.sourceNodeId.take(8)} for group ${parsed.groupId.take(8)}")
        }
    }

    // -- Serialization --

    private data class ParsedPayload(
        val groupId: String,
        val groupName: String,
        val senderName: String?,
        val groupType: ConversationType,
        val memberCount: Int,
        val senderKey: ByteArray? = null
    )

    private fun serializeInvitePayload(
        action: Byte,
        groupId: String,
        groupName: String,
        senderName: String?,
        groupType: ConversationType,
        memberCount: Int,
        senderKey: ByteArray? = null
    ): ByteArray {
        val gidBytes = groupId.toByteArray(StandardCharsets.UTF_8)
        val gnameBytes = groupName.toByteArray(StandardCharsets.UTF_8)
        val snameBytes = (senderName ?: "").toByteArray(StandardCharsets.UTF_8)
        val keyBytes = senderKey ?: ByteArray(0)

        val size = 1 + (4 + gidBytes.size) + (4 + gnameBytes.size) + (4 + snameBytes.size) +
                1 + 4 + (4 + keyBytes.size)
        val buffer = ByteBuffer.allocate(size)
        buffer.put(action)
        buffer.putInt(gidBytes.size).put(gidBytes)
        buffer.putInt(gnameBytes.size).put(gnameBytes)
        buffer.putInt(snameBytes.size).put(snameBytes)
        buffer.put(groupType.ordinal.toByte())
        buffer.putInt(memberCount)
        buffer.putInt(keyBytes.size).put(keyBytes)
        return buffer.array()
    }

    private fun deserializeInvitePayload(data: ByteArray): ParsedPayload? {
        return try {
            val buffer = ByteBuffer.wrap(data)
            buffer.get() // skip action byte (already read)

            fun readString(): String {
                val len = buffer.getInt()
                val bytes = ByteArray(len).also { buffer.get(it) }
                return String(bytes, StandardCharsets.UTF_8)
            }

            val groupId = readString()
            val groupName = readString()
            val senderName = readString().takeIf { it.isNotEmpty() }
            val groupType = ConversationType.entries[buffer.get().toInt()]
            val memberCount = buffer.getInt()

            val keyLen = if (buffer.remaining() >= 4) buffer.getInt() else 0
            val senderKey = if (keyLen > 0) ByteArray(keyLen).also { buffer.get(it) } else null

            ParsedPayload(groupId, groupName, senderName, groupType, memberCount, senderKey)
        } catch (e: Exception) {
            Timber.e(e, "Failed to deserialize group control payload")
            null
        }
    }
}
