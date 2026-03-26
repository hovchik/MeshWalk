package com.meshwalk.app.billing

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.meshwalk.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
 *   priority relay routing, extended message history, diagnostic tools,
 *   chat export, custom themes
 */
enum class PremiumFeature {
    EXTENDED_GROUP_SIZE,         // Groups > 5 members
    PRIORITY_RELAY_ROUTING,     // Prioritized packet forwarding
    EXTENDED_STORE_FORWARD,     // Larger offline queue (1000 vs 100)
    DIAGNOSTIC_TOOLS,           // Full diagnostics screen access
    EXTENDED_MESSAGE_HISTORY,   // Message retention > 7 days
    CUSTOM_IDENTITY_NAMES,      // Multiple named identities
    CHAT_EXPORT,                // Export chat history (JSON/CSV)
    CUSTOM_THEMES               // Custom color themes
}

/**
 * Subscription state observed by the UI layer.
 */
data class SubscriptionState(
    val isPremium: Boolean = false,
    val planName: String = "Free",
    val expiresAt: Long? = null,
    val isGracePeriod: Boolean = false,
    val isFreeTrial: Boolean = false
) {
    /** Subscription is active if premium and either not expired or in grace period. */
    val isActive: Boolean get() = isPremium && (!isExpired || isGracePeriod)
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
 * Attempts to initialize [GooglePlayBillingProvider] first, then falls back
 * to [FreeTierBillingProvider] when Google Play Billing is unavailable
 * (no Play Store, sideloaded, emulator, etc.).
 *
 * UI layer observes [subscriptionState] and calls [isFeatureEnabled]
 * to gate premium features — never checks billing directly.
 */
@Singleton
class BillingManager @Inject constructor(
    private val context: Context
) : BillingProvider {

    private var provider: BillingProvider = FreeTierBillingProvider()
    private var googlePlayProvider: GooglePlayBillingProvider? = null

    private val _subscriptionState = MutableStateFlow(SubscriptionState())
    override val subscriptionState: StateFlow<SubscriptionState> = _subscriptionState.asStateFlow()

    private val _availablePlans = MutableStateFlow<List<PlanDetails>>(emptyList())
    val availablePlans: StateFlow<List<PlanDetails>> = _availablePlans.asStateFlow()

    private var billingScope: CoroutineScope? = null
    private var initialized = false

    override suspend fun initialize() {
        if (initialized) return
        initialized = true

        // In debug builds, use a simulated billing provider so purchases
        // can be tested without Google Play Console configuration.
        if (BuildConfig.MESH_DEBUG) {
            Timber.d("Debug build: using DebugBillingProvider")
            val debugProvider = DebugBillingProvider()
            debugProvider.initialize()
            provider = debugProvider
            _subscriptionState.value = debugProvider.subscriptionState.value
            _availablePlans.value = debugProvider.availablePlans.value

            val scope = billingScope
            if (scope != null) {
                debugProvider.subscriptionState.onEach { state ->
                    _subscriptionState.value = state
                }.launchIn(scope)

                debugProvider.availablePlans.onEach { plans ->
                    _availablePlans.value = plans
                }.launchIn(scope)
            }
            return
        }

        try {
            val scope = billingScope
                ?: throw IllegalStateException("Call observeStateChanges() before initialize()")
            val gpProvider = GooglePlayBillingProvider(context, scope)
            gpProvider.initialize()
            provider = gpProvider
            googlePlayProvider = gpProvider

            // Mirror provider state
            _subscriptionState.value = gpProvider.subscriptionState.value
            _availablePlans.value = gpProvider.availablePlans.value.ifEmpty { defaultPlans() }

            // Observe ongoing state changes from the provider
            gpProvider.subscriptionState.onEach { state ->
                _subscriptionState.value = state
            }.launchIn(scope)

            gpProvider.availablePlans.onEach { plans ->
                _availablePlans.value = plans.ifEmpty { defaultPlans() }
            }.launchIn(scope)

            Timber.d("Google Play Billing initialized successfully")
        } catch (e: Exception) {
            Timber.w(e, "Google Play Billing unavailable, falling back to free tier")
            provider = FreeTierBillingProvider()
            googlePlayProvider = null
            _subscriptionState.value = SubscriptionState()
            _availablePlans.value = defaultPlans()
        }
    }

    /**
     * Set the coroutine scope for billing operations.
     * Must be called before [initialize].
     */
    fun observeStateChanges(scope: CoroutineScope) {
        billingScope = scope
    }

    override suspend fun launchPurchaseFlow(): PurchaseResult {
        return try {
            provider.launchPurchaseFlow()
        } catch (e: Exception) {
            Timber.e(e, "Purchase flow failed")
            PurchaseResult.Error(e.message ?: "Purchase failed")
        }
    }

    /**
     * Launch a purchase flow for a specific plan from an Activity.
     */
    suspend fun launchPurchaseFlow(activity: Activity, planDetails: PlanDetails): PurchaseResult {
        // Debug provider handles purchases directly
        val debugProvider = provider as? DebugBillingProvider
        if (debugProvider != null) {
            return try {
                debugProvider.launchPurchaseFlow(planDetails)
            } catch (e: Exception) {
                Timber.e(e, "Debug purchase flow failed for ${planDetails.productId}")
                PurchaseResult.Error(e.message ?: "Purchase failed")
            }
        }

        val gpProvider = googlePlayProvider
            ?: return PurchaseResult.BillingUnavailable
        return try {
            gpProvider.launchPurchaseFlow(activity, planDetails)
        } catch (e: Exception) {
            Timber.e(e, "Purchase flow failed for ${planDetails.productId}")
            PurchaseResult.Error(e.message ?: "Purchase failed")
        }
    }

    override suspend fun restorePurchases(): Boolean {
        return try {
            val restored = provider.restorePurchases()
            _subscriptionState.value = provider.subscriptionState.value
            restored
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
        googlePlayProvider = null
    }

    /**
     * Re-query purchases from Google Play to detect external changes.
     * Call from Activity.onResume() to keep subscription state fresh.
     */
    suspend fun refreshPurchases() {
        try {
            googlePlayProvider?.refreshPurchases()
            googlePlayProvider?.let {
                _subscriptionState.value = it.subscriptionState.value
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to refresh purchases")
        }
    }

    /**
     * Open Google Play subscription management for this app.
     * Returns an Intent the caller can use to launch the Play Store.
     */
    fun getManageSubscriptionIntent(): Intent {
        val uri = Uri.parse(
            "https://play.google.com/store/account/subscriptions?package=${context.packageName}"
        )
        return Intent(Intent.ACTION_VIEW, uri)
    }

    /** Whether the Google Play Billing provider is active (vs. free-tier fallback). */
    val isPlayBillingAvailable: Boolean
        get() = googlePlayProvider != null

    /**
     * Default plan details shown when Google Play is unavailable.
     * Prices are placeholders — real prices come from the Play Console.
     */
    private fun defaultPlans(): List<PlanDetails> = listOf(
        PlanDetails(
            productId = SubscriptionPlans.PRO_MONTHLY_ID,
            name = "MeshWalk Pro Monthly",
            description = "All premium features, billed monthly",
            formattedPrice = "$3.99",
            billingPeriod = BillingPeriod.MONTHLY,
            hasFreeTrial = true,
            freeTrialDays = SubscriptionPlans.FREE_TRIAL_DAYS
        ),
        PlanDetails(
            productId = SubscriptionPlans.PRO_ANNUAL_ID,
            name = "MeshWalk Pro Annual",
            description = "All premium features, save 37%",
            formattedPrice = "$29.99",
            billingPeriod = BillingPeriod.ANNUAL,
            hasFreeTrial = true,
            freeTrialDays = SubscriptionPlans.FREE_TRIAL_DAYS
        ),
        PlanDetails(
            productId = SubscriptionPlans.PRO_LIFETIME_ID,
            name = "MeshWalk Pro Lifetime",
            description = "One-time purchase, yours forever",
            formattedPrice = "$49.99",
            billingPeriod = BillingPeriod.LIFETIME
        )
    )
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

/**
 * Debug billing provider that simulates Google Play purchases.
 *
 * Used in debug builds so developers can test the full subscription flow
 * (plan selection, purchase, feature gating, restore) without needing
 * Google Play Console configuration or a signed release APK.
 *
 * Purchases are held in memory and reset when the process restarts.
 */
class DebugBillingProvider : BillingProvider {

    private val _subscriptionState = MutableStateFlow(SubscriptionState())
    override val subscriptionState: StateFlow<SubscriptionState> = _subscriptionState.asStateFlow()

    private val _availablePlans = MutableStateFlow<List<PlanDetails>>(emptyList())
    val availablePlans: StateFlow<List<PlanDetails>> = _availablePlans.asStateFlow()

    private var activePlanDetails: PlanDetails? = null

    override suspend fun initialize() {
        Timber.d("Debug billing provider initialized (simulated purchases)")
        _availablePlans.value = listOf(
            PlanDetails(
                productId = SubscriptionPlans.PRO_MONTHLY_ID,
                name = "MeshWalk Pro Monthly",
                description = "[DEBUG] 7-day free trial, then billed monthly",
                formattedPrice = "$3.99",
                billingPeriod = BillingPeriod.MONTHLY,
                offerToken = "debug-monthly-offer",
                hasFreeTrial = true,
                freeTrialDays = SubscriptionPlans.FREE_TRIAL_DAYS
            ),
            PlanDetails(
                productId = SubscriptionPlans.PRO_ANNUAL_ID,
                name = "MeshWalk Pro Annual",
                description = "[DEBUG] 7-day free trial, then save 37%",
                formattedPrice = "$29.99",
                billingPeriod = BillingPeriod.ANNUAL,
                offerToken = "debug-annual-offer",
                hasFreeTrial = true,
                freeTrialDays = SubscriptionPlans.FREE_TRIAL_DAYS
            ),
            PlanDetails(
                productId = SubscriptionPlans.PRO_LIFETIME_ID,
                name = "MeshWalk Pro Lifetime",
                description = "[DEBUG] One-time purchase, yours forever",
                formattedPrice = "$49.99",
                billingPeriod = BillingPeriod.LIFETIME
            )
        )
    }

    /**
     * Simulate a purchase. Immediately activates the selected plan.
     */
    suspend fun launchPurchaseFlow(planDetails: PlanDetails): PurchaseResult {
        Timber.d("Debug: simulating purchase of ${planDetails.productId}")
        activePlanDetails = planDetails

        val planName = when (planDetails.billingPeriod) {
            BillingPeriod.MONTHLY -> "Pro Monthly"
            BillingPeriod.ANNUAL -> "Pro Annual"
            BillingPeriod.LIFETIME -> "Pro Lifetime"
        }

        val expiresAt = when (planDetails.billingPeriod) {
            BillingPeriod.MONTHLY -> System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000
            BillingPeriod.ANNUAL -> System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
            BillingPeriod.LIFETIME -> null
        }

        _subscriptionState.value = SubscriptionState(
            isPremium = true,
            planName = planName,
            expiresAt = expiresAt,
            isGracePeriod = false,
            isFreeTrial = planDetails.hasFreeTrial
        )

        return PurchaseResult.Success
    }

    override suspend fun launchPurchaseFlow(): PurchaseResult {
        return PurchaseResult.BillingUnavailable
    }

    override suspend fun restorePurchases(): Boolean {
        // In debug, "restore" just returns current state
        return _subscriptionState.value.isPremium
    }

    override fun isFeatureEnabled(feature: PremiumFeature): Boolean {
        return _subscriptionState.value.isActive
    }

    override fun destroy() {
        Timber.d("Debug billing provider destroyed")
    }
}
