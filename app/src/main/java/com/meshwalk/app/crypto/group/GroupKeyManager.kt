package com.meshwalk.app.crypto.group

import com.meshwalk.app.crypto.envelope.MessageEnvelopeManager
import com.meshwalk.app.crypto.session.SessionManager
import timber.log.Timber
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages group encryption using the Sender Keys model.
 *
 * How it works:
 * 1. Each group member generates a "sender key" — a symmetric key chain
 * 2. The sender key is distributed to all other members, encrypted via pairwise sessions
 * 3. When a member sends a group message, they encrypt with their sender key
 * 4. All other members who have that sender's key can decrypt
 *
 * Benefits:
 * - Sender encrypts once regardless of group size
 * - Much more efficient than N pairwise encryptions
 *
 * Drawbacks:
 * - When a member is removed, all remaining members must rotate their sender keys
 * - No forward secrecy across sender key distributions
 *
 * This is the same approach used by Signal for group messages.
 */
@Singleton
class GroupKeyManager @Inject constructor(
    private val sessionManager: SessionManager,
    private val envelopeManager: MessageEnvelopeManager
) {
    private val secureRandom = SecureRandom()

    // groupId -> (senderNodeId -> SenderKeyState)
    private val groupSenderKeys = ConcurrentHashMap<String, ConcurrentHashMap<String, SenderKeyState>>()

    /**
     * Generate a new sender key for ourselves in a group.
     */
    fun generateSenderKey(groupId: String, ourNodeId: String): SenderKeyState {
        val keyBytes = ByteArray(32).also { secureRandom.nextBytes(it) }
        val senderKey = SenderKeyState(
            groupId = groupId,
            senderNodeId = ourNodeId,
            chainKey = SecretKeySpec(keyBytes, "AES"),
            iteration = 0,
            createdAt = System.currentTimeMillis()
        )

        groupSenderKeys.getOrPut(groupId) { ConcurrentHashMap() }[ourNodeId] = senderKey
        Timber.d("Generated sender key for group=$groupId, sender=$ourNodeId")
        return senderKey
    }

    /**
     * Store a sender key received from another group member.
     */
    fun storeSenderKey(groupId: String, senderNodeId: String, chainKeyBytes: ByteArray) {
        val state = SenderKeyState(
            groupId = groupId,
            senderNodeId = senderNodeId,
            chainKey = SecretKeySpec(chainKeyBytes, "AES"),
            iteration = 0,
            createdAt = System.currentTimeMillis()
        )
        groupSenderKeys.getOrPut(groupId) { ConcurrentHashMap() }[senderNodeId] = state
        Timber.d("Stored sender key for group=$groupId from sender=$senderNodeId")
    }

    /**
     * Get the current sender key for encrypting a group message we're sending.
     * Advances the chain key after each use.
     */
    fun getSendingKey(groupId: String, ourNodeId: String): SecretKey? {
        val state = groupSenderKeys[groupId]?.get(ourNodeId) ?: return null

        // Derive message key from chain key
        val messageKey = deriveMessageKey(state.chainKey, state.iteration)

        // Advance chain
        val newChainKey = advanceChain(state.chainKey)
        val updated = state.copy(
            chainKey = newChainKey,
            iteration = state.iteration + 1
        )
        groupSenderKeys[groupId]!![ourNodeId] = updated

        return messageKey
    }

    /**
     * Get the sender key for decrypting a group message from another member.
     */
    fun getReceivingKey(groupId: String, senderNodeId: String): SecretKey? {
        val state = groupSenderKeys[groupId]?.get(senderNodeId) ?: return null
        return deriveMessageKey(state.chainKey, state.iteration)
    }

    /**
     * Rotate all sender keys in a group (required when a member is removed).
     */
    fun rotateGroupKeys(groupId: String, remainingMemberIds: List<String>, ourNodeId: String): SenderKeyState? {
        // Remove old keys
        groupSenderKeys.remove(groupId)

        // Generate new sender key for ourselves
        return if (ourNodeId in remainingMemberIds) {
            generateSenderKey(groupId, ourNodeId)
        } else {
            null
        }
    }

    /**
     * Serialize a sender key for distribution (encrypted via pairwise session).
     */
    fun serializeSenderKey(groupId: String, ourNodeId: String): ByteArray? {
        val state = groupSenderKeys[groupId]?.get(ourNodeId) ?: return null
        return state.chainKey.encoded
    }

    /**
     * Check if we have all necessary sender keys for a group.
     */
    fun hasAllSenderKeys(groupId: String, memberNodeIds: List<String>): Boolean {
        val keys = groupSenderKeys[groupId] ?: return false
        return memberNodeIds.all { keys.containsKey(it) }
    }

    /**
     * Get members whose sender keys we're missing.
     */
    fun getMissingSenderKeys(groupId: String, memberNodeIds: List<String>): List<String> {
        val keys = groupSenderKeys[groupId] ?: return memberNodeIds
        return memberNodeIds.filter { !keys.containsKey(it) }
    }

    private fun deriveMessageKey(chainKey: SecretKey, iteration: Int): SecretKey {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(chainKey)
        val derived = mac.doFinal("msg-key-$iteration".toByteArray())
        return SecretKeySpec(derived, "AES")
    }

    private fun advanceChain(chainKey: SecretKey): SecretKey {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(chainKey)
        val next = mac.doFinal("chain-advance".toByteArray())
        return SecretKeySpec(next, "AES")
    }
}

data class SenderKeyState(
    val groupId: String,
    val senderNodeId: String,
    val chainKey: SecretKey,
    val iteration: Int,
    val createdAt: Long
)
