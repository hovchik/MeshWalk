package com.meshwalk.app.mesh.inbox

import com.meshwalk.app.crypto.envelope.MessageEnvelopeManager
import com.meshwalk.app.domain.model.DeliveryStatus
import com.meshwalk.app.domain.model.MeshMessage
import com.meshwalk.app.domain.model.MessageContent
import com.meshwalk.app.domain.model.PacketType
import com.meshwalk.app.domain.repository.ConversationRepository
import com.meshwalk.app.domain.repository.MessageRepository
import com.meshwalk.app.routing.engine.MeshRoutingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Inbound message processor.
 *
 * Subscribes to [MeshRoutingEngine.incomingPackets], decrypts each packet
 * via [MessageEnvelopeManager], persists the resulting [MeshMessage] to the
 * database, and updates the corresponding conversation.
 *
 * This is the receiving counterpart of [com.meshwalk.app.mesh.outbox.MeshOutbox].
 */
@Singleton
class MeshInbox @Inject constructor(
    private val routingEngine: MeshRoutingEngine,
    private val envelopeManager: MessageEnvelopeManager,
    private val messageRepo: MessageRepository,
    private val conversationRepo: ConversationRepository
) {

    fun start(ourNodeId: String, scope: CoroutineScope) {
        scope.launch {
            routingEngine.incomingPackets.collect { packet ->
                if (packet.packetType != PacketType.MESSAGE) {
                    Timber.d("Ignoring non-message packet: ${packet.packetType}")
                    return@collect
                }

                try {
                    val message = envelopeManager.decryptFromPeer(packet, ourNodeId)
                    if (message == null) {
                        Timber.w("Failed to decrypt packet ${packet.packetId} from ${packet.sourceNodeId}")
                        return@collect
                    }

                    // Ensure conversation exists
                    val conversation = conversationRepo.getOrCreateDirectConversation(packet.sourceNodeId)

                    // Update conversationId to match the local conversation
                    val localMessage = message.copy(
                        conversationId = conversation.conversationId,
                        deliveryStatus = DeliveryStatus.DELIVERED
                    )

                    // Avoid saving duplicate messages
                    val existing = messageRepo.getMessageById(localMessage.messageId)
                    if (existing != null) {
                        Timber.d("Duplicate message ${localMessage.messageId}, skipping")
                        return@collect
                    }

                    messageRepo.saveMessage(localMessage)

                    val preview = when (localMessage.content) {
                        is MessageContent.Text -> localMessage.content.text
                        is MessageContent.SystemEvent -> localMessage.content.event
                    }
                    conversationRepo.updateLastMessage(
                        conversation.conversationId,
                        preview,
                        localMessage.timestamp
                    )
                    conversationRepo.incrementUnread(conversation.conversationId)

                    Timber.d("Received message from ${packet.sourceNodeId}: ${preview.take(30)}")
                } catch (e: Exception) {
                    Timber.e(e, "Error processing incoming packet ${packet.packetId}")
                }
            }
        }
        Timber.d("MeshInbox started for node $ourNodeId")
    }
}
