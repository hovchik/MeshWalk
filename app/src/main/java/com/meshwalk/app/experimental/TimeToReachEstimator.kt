package com.meshwalk.app.experimental

import com.meshwalk.app.data.local.dao.PeerStatsDao
import com.meshwalk.app.data.local.entity.PeerStatsEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature 11 — Time-to-reach estimator.
 *
 * Measures per-peer delivery latency by subtracting send timestamp from ACK
 * timestamp. Stores an exponentially-weighted moving average per peer so
 * the UI can show "typically reaches in ~3s" next to each contact.
 */
@Singleton
class TimeToReachEstimator @Inject constructor(
    private val statsDao: PeerStatsDao
) {
    companion object {
        /** Weight of new samples in the EWMA (0..1). */
        private const val EWMA_ALPHA = 0.25
    }

    private val mutex = Mutex()

    suspend fun recordDelivery(peerNodeId: String, sentAt: Long, deliveredAt: Long) {
        val latency = (deliveredAt - sentAt).coerceAtLeast(0)
        mutex.withLock {
            val current = statsDao.get(peerNodeId)
            val updated = if (current == null || current.deliverySampleCount == 0L) {
                PeerStatsEntity(
                    nodeId = peerNodeId,
                    encounterCount = current?.encounterCount ?: 0,
                    lastEncounterAt = current?.lastEncounterAt ?: System.currentTimeMillis(),
                    deliveryLatencyEwmaMs = latency,
                    deliverySampleCount = 1
                )
            } else {
                val prev = current.deliveryLatencyEwmaMs.toDouble()
                val next = (prev * (1.0 - EWMA_ALPHA) + latency * EWMA_ALPHA).toLong()
                current.copy(
                    deliveryLatencyEwmaMs = next,
                    deliverySampleCount = current.deliverySampleCount + 1
                )
            }
            statsDao.upsert(updated)
        }
    }

    suspend fun estimateMs(peerNodeId: String): Long? {
        val stats = statsDao.get(peerNodeId) ?: return null
        if (stats.deliverySampleCount == 0L) return null
        return stats.deliveryLatencyEwmaMs
    }

    fun formatEstimate(ms: Long?): String = when {
        ms == null -> "Unknown"
        ms < 1_000 -> "<1s"
        ms < 60_000 -> "~${ms / 1000}s"
        ms < 3_600_000 -> "~${ms / 60_000}m"
        else -> "~${ms / 3_600_000}h"
    }
}
