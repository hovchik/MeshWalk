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
import kotlinx.coroutines.delay
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

    override suspend fun enqueueMessage(message: MeshMessage, recipientNodeId: String) {
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
                Timber.w("Packet ${packet.packetId.take(8)} queued for later delivery")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to encrypt/send message ${message.messageId.take(8)}")
            messageRepo.updateDeliveryStatus(message.messageId, DeliveryStatus.FAILED)
            throw e
        }
    }

    companion object {
        /** Max time to wait for the peer's exchange key advertisement to arrive. */
        private const val KEY_EXCHANGE_TIMEOUT_MS = 5_000L
        private const val KEY_EXCHANGE_POLL_INTERVAL_MS = 250L
    }

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
            throw IllegalStateException(
                "Waiting for key exchange with ${peerDisplayName ?: peerNodeId.take(8)}. " +
                "Please try again in a moment."
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

        val senderKey = groupKeyManager.getSendingKey(groupId, senderNodeId)
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
            for (recipientNodeId in recipientNodeIds) {
                val memberPacket = packet.copy(
                    packetId = UUID.randomUUID().toString(),
                    destinationNodeId = recipientNodeId,
                    encryptedPayload = wrappedPayload
                )
                val sent = routingEngine.sendPacket(memberPacket)
                if (!sent) {
                    Timber.w("Group packet ${memberPacket.packetId.take(8)} queued for ${recipientNodeId.take(8)}")
                }
            }
            messageRepo.updateDeliveryStatus(message.messageId, DeliveryStatus.SENT)
        } catch (e: Exception) {
            Timber.e(e, "Failed to encrypt/send group message ${message.messageId.take(8)}")
            messageRepo.updateDeliveryStatus(message.messageId, DeliveryStatus.FAILED)
            throw e
        }
    }
}
