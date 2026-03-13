package com.meshwalk.app.billing

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized feature-gating based on subscription status.
 *
 * Use this instead of checking billing state directly:
 *
 * ```kotlin
 * if (featureGate.maxGroupSize > memberCount) { ... }
 * if (featureGate.canAccessDiagnostics) { ... }
 * ```
 *
 * Free-tier limits are the defaults; premium unlocks higher limits.
 */
@Singleton
class FeatureGate @Inject constructor(
    private val billingManager: BillingManager
) {
    /** Maximum members per group (free: 5, premium: 50). */
    val maxGroupSize: Int
        get() = if (billingManager.isFeatureEnabled(PremiumFeature.EXTENDED_GROUP_SIZE)) 50 else 5

    /** Maximum store-and-forward queue depth (free: 100, premium: 1000). */
    val maxQueueSize: Int
        get() = if (billingManager.isFeatureEnabled(PremiumFeature.EXTENDED_STORE_FORWARD)) 1000 else 100

    /** Message retention in days (free: 7, premium: 90). */
    val messageRetentionDays: Int
        get() = if (billingManager.isFeatureEnabled(PremiumFeature.EXTENDED_MESSAGE_HISTORY)) 90 else 7

    /** Whether diagnostics screen is accessible. */
    val canAccessDiagnostics: Boolean
        get() = billingManager.isFeatureEnabled(PremiumFeature.DIAGNOSTIC_TOOLS)

    /** Whether this node gets priority in relay routing. */
    val hasPriorityRelay: Boolean
        get() = billingManager.isFeatureEnabled(PremiumFeature.PRIORITY_RELAY_ROUTING)

    /** Whether user can create multiple named identities. */
    val canCreateMultipleIdentities: Boolean
        get() = billingManager.isFeatureEnabled(PremiumFeature.CUSTOM_IDENTITY_NAMES)

    /** Current subscription state for UI display. */
    val subscriptionState get() = billingManager.subscriptionState
}
