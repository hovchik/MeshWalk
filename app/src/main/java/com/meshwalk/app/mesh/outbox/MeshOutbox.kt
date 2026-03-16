package com.meshwalk.app.mesh.outbox

import com.meshwalk.app.crypto.envelope.MessageEnvelopeManager
import com.meshwalk.app.crypto.group.GroupKeyManager
import com.meshwalk.app.crypto.keys.KeyStorage
import com.meshwalk.app.crypto.session.SessionManager
import com.meshwalk.app.domain.model.DeliveryStatus
import com.meshwalk.app.domain.model.MeshMessage
import com.meshwalk.app.domain.repository.MessageRepository
import com.meshwalk.app.domain.repository.PeerRepository
import com.meshwalk.app.domain.usecase.MeshOutboxPort
import com.meshwalk.app.routing.engine.MeshRoutingEngine
import kotlinx.coroutines.*
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete implementation of [MeshOutboxPort].
 *
 * Bridges the domain use-case layer and the mesh networking stack:
 *   UseCase -> MeshOutbox -> (encrypt) -> RoutingEngine -> (route) -> Transport
 *
 * For direct messages, the outgoing [MeshMessage] is encrypted with the peer's
 * session key via [MessageEnvelopeManager], signed with our ECDSA private key,
 * and handed to the routing engine for multi-hop delivery.
 *
 * For group messages, the Sender Keys model is used: each member encrypts with
 * their own sender key so that every group member (except self) can decrypt.
 */
@Singleton
class MeshOutbox @Inject constructor(
    private val envelopeManager: MessageEnvelopeManager,
    private val groupKeyManager: GroupKeyManager,
    private val keyStorage: KeyStorage,
    private val sessionManager: SessionManager,
    private val peerRepo: PeerRepository,
    private val routingEngine: MeshRoutingEngine,
    private val messageRepo: MessageRepository
) : MeshOutboxPort {

    private val retryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun enqueueMessage(message: MeshMessage, recipientNodeId: String) {
        trySendMessage(message, recipientNodeId, autoRetryAttempt = 0)
    }

    /**
     * Core send logic. When [autoRetryAttempt] is 0 this is the initial attempt
     * triggered by the user; on session-not-ready failures, automatic retries
     * are scheduled with an incrementing attempt counter. The message stays
     * PENDING during retries so the user sees the watch icon.
     */
    private suspend fun trySendMessage(
        message: MeshMessage,
        recipientNodeId: String,
        autoRetryAttempt: Int
    ) {
        val senderNodeId = message.senderNodeId
        val signingKey = keyStorage.getSigningPrivateKey(senderNodeId)
        if (signingKey == null) {
            Timber.e("Cannot send message: signing key not found for $senderNodeId")
            messageRepo.updateDeliveryStatus(message.messageId, DeliveryStatus.FAILED)
            throw IllegalStateException("Signing key not found. Please re-create your identity.")
        }

        try {
            // Ensure a pairwise session exists before encrypting
            ensureSession(senderNodeId, recipientNodeId)

            val packet = envelopeManager.encryptForPeer(
                message = message,
                senderNodeId = senderNodeId,
                recipientNodeId = recipientNodeId,
                signingPrivateKey = signingKey
            )
            val sent = routingEngine.sendPacket(packet)
            if (sent) {
                messageRepo.updateDeliveryStatus(message.messageId, DeliveryStatus.SENT)
            } else {
                // Packet was queued for store-and-forward delivery.
                // Keep status as PENDING so the MessageResendManager can pick it up
                // if the queue retry cycle exhausts all attempts without success.
                Timber.w("Packet ${packet.packetId.take(8)} queued for later delivery (message stays PENDING)")
            }
        } catch (e: SessionNotReadyException) {
            // Session not ready yet — schedule automatic retry.
            // Message stays PENDING so the user sees the watch icon.
            Timber.w("Session not ready for message ${message.messageId.take(8)}: ${e.message}")
            scheduleRetry(message, recipientNodeId, autoRetryAttempt + 1)
        } catch (e: Exception) {
            Timber.e(e, "Failed to encrypt/send message ${message.messageId.take(8)}")
            messageRepo.updateDeliveryStatus(message.messageId, DeliveryStatus.FAILED)
            throw e
        }
    }

    /**
     * Schedule an automatic retry for a message whose session establishment
     * timed out. Retries up to [MAX_AUTO_RETRIES] times with increasing delays.
     * The message stays PENDING during retries so the user sees the watch icon.
     */
    private fun scheduleRetry(
        message: MeshMessage,
        recipientNodeId: String,
        attempt: Int
    ) {
        if (attempt > MAX_AUTO_RETRIES) {
            Timber.w("Auto-retry exhausted for message ${message.messageId.take(8)}, marking FAILED")
            retryScope.launch {
                messageRepo.updateDeliveryStatus(message.messageId, DeliveryStatus.FAILED)
            }
            return
        }

        val delayMs = RETRY_DELAYS_MS[attempt - 1]
        Timber.d("Auto-retry ${attempt}/$MAX_AUTO_RETRIES for message ${message.messageId.take(8)} in ${delayMs}ms")

        retryScope.launch {
            delay(delayMs)
            try {
                trySendMessage(message, recipientNodeId, autoRetryAttempt = attempt)
            } catch (e: Exception) {
                Timber.e(e, "Auto-retry $attempt failed for message ${message.messageId.take(8)}")
                messageRepo.updateDeliveryStatus(message.messageId, DeliveryStatus.FAILED)
            }
        }
    }

    companion object {
        /** Max time to wait for the peer's exchange key advertisement to arrive. */
        private const val KEY_EXCHANGE_TIMEOUT_MS = 10_000L
        private const val KEY_EXCHANGE_POLL_INTERVAL_MS = 250L
        private const val MAX_AUTO_RETRIES = 3
        private val RETRY_DELAYS_MS = longArrayOf(3_000L, 5_000L, 8_000L)
    }

    /** Thrown when the peer's exchange key has not arrived yet. */
    class SessionNotReadyException(message: String) : Exception(message)

    /**
     * Ensure a pairwise session exists with the peer.
     * If no session exists, establish one using ECDH with the peer's exchange key.
     *
     * The peer's publicExchangeKey arrives asynchronously via an advertisement
     * payload after connection. This method waits briefly for it to arrive
     * rather than failing immediately.
     */
    private suspend fun ensureSession(ourNodeId: String, peerNodeId: String) {
        if (sessionManager.hasSession(ourNodeId, peerNodeId)) return

        val ourExchangeKey = keyStorage.getExchangePrivateKey(ourNodeId)
            ?: throw IllegalStateException("No exchange private key for $ourNodeId")

        // Wait for the peer's exchange key to arrive via advertisement
        var peerExchangeKey: ByteArray? = null
        var peerDisplayName: String? = null
        val deadline = System.currentTimeMillis() + KEY_EXCHANGE_TIMEOUT_MS

        while (peerExchangeKey == null && System.currentTimeMillis() < deadline) {
            val peer = peerRepo.getPeer(peerNodeId)
            peerDisplayName = peer?.displayName
            peerExchangeKey = peer?.publicExchangeKey
            if (peerExchangeKey == null) {
                delay(KEY_EXCHANGE_POLL_INTERVAL_MS)
            }
        }

        if (peerExchangeKey == null) {
            throw SessionNotReadyException(
                "Waiting for key exchange with ${peerDisplayName ?: peerNodeId.take(8)}."
            )
        }

        sessionManager.establishSessionWithKeys(
            ourNodeId = ourNodeId,
            peerNodeId = peerNodeId,
            ourExchangePrivateKey = ourExchangeKey,
            peerPublicExchangeKey = peerExchangeKey
        )
        Timber.d("Auto-established session with ${peerNodeId.take(8)} before sending")
    }

    /**
     * Wait for the group sender key to become available.
     * The key is generated locally during group creation/acceptance, but
     * the key distribution control messages may still be in flight.
     */
    private suspend fun ensureGroupSenderKey(
        groupId: String,
        senderNodeId: String
    ): javax.crypto.SecretKey? {
        var key = groupKeyManager.getSendingKey(groupId, senderNodeId)
        if (key != null) return key

        val deadline = System.currentTimeMillis() + KEY_EXCHANGE_TIMEOUT_MS
        while (key == null && System.currentTimeMillis() < deadline) {
            delay(KEY_EXCHANGE_POLL_INTERVAL_MS)
            key = groupKeyManager.getSendingKey(groupId, senderNodeId)
        }
        return key
    }

    override suspend fun enqueueGroupMessage(
        message: MeshMessage,
        recipientNodeIds: List<String>,
        groupId: String
    ) {
        val senderNodeId = message.senderNodeId
        val signingKey = keyStorage.getSigningPrivateKey(senderNodeId)
        if (signingKey == null) {
            Timber.e("Cannot send group message: signing key not found for $senderNodeId")
            messageRepo.updateDeliveryStatus(message.messageId, DeliveryStatus.FAILED)
            throw IllegalStateException("Signing key not found. Please re-create your identity.")
        }

        // Wait for the sender key to become available — it may still be
        // propagating through group control messages after joining.
        val senderKey = ensureGroupSenderKey(groupId, senderNodeId)
        if (senderKey == null) {
            Timber.e("Cannot send group message: sender key not found for group $groupId")
            messageRepo.updateDeliveryStatus(message.messageId, DeliveryStatus.FAILED)
            throw IllegalStateException("Group encryption key not found. Try leaving and rejoining the group.")
        }

        try {
            // Encrypt once with the sender key
            val packet = envelopeManager.encryptForGroup(
                message = message,
                senderNodeId = senderNodeId,
                groupId = groupId,
                groupSenderKey = senderKey,
                signingPrivateKey = signingKey
            )

            // Prepend groupId to the encrypted payload so the receiver can identify
            // which group this message belongs to (the destinationNodeId will be
            // overwritten with each recipient's nodeId for routing).
            val groupIdBytes = groupId.toByteArray(Charsets.UTF_8)
            val wrappedPayload = ByteBuffer.allocate(4 + groupIdBytes.size + packet.encryptedPayload.size)
                .putInt(groupIdBytes.size)
                .put(groupIdBytes)
                .put(packet.encryptedPayload)
                .array()

            // Send to each member with a unique packetId (to avoid deduplication)
            // and the wrapped payload containing the groupId prefix.
            var allSent = true
            for (recipientNodeId in recipientNodeIds) {
                val memberPacket = packet.copy(
                    packetId = UUID.randomUUID().toString(),
                    destinationNodeId = recipientNodeId,
                    encryptedPayload = wrappedPayload
                )
                val sent = routingEngine.sendPacket(memberPacket)
                if (!sent) {
                    allSent = false
                    Timber.w("Group packet ${memberPacket.packetId.take(8)} queued for ${recipientNodeId.take(8)}")
                }
            }
            // Only mark SENT if all recipients were reached; otherwise keep
            // PENDING so MessageResendManager can retry the missing members.
            if (allSent) {
                messageRepo.updateDeliveryStatus(message.messageId, DeliveryStatus.SENT)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to encrypt/send group message ${message.messageId.take(8)}")
            messageRepo.updateDeliveryStatus(message.messageId, DeliveryStatus.FAILED)
            throw e
        }
    }
}
