package com.meshwalk.app.billing

/**
 * Subscription plan definitions matching Google Play Console product IDs.
 *
 * Configure these product IDs in the Google Play Console under
 * Monetize → Products → Subscriptions before publishing.
 */
object SubscriptionPlans {

    /** Google Play product ID for the monthly Pro subscription. */
    const val PRO_MONTHLY_ID = "meshwalk_pro_monthly"

    /** Google Play product ID for the annual Pro subscription. */
    const val PRO_ANNUAL_ID = "meshwalk_pro_annual"

    /** Google Play product ID for lifetime one-time purchase. */
    const val PRO_LIFETIME_ID = "meshwalk_pro_lifetime"

    /** All subscription product IDs to query from Google Play. */
    val SUBSCRIPTION_IDS = listOf(PRO_MONTHLY_ID, PRO_ANNUAL_ID)

    /** All in-app (one-time) product IDs to query from Google Play. */
    val INAPP_IDS = listOf(PRO_LIFETIME_ID)
}

/**
 * Local representation of a subscription plan for UI display.
 */
data class PlanDetails(
    val productId: String,
    val name: String,
    val description: String,
    val formattedPrice: String,
    val billingPeriod: BillingPeriod,
    val offerToken: String? = null
)

enum class BillingPeriod(val label: String) {
    MONTHLY("month"),
    ANNUAL("year"),
    LIFETIME("one-time")
}
