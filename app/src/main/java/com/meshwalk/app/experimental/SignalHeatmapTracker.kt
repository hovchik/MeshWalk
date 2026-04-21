package com.meshwalk.app.experimental

import com.meshwalk.app.data.local.dao.SignalSampleDao
import com.meshwalk.app.data.local.entity.SignalSampleEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature 10 — Signal-strength heatmap overlay.
 *
 * Samples peer RSSI along with the user's optional location and stores them
 * for display as a heat map on the Network screen. Over time, a user walking
 * around can build a coverage map of their mesh neighborhood.
 */
data class SignalSample(
    val peerNodeId: String,
    val rssi: Int,
    val latitude: Double?,
    val longitude: Double?,
    val recordedAt: Long
)

@Singleton
class SignalHeatmapTracker @Inject constructor(
    private val dao: SignalSampleDao
) {
    suspend fun record(peerNodeId: String, rssi: Int?, latitude: Double?, longitude: Double?) {
        if (rssi == null) return
        dao.insert(
            SignalSampleEntity(
                peerNodeId = peerNodeId,
                rssi = rssi,
                latitude = latitude,
                longitude = longitude,
                recordedAt = System.currentTimeMillis()
            )
        )
    }

    fun observeRecent(windowMs: Long = 24L * 60 * 60 * 1000): Flow<List<SignalSample>> =
        dao.observeRecent(System.currentTimeMillis() - windowMs).map { list ->
            list.map { it.toDomain() }
        }

    suspend fun recentForPeer(peerNodeId: String, limit: Int = 100): List<SignalSample> =
        dao.getRecentForPeer(peerNodeId, limit).map { it.toDomain() }

    suspend fun pruneOlderThan(maxAgeMs: Long) {
        dao.pruneOlderThan(System.currentTimeMillis() - maxAgeMs)
    }

    private fun SignalSampleEntity.toDomain() = SignalSample(
        peerNodeId = peerNodeId,
        rssi = rssi,
        latitude = latitude,
        longitude = longitude,
        recordedAt = recordedAt
    )
}
