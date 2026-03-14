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
import timber.log.Timber
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

    /**
     * Ensure a pairwise session exists with the peer.
     * If no session exists, establish one using ECDH with the peer's exchange key.
     */
    private suspend fun ensureSession(ourNodeId: String, peerNodeId: String) {
        if (sessionManager.hasSession(ourNodeId, peerNodeId)) return

        val ourExchangeKey = keyStorage.getExchangePrivateKey(ourNodeId)
            ?: throw IllegalStateException("No exchange private key for $ourNodeId")

        val peer = peerRepo.getPeer(peerNodeId)
        val peerExchangeKey = peer?.publicExchangeKey
            ?: throw IllegalStateException(
                "Waiting for key exchange with ${peer?.displayName ?: peerNodeId.take(8)}. " +
                "Please try again in a moment."
            )

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
            // Send the same encrypted packet to each member, only changing the routing destination
            for (recipientNodeId in recipientNodeIds) {
                val memberPacket = packet.copy(destinationNodeId = recipientNodeId)
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
