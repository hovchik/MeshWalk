package com.meshwalk.app.routing.dedup

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Prevents processing the same packet multiple times.
 * Uses an in-memory cache of recently seen packet IDs with timestamps.
 *
 * Important for flooding-based routing where the same packet may arrive
 * via multiple paths.
 */
@Singleton
class PacketDeduplicator @Inject constructor() {

    companion object {
        private const val DEFAULT_EXPIRY_MS = 5 * 60 * 1000L // 5 minutes
        private const val MAX_CACHE_SIZE = 10_000
    }

    private val seenPackets = ConcurrentHashMap<String, Long>()

    /**
     * Check if we've already seen this packet.
     */
    fun isDuplicate(packetId: String): Boolean {
        val seenAt = seenPackets[packetId] ?: return false
        return System.currentTimeMillis() - seenAt < DEFAULT_EXPIRY_MS
    }

    /**
     * Mark a packet as seen.
     */
    fun markSeen(packetId: String) {
        // Evict oldest if cache is full
        if (seenPackets.size >= MAX_CACHE_SIZE) {
            pruneExpired()
            if (seenPackets.size >= MAX_CACHE_SIZE) {
                // Force evict oldest 20%
                val oldest = seenPackets.entries
                    .sortedBy { it.value }
                    .take(MAX_CACHE_SIZE / 5)
                oldest.forEach { seenPackets.remove(it.key) }
            }
        }
        seenPackets[packetId] = System.currentTimeMillis()
    }

    /**
     * Remove expired entries from the cache.
     */
    fun pruneExpired() {
        val now = System.currentTimeMillis()
        seenPackets.entries.removeIf { now - it.value > DEFAULT_EXPIRY_MS }
    }

    fun size(): Int = seenPackets.size
    fun clear() = seenPackets.clear()
}
