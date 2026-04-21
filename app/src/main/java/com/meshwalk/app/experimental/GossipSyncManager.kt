package com.meshwalk.app.experimental

import com.meshwalk.app.domain.repository.MessageRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Feature 8 — Opportunistic sync with passing strangers.
 *
 * On each peer connection, exchange a Bloom filter of the message IDs we
 * already have. Then we only offer the peer the packets they're missing
 * (and vice versa). This extends effective mesh range by making every
 * passing device a potential carrier, without broadcasting message contents
 * to everyone.
 *
 * Implementation is intentionally minimal: a fixed-size Bloom filter with
 * 3 hash functions, suitable for a few thousand message IDs with ~1%
 * false-positive rate.
 */
class BloomFilter(val bits: ByteArray, val hashCount: Int) {
    val sizeBits: Int get() = bits.size * 8

    fun add(value: String) {
        hashes(value).forEach { idx ->
            val pos = idx % sizeBits
            val byte = pos ushr 3
            val bit = pos and 7
            bits[byte] = (bits[byte].toInt() or (1 shl bit)).toByte()
        }
    }

    fun contains(value: String): Boolean = hashes(value).all { idx ->
        val pos = idx % sizeBits
        val byte = pos ushr 3
        val bit = pos and 7
        (bits[byte].toInt() and (1 shl bit)) != 0
    }

    private fun hashes(value: String): IntArray {
        val out = IntArray(hashCount)
        var h1 = fnv1a(value, seed = 0x811C9DC5.toInt())
        val h2 = fnv1a(value, seed = 0x1000193)
        for (i in 0 until hashCount) {
            h1 += h2
            out[i] = h1 and Int.MAX_VALUE
        }
        return out
    }

    private fun fnv1a(s: String, seed: Int): Int {
        var h = seed
        for (c in s) {
            h = h xor c.code
            h *= 0x01000193
        }
        return h
    }

    companion object {
        /** Size a filter for [expectedItems] and target [falsePositive] rate. */
        fun sized(expectedItems: Int, falsePositive: Double = 0.01): BloomFilter {
            val n = max(1, expectedItems)
            val mBits = max(64, (-n * ln(falsePositive) / (ln(2.0) * ln(2.0))).roundToInt())
            val k = max(1, (mBits.toDouble() / n * ln(2.0)).roundToInt())
            return BloomFilter(ByteArray((mBits + 7) / 8), k)
        }

        fun fromBytes(bytes: ByteArray, hashCount: Int) = BloomFilter(bytes.copyOf(), hashCount)
    }
}

@Singleton
class GossipSyncManager @Inject constructor(
    private val messageRepo: MessageRepository
) {
    /** Build a Bloom digest of our known message IDs. */
    suspend fun buildLocalDigest(capacity: Int = 2048): BloomFilter {
        val filter = BloomFilter.sized(capacity)
        // Use undelivered + recent messages as the advertised set.
        val known = messageRepo.getUndeliveredMessages().map { it.messageId }
        known.forEach { filter.add(it) }
        return filter
    }

    /**
     * Given a peer's digest, return the messageIds we have that the peer
     * doesn't (approximate — Bloom filters can false-positive).
     */
    suspend fun diffAgainst(peerDigest: BloomFilter, ourKnown: List<String>): List<String> =
        ourKnown.filterNot { peerDigest.contains(it) }

    /** Serialize a filter to a short wire frame: 2-byte hashCount + bits. */
    fun serialize(filter: BloomFilter): ByteArray {
        val out = ByteArray(2 + filter.bits.size)
        out[0] = (filter.hashCount ushr 8).toByte()
        out[1] = filter.hashCount.toByte()
        System.arraycopy(filter.bits, 0, out, 2, filter.bits.size)
        return out
    }

    fun deserialize(bytes: ByteArray): BloomFilter? {
        if (bytes.size < 3) return null
        val k = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        if (k == 0) return null
        return BloomFilter.fromBytes(bytes.copyOfRange(2, bytes.size), k)
    }
}
