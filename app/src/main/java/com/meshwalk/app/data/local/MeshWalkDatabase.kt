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
    version = 2,
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
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sender_keys (
                        groupId TEXT NOT NULL,
                        senderNodeId TEXT NOT NULL,
                        chainKey BLOB NOT NULL,
                        iteration INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY(groupId, senderNodeId)
                    )
                """.trimIndent())
            }
        }
    }
}
