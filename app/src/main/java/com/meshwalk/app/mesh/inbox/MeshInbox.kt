package com.meshwalk.app.mesh.inbox

import com.meshwalk.app.crypto.envelope.MessageEnvelopeManager
import com.meshwalk.app.crypto.group.GroupKeyManager
import com.meshwalk.app.domain.model.DeliveryStatus
import com.meshwalk.app.domain.model.MessageContent
import com.meshwalk.app.domain.model.PacketType
import com.meshwalk.app.domain.repository.ConversationRepository
import com.meshwalk.app.domain.repository.MessageRepository
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
    private val groupControlManager: GroupControlManager
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

        // The conversationId in the decrypted message should be the groupId
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

    /**
     * Find a group receiving key for a given sender across all our groups.
     */
    private fun findGroupReceivingKey(
        senderNodeId: String
    ): Pair<String, javax.crypto.SecretKey>? {
        // GroupKeyManager stores keys by groupId → senderNodeId
        // We need to iterate groups where we have this sender's key
        // The GroupKeyManager uses in-memory ConcurrentHashMap, so we can check directly
        return groupKeyManager.findReceivingKeyForSender(senderNodeId)
    }
}
