package com.meshwalk.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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
        GroupEntity::class
    ],
    version = 1,
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

    companion object {
        const val DATABASE_NAME = "meshwalk_db"
    }
}
