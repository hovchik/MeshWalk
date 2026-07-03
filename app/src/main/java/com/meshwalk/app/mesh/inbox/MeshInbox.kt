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
import com.meshwalk.app.routing.dedup.PacketDeduplicator
import com.meshwalk.app.routing.engine.MeshRoutingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
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
    private val notificationManager: MessageNotificationManager,
    private val deduplicator: PacketDeduplicator
) {

    fun start(ourNodeId: String, scope: CoroutineScope) {
        scope.launch {
            routingEngine.incomingPackets.collect { packet ->
                try {
                    // Silently drop packets from blocked peers.
                    if (peerRepo.isBlocked(packet.sourceNodeId)) {
                        Timber.d("Dropping packet from blocked peer ${packet.sourceNodeId.take(8)}")
                        return@collect
                    }

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
                        PacketType.REACTION -> {
                            handleReaction(packet, ourNodeId)
                        }
                        else -> {
                            Timber.d("Ignoring packet type: ${packet.packetType}")
                        }
                    }
                } catch (e: SessionNotReadyException) {
                    // Session couldn't be established — clear dedup so this packet
                    // can be re-processed if it arrives again via another path or
                    // after the advertisement with the exchange key arrives.
                    deduplicator.clearPacket(packet.packetId)
                    Timber.w("Session not ready for packet ${packet.packetId.take(8)}, cleared dedup for retry")
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
        // Ensure we have a session with the sender before decrypting.
        // If session establishment fails, we cannot decrypt — but the packet is
        // already dedup-marked, so we must NOT silently drop it. Throw a
        // SessionNotReadyException so the collector can clear the dedup entry
        // and allow re-processing when the exchange key arrives.
        if (!ensureSession(ourNodeId, packet.sourceNodeId)) {
            throw SessionNotReadyException("Cannot establish session with ${packet.sourceNodeId.take(8)}")
        }

        val message = envelopeManager.decryptFromPeer(packet, ourNodeId)
        if (message == null) {
            Timber.w("Failed to decrypt direct message from ${packet.sourceNodeId}")
            return
        }

        val senderPeer = peerRepo.getPeer(packet.sourceNodeId)
        val conversation = conversationRepo.getOrCreateDirectConversation(packet.sourceNodeId, senderPeer?.displayName)

        val isDelayed = System.currentTimeMillis() - message.timestamp > DELAYED_THRESHOLD_MS
        val localMessage = message.copy(
            conversationId = conversation.conversationId,
            deliveryStatus = DeliveryStatus.DELIVERED,
            isDelayed = isDelayed,
            // Apply this device's disappearing-messages timer for the conversation.
            expiresAt = conversation.messageTtlMs?.let { System.currentTimeMillis() + it }
        )

        if (messageRepo.getMessageById(localMessage.messageId) != null) {
            Timber.d("Duplicate message ${localMessage.messageId}, skipping")
            return
        }

        messageRepo.saveMessage(localMessage)

        val preview = localMessage.content.previewText
        conversationRepo.updateLastMessage(conversation.conversationId, preview, localMessage.timestamp)
        conversationRepo.incrementUnread(conversation.conversationId)

        val senderName = senderPeer?.displayName ?: packet.sourceNodeId.take(8)
        notificationManager.showMessageNotification(
            conversationId = conversation.conversationId,
            senderName = senderName,
            messagePreview = preview,
            isGroupMessage = false,
            peerNodeId = packet.sourceNodeId
        )

        Timber.d("Received direct message from ${packet.sourceNodeId.take(8)}: ${preview.take(30)}")
    }

    private suspend fun handleReaction(
        packet: com.meshwalk.app.domain.model.MeshPacket,
        ourNodeId: String
    ) {
        // Reactions ride the same pairwise session as direct messages, so we
        // must establish one if we haven't already.
        if (!ensureSession(ourNodeId, packet.sourceNodeId)) {
            throw SessionNotReadyException("Cannot establish session with ${packet.sourceNodeId.take(8)} for reaction")
        }

        val reaction = envelopeManager.decryptReactionFromPeer(packet, ourNodeId)
        if (reaction == null) {
            Timber.w("Failed to decrypt reaction from ${packet.sourceNodeId}")
            return
        }

        val target = messageRepo.getMessageById(reaction.messageId)
        if (target == null) {
            Timber.d("Reaction for unknown message ${reaction.messageId.take(8)}, dropping")
            return
        }

        // Merge the reactor's entry into the target message's reaction map.
        val updated = target.reactions.toMutableMap()
        if (reaction.isRemoval) {
            updated.remove(packet.sourceNodeId)
        } else {
            updated[packet.sourceNodeId] = reaction.emoji
        }
        messageRepo.updateReactions(target.messageId, updated)

        // Only the original sender of the reacted-to message gets notified.
        // Relays and other recipients (e.g. other direct-session peers) should
        // silently update their local state without a notification.
        if (!target.isIncoming && target.senderNodeId == ourNodeId) {
            val senderPeer = peerRepo.getPeer(packet.sourceNodeId)
            val senderName = senderPeer?.displayName ?: packet.sourceNodeId.take(8)
            val preview = target.content.previewText.take(80)
            notificationManager.showReactionNotification(
                conversationId = target.conversationId,
                senderName = senderName,
                emoji = reaction.emoji,
                messagePreview = preview,
                isRemoval = reaction.isRemoval
            )
        }

        Timber.d(
            "Received reaction ${reaction.emoji} (remove=${reaction.isRemoval}) " +
                "from ${packet.sourceNodeId.take(8)} on message ${reaction.messageId.take(8)}"
        )
    }

    /**
     * Ensure a pairwise session exists with the peer (needed for decryption).
     * Mirror of MeshOutbox.ensureSession().
     *
     * @return true if a session is available, false if establishment failed.
     *         When false is returned, the caller should NOT attempt decryption
     *         and should allow the packet to be re-processed later.
     */
    private suspend fun ensureSession(ourNodeId: String, peerNodeId: String): Boolean {
        if (sessionManager.hasSession(ourNodeId, peerNodeId)) return true

        val ourExchangeKey = keyStorage.getExchangePrivateKey(ourNodeId) ?: run {
            Timber.w("No exchange private key for $ourNodeId, cannot establish session")
            return false
        }

        // The peer's exchange key arrives asynchronously via the advertisement
        // payload sent right after connection. Wait for it to arrive, using the
        // same timeout as the outbox (10 seconds) to handle mesh network latency.
        var peerExchangeKey: ByteArray? = null
        val deadline = System.currentTimeMillis() + KEY_EXCHANGE_TIMEOUT_MS
        while (peerExchangeKey == null && System.currentTimeMillis() < deadline) {
            peerExchangeKey = peerRepo.getPeer(peerNodeId)?.publicExchangeKey
            if (peerExchangeKey == null) {
                delay(KEY_EXCHANGE_POLL_INTERVAL_MS)
            }
        }

        if (peerExchangeKey == null) {
            Timber.w("No exchange public key for ${peerNodeId.take(8)} after ${KEY_EXCHANGE_TIMEOUT_MS}ms, cannot establish session")
            return false
        }

        return try {
            sessionManager.establishSessionWithKeys(
                ourNodeId = ourNodeId,
                peerNodeId = peerNodeId,
                ourExchangePrivateKey = ourExchangeKey,
                peerPublicExchangeKey = peerExchangeKey
            )
            Timber.d("Auto-established session with ${peerNodeId.take(8)} for receiving")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to establish session with ${peerNodeId.take(8)}")
            false
        }
    }

    companion object {
        /** If a message arrives more than 30 seconds after it was sent, mark it as delayed. */
        private const val DELAYED_THRESHOLD_MS = 30_000L
        /** Max time to wait for peer's exchange key to arrive (matches outbox timeout). */
        private const val KEY_EXCHANGE_TIMEOUT_MS = 10_000L
        private const val KEY_EXCHANGE_POLL_INTERVAL_MS = 250L
        /** Max time to wait for a group sender key to arrive via KEY_DISTRIBUTION. */
        private const val GROUP_KEY_WAIT_TIMEOUT_MS = 10_000L
        private const val GROUP_KEY_POLL_INTERVAL_MS = 250L
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

        // Look up the sender's key for this specific group.
        // The KEY_DISTRIBUTION packet may still be in flight, so wait briefly
        // for the key to arrive rather than silently dropping the message.
        var key = groupKeyManager.getReceivingKey(groupId, packet.sourceNodeId)
        if (key == null) {
            Timber.d("Waiting for sender key from ${packet.sourceNodeId.take(8)} in group ${groupId.take(8)}...")
            val deadline = System.currentTimeMillis() + GROUP_KEY_WAIT_TIMEOUT_MS
            while (key == null && System.currentTimeMillis() < deadline) {
                delay(GROUP_KEY_POLL_INTERVAL_MS)
                key = groupKeyManager.getReceivingKey(groupId, packet.sourceNodeId)
            }
        }
        if (key == null) {
            // Key still not available after waiting — clear dedup so the packet
            // can be re-processed if it arrives again via another path or after
            // the KEY_DISTRIBUTION packet finally arrives.
            deduplicator.clearPacket(packet.packetId)
            Timber.w("No group sender key for ${packet.sourceNodeId.take(8)} in group ${groupId.take(8)} after waiting, cleared dedup for retry")
            return
        }

        // Decrypt using the actual payload (without the groupId prefix)
        val decryptPacket = packet.copy(encryptedPayload = actualPayload)
        val message = envelopeManager.decryptForGroup(decryptPacket, groupId, key)
        if (message == null) {
            Timber.w("Failed to decrypt group message from ${packet.sourceNodeId.take(8)}")
            return
        }

        val isDelayed = System.currentTimeMillis() - message.timestamp > DELAYED_THRESHOLD_MS
        // Force the conversationId to the extracted groupId so the message is
        // saved in the correct group conversation on this device. The sender's
        // conversationId field in the decrypted envelope is not trusted here
        // because encrypted payloads from older senders or crafted packets
        // could carry an incorrect value.
        val groupTtl = conversationRepo.getConversation(groupId)?.messageTtlMs
        val localMessage = message.copy(
            conversationId = groupId,
            deliveryStatus = DeliveryStatus.DELIVERED,
            isDelayed = isDelayed,
            expiresAt = groupTtl?.let { System.currentTimeMillis() + it }
        )

        if (messageRepo.getMessageById(localMessage.messageId) != null) {
            Timber.d("Duplicate group message ${localMessage.messageId}, skipping")
            return
        }

        messageRepo.saveMessage(localMessage)

        val preview = localMessage.content.previewText
        conversationRepo.updateLastMessage(localMessage.conversationId, preview, localMessage.timestamp)
        conversationRepo.incrementUnread(localMessage.conversationId)

        val senderPeer = peerRepo.getPeer(packet.sourceNodeId)
        val senderName = senderPeer?.displayName ?: packet.sourceNodeId.take(8)
        notificationManager.showMessageNotification(
            conversationId = localMessage.conversationId,
            senderName = senderName,
            messagePreview = preview,
            isGroupMessage = true,
            peerNodeId = packet.sourceNodeId
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

/**
 * Thrown when a session cannot be established for decryption.
 * The caller should clear the dedup entry so the packet can be re-processed later.
 */
class SessionNotReadyException(message: String) : Exception(message)
