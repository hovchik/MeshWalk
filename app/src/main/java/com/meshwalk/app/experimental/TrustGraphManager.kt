package com.meshwalk.app.experimental

import com.meshwalk.app.data.local.dao.PeerStatsDao
import com.meshwalk.app.data.local.entity.PeerStatsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.min

/**
 * Feature 5 — Proximity trust graph.
 *
 * Ranks contacts by "number of times we've been in direct BT range". The more
 * often we've physically encountered the same peer, the higher their
 * proximity-trust score. This feeds into the safety-number style badge shown
 * on peer list items so a casual relay can be distinguished from a regular
 * contact.
 */
data class ProximityTrust(
    val nodeId: String,
    val encounterCount: Long,
    val lastEncounterAt: Long
) {
    /** Score in [0.0, 1.0] based on log(encounters+1). Saturates around 100 encounters. */
    val score: Double
        get() = min(1.0, ln(encounterCount.toDouble() + 1.0) / ln(101.0))

    val tier: String
        get() = when {
            encounterCount >= 50 -> "Trusted"
            encounterCount >= 10 -> "Familiar"
            encounterCount >= 3 -> "Seen"
            else -> "Stranger"
        }
}

@Singleton
class TrustGraphManager @Inject constructor(
    private val statsDao: PeerStatsDao
) {
    private val mutex = Mutex()

    fun observeAll(): Flow<List<ProximityTrust>> = statsDao.observeAll().map { list ->
        list.map { ProximityTrust(it.nodeId, it.encounterCount, it.lastEncounterAt) }
    }

    suspend fun onDirectEncounter(nodeId: String) = mutex.withLock {
        val now = System.currentTimeMillis()
        val existing = statsDao.get(nodeId)
        val updated = existing?.copy(
            encounterCount = existing.encounterCount + 1,
            lastEncounterAt = now
        ) ?: PeerStatsEntity(
            nodeId = nodeId,
            encounterCount = 1,
            lastEncounterAt = now,
            deliveryLatencyEwmaMs = 0,
            deliverySampleCount = 0
        )
        statsDao.upsert(updated)
    }

    suspend fun getTrust(nodeId: String): ProximityTrust {
        val stats = statsDao.get(nodeId)
        return ProximityTrust(
            nodeId = nodeId,
            encounterCount = stats?.encounterCount ?: 0,
            lastEncounterAt = stats?.lastEncounterAt ?: 0
        )
    }
}
