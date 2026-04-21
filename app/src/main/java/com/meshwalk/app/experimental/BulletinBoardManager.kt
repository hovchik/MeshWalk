package com.meshwalk.app.experimental

import com.meshwalk.app.data.local.dao.BulletinPostDao
import com.meshwalk.app.data.local.entity.BulletinPostEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature 4 — Flood-fill anonymous bulletin board with proof-of-work.
 *
 * Posts are broadcast onto the mesh with no verified sender identity. To
 * prevent spam, every post must satisfy a proof-of-work constraint: the
 * SHA-256 of (postId || body || nonce) must start with >= [difficultyBits]
 * zero bits. Heavier posts (announcements) can use higher difficulty.
 */
data class BulletinPost(
    val postId: String,
    val body: String,
    val category: String,
    val createdAt: Long,
    val expiresAt: Long,
    val proofOfWork: Long,
    val difficultyBits: Int,
    val hopCount: Int,
    val isOwn: Boolean
)

@Singleton
class BulletinBoardManager @Inject constructor(
    private val dao: BulletinPostDao
) {
    companion object {
        const val DEFAULT_DIFFICULTY_BITS = 16 // ~65k hashes on average
        const val DEFAULT_TTL_MS = 24L * 60 * 60 * 1000
    }

    fun observeActive(): Flow<List<BulletinPost>> =
        dao.observeActive(System.currentTimeMillis()).map { list ->
            list.map { it.toDomain() }
        }

    /**
     * Create a new post locally with proof-of-work. Returns the wire payload
     * bytes so callers can broadcast the post over the mesh.
     */
    suspend fun createLocal(
        body: String,
        category: String = "general",
        difficultyBits: Int = DEFAULT_DIFFICULTY_BITS,
        ttlMs: Long = DEFAULT_TTL_MS
    ): BulletinPost = withContext(Dispatchers.Default) {
        val postId = UUID.randomUUID().toString()
        val nonce = mine(postId, body, difficultyBits)
        val now = System.currentTimeMillis()
        val post = BulletinPost(
            postId = postId,
            body = body,
            category = category,
            createdAt = now,
            expiresAt = now + ttlMs,
            proofOfWork = nonce,
            difficultyBits = difficultyBits,
            hopCount = 0,
            isOwn = true
        )
        dao.insert(post.toEntity())
        post
    }

    /** Save a received post. Rejects posts that don't satisfy their claimed proof. */
    suspend fun receive(post: BulletinPost): Boolean {
        if (!verify(post.postId, post.body, post.proofOfWork, post.difficultyBits)) return false
        if (post.expiresAt < System.currentTimeMillis()) return false
        if (dao.getById(post.postId) != null) return false
        dao.insert(post.copy(isOwn = false).toEntity())
        return true
    }

    suspend fun pruneExpired() = dao.deleteExpired(System.currentTimeMillis())

    /** Serialize for wire: newline-separated fields (postId, category, difficulty, nonce, expiresAt, body). */
    fun serialize(post: BulletinPost): String = buildString {
        append(post.postId).append('\n')
        append(post.category).append('\n')
        append(post.difficultyBits).append('\n')
        append(post.proofOfWork).append('\n')
        append(post.expiresAt).append('\n')
        append(post.body.replace("\n", " "))
    }

    fun deserialize(wire: String): BulletinPost? {
        val parts = wire.split("\n", limit = 6)
        if (parts.size < 6) return null
        return try {
            BulletinPost(
                postId = parts[0],
                category = parts[1],
                difficultyBits = parts[2].toInt(),
                proofOfWork = parts[3].toLong(),
                expiresAt = parts[4].toLong(),
                body = parts[5],
                createdAt = System.currentTimeMillis(),
                hopCount = 0,
                isOwn = false
            )
        } catch (_: Exception) {
            null
        }
    }

    // -- Proof-of-work --

    private fun mine(postId: String, body: String, difficultyBits: Int): Long {
        val md = MessageDigest.getInstance("SHA-256")
        val prefix = (postId + "" + body + "").toByteArray(Charsets.UTF_8)
        var nonce = 0L
        while (true) {
            md.reset()
            md.update(prefix)
            md.update(nonce.toString().toByteArray(Charsets.UTF_8))
            val hash = md.digest()
            if (leadingZeroBits(hash) >= difficultyBits) return nonce
            nonce++
            // Safety cap so we don't spin forever if caller asks for absurd difficulty.
            if (nonce > 10_000_000L) return nonce
        }
    }

    fun verify(postId: String, body: String, nonce: Long, difficultyBits: Int): Boolean {
        val md = MessageDigest.getInstance("SHA-256")
        md.update((postId + "" + body + "").toByteArray(Charsets.UTF_8))
        md.update(nonce.toString().toByteArray(Charsets.UTF_8))
        return leadingZeroBits(md.digest()) >= difficultyBits
    }

    private fun leadingZeroBits(hash: ByteArray): Int {
        var count = 0
        for (b in hash) {
            if (b == 0.toByte()) { count += 8; continue }
            val u = b.toInt() and 0xFF
            for (i in 7 downTo 0) {
                if ((u shr i) and 1 == 0) count++ else return count
            }
            return count
        }
        return count
    }

    private fun BulletinPostEntity.toDomain() = BulletinPost(
        postId = postId,
        body = body,
        category = category,
        createdAt = createdAt,
        expiresAt = expiresAt,
        proofOfWork = proofOfWork,
        difficultyBits = difficultyBits,
        hopCount = hopCount,
        isOwn = isOwn
    )

    private fun BulletinPost.toEntity() = BulletinPostEntity(
        postId = postId,
        body = body,
        category = category,
        createdAt = createdAt,
        expiresAt = expiresAt,
        proofOfWork = proofOfWork,
        difficultyBits = difficultyBits,
        hopCount = hopCount,
        isOwn = isOwn
    )
}
