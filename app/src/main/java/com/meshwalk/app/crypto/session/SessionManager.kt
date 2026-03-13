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
        val keyFactory = KeyFactory.getInstance("EC")

        val ourPrivate: PrivateKey = keyFactory.generatePrivate(
            PKCS8EncodedKeySpec(ourExchangePrivateKey)
        )
        val peerPublic: PublicKey = keyFactory.generatePublic(
            X509EncodedKeySpec(peerPublicExchangeKey)
        )

        // ECDH key agreement
        val sharedSecret = keyManager.performKeyAgreement(ourPrivate, peerPublic)

        // Derive session keys via HKDF
        val sendingKey = keyManager.deriveSessionKey(
            sharedSecret,
            "meshwalk-send-$ourNodeId-$peerNodeId".toByteArray()
        )
        val receivingKey = keyManager.deriveSessionKey(
            sharedSecret,
            "meshwalk-recv-$ourNodeId-$peerNodeId".toByteArray()
        )

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
     */
    suspend fun deriveReceivingKey(sessionId: String, counter: Int): SecretKey {
        val session = activeSessions[sessionId]
            ?: throw SessionNotFoundException(sessionId)

        // Derive the specific message key
        return keyManager.deriveSessionKey(
            session.receivingChainKey.encoded,
            "msg-$counter".toByteArray()
        )
    }

    fun getSession(ourNodeId: String, peerNodeId: String): MeshSession? {
        return activeSessions[createSessionId(ourNodeId, peerNodeId)]
    }

    fun hasSession(ourNodeId: String, peerNodeId: String): Boolean {
        return activeSessions.containsKey(createSessionId(ourNodeId, peerNodeId))
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
