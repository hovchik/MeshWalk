package com.meshwalk.app.routing.queue

import com.meshwalk.app.domain.model.MeshPacket
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Store-and-forward queue for packets that can't be delivered immediately.
 *
 * Packets are queued when:
 * - No direct route to destination
 * - No connected peers to forward through
 * - Destination node is offline/unreachable
 *
 * Queue is flushed when:
 * - New peer connects (may be destination or may relay)
 * - Periodic flush attempt
 *
 * Expiration:
 * - Packets expire after their TTL-based timeout
 * - Maximum queue size enforced (oldest packets dropped when full)
 */
@Singleton
class OfflineQueue @Inject constructor() {

    companion object {
        private const val MAX_QUEUE_SIZE = 1000
        private const val DEFAULT_EXPIRY_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    private val queue = ConcurrentLinkedQueue<QueuedPacket>()
    private val packetIndex = ConcurrentHashMap<String, QueuedPacket>()
    private val lock = Any()

    /**
     * Add a packet to the offline queue.
     */
    fun enqueue(packet: MeshPacket) {
        synchronized(lock) {
            // Don't queue duplicates
            if (packetIndex.containsKey(packet.packetId)) return

            // Evict if full
            while (queue.size >= MAX_QUEUE_SIZE) {
                val oldest = queue.poll()
                if (oldest != null) {
                    packetIndex.remove(oldest.packet.packetId)
                    Timber.d("Evicted oldest queued packet: ${oldest.packet.packetId}")
                }
            }

            val queued = QueuedPacket(
                packet = packet,
                queuedAt = System.currentTimeMillis(),
                retryCount = 0
            )
            queue.add(queued)
            packetIndex[packet.packetId] = queued
        }
        Timber.d("Queued packet ${packet.packetId} for ${packet.destinationNodeId}")
    }

    /**
     * Get all queued packets for a specific destination.
     */
    fun getPacketsForDestination(nodeId: String): List<MeshPacket> {
        return queue.filter { it.packet.destinationNodeId == nodeId && !it.isExpired }
            .map { it.packet }
    }

    /**
     * Get all non-expired queued packets.
     */
    fun getAll(): List<MeshPacket> {
        pruneExpired()
        return queue.map { it.packet }
    }

    /**
     * Remove a packet from the queue (after successful delivery).
     */
    fun remove(packetId: String) {
        synchronized(lock) {
            val queued = packetIndex.remove(packetId)
            if (queued != null) {
                queue.remove(queued)
            }
        }
    }

    /**
     * Increment retry count for a packet.
     */
    fun markRetry(packetId: String) {
        synchronized(lock) {
            packetIndex[packetId]?.let { old ->
                val updated = old.copy(retryCount = old.retryCount + 1)
                queue.remove(old)
                queue.add(updated)
                packetIndex[packetId] = updated
            }
        }
    }

    /**
     * Remove expired packets.
     */
    fun pruneExpired() {
        synchronized(lock) {
            val expired = queue.filter { it.isExpired }
            expired.forEach { queued ->
                queue.remove(queued)
                packetIndex.remove(queued.packet.packetId)
            }
            if (expired.isNotEmpty()) {
                Timber.d("Pruned ${expired.size} expired queued packets")
            }
        }
    }

    fun size(): Int = queue.size
    fun clear() {
        queue.clear()
        packetIndex.clear()
    }
}

data class QueuedPacket(
    val packet: MeshPacket,
    val queuedAt: Long,
    val retryCount: Int,
    val maxRetries: Int = 10,
    val expiryMs: Long = 24 * 60 * 60 * 1000L
) {
    val isExpired: Boolean
        get() = System.currentTimeMillis() - queuedAt > expiryMs || retryCount >= maxRetries
}
