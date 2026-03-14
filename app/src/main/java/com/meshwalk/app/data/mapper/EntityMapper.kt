package com.meshwalk.app.data.mapper

import com.meshwalk.app.data.local.entity.*
import com.meshwalk.app.domain.model.*
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

object EntityMapper {

    // -- Identity --
    fun IdentityEntity.toDomain() = NodeIdentity(
        nodeId = nodeId,
        displayName = displayName,
        identityType = identityType.toEnumSafe(IdentityType.NAMED),
        publicSigningKey = publicSigningKey,
        publicExchangeKey = publicExchangeKey,
        createdAt = createdAt,
        expiresAt = expiresAt
    )

    fun NodeIdentity.toEntity(isActive: Boolean = false) = IdentityEntity(
        nodeId = nodeId,
        displayName = displayName,
        identityType = identityType.name,
        publicSigningKey = publicSigningKey,
        publicExchangeKey = publicExchangeKey,
        createdAt = createdAt,
        expiresAt = expiresAt,
        isActive = isActive
    )

    // -- Message --
    fun MessageEntity.toDomain() = MeshMessage(
        messageId = messageId,
        conversationId = conversationId,
        senderNodeId = senderNodeId,
        content = when (contentType) {
            "system" -> MessageContent.SystemEvent(contentText)
            else -> MessageContent.Text(contentText)
        },
        timestamp = timestamp,
        deliveryStatus = deliveryStatus.toEnumSafe(DeliveryStatus.PENDING),
        isIncoming = isIncoming,
        hopCount = hopCount,
        expiresAt = expiresAt
    )

    fun MeshMessage.toEntity() = MessageEntity(
        messageId = messageId,
        conversationId = conversationId,
        senderNodeId = senderNodeId,
        contentType = when (content) {
            is MessageContent.Text -> "text"
            is MessageContent.SystemEvent -> "system"
        },
        contentText = when (content) {
            is MessageContent.Text -> content.text
            is MessageContent.SystemEvent -> content.event
        },
        timestamp = timestamp,
        deliveryStatus = deliveryStatus.name,
        isIncoming = isIncoming,
        hopCount = hopCount,
        expiresAt = expiresAt
    )

    // -- Conversation --
    fun ConversationEntity.toDomain() = Conversation(
        conversationId = conversationId,
        type = type.toEnumSafe(ConversationType.DIRECT),
        title = title,
        participants = parseJsonStringList(participants),
        peerDisplayName = peerDisplayName,
        createdAt = createdAt,
        lastMessageAt = lastMessageAt,
        lastMessagePreview = lastMessagePreview,
        unreadCount = unreadCount,
        isEncrypted = isEncrypted
    )

    fun Conversation.toEntity() = ConversationEntity(
        conversationId = conversationId,
        type = type.name,
        title = title,
        participants = toJsonStringList(participants),
        peerDisplayName = peerDisplayName,
        createdAt = createdAt,
        lastMessageAt = lastMessageAt,
        lastMessagePreview = lastMessagePreview,
        unreadCount = unreadCount,
        isEncrypted = isEncrypted
    )

    // -- Peer --
    fun PeerEntity.toDomain() = PeerNode(
        nodeId = nodeId,
        displayName = displayName,
        identityType = identityType.toEnumSafe(IdentityType.NAMED),
        publicSigningKey = publicSigningKey,
        publicExchangeKey = publicExchangeKey,
        connectionType = connectionType.toEnumSafe(ConnectionType.UNKNOWN),
        hopCount = hopCount,
        signalStrength = signalStrength,
        lastSeen = lastSeen,
        isConnected = isConnected,
        relayCapable = relayCapable,
        fingerprint = fingerprint
    )

    fun PeerNode.toEntity() = PeerEntity(
        nodeId = nodeId,
        displayName = displayName,
        identityType = identityType.name,
        publicSigningKey = publicSigningKey,
        publicExchangeKey = publicExchangeKey,
        connectionType = connectionType.name,
        hopCount = hopCount,
        signalStrength = signalStrength,
        lastSeen = lastSeen,
        isConnected = isConnected,
        relayCapable = relayCapable,
        fingerprint = fingerprint
    )

    // -- Routing --
    fun RoutingEntryEntity.toDomain() = RoutingEntry(
        destinationNodeId = destinationNodeId,
        nextHopNodeId = nextHopNodeId,
        hopCount = hopCount,
        connectionType = connectionType.toEnumSafe(ConnectionType.UNKNOWN),
        lastUpdated = lastUpdated,
        reliability = reliability,
        latencyMs = latencyMs
    )

    fun RoutingEntry.toEntity() = RoutingEntryEntity(
        destinationNodeId = destinationNodeId,
        nextHopNodeId = nextHopNodeId,
        hopCount = hopCount,
        connectionType = connectionType.name,
        lastUpdated = lastUpdated,
        reliability = reliability,
        latencyMs = latencyMs
    )

    // -- Group --
    fun GroupEntity.toDomain() = GroupInfo(
        groupId = groupId,
        name = name,
        creatorNodeId = creatorNodeId,
        members = parseGroupMembers(membersJson),
        groupType = groupType.toEnumSafe(ConversationType.GROUP),
        createdAt = createdAt,
        expiresAt = expiresAt,
        version = version,
        maxMembers = maxMembers
    )

    fun GroupInfo.toEntity() = GroupEntity(
        groupId = groupId,
        name = name,
        creatorNodeId = creatorNodeId,
        membersJson = serializeGroupMembers(members),
        groupType = groupType.name,
        createdAt = createdAt,
        expiresAt = expiresAt,
        version = version,
        maxMembers = maxMembers
    )

    // -- JSON helpers with malformed data protection --
    private fun parseJsonStringList(json: String): List<String> {
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                try { array.getString(i) } catch (_: Exception) { null }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse participant list JSON: ${json.take(50)}")
            emptyList()
        }
    }

    private fun toJsonStringList(list: List<String>): String {
        return JSONArray(list).toString()
    }

    private fun parseGroupMembers(json: String): List<GroupMember> {
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                try {
                    val obj = array.getJSONObject(i)
                    GroupMember(
                        nodeId = obj.getString("nodeId"),
                        displayName = obj.optString("displayName").takeIf { it.isNotEmpty() && it != "null" },
                        role = try {
                            GroupRole.valueOf(obj.getString("role"))
                        } catch (_: IllegalArgumentException) {
                            GroupRole.MEMBER
                        },
                        joinedAt = obj.optLong("joinedAt", System.currentTimeMillis()),
                        senderKeyDistributed = obj.optBoolean("senderKeyDistributed", false)
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Skipping malformed group member at index $i")
                    null
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse group members JSON: ${json.take(50)}")
            emptyList()
        }
    }

    private fun serializeGroupMembers(members: List<GroupMember>): String {
        val array = JSONArray()
        members.forEach { m ->
            array.put(JSONObject().apply {
                put("nodeId", m.nodeId)
                put("displayName", m.displayName ?: JSONObject.NULL)
                put("role", m.role.name)
                put("joinedAt", m.joinedAt)
                put("senderKeyDistributed", m.senderKeyDistributed)
            })
        }
        return array.toString()
    }

    /**
     * Safe enum parsing: returns [default] instead of throwing on unknown values.
     * Protects against schema drift when peers on older versions send unrecognized enum strings.
     */
    private inline fun <reified T : Enum<T>> String.toEnumSafe(default: T): T = try {
        enumValueOf<T>(this)
    } catch (_: IllegalArgumentException) {
        Timber.w("Unknown enum value '$this' for ${T::class.simpleName}, using $default")
        default
    }
}
