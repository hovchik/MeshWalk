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
        SenderKeyEntity::class
    ],
    version = 5,
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
    }
}
