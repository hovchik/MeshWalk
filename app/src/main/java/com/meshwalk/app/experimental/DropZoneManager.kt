package com.meshwalk.app.experimental

import com.meshwalk.app.crypto.envelope.MessageEnvelopeManager
import com.meshwalk.app.data.local.dao.DropZoneDao
import com.meshwalk.app.data.local.entity.DropZoneEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Feature 1 — Ephemeral store-and-forward drop zones.
 *
 * A drop zone is an encrypted message that only unlocks when the reader is in
 * a geographic radius *or* detects a specific BLE beacon node. Useful for
 * dead drops in hikes, festivals, protests.
 *
 * Encryption: AES-256-GCM with a key derived from the zone's seed. The seed
 * is either a location (rounded to the zone's radius cell) or the beacon's
 * nodeId — which means anyone entering the zone can derive the same key and
 * decrypt. This is intentionally not E2E: it's a "whoever enters the zone"
 * unlock model.
 */
data class DropZone(
    val zoneId: String,
    val creatorNodeId: String,
    val recipientNodeId: String?,
    val latitude: Double?,
    val longitude: Double?,
    val radiusMeters: Double?,
    val beaconNodeId: String?,
    val createdAt: Long,
    val expiresAt: Long,
    val isUnlocked: Boolean,
    val unlockedContent: String?
)

@Singleton
class DropZoneManager @Inject constructor(
    private val dao: DropZoneDao,
    private val envelope: MessageEnvelopeManager
) {
    fun observeZones(): Flow<List<DropZone>> = dao.observeAll().map { list ->
        list.map { it.toDomain(unlockedContent = null) }
    }

    suspend fun createLocationZone(
        creatorNodeId: String,
        recipientNodeId: String?,
        body: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        ttlMs: Long
    ): String {
        val zoneId = UUID.randomUUID().toString()
        val seed = locationSeed(latitude, longitude, radiusMeters)
        val nonce = randomNonce()
        val ciphertext = envelope.encryptAesGcm(
            plaintext = body.toByteArray(Charsets.UTF_8),
            key = SecretKeySpec(deriveKey(seed, zoneId), "AES"),
            nonce = nonce,
            aad = zoneId.toByteArray(Charsets.UTF_8)
        )
        dao.upsert(
            DropZoneEntity(
                zoneId = zoneId,
                creatorNodeId = creatorNodeId,
                recipientNodeId = recipientNodeId,
                encryptedContent = ciphertext,
                nonce = nonce,
                latitude = latitude,
                longitude = longitude,
                radiusMeters = radiusMeters,
                beaconNodeId = null,
                createdAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + ttlMs,
                isUnlocked = false,
                unlockedAt = null
            )
        )
        return zoneId
    }

    suspend fun createBeaconZone(
        creatorNodeId: String,
        recipientNodeId: String?,
        body: String,
        beaconNodeId: String,
        ttlMs: Long
    ): String {
        val zoneId = UUID.randomUUID().toString()
        val nonce = randomNonce()
        val ciphertext = envelope.encryptAesGcm(
            plaintext = body.toByteArray(Charsets.UTF_8),
            key = SecretKeySpec(deriveKey(beaconNodeId.toByteArray(Charsets.UTF_8), zoneId), "AES"),
            nonce = nonce,
            aad = zoneId.toByteArray(Charsets.UTF_8)
        )
        dao.upsert(
            DropZoneEntity(
                zoneId = zoneId,
                creatorNodeId = creatorNodeId,
                recipientNodeId = recipientNodeId,
                encryptedContent = ciphertext,
                nonce = nonce,
                latitude = null,
                longitude = null,
                radiusMeters = null,
                beaconNodeId = beaconNodeId,
                createdAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + ttlMs,
                isUnlocked = false,
                unlockedAt = null
            )
        )
        return zoneId
    }

    /**
     * Attempt to unlock a zone given the user's current position or the set of
     * nearby beacon nodeIds. Returns plaintext on success, null on failure.
     */
    suspend fun tryUnlock(
        zoneId: String,
        currentLat: Double?,
        currentLng: Double?,
        nearbyBeaconIds: Set<String>
    ): String? {
        val zone = dao.getById(zoneId) ?: return null
        if (zone.expiresAt < System.currentTimeMillis()) return null

        val seed = when {
            zone.beaconNodeId != null && zone.beaconNodeId in nearbyBeaconIds ->
                zone.beaconNodeId.toByteArray(Charsets.UTF_8)
            zone.latitude != null && zone.longitude != null && zone.radiusMeters != null
                && currentLat != null && currentLng != null
                && haversineMeters(zone.latitude, zone.longitude, currentLat, currentLng) <= zone.radiusMeters ->
                locationSeed(zone.latitude, zone.longitude, zone.radiusMeters)
            else -> return null
        }

        return try {
            val plaintext = envelope.decryptAesGcm(
                ciphertext = zone.encryptedContent,
                key = SecretKeySpec(deriveKey(seed, zoneId), "AES"),
                nonce = zone.nonce,
                aad = zoneId.toByteArray(Charsets.UTF_8)
            )
            dao.markUnlocked(zoneId, System.currentTimeMillis())
            String(plaintext, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun pruneExpired() = dao.deleteExpired(System.currentTimeMillis())

    private fun deriveKey(seed: ByteArray, zoneId: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(seed)
        md.update(zoneId.toByteArray(Charsets.UTF_8))
        return md.digest()
    }

    /** Round a location to its radius-cell so neighbours derive the same key. */
    private fun locationSeed(lat: Double, lng: Double, radiusMeters: Double): ByteArray {
        val cellDeg = (radiusMeters / 111_000.0).coerceAtLeast(0.0001) // ~meters per degree
        val cLat = Math.round(lat / cellDeg)
        val cLng = Math.round(lng / cellDeg)
        return "zone:$cLat:$cLng".toByteArray(Charsets.UTF_8)
    }

    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun randomNonce(): ByteArray = ByteArray(12).also { SecureRandom().nextBytes(it) }

    private fun DropZoneEntity.toDomain(unlockedContent: String?) = DropZone(
        zoneId = zoneId,
        creatorNodeId = creatorNodeId,
        recipientNodeId = recipientNodeId,
        latitude = latitude,
        longitude = longitude,
        radiusMeters = radiusMeters,
        beaconNodeId = beaconNodeId,
        createdAt = createdAt,
        expiresAt = expiresAt,
        isUnlocked = isUnlocked,
        unlockedContent = unlockedContent
    )
}
