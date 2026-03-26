package com.meshwalk.app.domain.model

/**
 * Application settings controlling mesh behavior, scanning, and battery usage.
 */
data class AppSettings(
    val scanMode: ScanMode = ScanMode.BALANCED,
    val relayEnabled: Boolean = true,
    val storeAndForwardEnabled: Boolean = true,
    val maxStoredMessages: Int = 500,
    val messageTtl: Int = MeshPacket.DEFAULT_TTL,
    val messageExpirationHours: Int = 24,
    val darkMode: DarkModeSetting = DarkModeSetting.SYSTEM,
    val showHopCount: Boolean = false,
    val showEncryptionBadge: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val autoStartMesh: Boolean = true,
    val maxRelayHops: Int = 7,
    val groupMessageHistoryCount: Int = 50,
    val fingerprintLockEnabled: Boolean = false,
    val networkGraphRefreshSeconds: Int = 5,
    /** Battery-aware mesh: reduce scan frequency when battery is low. */
    val batteryAwareMeshEnabled: Boolean = true,
    /** Battery threshold (%) below which scan switches to BATTERY_SAVER mode. */
    val lowBatteryThresholdPercent: Int = 20,
    /** Show signal strength indicators per peer. */
    val showSignalStrength: Boolean = true,
    /** Enable haptic feedback on key interactions. */
    val hapticFeedbackEnabled: Boolean = true,
    /** Auto-retry failed messages on reconnect. */
    val autoRetryFailedMessages: Boolean = true,
    /** Enable read receipts. */
    val readReceiptsEnabled: Boolean = true
)

/**
 * Scanning/discovery aggressiveness modes.
 *
 * AGGRESSIVE: Continuous scanning, fastest discovery, highest battery drain.
 *   - BLE scan every 5s, Nearby advertising always on
 *   - Best for: active use, quickly finding peers
 *
 * BALANCED: Periodic scanning with moderate intervals.
 *   - BLE scan every 30s, Nearby advertising on
 *   - Best for: normal daily use
 *
 * BATTERY_SAVER: Infrequent scanning, minimal background activity.
 *   - BLE scan every 2min, Nearby advertising periodic
 *   - Best for: background relay operation with minimal drain
 */
enum class ScanMode(
    val scanIntervalMs: Long,
    val advertisingDutyCycle: Float,
    val label: String,
    val description: String
) {
    AGGRESSIVE(
        scanIntervalMs = 5_000L,
        advertisingDutyCycle = 1.0f,
        label = "Aggressive",
        description = "Fastest discovery, highest battery usage"
    ),
    BALANCED(
        scanIntervalMs = 30_000L,
        advertisingDutyCycle = 0.5f,
        label = "Balanced",
        description = "Good discovery with moderate battery usage"
    ),
    BATTERY_SAVER(
        scanIntervalMs = 120_000L,
        advertisingDutyCycle = 0.2f,
        label = "Battery Saver",
        description = "Minimal scanning, lowest battery usage"
    )
}

enum class DarkModeSetting {
    LIGHT,
    DARK,
    SYSTEM
}
