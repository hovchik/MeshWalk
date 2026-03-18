package com.meshwalk.app.crypto.session

import com.meshwalk.app.crypto.keys.MeshKeyManager
import timber.log.Timber
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages encrypted sessions between pairs of nodes.
 *
 * Session establishment flow:
 * 1. Node A discovers Node B's public exchange key (via discovery/pre-keys)
 * 2. A performs ECDH with B's public key and A's private key -> shared secret
 * 3. Shared secret is run through HKDF to derive symmetric session key
 * 4. Session key is used for AES-256-GCM encryption of messages
 *
 * Forward secrecy approach:
 * - Ratcheting: after each message exchange, derive new sending/receiving chain keys
 * - Simplified symmetric ratchet (not full Double Ratchet for practical reasons)
 * - New DH ratchet when both parties are online simultaneously
 */
@Singleton
class SessionManager @Inject constructor(
    private val keyManager: MeshKeyManager,
    private val sessionStore: SessionStore
) {
    // In-memory session cache
    private val activeSessions = ConcurrentHashMap<String, MeshSession>()

    /**
     * Get or establish a session with a peer.
     */
    suspend fun getOrCreateSession(
        ourNodeId: String,
        peerNodeId: String,
        peerPublicExchangeKey: ByteArray
    ): MeshSession {
        val sessionId = createSessionId(ourNodeId, peerNodeId)

        // Check memory cache
        activeSessions[sessionId]?.let { return it }

        // Check persistent store
        sessionStore.getSession(sessionId)?.let { stored ->
            activeSessions[sessionId] = stored
            return stored
        }

        // Create new session via ECDH
        return establishSession(ourNodeId, peerNodeId, peerPublicExchangeKey)
    }

    /**
     * Establish a new session with ECDH key agreement.
     */
    private suspend fun establishSession(
        ourNodeId: String,
        peerNodeId: String,
        peerPublicExchangeKey: ByteArray
    ): MeshSession {
        val sessionId = createSessionId(ourNodeId, peerNodeId)
        val keyFactory = KeyFactory.getInstance("EC")

        val ourPrivateKey = com.meshwalk.app.crypto.keys.KeyStorage::class.java
            .let {
                // In production, retrieve from KeyStorage. Here we use the key manager's approach.
                // This will be wired through DI properly.
                throw SessionEstablishmentException("Exchange key not found for $ourNodeId")
            }

        // This method will be called via the properly injected dependency chain
        // The actual implementation is below in establishSessionWithKeys
        throw SessionEstablishmentException("Use establishSessionWithKeys instead")
    }

    /**
     * Establish session with actual key material.
     */
    suspend fun establishSessionWithKeys(
        ourNodeId: String,
        peerNodeId: String,
        ourExchangePrivateKey: ByteArray,
        peerPublicExchangeKey: ByteArray
    ): MeshSession {
        val sessionId = createSessionId(ourNodeId, peerNodeId)

        // If an existing session is already loaded (or persisted), reuse it
        // to avoid resetting chain counters and breaking ongoing conversations.
        activeSessions[sessionId]?.let {
            Timber.d("Reusing existing in-memory session $sessionId")
            return it
        }
        sessionStore.getSession(sessionId)?.let { stored ->
            activeSessions[sessionId] = stored
            Timber.d("Reusing persisted session $sessionId (send=${stored.sendCounter}, recv=${stored.receiveCounter})")
            return stored
        }

        val keyFactory = KeyFactory.getInstance("EC")

        val ourPrivate: PrivateKey = keyFactory.generatePrivate(
            PKCS8EncodedKeySpec(ourExchangePrivateKey)
        )
        val peerPublic: PublicKey = keyFactory.generatePublic(
            X509EncodedKeySpec(peerPublicExchangeKey)
        )

        // ECDH key agreement
        val sharedSecret = keyManager.performKeyAgreement(ourPrivate, peerPublic)

        // Derive session keys via HKDF using canonical (sorted) node ordering
        // so both sides derive the same key pair regardless of who initiates.
        val (first, second) = if (ourNodeId < peerNodeId) ourNodeId to peerNodeId
                              else peerNodeId to ourNodeId
        val keyA = keyManager.deriveSessionKey(
            sharedSecret,
            "meshwalk-key-a-$first-$second".toByteArray()
        )
        val keyB = keyManager.deriveSessionKey(
            sharedSecret,
            "meshwalk-key-b-$first-$second".toByteArray()
        )
        // The node that sorts first uses keyA for sending and keyB for receiving.
        // The other node uses keyB for sending and keyA for receiving.
        val sendingKey = if (ourNodeId == first) keyA else keyB
        val receivingKey = if (ourNodeId == first) keyB else keyA

        val session = MeshSession(
            sessionId = sessionId,
            peerNodeId = peerNodeId,
            sendingChainKey = sendingKey,
            receivingChainKey = receivingKey,
            sendCounter = 0,
            receiveCounter = 0,
            createdAt = System.currentTimeMillis(),
            lastUsed = System.currentTimeMillis()
        )

        activeSessions[sessionId] = session
        sessionStore.saveSession(session)

        Timber.d("Established session $sessionId with $peerNodeId")
        return session
    }

    /**
     * Advance the sending chain to derive the next message key.
     * Provides limited forward secrecy within a session.
     */
    suspend fun advanceSendingChain(sessionId: String): Pair<SecretKey, Int> {
        val session = activeSessions[sessionId]
            ?: throw SessionNotFoundException(sessionId)

        val counter = session.sendCounter
        val messageKey = keyManager.deriveSessionKey(
            session.sendingChainKey.encoded,
            "msg-$counter".toByteArray()
        )

        // Ratchet the chain key forward
        val newChainKey = keyManager.deriveSessionKey(
            session.sendingChainKey.encoded,
            "chain-advance".toByteArray()
        )

        val updated = session.copy(
            sendingChainKey = newChainKey,
            sendCounter = counter + 1,
            lastUsed = System.currentTimeMillis()
        )
        activeSessions[sessionId] = updated
        sessionStore.saveSession(updated)

        return messageKey to counter
    }

    /**
     * Derive the receiving message key for a given counter.
     *
     * Ratchets the receiving chain forward to match the sender's chain state.
     * The sender ratchets their sending chain after each message, so we must
     * ratchet our receiving chain the same way to derive matching keys.
     */
    suspend fun deriveReceivingKey(sessionId: String, counter: Int): SecretKey {
        val session = activeSessions[sessionId]
            ?: throw SessionNotFoundException(sessionId)

        // Ratchet the receiving chain from our current counter to the target counter.
        // Each step: derive message key, then advance chain key.
        var chainKey = session.receivingChainKey
        for (i in session.receiveCounter until counter) {
            // Skip intermediate message keys (advance chain only)
            chainKey = keyManager.deriveSessionKey(
                chainKey.encoded,
                "chain-advance".toByteArray()
            )
        }

        // Derive the actual message key at the target counter
        val messageKey = keyManager.deriveSessionKey(
            chainKey.encoded,
            "msg-$counter".toByteArray()
        )

        // Advance chain one more time past this message and persist
        val nextChainKey = keyManager.deriveSessionKey(
            chainKey.encoded,
            "chain-advance".toByteArray()
        )
        val updated = session.copy(
            receivingChainKey = nextChainKey,
            receiveCounter = counter + 1,
            lastUsed = System.currentTimeMillis()
        )
        activeSessions[sessionId] = updated
        sessionStore.saveSession(updated)

        return messageKey
    }

    fun getSession(ourNodeId: String, peerNodeId: String): MeshSession? {
        return activeSessions[createSessionId(ourNodeId, peerNodeId)]
    }

    /**
     * Evict in-memory session objects that have not been used for more than 24 hours.
     * Sessions remain persisted to disk via [SessionStore] and are restored transparently
     * on the next [getOrCreateSession] / [hasSession] call, so evicting them here
     * only frees memory without affecting correctness.
     *
     * Called from the routing engine's periodic maintenance loop.
     */
    fun pruneStaleActiveSessions() {
        val staleIds = activeSessions.entries
            .filter { it.value.isStale }
            .map { it.key }
        staleIds.forEach { activeSessions.remove(it) }
        if (staleIds.isNotEmpty()) {
            Timber.d("SessionManager: evicted ${staleIds.size} stale in-memory sessions")
        }
    }

    /**
     * Check if a session exists, loading from persistent storage if needed.
     * This ensures sessions survive app restarts without re-establishing
     * (which would reset chain counters and break decryption).
     */
    suspend fun hasSession(ourNodeId: String, peerNodeId: String): Boolean {
        val sessionId = createSessionId(ourNodeId, peerNodeId)
        if (activeSessions.containsKey(sessionId)) return true

        // Try loading from persistent store (session may exist from before app restart)
        val stored = sessionStore.getSession(sessionId)
        if (stored != null) {
            activeSessions[sessionId] = stored
            Timber.d("Restored session $sessionId from persistent store (send=${stored.sendCounter}, recv=${stored.receiveCounter})")
            return true
        }
        return false
    }

    private fun createSessionId(nodeA: String, nodeB: String): String {
        // Canonical ordering ensures both parties derive the same session ID
        return if (nodeA < nodeB) "$nodeA:$nodeB" else "$nodeB:$nodeA"
    }
}

/**
 * Represents an active encrypted session between two nodes.
 */
data class MeshSession(
    val sessionId: String,
    val peerNodeId: String,
    val sendingChainKey: SecretKey,
    val receivingChainKey: SecretKey,
    val sendCounter: Int,
    val receiveCounter: Int,
    val createdAt: Long,
    val lastUsed: Long
) {
    val isStale: Boolean
        get() = System.currentTimeMillis() - lastUsed > 24 * 60 * 60 * 1000 // 24h
}

class SessionNotFoundException(sessionId: String) :
    Exception("Session not found: $sessionId")

class SessionEstablishmentException(message: String) :
    Exception(message)
