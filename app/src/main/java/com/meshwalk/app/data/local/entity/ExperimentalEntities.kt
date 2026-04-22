package com.meshwalk.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Drop zone: encrypted message that only unlocks when the reader is in the
 * zone. "Zone" is either a lat/lng radius or the BLE advertisement of a
 * specific beacon nodeId. The encrypted payload is stored in the entity; the
 * zone metadata is plaintext so unlocking can be evaluated locally.
 */
@Entity(tableName = "drop_zones")
data class DropZoneEntity(
    @PrimaryKey val zoneId: String,
    val creatorNodeId: String,
    val recipientNodeId: String?,   // null = public drop for whoever enters zone
    val encryptedContent: ByteArray,
    val nonce: ByteArray,
    val latitude: Double?,          // null if beacon-based
    val longitude: Double?,
    val radiusMeters: Double?,
    val beaconNodeId: String?,       // null if location-based
    val createdAt: Long,
    val expiresAt: Long,
    val isUnlocked: Boolean,
    val unlockedAt: Long?
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is DropZoneEntity && zoneId == other.zoneId)
    override fun hashCode(): Int = zoneId.hashCode()
}

/**
 * Bulletin-board post: anonymous broadcast with proof-of-work. The
 * proofOfWork field is a nonce such that SHA-256(postId || body || nonce)
 * has >= difficultyBits leading zeros.
 */
@Entity(tableName = "bulletin_posts")
data class BulletinPostEntity(
    @PrimaryKey val postId: String,
    val body: String,
    val category: String,             // e.g. "general", "help", "events"
    val createdAt: Long,
    val expiresAt: Long,
    val proofOfWork: Long,
    val difficultyBits: Int,
    val hopCount: Int,
    val isOwn: Boolean
)

/**
 * Reputation attestation: signed claim that `attesterNodeId` saw
 * `subjectNodeId` successfully relay packets. Signature is over
 * (subjectNodeId || attesterNodeId || relayCount || issuedAt).
 */
@Entity(
    tableName = "reputation_tokens",
    primaryKeys = ["subjectNodeId", "attesterNodeId", "issuedAt"]
)
data class ReputationTokenEntity(
    val subjectNodeId: String,
    val attesterNodeId: String,
    val relayCount: Int,
    val issuedAt: Long,
    val signature: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReputationTokenEntity) return false
        return subjectNodeId == other.subjectNodeId &&
            attesterNodeId == other.attesterNodeId &&
            issuedAt == other.issuedAt
    }
    override fun hashCode(): Int {
        var r = subjectNodeId.hashCode()
        r = 31 * r + attesterNodeId.hashCode()
        r = 31 * r + issuedAt.hashCode()
        return r
    }
}

/**
 * Signal-strength sample: RSSI reading for a peer at a point in time, with
 * optional location so we can render a coverage heatmap.
 */
@Entity(tableName = "signal_samples")
data class SignalSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val peerNodeId: String,
    val rssi: Int,
    val latitude: Double?,
    val longitude: Double?,
    val recordedAt: Long
)

/**
 * Per-peer aggregated mesh stats: relayed packet count (relay-incentive
 * dashboard), average delivery latency in ms, encounter count (trust graph).
 */
@Entity(tableName = "peer_stats")
data class PeerStatsEntity(
    @PrimaryKey val nodeId: String,
    val encounterCount: Long,        // # of times we've been in direct BT range
    val lastEncounterAt: Long,
    val deliveryLatencyEwmaMs: Long, // EWMA of observed delivery latencies
    val deliverySampleCount: Long
)

/**
 * Global counters for the relay-incentives dashboard. Single-row table
 * (primary key "self").
 */
@Entity(tableName = "relay_counters")
data class RelayCounterEntity(
    @PrimaryKey val id: String = "self",
    val packetsRelayed: Long,
    val bytesRelayed: Long,
    val sosRelayed: Long,
    val lastUpdated: Long
)
