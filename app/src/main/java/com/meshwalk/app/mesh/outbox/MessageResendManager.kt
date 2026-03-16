package com.meshwalk.app.mesh.outbox

import com.meshwalk.app.domain.model.ConversationType
import com.meshwalk.app.domain.model.DeliveryStatus
import com.meshwalk.app.domain.model.MeshMessage
import com.meshwalk.app.domain.repository.ConversationRepository
import com.meshwalk.app.domain.repository.GroupRepository
import com.meshwalk.app.domain.repository.MessageRepository
import com.meshwalk.app.domain.usecase.MeshOutboxPort
import com.meshwalk.app.routing.queue.OfflineQueue
import com.meshwalk.app.transport.api.TransportEvent
import com.meshwalk.app.transport.manager.TransportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Automatically resends undelivered messages when their recipient peer comes back online.
 *
 * Listens for [TransportEvent.Connected] events and queries for any outgoing messages
 * with FAILED or stale PENDING delivery status that belong to conversations with that
 * peer. Each message is re-enqueued through the outbox for encryption and routing.
 *
 * Stale PENDING messages arise when the routing engine queued a packet in the offline
 * queue (returning false from sendPacket) and the QueuedMessageHandler eventually gave
 * up. The packet is gone but the message was never marked FAILED or SENT.
 */
@Singleton
class MessageResendManager @Inject constructor(
    private val transportManager: TransportManager,
    private val messageRepo: MessageRepository,
    private val conversationRepo: ConversationRepository,
    private val groupRepo: GroupRepository,
    private val meshOutbox: MeshOutboxPort,
    private val offlineQueue: OfflineQueue
) {

    companion object {
        /** PENDING messages older than this are considered stale and should be resent. */
        private const val STALE_PENDING_THRESHOLD_MS = 30_000L
    }

    /** Prevents concurrent resend attempts for the same peer. */
    private val resendMutex = Mutex()

    /** Tracks peers currently being resent to, so we skip duplicate Connected events. */
    private val activeResends = mutableSetOf<String>()

    fun start(ourNodeId: String, scope: CoroutineScope) {
        scope.launch {
            transportManager.transportEvents.collect { event ->
                if (event is TransportEvent.Connected && event.nodeId != null) {
                    resendUndeliveredMessages(ourNodeId, event.nodeId!!)
                }
            }
        }
        Timber.d("MessageResendManager started")
    }

    private suspend fun resendUndeliveredMessages(ourNodeId: String, peerNodeId: String) {
        // Guard against concurrent resends for the same peer (e.g. rapid Connected events)
        resendMutex.withLock {
            if (activeResends.contains(peerNodeId)) {
                Timber.d("Resend already in progress for ${peerNodeId.take(8)}, skipping")
                return
            }
            activeResends.add(peerNodeId)
        }

        try {
            val failedMessages = messageRepo.getFailedMessagesForPeer(peerNodeId)
            val stalePendingMessages = messageRepo.getStalePendingMessagesForPeer(
                peerNodeId,
                System.currentTimeMillis() - STALE_PENDING_THRESHOLD_MS
            )

            val messagesToResend = (failedMessages + stalePendingMessages)
                .distinctBy { it.messageId }

            if (messagesToResend.isEmpty()) return

            Timber.d("Peer ${peerNodeId.take(8)} reconnected, resending ${messagesToResend.size} undelivered message(s) (${failedMessages.size} failed, ${stalePendingMessages.size} stale pending)")

            // Clear any stale queued packets for this peer before re-encrypting,
            // to prevent the offline queue from also delivering the old packets.
            clearQueuedPacketsForPeer(peerNodeId)

            for (message in messagesToResend) {
                resendMessage(ourNodeId, peerNodeId, message)
            }
        } finally {
            resendMutex.withLock {
                activeResends.remove(peerNodeId)
            }
        }
    }

    /**
     * Remove stale queued packets destined for this peer.
     * Prevents the offline queue from delivering old packets at the same time
     * the resend manager is creating fresh ones with up-to-date encryption.
     */
    private fun clearQueuedPacketsForPeer(peerNodeId: String) {
        val stalePackets = offlineQueue.getPacketsForDestination(peerNodeId)
        for (packet in stalePackets) {
            offlineQueue.remove(packet.packetId)
        }
        if (stalePackets.isNotEmpty()) {
            Timber.d("Cleared ${stalePackets.size} stale queued packet(s) for ${peerNodeId.take(8)} before resend")
        }
    }

    private suspend fun resendMessage(ourNodeId: String, peerNodeId: String, message: MeshMessage) {
        try {
            // Mark as PENDING before resend attempt
            messageRepo.updateDeliveryStatus(message.messageId, DeliveryStatus.PENDING)

            val conversation = conversationRepo.getConversation(message.conversationId)
            if (conversation == null) {
                Timber.w("Conversation ${message.conversationId.take(8)} deleted, marking message FAILED")
                messageRepo.updateDeliveryStatus(message.messageId, DeliveryStatus.FAILED)
                return
            }

            when (conversation.type) {
                ConversationType.DIRECT -> {
                    meshOutbox.enqueueMessage(message, peerNodeId)
                }
                ConversationType.GROUP, ConversationType.TEMPORARY_GROUP -> {
                    val group = groupRepo.getGroup(conversation.conversationId)
                    if (group == null) {
                        Timber.w("Group ${conversation.conversationId.take(8)} deleted, marking message FAILED")
                        messageRepo.updateDeliveryStatus(message.messageId, DeliveryStatus.FAILED)
                        return
                    }
                    // Only resend to the reconnected peer rather than all members.
                    // Other members either already received it or will trigger their
                    // own resend when they reconnect. Receiver-side messageId dedup
                    // prevents duplicates if they already got it via a different path.
                    val isMember = group.members.any { it.nodeId == peerNodeId }
                    if (isMember) {
                        meshOutbox.enqueueGroupMessage(message, listOf(peerNodeId), group.groupId)
                    } else {
                        Timber.d("Peer ${peerNodeId.take(8)} not in group, skipping group resend")
                        return
                    }
                }
                ConversationType.BROADCAST -> {
                    Timber.d("Broadcast resend not supported, marking message FAILED")
                    messageRepo.updateDeliveryStatus(message.messageId, DeliveryStatus.FAILED)
                    return
                }
            }

            Timber.d("Resent message ${message.messageId.take(8)} to ${peerNodeId.take(8)}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to resend message ${message.messageId.take(8)}")
            messageRepo.updateDeliveryStatus(message.messageId, DeliveryStatus.FAILED)
        }
    }
}
