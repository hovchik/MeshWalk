package com.meshwalk.app.mesh.outbox

import com.meshwalk.app.domain.model.ConversationType
import com.meshwalk.app.domain.model.DeliveryStatus
import com.meshwalk.app.domain.repository.ConversationRepository
import com.meshwalk.app.domain.repository.GroupRepository
import com.meshwalk.app.domain.repository.MessageRepository
import com.meshwalk.app.domain.usecase.MeshOutboxPort
import com.meshwalk.app.transport.api.TransportEvent
import com.meshwalk.app.transport.manager.TransportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Automatically resends FAILED messages when their recipient peer comes back online.
 *
 * Listens for [TransportEvent.Connected] events and queries for any outgoing messages
 * with FAILED delivery status that belong to conversations with that peer. Each failed
 * message is re-enqueued through the outbox, which handles encryption and routing.
 */
@Singleton
class MessageResendManager @Inject constructor(
    private val transportManager: TransportManager,
    private val messageRepo: MessageRepository,
    private val conversationRepo: ConversationRepository,
    private val groupRepo: GroupRepository,
    private val meshOutbox: MeshOutboxPort
) {

    fun start(ourNodeId: String, scope: CoroutineScope) {
        scope.launch {
            transportManager.transportEvents.collect { event ->
                if (event is TransportEvent.Connected && event.nodeId != null) {
                    resendFailedMessages(ourNodeId, event.nodeId!!)
                }
            }
        }
        Timber.d("MessageResendManager started")
    }

    private suspend fun resendFailedMessages(ourNodeId: String, peerNodeId: String) {
        val failedMessages = messageRepo.getFailedMessagesForPeer(peerNodeId)
        if (failedMessages.isEmpty()) return

        Timber.d("Peer ${peerNodeId.take(8)} reconnected, resending ${failedMessages.size} failed message(s)")

        for (message in failedMessages) {
            try {
                // Mark as PENDING before resend attempt
                messageRepo.updateDeliveryStatus(message.messageId, DeliveryStatus.PENDING)

                val conversation = conversationRepo.getConversation(message.conversationId)
                if (conversation == null) {
                    Timber.w("Conversation ${message.conversationId.take(8)} not found, skipping resend")
                    continue
                }

                when (conversation.type) {
                    ConversationType.DIRECT -> {
                        meshOutbox.enqueueMessage(message, peerNodeId)
                    }
                    ConversationType.GROUP, ConversationType.TEMPORARY_GROUP -> {
                        val group = groupRepo.getGroup(conversation.conversationId)
                        if (group != null) {
                            val recipientIds = group.members
                                .filter { it.nodeId != ourNodeId }
                                .map { it.nodeId }
                            meshOutbox.enqueueGroupMessage(message, recipientIds, group.groupId)
                        }
                    }
                    ConversationType.BROADCAST -> {
                        // Broadcast resend not supported
                    }
                }

                Timber.d("Resent message ${message.messageId.take(8)} to ${peerNodeId.take(8)}")
            } catch (e: Exception) {
                Timber.e(e, "Failed to resend message ${message.messageId.take(8)}")
                messageRepo.updateDeliveryStatus(message.messageId, DeliveryStatus.FAILED)
            }
        }
    }
}
