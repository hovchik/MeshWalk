package com.meshwalk.app.experimental

import com.meshwalk.app.data.local.dao.RelayCounterDao
import com.meshwalk.app.data.local.entity.RelayCounterEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature 2 — Blind relay incentives.
 *
 * Tracks how many packets this node has relayed on behalf of others. Call
 * [onPacketRelayed] from the routing engine whenever a packet is forwarded.
 * The UI surfaces these counters as a "you relayed X packets" dashboard.
 */
data class RelayStats(
    val packetsRelayed: Long,
    val bytesRelayed: Long,
    val sosRelayed: Long,
    val lastUpdated: Long
) {
    companion object {
        val EMPTY = RelayStats(0L, 0L, 0L, 0L)
    }
}

@Singleton
class RelayStatsTracker @Inject constructor(
    private val dao: RelayCounterDao
) {
    private val mutex = Mutex()

    fun observe(): Flow<RelayStats> = dao.observe().map { it?.toDomain() ?: RelayStats.EMPTY }

    suspend fun onPacketRelayed(payloadBytes: Int, isSos: Boolean) = mutex.withLock {
        val current = dao.get() ?: RelayCounterEntity(
            packetsRelayed = 0,
            bytesRelayed = 0,
            sosRelayed = 0,
            lastUpdated = 0
        )
        dao.upsert(
            current.copy(
                packetsRelayed = current.packetsRelayed + 1,
                bytesRelayed = current.bytesRelayed + payloadBytes,
                sosRelayed = current.sosRelayed + if (isSos) 1 else 0,
                lastUpdated = System.currentTimeMillis()
            )
        )
    }

    private fun RelayCounterEntity.toDomain() = RelayStats(
        packetsRelayed = packetsRelayed,
        bytesRelayed = bytesRelayed,
        sosRelayed = sosRelayed,
        lastUpdated = lastUpdated
    )
}
