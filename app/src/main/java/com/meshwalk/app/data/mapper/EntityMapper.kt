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
            "location" -> parseLocationContent(contentText)
            "image" -> parseImageContent(contentText)
            else -> MessageContent.Text(contentText)
        },
        timestamp = timestamp,
        deliveryStatus = deliveryStatus.toEnumSafe(DeliveryStatus.PENDING),
        isIncoming = isIncoming,
        hopCount = hopCount,
        expiresAt = expiresAt,
        isDelayed = isDelayed,
        reactions = parseReactionsJson(reactionsJson),
        replyToMessageId = replyToMessageId?.takeIf { it.isNotEmpty() },
        replyToPreview = replyToPreview?.takeIf { it.isNotEmpty() },
        isPinned = isPinned
    )

    fun MeshMessage.toEntity() = MessageEntity(
        messageId = messageId,
        conversationId = conversationId,
        senderNodeId = senderNodeId,
        contentType = when (content) {
            is MessageContent.Text -> "text"
            is MessageContent.SystemEvent -> "system"
            is MessageContent.Location -> "location"
            is MessageContent.Image -> "image"
        },
        contentText = when (content) {
            is MessageContent.Text -> content.text
            is MessageContent.SystemEvent -> content.event
            is MessageContent.Location ->
                "${content.latitude},${content.longitude},${content.accuracyMeters ?: ""}"
            is MessageContent.Image ->
                "${content.width},${content.height}:${content.base64Jpeg}"
        },
        timestamp = timestamp,
        deliveryStatus = deliveryStatus.name,
        isIncoming = isIncoming,
        hopCount = hopCount,
        expiresAt = expiresAt,
        isDelayed = isDelayed,
        reactionsJson = serializeReactionsJson(reactions),
        replyToMessageId = replyToMessageId ?: "",
        replyToPreview = replyToPreview ?: "",
        isPinned = isPinned
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
        isEncrypted = isEncrypted,
        nickname = nickname?.takeIf { it.isNotEmpty() },
        isFavorite = isFavorite,
        messageTtlMs = messageTtlMs
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
        isEncrypted = isEncrypted,
        nickname = nickname ?: "",
        isFavorite = isFavorite,
        messageTtlMs = messageTtlMs
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
        fingerprint = fingerprint,
        isVerified = isVerified
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
        fingerprint = fingerprint,
        isVerified = isVerified
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

    // -- Message content helpers (same encodings as the wire format) --

    private fun parseLocationContent(body: String): MessageContent {
        val fields = body.split(",", limit = 3)
        val lat = fields.getOrNull(0)?.toDoubleOrNull()
        val lng = fields.getOrNull(1)?.toDoubleOrNull()
        if (lat == null || lng == null) return MessageContent.Text(body)
        return MessageContent.Location(lat, lng, fields.getOrNull(2)?.toFloatOrNull())
    }

    private fun parseImageContent(body: String): MessageContent {
        val sep = body.indexOf(':')
        if (sep <= 0) return MessageContent.Text(body)
        val dims = body.substring(0, sep).split(",", limit = 2)
        return MessageContent.Image(
            base64Jpeg = body.substring(sep + 1),
            width = dims.getOrNull(0)?.toIntOrNull() ?: 0,
            height = dims.getOrNull(1)?.toIntOrNull() ?: 0
        )
    }

    // -- Reactions JSON helpers --
    private fun parseReactionsJson(json: String): Map<String, String> {
        if (json.isEmpty()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { key -> map[key] = obj.getString(key) }
            map
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse reactions JSON")
            emptyMap()
        }
    }

    fun serializeReactionsJson(reactions: Map<String, String>): String {
        if (reactions.isEmpty()) return ""
        return JSONObject(reactions).toString()
    }

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
