package com.meshwalk.app.mesh.group

import com.meshwalk.app.crypto.group.GroupKeyManager
import com.meshwalk.app.crypto.keys.KeyStorage
import com.meshwalk.app.crypto.keys.MeshKeyManager
import com.meshwalk.app.domain.model.*
import com.meshwalk.app.domain.repository.ConversationRepository
import com.meshwalk.app.domain.repository.GroupRepository
import com.meshwalk.app.domain.repository.PeerRepository
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
 * Manages group control packets: invitations, acceptance, key distribution,
 * and member management.
 */
@Singleton
class GroupControlManager @Inject constructor(
    private val groupRepo: GroupRepository,
    private val conversationRepo: ConversationRepository,
    private val peerRepo: PeerRepository,
    private val groupKeyManager: GroupKeyManager,
    private val keyStorage: KeyStorage,
    private val keyManager: MeshKeyManager,
    private val routingEngine: MeshRoutingEngine
) {
    companion object {
        const val ACTION_INVITE: Byte = 0x01
        const val ACTION_ACCEPT: Byte = 0x02
        const val ACTION_REJECT: Byte = 0x03
        const val ACTION_KEY_DISTRIBUTION: Byte = 0x04
        const val ACTION_MEMBER_ADDED: Byte = 0x05
    }

    private val _pendingInvitations = MutableStateFlow<List<GroupInvitation>>(emptyList())
    val pendingInvitations: StateFlow<List<GroupInvitation>> = _pendingInvitations.asStateFlow()

    /**
     * Send group invitations to all members after group creation.
     * Includes the full member list so invitees see all members.
     */
    suspend fun sendInvitations(group: GroupInfo, ourNodeId: String) {
        groupKeyManager.generateSenderKey(group.groupId, ourNodeId)

        val members = group.members.filter { it.nodeId != ourNodeId }
        val memberNodeIds = group.members.map { it.nodeId }

        for (member in members) {
            val payload = serializePayload(
                action = ACTION_INVITE,
                groupId = group.groupId,
                groupName = group.name,
                senderName = group.members.find { it.nodeId == ourNodeId }?.displayName,
                groupType = group.groupType,
                memberNodeIds = memberNodeIds,
                expiresAt = group.expiresAt
            )

            val packet = MeshPacket(
                sourceNodeId = ourNodeId,
                destinationNodeId = member.nodeId,
                packetType = PacketType.GROUP_CONTROL,
                encryptedPayload = payload,
                nonce = ByteArray(12),
                senderSignature = signIfSelf(ourNodeId, payload),
                flags = MeshPacket.FLAG_ACK_REQUESTED
            )
            routingEngine.sendPacket(packet)
            Timber.d("Sent group invite for ${group.name} to ${member.nodeId.take(8)}")
        }
    }

    /**
     * Sign a group-control payload with our identity key when we originate it
     * as ourselves. Relayed packets whose source is another member (e.g. key
     * forwarding) are left unsigned because we don't hold that member's key.
     */
    private suspend fun signIfSelf(sourceNodeId: String, payload: ByteArray): ByteArray {
        val signingKey = keyStorage.getSigningPrivateKey(sourceNodeId) ?: return ByteArray(0)
        return try {
            val priv = java.security.KeyFactory.getInstance("EC")
                .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(signingKey))
            keyManager.sign(payload, priv)
        } catch (e: Exception) {
            Timber.w(e, "Failed to sign group-control payload")
            ByteArray(0)
        }
    }

    /**
     * Verify a group-control packet's signature against the sender's known
     * public signing key. Returns true when the packet is acceptable:
     * - signature present and valid, or
     * - no signature / sender key unknown (backward-compatible: relayed key
     *   distribution packets are unsigned, and older peers don't sign at all).
     * Returns false ONLY when we can check a signature and it fails — that's a
     * spoofing attempt and the packet is dropped.
     */
    private suspend fun verifySignature(packet: MeshPacket): Boolean {
        if (packet.senderSignature.isEmpty()) return true
        val pubKeyBytes = peerRepo.getPeer(packet.sourceNodeId)?.publicSigningKey ?: return true
        return try {
            val pub = java.security.KeyFactory.getInstance("EC")
                .generatePublic(java.security.spec.X509EncodedKeySpec(pubKeyBytes))
            val ok = keyManager.verify(packet.encryptedPayload, packet.senderSignature, pub)
            if (!ok) Timber.w("Dropping group-control packet with bad signature from ${packet.sourceNodeId.take(8)}")
            ok
        } catch (e: Exception) {
            Timber.w(e, "Signature check errored for ${packet.sourceNodeId.take(8)}; accepting")
            true
        }
    }

    /**
     * Handle an incoming GROUP_CONTROL packet.
     */
    suspend fun handleGroupControl(packet: MeshPacket) {
        val payload = packet.encryptedPayload
        if (payload.isEmpty()) return
        if (!verifySignature(packet)) return

        when (payload[0]) {
            ACTION_INVITE -> handleInvite(packet)
            ACTION_ACCEPT -> handleAccept(packet)
            ACTION_REJECT -> handleReject(packet)
            ACTION_KEY_DISTRIBUTION -> handleKeyDistribution(packet)
            ACTION_MEMBER_ADDED -> handleMemberAdded(packet)
            else -> Timber.w("Unknown group control action: ${payload[0]}")
        }
    }

    private fun handleInvite(packet: MeshPacket) {
        val parsed = deserializePayload(packet.encryptedPayload) ?: return
        val invitation = GroupInvitation(
            groupId = parsed.groupId,
            groupName = parsed.groupName,
            inviterNodeId = packet.sourceNodeId,
            inviterName = parsed.senderName,
            groupType = parsed.groupType,
            memberCount = parsed.memberNodeIds.size,
            memberNodeIds = parsed.memberNodeIds,
            expiresAt = parsed.expiresAt
        )

        val current = _pendingInvitations.value.toMutableList()
        current.removeAll { it.groupId == invitation.groupId }
        current.add(invitation)
        _pendingInvitations.value = current

        Timber.d("Received group invite: ${invitation.groupName} from ${packet.sourceNodeId.take(8)} (${invitation.memberCount} members)")
    }

    /**
     * Accept a pending invitation. Creates the group locally with all members.
     */
    suspend fun acceptInvitation(invitation: GroupInvitation, ourNodeId: String, ourName: String?) {
        // Build the full member list from the invitation
        val members = invitation.memberNodeIds.map { nodeId ->
            GroupMember(
                nodeId = nodeId,
                displayName = if (nodeId == invitation.inviterNodeId) invitation.inviterName
                              else if (nodeId == ourNodeId) ourName
                              else null,
                role = if (nodeId == invitation.inviterNodeId) GroupRole.ADMIN else GroupRole.MEMBER
            )
        }

        val group = GroupInfo(
            groupId = invitation.groupId,
            name = invitation.groupName,
            creatorNodeId = invitation.inviterNodeId,
            members = members,
            groupType = invitation.groupType,
            expiresAt = invitation.expiresAt
        )
        groupRepo.createGroup(group)
        conversationRepo.createConversation(
            Conversation(
                conversationId = group.groupId,
                type = group.groupType,
                title = group.name,
                participants = members.map { it.nodeId }
            )
        )

        // Generate our sender key
        groupKeyManager.generateSenderKey(invitation.groupId, ourNodeId)

        // Send accept + our sender key to inviter
        val senderKeyBytes = groupKeyManager.serializeSenderKey(invitation.groupId, ourNodeId)
        val payload = serializePayload(
            action = ACTION_ACCEPT,
            groupId = invitation.groupId,
            groupName = invitation.groupName,
            senderName = ourName,
            groupType = invitation.groupType,
            senderKey = senderKeyBytes
        )

        val packet = MeshPacket(
            sourceNodeId = ourNodeId,
            destinationNodeId = invitation.inviterNodeId,
            packetType = PacketType.GROUP_CONTROL,
            encryptedPayload = payload,
            nonce = ByteArray(12),
            senderSignature = signIfSelf(ourNodeId, payload),
            flags = MeshPacket.FLAG_ACK_REQUESTED
        )
        routingEngine.sendPacket(packet)

        // Distribute our sender key directly to every other member (not just
        // the creator).  Previously key distribution relied entirely on the
        // creator relaying keys in handleAccept.  If any of those relay
        // packets were lost, non-creator members could never decrypt our
        // messages.  Sending directly provides a redundant path that does not
        // depend on the creator being online or on the relay succeeding.
        if (senderKeyBytes != null) {
            val otherMembers = invitation.memberNodeIds.filter {
                it != ourNodeId && it != invitation.inviterNodeId
            }
            for (memberNodeId in otherMembers) {
                val keyPayload = serializePayload(
                    action = ACTION_KEY_DISTRIBUTION,
                    groupId = invitation.groupId,
                    groupName = invitation.groupName,
                    senderName = null,
                    groupType = invitation.groupType,
                    senderKey = senderKeyBytes
                )
                val keyPacket = MeshPacket(
                    sourceNodeId = ourNodeId,
                    destinationNodeId = memberNodeId,
                    packetType = PacketType.GROUP_CONTROL,
                    encryptedPayload = keyPayload,
                    nonce = ByteArray(12),
                    senderSignature = signIfSelf(ourNodeId, keyPayload),
                    flags = MeshPacket.FLAG_ACK_REQUESTED
                )
                routingEngine.sendPacket(keyPacket)
                Timber.d("Sent our sender key directly to ${memberNodeId.take(8)} for group ${invitation.groupId.take(8)}")
            }
        }

        _pendingInvitations.value = _pendingInvitations.value.filter { it.groupId != invitation.groupId }

        Timber.d("Accepted group invite: ${invitation.groupName}")
    }

    /**
     * Reject a pending invitation.
     */
    suspend fun rejectInvitation(invitation: GroupInvitation, ourNodeId: String) {
        val payload = serializePayload(
            action = ACTION_REJECT,
            groupId = invitation.groupId,
            groupName = invitation.groupName,
            senderName = null,
            groupType = invitation.groupType
        )

        val packet = MeshPacket(
            sourceNodeId = ourNodeId,
            destinationNodeId = invitation.inviterNodeId,
            packetType = PacketType.GROUP_CONTROL,
            encryptedPayload = payload,
            nonce = ByteArray(12),
            senderSignature = signIfSelf(ourNodeId, payload),
            flags = 0
        )
        routingEngine.sendPacket(packet)

        _pendingInvitations.value = _pendingInvitations.value.filter { it.groupId != invitation.groupId }

        Timber.d("Rejected group invite: ${invitation.groupName}")
    }

    /**
     * Add a new member to an existing group. Sends invite to the new member
     * and notifies all existing members.
     */
    suspend fun addMemberToGroup(groupId: String, newMemberNodeId: String, ourNodeId: String) {
        val group = groupRepo.getGroup(groupId) ?: return

        if (group.members.any { it.nodeId == newMemberNodeId }) {
            Timber.d("${newMemberNodeId.take(8)} is already a member of group $groupId")
            return
        }

        val peerName = peerRepo.getPeer(newMemberNodeId)?.displayName
        val newMember = GroupMember(
            nodeId = newMemberNodeId,
            displayName = peerName,
            role = GroupRole.MEMBER
        )
        val updatedMembers = group.members + newMember
        val updatedGroup = group.copy(members = updatedMembers, version = group.version + 1)
        groupRepo.updateGroup(updatedGroup)

        // Send invite to the new member with full member list
        val memberNodeIds = updatedMembers.map { it.nodeId }
        val invitePayload = serializePayload(
            action = ACTION_INVITE,
            groupId = groupId,
            groupName = group.name,
            senderName = group.members.find { it.nodeId == ourNodeId }?.displayName,
            groupType = group.groupType,
            memberNodeIds = memberNodeIds,
            expiresAt = group.expiresAt
        )
        val invitePacket = MeshPacket(
            sourceNodeId = ourNodeId,
            destinationNodeId = newMemberNodeId,
            packetType = PacketType.GROUP_CONTROL,
            encryptedPayload = invitePayload,
            nonce = ByteArray(12),
            senderSignature = signIfSelf(ourNodeId, invitePayload),
            flags = MeshPacket.FLAG_ACK_REQUESTED
        )
        routingEngine.sendPacket(invitePacket)

        // Notify existing members about the new member
        val notifyPayload = serializePayload(
            action = ACTION_MEMBER_ADDED,
            groupId = groupId,
            groupName = group.name,
            senderName = null,
            groupType = group.groupType,
            memberNodeIds = listOf(newMemberNodeId)
        )
        for (member in group.members.filter { it.nodeId != ourNodeId }) {
            val notifyPacket = MeshPacket(
                sourceNodeId = ourNodeId,
                destinationNodeId = member.nodeId,
                packetType = PacketType.GROUP_CONTROL,
                encryptedPayload = notifyPayload,
                nonce = ByteArray(12),
                senderSignature = signIfSelf(ourNodeId, notifyPayload),
                flags = 0
            )
            routingEngine.sendPacket(notifyPacket)
        }

        Timber.d("Added ${newMemberNodeId.take(8)} to group ${group.name}")
    }

    /**
     * Update group name.
     */
    suspend fun updateGroupName(groupId: String, newName: String) {
        val group = groupRepo.getGroup(groupId) ?: return
        groupRepo.updateGroup(group.copy(name = newName))
    }

    private suspend fun handleAccept(packet: MeshPacket) {
        val parsed = deserializePayload(packet.encryptedPayload) ?: return
        val acceptorNodeId = packet.sourceNodeId
        val ourNodeId = packet.destinationNodeId

        if (parsed.senderKey != null) {
            groupKeyManager.storeSenderKey(parsed.groupId, acceptorNodeId, parsed.senderKey)
        }

        // Update the acceptor's display name in the group member list if provided
        if (parsed.senderName != null) {
            val grp = groupRepo.getGroup(parsed.groupId)
            if (grp != null) {
                val updatedMembers = grp.members.map { m ->
                    if (m.nodeId == acceptorNodeId && m.displayName == null) {
                        m.copy(displayName = parsed.senderName)
                    } else m
                }
                if (updatedMembers != grp.members) {
                    groupRepo.updateGroup(grp.copy(members = updatedMembers))
                }
            }
        }

        // Send our sender key back to the acceptor
        val ourSenderKeyBytes = groupKeyManager.serializeSenderKey(parsed.groupId, ourNodeId)
        if (ourSenderKeyBytes != null) {
            sendKeyDistribution(parsed, ourNodeId, acceptorNodeId, ourSenderKeyBytes)
        }

        // Distribute keys between the new member and all other existing members
        // so that every member can decrypt messages from every other member.
        val group = groupRepo.getGroup(parsed.groupId)
        if (group != null) {
            val otherMembers = group.members.filter {
                it.nodeId != ourNodeId && it.nodeId != acceptorNodeId
            }

            for (member in otherMembers) {
                // Forward the new acceptor's sender key to each existing member
                if (parsed.senderKey != null) {
                    sendKeyDistribution(parsed, acceptorNodeId, member.nodeId, parsed.senderKey)
                }

                // Forward each existing member's sender key to the new acceptor
                val memberKeyBytes = groupKeyManager.serializeSenderKey(parsed.groupId, member.nodeId)
                if (memberKeyBytes != null) {
                    sendKeyDistribution(parsed, member.nodeId, acceptorNodeId, memberKeyBytes)
                }
            }
        }

        Timber.d("${acceptorNodeId.take(8)} accepted invite for group ${parsed.groupId.take(8)}")
    }

    /**
     * Send a KEY_DISTRIBUTION packet to distribute a member's sender key to a recipient.
     * The sourceNodeId in the packet is set to [keyOwnerNodeId] so the recipient knows
     * whose sender key it is.
     */
    private suspend fun sendKeyDistribution(
        parsed: ParsedPayload,
        keyOwnerNodeId: String,
        recipientNodeId: String,
        senderKeyBytes: ByteArray
    ) {
        val keyPayload = serializePayload(
            action = ACTION_KEY_DISTRIBUTION,
            groupId = parsed.groupId,
            groupName = parsed.groupName,
            senderName = null,
            groupType = parsed.groupType,
            senderKey = senderKeyBytes
        )
        val keyPacket = MeshPacket(
            sourceNodeId = keyOwnerNodeId,
            destinationNodeId = recipientNodeId,
            packetType = PacketType.GROUP_CONTROL,
            encryptedPayload = keyPayload,
            nonce = ByteArray(12),
            senderSignature = signIfSelf(keyOwnerNodeId, keyPayload),
            flags = MeshPacket.FLAG_ACK_REQUESTED
        )
        routingEngine.sendPacket(keyPacket)
    }

    private fun handleReject(packet: MeshPacket) {
        val parsed = deserializePayload(packet.encryptedPayload) ?: return
        Timber.d("${packet.sourceNodeId.take(8)} rejected invite for group ${parsed.groupId.take(8)}")
    }

    private fun handleKeyDistribution(packet: MeshPacket) {
        val parsed = deserializePayload(packet.encryptedPayload) ?: return
        if (parsed.senderKey != null) {
            groupKeyManager.storeSenderKey(parsed.groupId, packet.sourceNodeId, parsed.senderKey)
            Timber.d("Received sender key from ${packet.sourceNodeId.take(8)} for group ${parsed.groupId.take(8)}")
        }
    }

    private suspend fun handleMemberAdded(packet: MeshPacket) {
        val parsed = deserializePayload(packet.encryptedPayload) ?: return
        val group = groupRepo.getGroup(parsed.groupId) ?: return

        for (newNodeId in parsed.memberNodeIds) {
            if (group.members.none { it.nodeId == newNodeId }) {
                val peerName = peerRepo.getPeer(newNodeId)?.displayName
                val newMember = GroupMember(
                    nodeId = newNodeId,
                    displayName = peerName,
                    role = GroupRole.MEMBER
                )
                groupRepo.addMember(parsed.groupId, newMember)
                Timber.d("Added ${newNodeId.take(8)} to group ${parsed.groupId.take(8)} (notified by ${packet.sourceNodeId.take(8)})")
            }
        }
    }

    // -- Serialization --

    private data class ParsedPayload(
        val groupId: String,
        val groupName: String,
        val senderName: String?,
        val groupType: ConversationType,
        val memberNodeIds: List<String> = emptyList(),
        val senderKey: ByteArray? = null,
        val expiresAt: Long? = null
    )

    /**
     * Payload format:
     * [action:1][groupIdLen:4][groupId][groupNameLen:4][groupName]
     * [senderNameLen:4][senderName][groupType:1]
     * [memberCount:4][memberNodeId1Len:4][memberNodeId1]...[memberNodeIdNLen:4][memberNodeIdN]
     * [senderKeyLen:4][senderKey]
     * [hasExpiresAt:1][expiresAt:8] (optional, only present if hasExpiresAt == 1)
     */
    private fun serializePayload(
        action: Byte,
        groupId: String,
        groupName: String,
        senderName: String?,
        groupType: ConversationType,
        memberNodeIds: List<String> = emptyList(),
        senderKey: ByteArray? = null,
        expiresAt: Long? = null
    ): ByteArray {
        val gidBytes = groupId.toByteArray(StandardCharsets.UTF_8)
        val gnameBytes = groupName.toByteArray(StandardCharsets.UTF_8)
        val snameBytes = (senderName ?: "").toByteArray(StandardCharsets.UTF_8)
        val keyBytes = senderKey ?: ByteArray(0)
        val memberBytesList = memberNodeIds.map { it.toByteArray(StandardCharsets.UTF_8) }

        val memberSize = 4 + memberBytesList.sumOf { 4 + it.size } // count + each member
        val expiresAtSize = 1 + if (expiresAt != null) 8 else 0
        val size = 1 + (4 + gidBytes.size) + (4 + gnameBytes.size) + (4 + snameBytes.size) +
                1 + memberSize + (4 + keyBytes.size) + expiresAtSize
        val buffer = ByteBuffer.allocate(size)
        buffer.put(action)
        buffer.putInt(gidBytes.size).put(gidBytes)
        buffer.putInt(gnameBytes.size).put(gnameBytes)
        buffer.putInt(snameBytes.size).put(snameBytes)
        buffer.put(groupType.ordinal.toByte())
        buffer.putInt(memberBytesList.size)
        for (mBytes in memberBytesList) {
            buffer.putInt(mBytes.size).put(mBytes)
        }
        buffer.putInt(keyBytes.size).put(keyBytes)
        if (expiresAt != null) {
            buffer.put(1.toByte())
            buffer.putLong(expiresAt)
        } else {
            buffer.put(0.toByte())
        }
        return buffer.array()
    }

    private fun deserializePayload(data: ByteArray): ParsedPayload? {
        return try {
            val buffer = ByteBuffer.wrap(data)
            buffer.get() // skip action byte

            fun readString(): String {
                val len = buffer.getInt()
                // Validate before allocating so a corrupt/malicious length prefix
                // can't request a huge array and OOM the process.
                require(len in 0..buffer.remaining()) { "Invalid string length: $len" }
                val bytes = ByteArray(len).also { buffer.get(it) }
                return String(bytes, StandardCharsets.UTF_8)
            }

            val groupId = readString()
            val groupName = readString()
            val senderName = readString().takeIf { it.isNotEmpty() }
            val groupType = ConversationType.entries[buffer.get().toInt()]

            val memberCount = buffer.getInt()
            // Each member entry needs at least a 4-byte length prefix.
            require(memberCount in 0..buffer.remaining() / 4) { "Invalid member count: $memberCount" }
            val memberNodeIds = (0 until memberCount).map { readString() }

            val keyLen = if (buffer.remaining() >= 4) buffer.getInt() else 0
            require(keyLen <= buffer.remaining()) { "Invalid sender key length: $keyLen" }
            val senderKey = if (keyLen > 0) ByteArray(keyLen).also { buffer.get(it) } else null

            // Read expiresAt if present (backwards-compatible with older payloads)
            val expiresAt = if (buffer.remaining() >= 1) {
                val hasExpiresAt = buffer.get()
                if (hasExpiresAt == 1.toByte() && buffer.remaining() >= 8) buffer.getLong() else null
            } else null

            ParsedPayload(groupId, groupName, senderName, groupType, memberNodeIds, senderKey, expiresAt)
        } catch (e: Exception) {
            Timber.e(e, "Failed to deserialize group control payload")
            null
        }
    }
}
