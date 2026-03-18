package com.meshwalk.app.routing.dedup

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Prevents processing the same packet multiple times.
 * Uses an in-memory cache of recently seen packet IDs with timestamps.
 *
 * Important for flooding-based routing where the same packet may arrive
 * via multiple paths.
 *
 * Implementation uses a LinkedHashMap in insertion order so that the oldest
 * entries can be evicted in O(k) time instead of sorting O(n log n).
 */
@Singleton
class PacketDeduplicator @Inject constructor() {

    companion object {
        private const val DEFAULT_EXPIRY_MS = 5 * 60 * 1000L // 5 minutes
        private const val MAX_CACHE_SIZE = 10_000
        private const val EVICT_COUNT = MAX_CACHE_SIZE / 5  // evict oldest 20%
    }

    // LinkedHashMap in insertion order: oldest entries appear first on iteration.
    // Timestamps are in ascending order because each markSeen() uses currentTimeMillis(),
    // so insertion order == chronological order == correct eviction order.
    private val seenPackets = LinkedHashMap<String, Long>(MAX_CACHE_SIZE + 1, 0.75f, false)
    private val lock = Any()

    /**
     * Check if we've already seen this packet.
     */
    fun isDuplicate(packetId: String): Boolean {
        synchronized(lock) {
            val seenAt = seenPackets[packetId] ?: return false
            return System.currentTimeMillis() - seenAt < DEFAULT_EXPIRY_MS
        }
    }

    /**
     * Mark a packet as seen.
     */
    fun markSeen(packetId: String) {
        synchronized(lock) {
            if (seenPackets.size >= MAX_CACHE_SIZE) {
                pruneExpiredLocked()
                if (seenPackets.size >= MAX_CACHE_SIZE) {
                    // Evict oldest EVICT_COUNT entries in insertion order — O(k), not O(n log n).
                    val iter = seenPackets.iterator()
                    var removed = 0
                    while (iter.hasNext() && removed < EVICT_COUNT) {
                        iter.next()
                        iter.remove()
                        removed++
                    }
                    if (removed > 0) {
                        Timber.d("PacketDeduplicator: evicted $removed oldest entries (cache full)")
                    }
                }
            }
            seenPackets[packetId] = System.currentTimeMillis()
        }
    }

    /**
     * Remove a specific packet from the dedup cache.
     * Used when a packet couldn't be processed (e.g. session not ready)
     * and should be allowed to be re-processed on arrival.
     */
    fun clearPacket(packetId: String) {
        synchronized(lock) {
            seenPackets.remove(packetId)
        }
    }

    /**
     * Remove expired entries from the cache.
     */
    fun pruneExpired() {
        synchronized(lock) {
            pruneExpiredLocked()
        }
    }

    /** Must be called while holding [lock]. */
    private fun pruneExpiredLocked() {
        val now = System.currentTimeMillis()
        val before = seenPackets.size
        seenPackets.entries.removeIf { now - it.value > DEFAULT_EXPIRY_MS }
        val pruned = before - seenPackets.size
        if (pruned > 0) {
            Timber.d("PacketDeduplicator: pruned $pruned expired entries")
        }
    }

    fun size(): Int = synchronized(lock) { seenPackets.size }
    fun clear() = synchronized(lock) { seenPackets.clear() }
}
