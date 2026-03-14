package com.meshwalk.app.crypto.envelope

import com.meshwalk.app.crypto.keys.MeshKeyManager
import com.meshwalk.app.crypto.session.SessionManager
import com.meshwalk.app.domain.model.MeshMessage
import com.meshwalk.app.domain.model.MeshPacket
import com.meshwalk.app.domain.model.MessageContent
import com.meshwalk.app.domain.model.PacketType
import timber.log.Timber
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles message encryption and decryption.
 * Produces MeshPackets (encrypted wire format) from MeshMessages (plaintext).
 *
 * Encryption scheme: AES-256-GCM with 12-byte nonce.
 * Authenticated additional data (AAD): packet header fields to prevent header tampering.
 * Signature: ECDSA over (header || ciphertext) for sender authentication.
 */
@Singleton
class MessageEnvelopeManager @Inject constructor(
    private val sessionManager: SessionManager,
    private val keyManager: MeshKeyManager
) {
    companion object {
        private const val GCM_NONCE_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128 // bits
        private const val AES_GCM = "AES/GCM/NoPadding"
    }

    private val secureRandom = SecureRandom()

    /**
     * Encrypt a message for a specific peer, producing a MeshPacket.
     */
    suspend fun encryptForPeer(
        message: MeshMessage,
        senderNodeId: String,
        recipientNodeId: String,
        signingPrivateKey: ByteArray
    ): MeshPacket {
        val sessionId = buildSessionId(senderNodeId, recipientNodeId)

        // Get message key from session chain ratchet
        val (messageKey, counter) = sessionManager.advanceSendingChain(sessionId)

        // Serialize message content
        val plaintext = serializeMessage(message)

        // Generate nonce
        val nonce = ByteArray(GCM_NONCE_LENGTH).also { secureRandom.nextBytes(it) }

        // Encrypt with AES-256-GCM
        val ciphertext = encryptAesGcm(plaintext, messageKey, nonce, buildAad(senderNodeId, recipientNodeId, counter))

        // Prepend the 4-byte counter to the ciphertext so the receiver can derive
        // the correct message key and AAD for decryption.
        val counterPrefixedPayload = ByteBuffer.allocate(4 + ciphertext.size)
            .putInt(counter)
            .put(ciphertext)
            .array()

        // Sign the packet (header fields + ciphertext)
        val signingKey = KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(signingPrivateKey))
        val dataToSign = buildSignatureInput(senderNodeId, recipientNodeId, counterPrefixedPayload, nonce)
        val signature = keyManager.sign(dataToSign, signingKey)

        return MeshPacket(
            sourceNodeId = senderNodeId,
            destinationNodeId = recipientNodeId,
            packetType = PacketType.MESSAGE,
            encryptedPayload = counterPrefixedPayload,
            nonce = nonce,
            senderSignature = signature,
            flags = MeshPacket.FLAG_ACK_REQUESTED
        )
    }

    /**
     * Decrypt a received MeshPacket into a MeshMessage.
     */
    suspend fun decryptFromPeer(
        packet: MeshPacket,
        ourNodeId: String
    ): MeshMessage? {
        return try {
            val sessionId = buildSessionId(ourNodeId, packet.sourceNodeId)

            // Extract the 4-byte counter prefix from the payload
            val payloadBuffer = ByteBuffer.wrap(packet.encryptedPayload)
            val counter = payloadBuffer.getInt()
            val ciphertext = ByteArray(payloadBuffer.remaining()).also { payloadBuffer.get(it) }

            // Derive the receiving key for this message's counter,
            // ratcheting the chain forward as needed
            val messageKey = sessionManager.deriveReceivingKey(sessionId, counter)

            // Decrypt using the actual counter for AAD
            val plaintext = decryptAesGcm(
                ciphertext,
                messageKey,
                packet.nonce,
                buildAad(packet.sourceNodeId, ourNodeId, counter)
            )

            // Deserialize
            deserializeMessage(plaintext, packet)
        } catch (e: Exception) {
            Timber.e(e, "Failed to decrypt message from ${packet.sourceNodeId}")
            null
        }
    }

    /**
     * Encrypt a message for a group using the sender's group chain key.
     * All group members with the sender's key can decrypt.
     */
    suspend fun encryptForGroup(
        message: MeshMessage,
        senderNodeId: String,
        groupId: String,
        groupSenderKey: SecretKey,
        signingPrivateKey: ByteArray
    ): MeshPacket {
        val plaintext = serializeMessage(message)
        val nonce = ByteArray(GCM_NONCE_LENGTH).also { secureRandom.nextBytes(it) }

        val ciphertext = encryptAesGcm(
            plaintext,
            groupSenderKey,
            nonce,
            buildAad(senderNodeId, groupId, 0)
        )

        val signingKey = KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(signingPrivateKey))
        val signature = keyManager.sign(
            buildSignatureInput(senderNodeId, groupId, ciphertext, nonce),
            signingKey
        )

        return MeshPacket(
            sourceNodeId = senderNodeId,
            destinationNodeId = groupId,
            packetType = PacketType.MESSAGE,
            encryptedPayload = ciphertext,
            nonce = nonce,
            senderSignature = signature,
            flags = MeshPacket.FLAG_ACK_REQUESTED or MeshPacket.FLAG_GROUP_MESSAGE
        )
    }

    /**
     * Decrypt a received group MeshPacket into a MeshMessage.
     * Uses the sender's group key from GroupKeyManager.
     */
    fun decryptForGroup(
        packet: MeshPacket,
        groupId: String,
        receivingKey: SecretKey
    ): MeshMessage? {
        return try {
            val plaintext = decryptAesGcm(
                packet.encryptedPayload,
                receivingKey,
                packet.nonce,
                buildAad(packet.sourceNodeId, groupId, 0)
            )
            deserializeMessage(plaintext, packet)
        } catch (e: Exception) {
            Timber.e(e, "Failed to decrypt group message from ${packet.sourceNodeId}")
            null
        }
    }

    // -- AES-GCM operations --

    fun encryptAesGcm(
        plaintext: ByteArray,
        key: SecretKey,
        nonce: ByteArray,
        aad: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    fun decryptAesGcm(
        ciphertext: ByteArray,
        key: SecretKey,
        nonce: ByteArray,
        aad: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    // -- Serialization --

    private fun serializeMessage(message: MeshMessage): ByteArray {
        val content = when (message.content) {
            is MessageContent.Text -> "T:${message.content.text}"
            is MessageContent.SystemEvent -> "S:${message.content.event}"
        }
        val payload = "${message.messageId}|${message.conversationId}|${message.timestamp}|$content"
        return payload.toByteArray(Charsets.UTF_8)
    }

    private fun deserializeMessage(data: ByteArray, packet: MeshPacket): MeshMessage {
        val payload = String(data, Charsets.UTF_8)
        val parts = payload.split("|", limit = 4)

        val content = when {
            parts[3].startsWith("T:") -> MessageContent.Text(parts[3].removePrefix("T:"))
            parts[3].startsWith("S:") -> MessageContent.SystemEvent(parts[3].removePrefix("S:"))
            else -> MessageContent.Text(parts[3])
        }

        return MeshMessage(
            messageId = parts[0],
            conversationId = parts[1],
            senderNodeId = packet.sourceNodeId,
            content = content,
            timestamp = parts[2].toLongOrNull() ?: System.currentTimeMillis(),
            isIncoming = true,
            hopCount = packet.hopCount
        )
    }

    // -- Helper builders --

    private fun buildAad(source: String, dest: String, counter: Int): ByteArray {
        return "$source|$dest|$counter".toByteArray(Charsets.UTF_8)
    }

    private fun buildSignatureInput(
        source: String,
        dest: String,
        ciphertext: ByteArray,
        nonce: ByteArray
    ): ByteArray {
        val header = "$source|$dest".toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(header.size + nonce.size + ciphertext.size)
            .put(header)
            .put(nonce)
            .put(ciphertext)
            .array()
    }

    private fun buildSessionId(nodeA: String, nodeB: String): String {
        return if (nodeA < nodeB) "$nodeA:$nodeB" else "$nodeB:$nodeA"
    }
}
