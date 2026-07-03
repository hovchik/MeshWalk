package com.meshwalk.app.experimental

import com.meshwalk.app.domain.model.MeshPacket
import com.meshwalk.app.domain.model.PacketType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature 3 — Mesh-wide SOS broadcast.
 *
 * One-tap emergency broadcast with location, battery %, and a short note.
 * The SOS packet uses PacketType.SOS + FLAG_SOS so relays can prioritise it
 * and bypass the default TTL. It's authenticated by the sender's identity
 * key so it can't be spoofed (signing hook is the routing engine's existing
 * signature path).
 *
 * This manager just builds the [SosBroadcast] model and produces
 * [SosAnnouncement]s it has received — actual wire-level transmission is
 * done by the routing engine (via MeshOutbox.sendSos, added separately).
 */
data class SosBroadcast(
    val broadcastId: String,
    val senderNodeId: String,
    val senderName: String?,
    val note: String,
    val latitude: Double?,
    val longitude: Double?,
    val batteryPercent: Int?,
    val timestamp: Long
) {
    /** Short plaintext preamble used as the packet payload. */
    fun toPayloadString(): String = buildString {
        append("SOS|")
        append(broadcastId).append('|')
        // Field separator must be escaped in user-controlled values or parsing breaks.
        append(senderName?.replace('|', '/') ?: "").append('|')
        append(latitude ?: "").append('|')
        append(longitude ?: "").append('|')
        append(batteryPercent ?: "").append('|')
        append(timestamp).append('|')
        append(note.replace('|', '/'))
    }

    companion object {
        fun parse(payload: String): SosBroadcast? {
            val parts = payload.split('|', limit = 8)
            if (parts.size < 8 || parts[0] != "SOS") return null
            return try {
                SosBroadcast(
                    broadcastId = parts[1],
                    senderNodeId = "", // filled in by caller from packet header
                    senderName = parts[2].ifBlank { null },
                    latitude = parts[3].toDoubleOrNull(),
                    longitude = parts[4].toDoubleOrNull(),
                    batteryPercent = parts[5].toIntOrNull(),
                    timestamp = parts[6].toLongOrNull() ?: System.currentTimeMillis(),
                    note = parts[7]
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

@Singleton
class SosManager @Inject constructor() {

    private val _broadcasts = MutableSharedFlow<SosBroadcast>(replay = 10, extraBufferCapacity = 10)
    val broadcasts: SharedFlow<SosBroadcast> = _broadcasts.asSharedFlow()

    fun build(
        senderNodeId: String,
        senderName: String?,
        note: String,
        latitude: Double?,
        longitude: Double?,
        batteryPercent: Int?
    ): SosBroadcast = SosBroadcast(
        broadcastId = UUID.randomUUID().toString(),
        senderNodeId = senderNodeId,
        senderName = senderName,
        note = note.ifBlank { "Emergency — please respond" },
        latitude = latitude,
        longitude = longitude,
        batteryPercent = batteryPercent,
        timestamp = System.currentTimeMillis()
    )

    /** Build a raw wire packet carrying the SOS payload with max TTL and FLAG_SOS. */
    fun buildPacket(broadcast: SosBroadcast, senderSignature: ByteArray, nonce: ByteArray) = MeshPacket(
        sourceNodeId = broadcast.senderNodeId,
        destinationNodeId = "BROADCAST",
        packetType = PacketType.SOS,
        ttl = MeshPacket.MAX_TTL,
        encryptedPayload = broadcast.toPayloadString().toByteArray(Charsets.UTF_8),
        nonce = nonce,
        senderSignature = senderSignature,
        flags = MeshPacket.FLAG_SOS or MeshPacket.FLAG_IS_RELAY
    )

    suspend fun emit(broadcast: SosBroadcast) {
        _broadcasts.emit(broadcast)
    }
}
