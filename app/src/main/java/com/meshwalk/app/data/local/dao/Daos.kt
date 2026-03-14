package com.meshwalk.app.data.local.dao

import androidx.room.*
import com.meshwalk.app.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface IdentityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(identity: IdentityEntity)

    @Query("SELECT * FROM identities WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): IdentityEntity?

    @Query("SELECT * FROM identities WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<IdentityEntity?>

    @Query("UPDATE identities SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE identities SET isActive = 1 WHERE nodeId = :nodeId")
    suspend fun activate(nodeId: String)

    @Query("SELECT * FROM identities")
    suspend fun getAll(): List<IdentityEntity>

    @Query("UPDATE identities SET displayName = :name WHERE nodeId = :nodeId")
    suspend fun updateDisplayName(nodeId: String, name: String?)

    @Query("UPDATE identities SET identityType = :type, displayName = :name WHERE nodeId = :nodeId")
    suspend fun updateProfile(nodeId: String, name: String?, type: String)

    @Query("DELETE FROM identities WHERE nodeId = :nodeId")
    suspend fun delete(nodeId: String)
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun observeByConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM (SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT :limit) ORDER BY timestamp ASC")
    fun observeRecentByConversation(conversationId: String, limit: Int): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET deliveryStatus = :status WHERE messageId = :messageId")
    suspend fun updateStatus(messageId: String, status: String)

    @Query("SELECT * FROM messages WHERE deliveryStatus IN ('PENDING', 'SENT')")
    suspend fun getUndelivered(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE messageId = :messageId")
    suspend fun getById(messageId: String): MessageEntity?

    @Query("DELETE FROM messages WHERE messageId = :messageId")
    suspend fun delete(messageId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE expiresAt IS NOT NULL AND expiresAt < :now")
    suspend fun deleteExpired(now: Long)
}

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations WHERE conversationId = :id")
    suspend fun getById(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations ORDER BY lastMessageAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("UPDATE conversations SET lastMessageAt = :timestamp, lastMessagePreview = :preview WHERE conversationId = :id")
    suspend fun updateLastMessage(id: String, preview: String, timestamp: Long)

    @Query("UPDATE conversations SET unreadCount = unreadCount + 1 WHERE conversationId = :id")
    suspend fun incrementUnread(id: String)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE conversationId = :id")
    suspend fun clearUnread(id: String)

    @Query("DELETE FROM conversations WHERE conversationId = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM conversations WHERE type = 'DIRECT' AND participants LIKE '%' || :nodeId || '%' LIMIT 1")
    suspend fun findDirectConversation(nodeId: String): ConversationEntity?
}

@Dao
interface PeerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(peer: PeerEntity)

    @Query("SELECT * FROM peers ORDER BY lastSeen DESC")
    fun observeAll(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE nodeId = :nodeId")
    suspend fun getById(nodeId: String): PeerEntity?

    @Query("SELECT * FROM peers WHERE hopCount = 0")
    suspend fun getDirectPeers(): List<PeerEntity>

    @Query("DELETE FROM peers WHERE nodeId = :nodeId")
    suspend fun delete(nodeId: String)

    @Query("DELETE FROM peers WHERE lastSeen < :threshold AND publicExchangeKey IS NULL AND publicSigningKey IS NULL")
    suspend fun pruneStale(threshold: Long)
}

@Dao
interface RoutingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: RoutingEntryEntity)

    @Query("SELECT * FROM routing_entries WHERE destinationNodeId = :nodeId")
    suspend fun getByDestination(nodeId: String): RoutingEntryEntity?

    @Query("SELECT * FROM routing_entries")
    suspend fun getAll(): List<RoutingEntryEntity>

    @Query("DELETE FROM routing_entries WHERE destinationNodeId = :nodeId")
    suspend fun delete(nodeId: String)

    @Query("DELETE FROM routing_entries WHERE lastUpdated < :threshold")
    suspend fun pruneStale(threshold: Long)
}

@Dao
interface SenderKeyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(key: SenderKeyEntity)

    @Query("SELECT * FROM sender_keys WHERE groupId = :groupId AND senderNodeId = :senderNodeId")
    suspend fun get(groupId: String, senderNodeId: String): SenderKeyEntity?

    @Query("SELECT * FROM sender_keys WHERE groupId = :groupId")
    suspend fun getByGroup(groupId: String): List<SenderKeyEntity>

    @Query("DELETE FROM sender_keys WHERE groupId = :groupId")
    suspend fun deleteByGroup(groupId: String)

    @Query("DELETE FROM sender_keys WHERE groupId = :groupId AND senderNodeId = :senderNodeId")
    suspend fun delete(groupId: String, senderNodeId: String)
}

@Dao
interface GroupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(group: GroupEntity)

    @Query("SELECT * FROM groups WHERE groupId = :groupId")
    suspend fun getById(groupId: String): GroupEntity?

    @Query("SELECT * FROM groups ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<GroupEntity>>

    @Query("DELETE FROM groups WHERE groupId = :groupId")
    suspend fun delete(groupId: String)

    @Query("UPDATE groups SET membersJson = :membersJson, version = :version WHERE groupId = :groupId")
    suspend fun updateMembers(groupId: String, membersJson: String, version: Long)
}
