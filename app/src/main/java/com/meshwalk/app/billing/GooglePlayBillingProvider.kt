package com.meshwalk.app.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * Google Play Billing implementation of [BillingProvider].
 *
 * Manages the BillingClient lifecycle, queries subscription/in-app products,
 * processes purchases, and exposes subscription state to the rest of the app.
 *
 * Purchase acknowledgement is handled automatically — unacknowledged purchases
 * are refunded by Google after 3 days.
 */
class GooglePlayBillingProvider(
    private val context: Context
) : BillingProvider, PurchasesUpdatedListener {

    private val _subscriptionState = MutableStateFlow(SubscriptionState())
    override val subscriptionState: StateFlow<SubscriptionState> = _subscriptionState.asStateFlow()

    private val _availablePlans = MutableStateFlow<List<PlanDetails>>(emptyList())
    val availablePlans: StateFlow<List<PlanDetails>> = _availablePlans.asStateFlow()

    private var billingClient: BillingClient? = null
    private var pendingPurchaseActivity: Activity? = null

    // Cached product details for launching purchase flows
    private val subscriptionProducts = mutableMapOf<String, ProductDetails>()
    private val inAppProducts = mutableMapOf<String, ProductDetails>()

    override suspend fun initialize() {
        val client = BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases()
            .build()
        billingClient = client

        val connected = connectClient(client)
        if (!connected) {
            Timber.w("Google Play Billing connection failed")
            return
        }

        queryProducts(client)
        queryExistingPurchases(client)
    }

    private suspend fun connectClient(client: BillingClient): Boolean =
        suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingResponseCode.OK) {
                        Timber.d("Google Play Billing connected")
                        cont.resume(true)
                    } else {
                        Timber.w("Billing setup failed: ${result.debugMessage}")
                        cont.resume(false)
                    }
                }

                override fun onBillingServiceDisconnected() {
                    Timber.w("Billing service disconnected")
                }
            })
        }

    private suspend fun queryProducts(client: BillingClient) {
        // Query subscriptions
        if (SubscriptionPlans.SUBSCRIPTION_IDS.isNotEmpty()) {
            val subParams = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    SubscriptionPlans.SUBSCRIPTION_IDS.map { productId ->
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(productId)
                            .setProductType(ProductType.SUBS)
                            .build()
                    }
                ).build()

            val subResult = client.queryProductDetails(subParams)
            if (subResult.billingResult.responseCode == BillingResponseCode.OK) {
                subResult.productDetailsList?.forEach { product ->
                    subscriptionProducts[product.productId] = product
                }
            }
        }

        // Query one-time purchases
        if (SubscriptionPlans.INAPP_IDS.isNotEmpty()) {
            val inAppParams = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    SubscriptionPlans.INAPP_IDS.map { productId ->
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(productId)
                            .setProductType(ProductType.INAPP)
                            .build()
                    }
                ).build()

            val inAppResult = client.queryProductDetails(inAppParams)
            if (inAppResult.billingResult.responseCode == BillingResponseCode.OK) {
                inAppResult.productDetailsList?.forEach { product ->
                    inAppProducts[product.productId] = product
                }
            }
        }

        // Build plan details for UI
        _availablePlans.value = buildPlanDetailsList()
    }

    private fun buildPlanDetailsList(): List<PlanDetails> {
        val plans = mutableListOf<PlanDetails>()

        subscriptionProducts[SubscriptionPlans.PRO_MONTHLY_ID]?.let { product ->
            val offer = product.subscriptionOfferDetails?.firstOrNull()
            val price = offer?.pricingPhases?.pricingPhaseList?.firstOrNull()
            plans.add(
                PlanDetails(
                    productId = product.productId,
                    name = "MeshWalk Pro Monthly",
                    description = "All premium features, billed monthly",
                    formattedPrice = price?.formattedPrice ?: "$3.99",
                    billingPeriod = BillingPeriod.MONTHLY,
                    offerToken = offer?.offerToken
                )
            )
        }

        subscriptionProducts[SubscriptionPlans.PRO_ANNUAL_ID]?.let { product ->
            val offer = product.subscriptionOfferDetails?.firstOrNull()
            val price = offer?.pricingPhases?.pricingPhaseList?.firstOrNull()
            plans.add(
                PlanDetails(
                    productId = product.productId,
                    name = "MeshWalk Pro Annual",
                    description = "All premium features, save 37%",
                    formattedPrice = price?.formattedPrice ?: "$29.99",
                    billingPeriod = BillingPeriod.ANNUAL,
                    offerToken = offer?.offerToken
                )
            )
        }

        inAppProducts[SubscriptionPlans.PRO_LIFETIME_ID]?.let { product ->
            val price = product.oneTimePurchaseOfferDetails
            plans.add(
                PlanDetails(
                    productId = product.productId,
                    name = "MeshWalk Pro Lifetime",
                    description = "One-time purchase, yours forever",
                    formattedPrice = price?.formattedPrice ?: "$79.99",
                    billingPeriod = BillingPeriod.LIFETIME
                )
            )
        }

        return plans
    }

    /**
     * Query existing purchases to restore state on app launch.
     */
    private suspend fun queryExistingPurchases(client: BillingClient) {
        // Check subscriptions
        val subParams = QueryPurchasesParams.newBuilder()
            .setProductType(ProductType.SUBS)
            .build()
        val subResult = client.queryPurchasesAsync(subParams)
        if (subResult.billingResult.responseCode == BillingResponseCode.OK) {
            subResult.purchasesList.forEach { purchase ->
                processPurchase(purchase)
            }
        }

        // Check one-time purchases
        val inAppParams = QueryPurchasesParams.newBuilder()
            .setProductType(ProductType.INAPP)
            .build()
        val inAppResult = client.queryPurchasesAsync(inAppParams)
        if (inAppResult.billingResult.responseCode == BillingResponseCode.OK) {
            inAppResult.purchasesList.forEach { purchase ->
                processPurchase(purchase)
            }
        }
    }

    override suspend fun launchPurchaseFlow(): PurchaseResult {
        return PurchaseResult.BillingUnavailable
    }

    /**
     * Launch a purchase flow for a specific plan.
     * Must be called from an Activity context.
     */
    suspend fun launchPurchaseFlow(activity: Activity, planDetails: PlanDetails): PurchaseResult {
        val client = billingClient ?: return PurchaseResult.BillingUnavailable

        pendingPurchaseActivity = activity

        val isSubscription = planDetails.billingPeriod != BillingPeriod.LIFETIME
        val productDetails = if (isSubscription) {
            subscriptionProducts[planDetails.productId]
        } else {
            inAppProducts[planDetails.productId]
        } ?: return PurchaseResult.Error("Product not found: ${planDetails.productId}")

        val productDetailsParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)

        // Subscriptions require an offer token
        if (isSubscription) {
            val offerToken = planDetails.offerToken
                ?: productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken
                ?: return PurchaseResult.Error("No offer available")
            productDetailsParamsBuilder.setOfferToken(offerToken)
        }

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParamsBuilder.build()))
            .build()

        val result = client.launchBillingFlow(activity, flowParams)
        return when (result.responseCode) {
            BillingResponseCode.OK -> PurchaseResult.Success
            BillingResponseCode.USER_CANCELED -> PurchaseResult.Cancelled
            else -> PurchaseResult.Error(result.debugMessage)
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    processPurchase(purchase)
                }
            }
            BillingResponseCode.USER_CANCELED -> {
                Timber.d("Purchase cancelled by user")
            }
            else -> {
                Timber.w("Purchase error: ${result.responseCode} ${result.debugMessage}")
            }
        }
        pendingPurchaseActivity = null
    }

    private fun processPurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return

        // Acknowledge the purchase if not already acknowledged
        if (!purchase.isAcknowledged) {
            val ackParams = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            billingClient?.acknowledgePurchase(ackParams) { ackResult ->
                if (ackResult.responseCode == BillingResponseCode.OK) {
                    Timber.d("Purchase acknowledged: ${purchase.products}")
                } else {
                    Timber.w("Acknowledge failed: ${ackResult.debugMessage}")
                }
            }
        }

        // Determine plan name from product IDs
        val planName = when {
            SubscriptionPlans.PRO_MONTHLY_ID in purchase.products -> "Pro Monthly"
            SubscriptionPlans.PRO_ANNUAL_ID in purchase.products -> "Pro Annual"
            SubscriptionPlans.PRO_LIFETIME_ID in purchase.products -> "Pro Lifetime"
            else -> "Pro"
        }

        // Determine expiration — lifetime purchases don't expire
        val isLifetime = SubscriptionPlans.PRO_LIFETIME_ID in purchase.products
        val expiresAt = if (isLifetime) null else {
            // Google doesn't expose exact expiry in Purchase object;
            // use purchaseTime + period as an estimate. The real expiry
            // is validated server-side via Google Play Developer API.
            val periodMs = if (SubscriptionPlans.PRO_ANNUAL_ID in purchase.products) {
                365L * 24 * 60 * 60 * 1000
            } else {
                30L * 24 * 60 * 60 * 1000
            }
            purchase.purchaseTime + periodMs
        }

        _subscriptionState.value = SubscriptionState(
            isPremium = true,
            planName = planName,
            expiresAt = expiresAt,
            isGracePeriod = false
        )

        Timber.d("Subscription activated: $planName")
    }

    override suspend fun restorePurchases(): Boolean {
        val client = billingClient ?: return false
        if (!client.isReady) {
            val reconnected = connectClient(client)
            if (!reconnected) return false
        }
        queryExistingPurchases(client)
        return _subscriptionState.value.isPremium
    }

    override fun isFeatureEnabled(feature: PremiumFeature): Boolean {
        return _subscriptionState.value.isActive
    }

    override fun destroy() {
        billingClient?.endConnection()
        billingClient = null
        pendingPurchaseActivity = null
        Timber.d("Google Play Billing destroyed")
    }
}
