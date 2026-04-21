package com.meshwalk.app.domain.model

/**
 * Toggle flags for the 13 experimental features. All default to off — users
 * enable them individually from Settings → Experimental.
 */
data class ExperimentalFeatures(
    val dropZonesEnabled: Boolean = false,
    val relayIncentivesEnabled: Boolean = false,
    val sosBroadcastEnabled: Boolean = false,
    val bulletinBoardEnabled: Boolean = false,
    val trustGraphEnabled: Boolean = false,
    val partialDoubleRatchetEnabled: Boolean = false,
    val phrasebookEnabled: Boolean = false,
    val gossipSyncEnabled: Boolean = false,
    val reputationTokensEnabled: Boolean = false,
    val signalHeatmapEnabled: Boolean = false,
    val timeToReachEnabled: Boolean = false,
    val voiceTranscriptsEnabled: Boolean = false,
    val duressPinEnabled: Boolean = false,
    /** SHA-256 of the duress PIN, or null when unset. Stored separately so
     *  toggling the feature on/off doesn't wipe the PIN. */
    val duressPinHash: String? = null
)

enum class ForwardSecrecyMode {
    /** Current default: chain ratchet (partial forward secrecy in store-and-forward). */
    CHAIN_RATCHET,
    /** Experimental: double ratchet (stronger FS, breaks on store-and-forward). */
    DOUBLE_RATCHET
}
