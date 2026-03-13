package com.meshwalk.app.billing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Premium feature definitions for MeshWalk.
 *
 * Free tier:
 * - 1:1 chat, groups up to 5 members, 100-message store-and-forward queue
 *
 * Premium tier:
 * - Unlimited group size (up to 50), 1000-message queue,
 *   priority relay routing, extended message history, diagnostic tools
 */
enum class PremiumFeature {
    EXTENDED_GROUP_SIZE,         // Groups > 5 members
    PRIORITY_RELAY_ROUTING,     // Prioritized packet forwarding
    EXTENDED_STORE_FORWARD,     // Larger offline queue (1000 vs 100)
    DIAGNOSTIC_TOOLS,           // Full diagnostics screen access
    EXTENDED_MESSAGE_HISTORY,   // Message retention > 7 days
    CUSTOM_IDENTITY_NAMES       // Multiple named identities
}

/**
 * Subscription state observed by the UI layer.
 */
data class SubscriptionState(
    val isPremium: Boolean = false,
    val planName: String = "Free",
    val expiresAt: Long? = null,
    val isGracePeriod: Boolean = false
) {
    val isActive: Boolean get() = isPremium && !isExpired
    private val isExpired: Boolean
        get() = expiresAt != null && System.currentTimeMillis() > expiresAt
}

/**
 * Abstraction over billing provider (Google Play Billing, etc.).
 *
 * The interface is provider-agnostic so the app can be tested without
 * Google Play Services and can gracefully degrade on devices without
 * a billing client (e.g., sideloaded APKs, AOSP builds).
 */
interface BillingProvider {
    val subscriptionState: StateFlow<SubscriptionState>
    suspend fun initialize()
    suspend fun launchPurchaseFlow(): PurchaseResult
    suspend fun restorePurchases(): Boolean
    fun isFeatureEnabled(feature: PremiumFeature): Boolean
    fun destroy()
}

sealed interface PurchaseResult {
    data object Success : PurchaseResult
    data object Cancelled : PurchaseResult
    data class Error(val message: String) : PurchaseResult
    data object BillingUnavailable : PurchaseResult
}

/**
 * Concrete billing manager that wraps the billing provider.
 *
 * Falls back to [FreeTierBillingProvider] when Google Play Billing
 * is unavailable (no Play Store, sideloaded, emulator, etc.).
 *
 * UI layer observes [subscriptionState] and calls [isFeatureEnabled]
 * to gate premium features — never checks billing directly.
 */
@Singleton
class BillingManager @Inject constructor() : BillingProvider {

    // In production, this would be initialized with Google Play BillingClient.
    // For now, uses the free-tier fallback so the app compiles and runs
    // without play-services-billing dependency.
    private val provider: BillingProvider = FreeTierBillingProvider()

    private val _subscriptionState = MutableStateFlow(SubscriptionState())
    override val subscriptionState: StateFlow<SubscriptionState> = _subscriptionState.asStateFlow()

    override suspend fun initialize() {
        try {
            provider.initialize()
            // Mirror provider state
            _subscriptionState.value = provider.subscriptionState.value
        } catch (e: Exception) {
            Timber.w(e, "Billing initialization failed, falling back to free tier")
            _subscriptionState.value = SubscriptionState()
        }
    }

    override suspend fun launchPurchaseFlow(): PurchaseResult {
        return try {
            provider.launchPurchaseFlow()
        } catch (e: Exception) {
            Timber.e(e, "Purchase flow failed")
            PurchaseResult.Error(e.message ?: "Purchase failed")
        }
    }

    override suspend fun restorePurchases(): Boolean {
        return try {
            provider.restorePurchases()
        } catch (e: Exception) {
            Timber.w(e, "Restore purchases failed")
            false
        }
    }

    override fun isFeatureEnabled(feature: PremiumFeature): Boolean {
        return provider.isFeatureEnabled(feature)
    }

    override fun destroy() {
        provider.destroy()
    }
}

/**
 * Free-tier fallback when no billing provider is available.
 * All premium features are disabled; the app still works with free limits.
 */
class FreeTierBillingProvider : BillingProvider {
    override val subscriptionState = MutableStateFlow(SubscriptionState())

    override suspend fun initialize() {
        Timber.d("Free tier billing provider initialized (no Play Store billing)")
    }

    override suspend fun launchPurchaseFlow(): PurchaseResult {
        return PurchaseResult.BillingUnavailable
    }

    override suspend fun restorePurchases(): Boolean = false

    override fun isFeatureEnabled(feature: PremiumFeature): Boolean = false

    override fun destroy() { /* no-op */ }
}
