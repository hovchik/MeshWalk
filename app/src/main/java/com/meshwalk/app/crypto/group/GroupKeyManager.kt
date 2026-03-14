package com.meshwalk.app.crypto.group

import com.meshwalk.app.crypto.envelope.MessageEnvelopeManager
import com.meshwalk.app.crypto.session.SessionManager
import com.meshwalk.app.data.local.dao.SenderKeyDao
import com.meshwalk.app.data.local.entity.SenderKeyEntity
import kotlinx.coroutines.runBlocking
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
 * Sender keys are persisted to the Room database so they survive app restarts.
 */
@Singleton
class GroupKeyManager @Inject constructor(
    private val sessionManager: SessionManager,
    private val envelopeManager: MessageEnvelopeManager,
    private val senderKeyDao: SenderKeyDao
) {
    private val secureRandom = SecureRandom()

    // In-memory cache: groupId -> (senderNodeId -> SenderKeyState)
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
        persistKey(senderKey)
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
        persistKey(state)
        Timber.d("Stored sender key for group=$groupId from sender=$senderNodeId")
    }

    /**
     * Get the current sender key for encrypting a group message we're sending.
     * Advances the chain key after each use.
     */
    fun getSendingKey(groupId: String, ourNodeId: String): SecretKey? {
        val state = getKeyState(groupId, ourNodeId) ?: return null

        // Derive message key from chain key
        val messageKey = deriveMessageKey(state.chainKey, state.iteration)

        // Advance chain
        val newChainKey = advanceChain(state.chainKey)
        val updated = state.copy(
            chainKey = newChainKey,
            iteration = state.iteration + 1
        )
        groupSenderKeys.getOrPut(groupId) { ConcurrentHashMap() }[ourNodeId] = updated
        persistKey(updated)

        return messageKey
    }

    /**
     * Get the sender key for decrypting a group message from another member.
     * Also advances the chain so the next message can be decrypted correctly.
     */
    fun getReceivingKey(groupId: String, senderNodeId: String): SecretKey? {
        val state = getKeyState(groupId, senderNodeId) ?: return null
        val messageKey = deriveMessageKey(state.chainKey, state.iteration)
        // Advance the receiver's chain to stay in sync with the sender
        val newChainKey = advanceChain(state.chainKey)
        val updated = state.copy(
            chainKey = newChainKey,
            iteration = state.iteration + 1
        )
        groupSenderKeys.getOrPut(groupId) { ConcurrentHashMap() }[senderNodeId] = updated
        persistKey(updated)
        return messageKey
    }

    /**
     * Rotate all sender keys in a group (required when a member is removed).
     */
    fun rotateGroupKeys(groupId: String, remainingMemberIds: List<String>, ourNodeId: String): SenderKeyState? {
        // Remove old keys from memory and database
        groupSenderKeys.remove(groupId)
        runBlocking { senderKeyDao.deleteByGroup(groupId) }

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
        val state = getKeyState(groupId, ourNodeId) ?: return null
        return state.chainKey.encoded
    }

    /**
     * Check if we have all necessary sender keys for a group.
     */
    fun hasAllSenderKeys(groupId: String, memberNodeIds: List<String>): Boolean {
        return memberNodeIds.all { getKeyState(groupId, it) != null }
    }

    /**
     * Get members whose sender keys we're missing.
     */
    fun getMissingSenderKeys(groupId: String, memberNodeIds: List<String>): List<String> {
        return memberNodeIds.filter { getKeyState(groupId, it) == null }
    }

    /**
     * Find a receiving key for a sender across all groups.
     * Returns (groupId, messageKey) or null if not found.
     * Also advances the receiver chain so the next message can be decrypted.
     */
    fun findReceivingKeyForSender(senderNodeId: String): Pair<String, SecretKey>? {
        for ((groupId, members) in groupSenderKeys) {
            val state = members[senderNodeId]
            if (state != null) {
                val key = deriveMessageKey(state.chainKey, state.iteration)
                // Advance the receiver's chain to stay in sync with the sender
                val newChainKey = advanceChain(state.chainKey)
                val updated = state.copy(
                    chainKey = newChainKey,
                    iteration = state.iteration + 1
                )
                members[senderNodeId] = updated
                persistKey(updated)
                return Pair(groupId, key)
            }
        }
        return null
    }

    /**
     * Get a key state, checking in-memory cache first, then falling back to database.
     */
    private fun getKeyState(groupId: String, nodeId: String): SenderKeyState? {
        // Check in-memory cache first
        val cached = groupSenderKeys[groupId]?.get(nodeId)
        if (cached != null) return cached

        // Fall back to database
        val entity = runBlocking { senderKeyDao.get(groupId, nodeId) } ?: return null
        val state = SenderKeyState(
            groupId = entity.groupId,
            senderNodeId = entity.senderNodeId,
            chainKey = SecretKeySpec(entity.chainKey, "AES"),
            iteration = entity.iteration,
            createdAt = entity.createdAt
        )
        // Populate cache
        groupSenderKeys.getOrPut(groupId) { ConcurrentHashMap() }[nodeId] = state
        return state
    }

    /**
     * Persist a sender key state to the database.
     */
    private fun persistKey(state: SenderKeyState) {
        runBlocking {
            senderKeyDao.upsert(
                SenderKeyEntity(
                    groupId = state.groupId,
                    senderNodeId = state.senderNodeId,
                    chainKey = state.chainKey.encoded,
                    iteration = state.iteration,
                    createdAt = state.createdAt
                )
            )
        }
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
