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
    private val peerRepo: PeerRepository
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

        val conversation = conversationRepo.getOrCreateDirectConversation(packet.sourceNodeId)

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
        // The packet's destinationNodeId was overwritten to our nodeId for routing,
        // but the AAD used the groupId. Find the right group by looking up the sender's key.
        val receivingKey = findGroupReceivingKey(packet.sourceNodeId)
        if (receivingKey == null) {
            Timber.w("No group sender key for ${packet.sourceNodeId.take(8)}, cannot decrypt group message")
            return
        }

        val (groupId, key) = receivingKey
        val message = envelopeManager.decryptForGroup(packet, groupId, key)
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

        Timber.d("Received group message in ${groupId.take(8)} from ${packet.sourceNodeId.take(8)}")
    }

    private fun findGroupReceivingKey(
        senderNodeId: String
    ): Pair<String, javax.crypto.SecretKey>? {
        return groupKeyManager.findReceivingKeyForSender(senderNodeId)
    }
}
