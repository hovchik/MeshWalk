package com.meshwalk.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.meshwalk.app.data.local.converter.Converters
import com.meshwalk.app.data.local.dao.*
import com.meshwalk.app.data.local.entity.*

@Database(
    entities = [
        IdentityEntity::class,
        MessageEntity::class,
        ConversationEntity::class,
        PeerEntity::class,
        RoutingEntryEntity::class,
        GroupEntity::class,
        SenderKeyEntity::class,
        BlockedPeerEntity::class,
        GroupArchiveEntity::class,
        DropZoneEntity::class,
        BulletinPostEntity::class,
        ReputationTokenEntity::class,
        SignalSampleEntity::class,
        PeerStatsEntity::class,
        RelayCounterEntity::class
    ],
    version = 10,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class MeshWalkDatabase : RoomDatabase() {
    abstract fun identityDao(): IdentityDao
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun peerDao(): PeerDao
    abstract fun routingDao(): RoutingDao
    abstract fun groupDao(): GroupDao
    abstract fun senderKeyDao(): SenderKeyDao
    abstract fun blockedPeerDao(): BlockedPeerDao
    abstract fun groupArchiveDao(): GroupArchiveDao
    abstract fun dropZoneDao(): DropZoneDao
    abstract fun bulletinPostDao(): BulletinPostDao
    abstract fun reputationTokenDao(): ReputationTokenDao
    abstract fun signalSampleDao(): SignalSampleDao
    abstract fun peerStatsDao(): PeerStatsDao
    abstract fun relayCounterDao(): RelayCounterDao

    companion object {
        const val DATABASE_NAME = "meshwalk_db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // chainKey is TEXT because the @TypeConverter encodes ByteArray as Base64 String
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sender_keys (
                        groupId TEXT NOT NULL,
                        senderNodeId TEXT NOT NULL,
                        chainKey TEXT NOT NULL,
                        iteration INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY(groupId, senderNodeId)
                    )
                """.trimIndent())
            }
        }

        /**
         * Recovery migration: if MIGRATION_1_2 ran with the wrong column type (BLOB
         * instead of TEXT), the destructive fallback already recreated the DB at v2.
         * This no-op migration lets us bump to v3 so Room re-validates cleanly.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Schema already correct from either:
                // a) destructive fallback (recreated all tables from entities), or
                // b) a clean install at v2+
                // Just drop and recreate sender_keys to be safe.
                db.execSQL("DROP TABLE IF EXISTS sender_keys")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sender_keys (
                        groupId TEXT NOT NULL,
                        senderNodeId TEXT NOT NULL,
                        chainKey TEXT NOT NULL,
                        iteration INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY(groupId, senderNodeId)
                    )
                """.trimIndent())
            }
        }

        /**
         * Add peerDisplayName column to conversations table so that direct
         * conversation titles survive app restarts even when the peer is offline.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN peerDisplayName TEXT DEFAULT NULL")
            }
        }

        /**
         * Add isDelayed column to messages table to track messages that arrived
         * significantly later than when they were sent (offline/resend scenarios).
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN isDelayed INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS blocked_peers (
                        nodeId TEXT NOT NULL PRIMARY KEY,
                        displayName TEXT,
                        blockedAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        /**
         * Add group_archives table for storing encrypted archives of expired
         * temporary groups. Archives are encrypted with the admin's public key.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS group_archives (
                        groupId TEXT NOT NULL PRIMARY KEY,
                        groupName TEXT NOT NULL,
                        adminNodeId TEXT NOT NULL,
                        encryptedData BLOB NOT NULL,
                        ephemeralPublicKey BLOB NOT NULL,
                        nonce BLOB NOT NULL,
                        memberCount INTEGER NOT NULL,
                        messageCount INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        archivedAt INTEGER NOT NULL,
                        expiresAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        /**
         * v7->v8: Add reactions, reply-to, pinned to messages; nickname and favorite to conversations.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN reactionsJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN replyToMessageId TEXT DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN replyToPreview TEXT DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN nickname TEXT DEFAULT ''")
                db.execSQL("ALTER TABLE conversations ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v8->v9: Experimental feature tables (drop zones, bulletin posts,
         * reputation tokens, signal samples, peer stats, relay counters).
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS drop_zones (
                        zoneId TEXT NOT NULL PRIMARY KEY,
                        creatorNodeId TEXT NOT NULL,
                        recipientNodeId TEXT,
                        encryptedContent BLOB NOT NULL,
                        nonce BLOB NOT NULL,
                        latitude REAL,
                        longitude REAL,
                        radiusMeters REAL,
                        beaconNodeId TEXT,
                        createdAt INTEGER NOT NULL,
                        expiresAt INTEGER NOT NULL,
                        isUnlocked INTEGER NOT NULL,
                        unlockedAt INTEGER
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS bulletin_posts (
                        postId TEXT NOT NULL PRIMARY KEY,
                        body TEXT NOT NULL,
                        category TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        expiresAt INTEGER NOT NULL,
                        proofOfWork INTEGER NOT NULL,
                        difficultyBits INTEGER NOT NULL,
                        hopCount INTEGER NOT NULL,
                        isOwn INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS reputation_tokens (
                        subjectNodeId TEXT NOT NULL,
                        attesterNodeId TEXT NOT NULL,
                        relayCount INTEGER NOT NULL,
                        issuedAt INTEGER NOT NULL,
                        signature BLOB NOT NULL,
                        PRIMARY KEY(subjectNodeId, attesterNodeId, issuedAt)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS signal_samples (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        peerNodeId TEXT NOT NULL,
                        rssi INTEGER NOT NULL,
                        latitude REAL,
                        longitude REAL,
                        recordedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS peer_stats (
                        nodeId TEXT NOT NULL PRIMARY KEY,
                        encounterCount INTEGER NOT NULL,
                        lastEncounterAt INTEGER NOT NULL,
                        deliveryLatencyEwmaMs INTEGER NOT NULL,
                        deliverySampleCount INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS relay_counters (
                        id TEXT NOT NULL PRIMARY KEY,
                        packetsRelayed INTEGER NOT NULL,
                        bytesRelayed INTEGER NOT NULL,
                        sosRelayed INTEGER NOT NULL,
                        lastUpdated INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        /**
         * v9->v10: Disappearing messages (per-conversation TTL) and contact
         * verification (peer isVerified flag).
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN messageTtlMs INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE peers ADD COLUMN isVerified INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
