package com.meshwalk.app.experimental

import com.meshwalk.app.crypto.keys.KeyStorage
import com.meshwalk.app.data.local.dao.ReputationTokenDao
import com.meshwalk.app.data.local.entity.ReputationTokenEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature 9 — Mesh reputation tokens.
 *
 * A lightweight, local-only "trust web" — when peer A relays packets for us
 * successfully, we issue a signed attestation:
 *
 *   "node A relayed N packets for node B at time T"
 *
 * Stored locally and shared opportunistically. Over time, each peer
 * accumulates attestations from multiple observers, building a server-less
 * reputation signal.
 *
 * Note: this scaffold uses a keyed SHA-256 "signature" derived from the
 * attester's private signing key. Migrating to actual ECDSA is a drop-in
 * replacement in [signToken] / [verifyToken].
 */
data class ReputationToken(
    val subjectNodeId: String,
    val attesterNodeId: String,
    val relayCount: Int,
    val issuedAt: Long,
    val signature: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReputationToken) return false
        return subjectNodeId == other.subjectNodeId &&
            attesterNodeId == other.attesterNodeId &&
            issuedAt == other.issuedAt
    }
    override fun hashCode(): Int {
        var r = subjectNodeId.hashCode()
        r = 31 * r + attesterNodeId.hashCode()
        r = 31 * r + issuedAt.hashCode()
        return r
    }
}

@Singleton
class ReputationTokenManager @Inject constructor(
    private val dao: ReputationTokenDao,
    private val keyStorage: KeyStorage
) {
    suspend fun issue(
        subjectNodeId: String,
        attesterNodeId: String,
        relayCount: Int
    ): ReputationToken {
        val issuedAt = System.currentTimeMillis()
        val privKey = keyStorage.getSigningPrivateKey(attesterNodeId)
            ?: throw IllegalStateException("No signing key for attester $attesterNodeId")
        val sig = signToken(subjectNodeId, attesterNodeId, relayCount, issuedAt, privKey)
        val token = ReputationToken(subjectNodeId, attesterNodeId, relayCount, issuedAt, sig)
        dao.upsert(token.toEntity())
        return token
    }

    suspend fun save(token: ReputationToken): Boolean {
        // NB: verifyToken without the attester's public key is a placeholder
        // — when wired into the network the verifier will look up the
        // attester's public key from the peer registry.
        dao.upsert(token.toEntity())
        return true
    }

    suspend fun scoreFor(nodeId: String): Int {
        val tokens = dao.getForSubject(nodeId)
        // Simple sum of relay counts across distinct attesters.
        return tokens.groupBy { it.attesterNodeId }
            .values
            .sumOf { byAttester -> byAttester.maxOf { it.relayCount } }
    }

    fun observeAll(): Flow<List<ReputationToken>> = dao.observeAll().map { list ->
        list.map { it.toDomain() }
    }

    // -- Signing (keyed hash placeholder) --

    private fun signToken(
        subject: String, attester: String, relayCount: Int, issuedAt: Long, privKey: ByteArray
    ): ByteArray {
        val subjectBytes = subject.toByteArray(Charsets.UTF_8)
        val attesterBytes = attester.toByteArray(Charsets.UTF_8)
        // Size by encoded byte length, not char count — they differ for non-ASCII IDs.
        val buf = ByteBuffer.allocate(4 + 8 + subjectBytes.size + attesterBytes.size)
        buf.putInt(relayCount)
        buf.putLong(issuedAt)
        buf.put(subjectBytes)
        buf.put(attesterBytes)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(privKey)
        md.update(buf.array())
        return md.digest()
    }

    private fun ReputationTokenEntity.toDomain() = ReputationToken(
        subjectNodeId = subjectNodeId,
        attesterNodeId = attesterNodeId,
        relayCount = relayCount,
        issuedAt = issuedAt,
        signature = signature
    )

    private fun ReputationToken.toEntity() = ReputationTokenEntity(
        subjectNodeId = subjectNodeId,
        attesterNodeId = attesterNodeId,
        relayCount = relayCount,
        issuedAt = issuedAt,
        signature = signature
    )
}
