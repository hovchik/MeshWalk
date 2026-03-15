package com.meshwalk.app.mesh.inbox

import com.meshwalk.app.crypto.envelope.MessageEnvelopeManager
import com.meshwalk.app.crypto.group.GroupKeyManager
import com.meshwalk.app.crypto.keys.KeyStorage
import com.meshwalk.app.crypto.session.SessionManager
import com.meshwalk.app.domain.model.DeliveryStatus
import com.meshwalk.app.domain.model.MessageContent
import com.meshwalk.app.domain.model.PacketType
import com.meshwalk.app.domain.repository.ConversationRepository
import com.meshwalk.app.domain.repository.MessageRepository
import com.meshwalk.app.domain.repository.PeerRepository
import com.meshwalk.app.mesh.group.GroupControlManager
import com.meshwalk.app.routing.engine.MeshRoutingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Inbound message processor.
 *
 * Subscribes to [MeshRoutingEngine.incomingPackets] and handles:
 * - Direct messages: decrypt via pairwise session, save to 1:1 conversation
 * - Group messages: decrypt via group sender key, save to group conversation
 * - Group control: invitations, acceptance, key distribution
 */
@Singleton
class MeshInbox @Inject constructor(
    private val routingEngine: MeshRoutingEngine,
    private val envelopeManager: MessageEnvelopeManager,
    private val messageRepo: MessageRepository,
    private val conversationRepo: ConversationRepository,
    private val groupKeyManager: GroupKeyManager,
    private val groupControlManager: GroupControlManager,
    private val sessionManager: SessionManager,
    private val keyStorage: KeyStorage,
    private val peerRepo: PeerRepository,
    private val notificationManager: MessageNotificationManager
) {

    fun start(ourNodeId: String, scope: CoroutineScope) {
        scope.launch {
            routingEngine.incomingPackets.collect { packet ->
                try {
                    when (packet.packetType) {
                        PacketType.GROUP_CONTROL -> {
                            groupControlManager.handleGroupControl(packet)
                        }
                        PacketType.MESSAGE -> {
                            if (packet.isGroupMessage) {
                                handleGroupMessage(packet, ourNodeId)
                            } else {
                                handleDirectMessage(packet, ourNodeId)
                            }
                        }
                        else -> {
                            Timber.d("Ignoring packet type: ${packet.packetType}")
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error processing incoming packet ${packet.packetId}")
                }
            }
        }
        Timber.d("MeshInbox started for node $ourNodeId")
    }

    private suspend fun handleDirectMessage(
        packet: com.meshwalk.app.domain.model.MeshPacket,
        ourNodeId: String
    ) {
        // Ensure we have a session with the sender before decrypting
        ensureSession(ourNodeId, packet.sourceNodeId)

        val message = envelopeManager.decryptFromPeer(packet, ourNodeId)
        if (message == null) {
            Timber.w("Failed to decrypt direct message from ${packet.sourceNodeId}")
            return
        }

        val senderPeer = peerRepo.getPeer(packet.sourceNodeId)
        val conversation = conversationRepo.getOrCreateDirectConversation(packet.sourceNodeId, senderPeer?.displayName)

        val localMessage = message.copy(
            conversationId = conversation.conversationId,
            deliveryStatus = DeliveryStatus.DELIVERED
        )

        if (messageRepo.getMessageById(localMessage.messageId) != null) {
            Timber.d("Duplicate message ${localMessage.messageId}, skipping")
            return
        }

        messageRepo.saveMessage(localMessage)

        val preview = when (localMessage.content) {
            is MessageContent.Text -> localMessage.content.text
            is MessageContent.SystemEvent -> localMessage.content.event
        }
        conversationRepo.updateLastMessage(conversation.conversationId, preview, localMessage.timestamp)
        conversationRepo.incrementUnread(conversation.conversationId)

        val senderName = senderPeer?.displayName ?: packet.sourceNodeId.take(8)
        notificationManager.showMessageNotification(
            conversationId = conversation.conversationId,
            senderName = senderName,
            messagePreview = preview,
            isGroupMessage = false
        )

        Timber.d("Received direct message from ${packet.sourceNodeId.take(8)}: ${preview.take(30)}")
    }

    /**
     * Ensure a pairwise session exists with the peer (needed for decryption).
     * Mirror of MeshOutbox.ensureSession().
     */
    private suspend fun ensureSession(ourNodeId: String, peerNodeId: String) {
        if (sessionManager.hasSession(ourNodeId, peerNodeId)) return

        val ourExchangeKey = keyStorage.getExchangePrivateKey(ourNodeId) ?: run {
            Timber.w("No exchange private key for $ourNodeId, cannot establish session")
            return
        }

        val peer = peerRepo.getPeer(peerNodeId)
        val peerExchangeKey = peer?.publicExchangeKey ?: run {
            Timber.w("No exchange public key for ${peerNodeId.take(8)}, cannot establish session")
            return
        }

        sessionManager.establishSessionWithKeys(
            ourNodeId = ourNodeId,
            peerNodeId = peerNodeId,
            ourExchangePrivateKey = ourExchangeKey,
            peerPublicExchangeKey = peerExchangeKey
        )
        Timber.d("Auto-established session with ${peerNodeId.take(8)} for receiving")
    }

    private suspend fun handleGroupMessage(
        packet: com.meshwalk.app.domain.model.MeshPacket,
        ourNodeId: String
    ) {
        // Extract the groupId prefix that was prepended by MeshOutbox.
        // Format: [groupIdLen:4][groupId bytes][actual encrypted payload]
        val groupIdAndPayload = extractGroupId(packet.encryptedPayload)
        if (groupIdAndPayload == null) {
            Timber.w("Failed to extract groupId from group message packet ${packet.packetId.take(8)}")
            return
        }

        val (groupId, actualPayload) = groupIdAndPayload

        // Look up the sender's key for this specific group
        val key = groupKeyManager.getReceivingKey(groupId, packet.sourceNodeId)
        if (key == null) {
            Timber.w("No group sender key for ${packet.sourceNodeId.take(8)} in group ${groupId.take(8)}")
            return
        }

        // Decrypt using the actual payload (without the groupId prefix)
        val decryptPacket = packet.copy(encryptedPayload = actualPayload)
        val message = envelopeManager.decryptForGroup(decryptPacket, groupId, key)
        if (message == null) {
            Timber.w("Failed to decrypt group message from ${packet.sourceNodeId.take(8)}")
            return
        }

        val localMessage = message.copy(deliveryStatus = DeliveryStatus.DELIVERED)

        if (messageRepo.getMessageById(localMessage.messageId) != null) {
            Timber.d("Duplicate group message ${localMessage.messageId}, skipping")
            return
        }

        messageRepo.saveMessage(localMessage)

        val preview = when (localMessage.content) {
            is MessageContent.Text -> localMessage.content.text
            is MessageContent.SystemEvent -> localMessage.content.event
        }
        conversationRepo.updateLastMessage(localMessage.conversationId, preview, localMessage.timestamp)
        conversationRepo.incrementUnread(localMessage.conversationId)

        val senderPeer = peerRepo.getPeer(packet.sourceNodeId)
        val senderName = senderPeer?.displayName ?: packet.sourceNodeId.take(8)
        notificationManager.showMessageNotification(
            conversationId = localMessage.conversationId,
            senderName = senderName,
            messagePreview = preview,
            isGroupMessage = true
        )

        Timber.d("Received group message in ${groupId.take(8)} from ${packet.sourceNodeId.take(8)}")
    }

    /**
     * Extract the groupId prefix from the wrapped payload.
     * Returns (groupId, actualEncryptedPayload) or null on failure.
     */
    private fun extractGroupId(payload: ByteArray): Pair<String, ByteArray>? {
        return try {
            val buffer = ByteBuffer.wrap(payload)
            val groupIdLen = buffer.getInt()
            if (groupIdLen <= 0 || groupIdLen > buffer.remaining()) return null
            val groupIdBytes = ByteArray(groupIdLen).also { buffer.get(it) }
            val groupId = String(groupIdBytes, Charsets.UTF_8)
            val actualPayload = ByteArray(buffer.remaining()).also { buffer.get(it) }
            Pair(groupId, actualPayload)
        } catch (e: Exception) {
            Timber.e(e, "Error extracting groupId from payload")
            null
        }
    }
}
