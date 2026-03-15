package com.meshwalk.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.meshwalk.app.crypto.keys.MeshKeyManager
import com.meshwalk.app.crypto.keys.KeyStorage
import com.meshwalk.app.data.local.dao.*
import com.meshwalk.app.data.mapper.EntityMapper.toDomain
import com.meshwalk.app.data.mapper.EntityMapper.toEntity
import com.meshwalk.app.domain.model.*
import com.meshwalk.app.domain.repository.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// Identity Repository
// ============================================================
@Singleton
class IdentityRepositoryImpl @Inject constructor(
    private val identityDao: IdentityDao,
    private val keyManager: MeshKeyManager,
    private val keyStorage: KeyStorage
) : IdentityRepository {

    override suspend fun createIdentity(name: String?, type: IdentityType): NodeIdentity {
        val keyPair = keyManager.generateIdentityKeyPair()
        val identity = NodeIdentity(
            displayName = name,
            identityType = type,
            publicSigningKey = keyPair.signingPublicKey,
            publicExchangeKey = keyPair.exchangePublicKey,
            expiresAt = if (type == IdentityType.TEMPORARY) {
                System.currentTimeMillis() + 24 * 3600 * 1000
            } else null
        )

        // Store keys securely
        keyStorage.storeIdentityKeys(identity.nodeId, keyPair)

        // Deactivate others and activate this one
        identityDao.deactivateAll()
        identityDao.insert(identity.toEntity(isActive = true))
        keyStorage.setActiveNodeId(identity.nodeId)

        return identity
    }

    override suspend fun getActiveIdentity(): NodeIdentity? {
        return identityDao.getActive()?.toDomain()
    }

    override suspend fun setActiveIdentity(nodeId: String) {
        identityDao.deactivateAll()
        identityDao.activate(nodeId)
        keyStorage.setActiveNodeId(nodeId)
    }

    override suspend fun deleteIdentity(nodeId: String) {
        identityDao.delete(nodeId)
        keyStorage.deleteKeys(nodeId)
    }

    override suspend fun updateProfile(nodeId: String, name: String?, type: IdentityType) {
        identityDao.updateProfile(nodeId, name, type.name)
    }

    override fun observeActiveIdentity(): Flow<NodeIdentity?> {
        return identityDao.observeActive().map { it?.toDomain() }
    }

    override suspend fun getAllIdentities(): List<NodeIdentity> {
        return identityDao.getAll().map { it.toDomain() }
    }
}

// ============================================================
// Message Repository
// ============================================================
@Singleton
class MessageRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao
) : MessageRepository {

    override suspend fun saveMessage(message: MeshMessage) {
        messageDao.insert(message.toEntity())
    }

    override suspend fun updateDeliveryStatus(messageId: String, status: DeliveryStatus) {
        messageDao.updateStatus(messageId, status.name)
    }

    override fun observeMessages(conversationId: String): Flow<List<MeshMessage>> {
        return messageDao.observeByConversation(conversationId)
            .map { entities -> entities.map { it.toDomain() } }
    }

    override fun observeRecentMessages(conversationId: String, limit: Int): Flow<List<MeshMessage>> {
        return messageDao.observeRecentByConversation(conversationId, limit)
            .map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun getUndeliveredMessages(): List<MeshMessage> {
        return messageDao.getUndelivered().map { it.toDomain() }
    }

    override suspend fun deleteMessage(messageId: String) {
        messageDao.delete(messageId)
    }

    override suspend fun deleteMessagesByConversation(conversationId: String) {
        messageDao.deleteByConversation(conversationId)
    }

    override suspend fun deleteExpiredMessages() {
        messageDao.deleteExpired(System.currentTimeMillis())
    }

    override suspend fun getMessageById(messageId: String): MeshMessage? {
        return messageDao.getById(messageId)?.toDomain()
    }

    override suspend fun getFailedMessagesForPeer(peerNodeId: String): List<MeshMessage> {
        return messageDao.getFailedForPeer(peerNodeId).map { it.toDomain() }
    }
}

// ============================================================
// Conversation Repository
// ============================================================
@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao
) : ConversationRepository {

    override suspend fun createConversation(conversation: Conversation): Conversation {
        conversationDao.insert(conversation.toEntity())
        return conversation
    }

    override suspend fun getConversation(conversationId: String): Conversation? {
        return conversationDao.getById(conversationId)?.toDomain()
    }

    override suspend fun getOrCreateDirectConversation(peerNodeId: String, peerDisplayName: String?): Conversation {
        val existing = conversationDao.findDirectConversation(peerNodeId)
        if (existing != null) {
            // Update cached peer name if we now have one and it's different
            val conv = existing.toDomain()
            if (peerDisplayName != null && conv.peerDisplayName != peerDisplayName) {
                conversationDao.updatePeerDisplayName(conv.conversationId, peerDisplayName)
                return conv.copy(peerDisplayName = peerDisplayName)
            }
            return conv
        }

        val conversation = Conversation(
            type = ConversationType.DIRECT,
            title = null,
            participants = listOf(peerNodeId),
            peerDisplayName = peerDisplayName
        )
        conversationDao.insert(conversation.toEntity())
        return conversation
    }

    override suspend fun updatePeerDisplayName(conversationId: String, displayName: String) {
        conversationDao.updatePeerDisplayName(conversationId, displayName)
    }

    override fun observeConversations(): Flow<List<Conversation>> {
        return conversationDao.observeAll().map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun updateLastMessage(conversationId: String, preview: String, timestamp: Long) {
        conversationDao.updateLastMessage(conversationId, preview, timestamp)
    }

    override suspend fun incrementUnread(conversationId: String) {
        conversationDao.incrementUnread(conversationId)
    }

    override suspend fun clearUnread(conversationId: String) {
        conversationDao.clearUnread(conversationId)
    }

    override suspend fun deleteConversation(conversationId: String) {
        conversationDao.delete(conversationId)
    }
}

// ============================================================
// Peer Repository
// ============================================================
@Singleton
class PeerRepositoryImpl @Inject constructor(
    private val peerDao: PeerDao
) : PeerRepository {

    override suspend fun upsertPeer(peer: PeerNode) {
        peerDao.upsert(peer.toEntity())
    }

    override suspend fun removePeer(nodeId: String) {
        peerDao.delete(nodeId)
    }

    override fun observeNearbyPeers(): Flow<List<PeerNode>> {
        return peerDao.observeAll().map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun getPeer(nodeId: String): PeerNode? {
        return peerDao.getById(nodeId)?.toDomain()
    }

    override suspend fun getDirectPeers(): List<PeerNode> {
        return peerDao.getDirectPeers().map { it.toDomain() }
    }

    override suspend fun pruneStale(maxAgeMs: Long) {
        peerDao.pruneStale(System.currentTimeMillis() - maxAgeMs)
    }
}

// ============================================================
// Group Repository
// ============================================================
@Singleton
class GroupRepositoryImpl @Inject constructor(
    private val groupDao: GroupDao
) : GroupRepository {

    override suspend fun createGroup(group: GroupInfo): GroupInfo {
        groupDao.insert(group.toEntity())
        return group
    }

    override suspend fun getGroup(groupId: String): GroupInfo? {
        return groupDao.getById(groupId)?.toDomain()
    }

    override suspend fun updateGroup(group: GroupInfo) {
        groupDao.insert(group.toEntity())
    }

    override fun observeGroups(): Flow<List<GroupInfo>> {
        return groupDao.observeAll().map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun addMember(groupId: String, member: GroupMember) {
        val group = getGroup(groupId) ?: return
        val updated = group.copy(
            members = group.members + member,
            version = group.version + 1
        )
        updateGroup(updated)
    }

    override suspend fun removeMember(groupId: String, nodeId: String) {
        val group = getGroup(groupId) ?: return
        val updated = group.copy(
            members = group.members.filter { it.nodeId != nodeId },
            version = group.version + 1
        )
        updateGroup(updated)
    }

    override suspend fun deleteGroup(groupId: String) {
        groupDao.delete(groupId)
    }

    override suspend fun applyMembershipChange(change: GroupMembershipChange) {
        val group = getGroup(change.groupId) ?: return
        val updated = when (change.changeType) {
            MembershipChangeType.JOINED -> group.copy(
                members = group.members + GroupMember(
                    nodeId = change.nodeId,
                    displayName = null,
                    role = GroupRole.MEMBER
                ),
                version = change.version
            )
            MembershipChangeType.LEFT, MembershipChangeType.REMOVED -> group.copy(
                members = group.members.filter { it.nodeId != change.nodeId },
                version = change.version
            )
            MembershipChangeType.PROMOTED -> group.copy(
                members = group.members.map { member ->
                    if (member.nodeId == change.nodeId) member.copy(role = GroupRole.ADMIN)
                    else member
                },
                version = change.version
            )
            MembershipChangeType.GROUP_CREATED -> group.copy(version = change.version)
            MembershipChangeType.GROUP_DISSOLVED -> {
                deleteGroup(change.groupId)
                return
            }
        }
        updateGroup(updated)
    }
}

// ============================================================
// Routing Repository
// ============================================================
@Singleton
class RoutingRepositoryImpl @Inject constructor(
    private val routingDao: RoutingDao,
    private val peerDao: PeerDao,
    private val keyStorage: KeyStorage
) : RoutingRepository {

    override suspend fun updateRoute(entry: RoutingEntry) {
        routingDao.upsert(entry.toEntity())
    }

    override suspend fun getRoute(destinationNodeId: String): RoutingEntry? {
        return routingDao.getByDestination(destinationNodeId)?.toDomain()
    }

    override suspend fun getAllRoutes(): List<RoutingEntry> {
        return routingDao.getAll().map { it.toDomain() }
    }

    override suspend fun removeRoute(destinationNodeId: String) {
        routingDao.delete(destinationNodeId)
    }

    override suspend fun pruneStaleRoutes() {
        routingDao.pruneStale(System.currentTimeMillis() - RoutingEntry.STALE_THRESHOLD_MS)
    }

    override fun observeNetworkGraph(): Flow<NetworkGraph> {
        return peerDao.observeAll().map { peers ->
            val routes = routingDao.getAll()
            val selfNodeId = keyStorage.getActiveNodeId() ?: ""
            NetworkGraph(
                selfNodeId = selfNodeId,
                nodes = peers.map { p ->
                    GraphNode(
                        nodeId = p.nodeId,
                        displayName = p.displayName,
                        identityType = safeEnum(p.identityType, IdentityType.NAMED),
                        isDirect = p.hopCount == 0,
                        hopCount = p.hopCount,
                        lastSeen = p.lastSeen
                    )
                },
                edges = routes.map { r ->
                    GraphEdge(
                        fromNodeId = r.nextHopNodeId,
                        toNodeId = r.destinationNodeId,
                        connectionType = safeEnum(r.connectionType, ConnectionType.UNKNOWN),
                        isActive = true
                    )
                }
            )
        }
    }

    private inline fun <reified T : Enum<T>> safeEnum(value: String, default: T): T = try {
        enumValueOf<T>(value)
    } catch (_: IllegalArgumentException) { default }
}

// ============================================================
// Settings Repository
// ============================================================
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("mesh_settings")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {

    companion object {
        private val SCAN_MODE = stringPreferencesKey("scan_mode")
        private val RELAY_ENABLED = booleanPreferencesKey("relay_enabled")
        private val STORE_FORWARD = booleanPreferencesKey("store_forward")
        private val DARK_MODE = stringPreferencesKey("dark_mode")
        private val NOTIFICATIONS = booleanPreferencesKey("notifications")
        private val AUTO_START = booleanPreferencesKey("auto_start")
        private val MESSAGE_TTL = intPreferencesKey("message_ttl")
        private val SHOW_HOP_COUNT = booleanPreferencesKey("show_hop_count")
        private val SHOW_ENCRYPTION_BADGE = booleanPreferencesKey("show_encryption_badge")
        private val GROUP_MESSAGE_HISTORY_COUNT = intPreferencesKey("group_message_history_count")
        private val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    }

    override fun observeSettings(): Flow<AppSettings> {
        return context.settingsDataStore.data.map { prefs -> prefs.toAppSettings() }
    }

    override suspend fun getSettings(): AppSettings {
        return context.settingsDataStore.data.first().toAppSettings()
    }

    override suspend fun updateSettings(settings: AppSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[SCAN_MODE] = settings.scanMode.name
            prefs[RELAY_ENABLED] = settings.relayEnabled
            prefs[STORE_FORWARD] = settings.storeAndForwardEnabled
            prefs[DARK_MODE] = settings.darkMode.name
            prefs[NOTIFICATIONS] = settings.notificationsEnabled
            prefs[AUTO_START] = settings.autoStartMesh
            prefs[MESSAGE_TTL] = settings.messageTtl
            prefs[SHOW_HOP_COUNT] = settings.showHopCount
            prefs[SHOW_ENCRYPTION_BADGE] = settings.showEncryptionBadge
            prefs[GROUP_MESSAGE_HISTORY_COUNT] = settings.groupMessageHistoryCount
        }
    }

    override suspend fun isOnboardingComplete(): Boolean {
        return context.settingsDataStore.data.first()[ONBOARDING_DONE] ?: false
    }

    override suspend fun setOnboardingComplete() {
        context.settingsDataStore.edit { it[ONBOARDING_DONE] = true }
    }

    private fun Preferences.toAppSettings() = AppSettings(
        scanMode = this[SCAN_MODE]?.let {
            try { ScanMode.valueOf(it) } catch (_: IllegalArgumentException) { ScanMode.BALANCED }
        } ?: ScanMode.BALANCED,
        relayEnabled = this[RELAY_ENABLED] ?: true,
        storeAndForwardEnabled = this[STORE_FORWARD] ?: true,
        darkMode = this[DARK_MODE]?.let {
            try { DarkModeSetting.valueOf(it) } catch (_: IllegalArgumentException) { DarkModeSetting.SYSTEM }
        } ?: DarkModeSetting.SYSTEM,
        notificationsEnabled = this[NOTIFICATIONS] ?: true,
        autoStartMesh = this[AUTO_START] ?: true,
        messageTtl = this[MESSAGE_TTL] ?: MeshPacket.DEFAULT_TTL,
        showHopCount = this[SHOW_HOP_COUNT] ?: false,
        showEncryptionBadge = this[SHOW_ENCRYPTION_BADGE] ?: true,
        groupMessageHistoryCount = this[GROUP_MESSAGE_HISTORY_COUNT] ?: 50
    )
}
