package com.meshwalk.app.data.local.dao

import androidx.room.*
import com.meshwalk.app.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DropZoneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(zone: DropZoneEntity)

    @Query("SELECT * FROM drop_zones ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DropZoneEntity>>

    @Query("SELECT * FROM drop_zones WHERE zoneId = :zoneId")
    suspend fun getById(zoneId: String): DropZoneEntity?

    @Query("UPDATE drop_zones SET isUnlocked = 1, unlockedAt = :now WHERE zoneId = :zoneId")
    suspend fun markUnlocked(zoneId: String, now: Long)

    @Query("DELETE FROM drop_zones WHERE expiresAt < :now")
    suspend fun deleteExpired(now: Long)

    @Query("DELETE FROM drop_zones WHERE zoneId = :zoneId")
    suspend fun delete(zoneId: String)
}

@Dao
interface BulletinPostDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(post: BulletinPostEntity): Long

    @Query("SELECT * FROM bulletin_posts WHERE expiresAt > :now ORDER BY createdAt DESC")
    fun observeActive(now: Long): Flow<List<BulletinPostEntity>>

    @Query("SELECT * FROM bulletin_posts WHERE postId = :id")
    suspend fun getById(id: String): BulletinPostEntity?

    @Query("DELETE FROM bulletin_posts WHERE expiresAt <= :now")
    suspend fun deleteExpired(now: Long)

    @Query("SELECT COUNT(*) FROM bulletin_posts")
    suspend fun count(): Int
}

@Dao
interface ReputationTokenDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(token: ReputationTokenEntity)

    @Query("SELECT * FROM reputation_tokens WHERE subjectNodeId = :nodeId ORDER BY issuedAt DESC")
    suspend fun getForSubject(nodeId: String): List<ReputationTokenEntity>

    @Query("SELECT COUNT(*) FROM reputation_tokens WHERE subjectNodeId = :nodeId")
    suspend fun countForSubject(nodeId: String): Int

    @Query("SELECT * FROM reputation_tokens ORDER BY issuedAt DESC")
    fun observeAll(): Flow<List<ReputationTokenEntity>>
}

@Dao
interface SignalSampleDao {
    @Insert
    suspend fun insert(sample: SignalSampleEntity): Long

    @Query("SELECT * FROM signal_samples WHERE peerNodeId = :peerNodeId ORDER BY recordedAt DESC LIMIT :limit")
    suspend fun getRecentForPeer(peerNodeId: String, limit: Int): List<SignalSampleEntity>

    @Query("SELECT * FROM signal_samples WHERE recordedAt > :sinceMs ORDER BY recordedAt DESC")
    fun observeRecent(sinceMs: Long): Flow<List<SignalSampleEntity>>

    @Query("DELETE FROM signal_samples WHERE recordedAt < :threshold")
    suspend fun pruneOlderThan(threshold: Long)
}

@Dao
interface PeerStatsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stats: PeerStatsEntity)

    @Query("SELECT * FROM peer_stats WHERE nodeId = :nodeId")
    suspend fun get(nodeId: String): PeerStatsEntity?

    @Query("SELECT * FROM peer_stats ORDER BY encounterCount DESC")
    fun observeAll(): Flow<List<PeerStatsEntity>>
}

@Dao
interface RelayCounterDao {
    @Query("SELECT * FROM relay_counters WHERE id = 'self' LIMIT 1")
    fun observe(): Flow<RelayCounterEntity?>

    @Query("SELECT * FROM relay_counters WHERE id = 'self' LIMIT 1")
    suspend fun get(): RelayCounterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(counter: RelayCounterEntity)
}
