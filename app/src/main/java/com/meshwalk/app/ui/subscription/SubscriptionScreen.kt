package com.meshwalk.app.ui.subscription

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshwalk.app.billing.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SubscriptionViewModel @Inject constructor(
    private val billingManager: BillingManager,
    private val featureGate: FeatureGate
) : ViewModel() {

    data class UiState(
        val subscriptionState: SubscriptionState = SubscriptionState(),
        val availablePlans: List<PlanDetails> = emptyList(),
        val selectedPlanId: String? = null,
        val isPurchasing: Boolean = false,
        val isRestoring: Boolean = false,
        val message: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            billingManager.initialize()
            billingManager.observeStateChanges(viewModelScope)
        }

        viewModelScope.launch {
            billingManager.subscriptionState.collect { subState ->
                _state.update { it.copy(subscriptionState = subState) }
            }
        }

        viewModelScope.launch {
            billingManager.availablePlans.collect { plans ->
                _state.update { it.copy(availablePlans = plans) }
            }
        }
    }

    fun selectPlan(productId: String) {
        _state.update { it.copy(selectedPlanId = productId) }
    }

    fun purchase(activity: Activity) {
        val planId = _state.value.selectedPlanId ?: return
        val plan = _state.value.availablePlans.find { it.productId == planId } ?: return

        viewModelScope.launch {
            _state.update { it.copy(isPurchasing = true, message = null) }
            val result = billingManager.launchPurchaseFlow(activity, plan)
            val message = when (result) {
                is PurchaseResult.Success -> "Purchase successful! Welcome to Pro."
                is PurchaseResult.Cancelled -> "Purchase cancelled."
                is PurchaseResult.Error -> "Purchase failed: ${result.message}"
                is PurchaseResult.BillingUnavailable ->
                    "Google Play Billing is not available on this device."
            }
            _state.update { it.copy(isPurchasing = false, message = message) }
        }
    }

    fun restorePurchases() {
        viewModelScope.launch {
            _state.update { it.copy(isRestoring = true, message = null) }
            val restored = billingManager.restorePurchases()
            val message = if (restored) {
                "Purchases restored successfully!"
            } else {
                "No previous purchases found."
            }
            _state.update { it.copy(isRestoring = false, message = message) }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionScreen(
    onBack: () -> Unit,
    viewModel: SubscriptionViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MeshWalk Pro") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Current status banner
            if (state.subscriptionState.isActive) {
                ActiveSubscriptionBanner(state.subscriptionState)
            } else {
                ProHeader()
                // Free trial callout
                val hasTrialPlans = state.availablePlans.any { it.hasFreeTrial }
                if (hasTrialPlans) {
                    Spacer(modifier = Modifier.height(12.dp))
                    FreeTrialBanner()
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Feature comparison
            if (!state.subscriptionState.isActive) {
                FeatureComparisonSection()
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Plan cards
            if (!state.subscriptionState.isActive) {
                Text(
                    "Choose your plan",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))

                state.availablePlans.forEach { plan ->
                    PlanCard(
                        plan = plan,
                        isSelected = state.selectedPlanId == plan.productId,
                        onClick = { viewModel.selectPlan(plan.productId) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Purchase button
                val selectedPlan = state.availablePlans.find {
                    it.productId == state.selectedPlanId
                }
                val buttonText = when {
                    state.isPurchasing -> "Processing..."
                    selectedPlan?.hasFreeTrial == true ->
                        "Start ${selectedPlan.freeTrialDays}-Day Free Trial"
                    else -> "Subscribe Now"
                }

                Button(
                    onClick = {
                        (context as? Activity)?.let { activity ->
                            viewModel.purchase(activity)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    enabled = state.selectedPlanId != null && !state.isPurchasing
                ) {
                    if (state.isPurchasing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(buttonText, style = MaterialTheme.typography.titleMedium)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Restore purchases
                TextButton(
                    onClick = { viewModel.restorePurchases() },
                    enabled = !state.isRestoring
                ) {
                    if (state.isRestoring) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Restore Purchases")
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Legal text
                Text(
                    "Free trial available for new subscribers. Your subscription begins " +
                            "after the 7-day trial ends. Cancel anytime during the trial at no " +
                            "charge. Subscriptions auto-renew unless cancelled at least 24 hours " +
                            "before the end of the current period. Manage subscriptions in " +
                            "Google Play Store settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ProHeader() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            Icons.Filled.WorkspacePremium,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "Upgrade to MeshWalk Pro",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Unlock the full power of offline mesh communication",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ActiveSubscriptionBanner(subscription: SubscriptionState) {
    val containerColor = if (subscription.isFreeTrial)
        MaterialTheme.colorScheme.tertiaryContainer
    else MaterialTheme.colorScheme.primaryContainer
    val contentColor = if (subscription.isFreeTrial)
        MaterialTheme.colorScheme.onTertiaryContainer
    else MaterialTheme.colorScheme.onPrimaryContainer

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (subscription.isFreeTrial) Icons.Filled.Timer else Icons.Filled.Verified,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = if (subscription.isFreeTrial)
                    MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    if (subscription.isFreeTrial) "Free Trial Active"
                    else "MeshWalk Pro Active",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )
                Text(
                    if (subscription.isFreeTrial) "All Pro features unlocked"
                    else "Plan: ${subscription.planName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor
                )
                if (subscription.expiresAt != null) {
                    val daysLeft = ((subscription.expiresAt - System.currentTimeMillis()) /
                            (24 * 60 * 60 * 1000)).toInt()
                    if (daysLeft > 0) {
                        Text(
                            if (subscription.isFreeTrial) "$daysLeft days left in trial"
                            else "$daysLeft days remaining",
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor.copy(alpha = 0.7f)
                        )
                    }
                } else {
                    Text(
                        "Lifetime access",
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun FreeTrialBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.CardGiftcard,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.tertiary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    "Try Pro free for 7 days",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Full access to all features. Cancel anytime, no charge.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FeatureComparisonSection() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "What you get with Pro",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(12.dp))

        FeatureRow(Icons.Filled.Group, "Groups up to 50 members", "Free: 5 members")
        FeatureRow(Icons.Filled.Inbox, "1,000 message queue", "Free: 100 messages")
        FeatureRow(Icons.Filled.History, "90-day message history", "Free: 7 days")
        FeatureRow(Icons.Filled.Speed, "Priority relay routing", "Faster message delivery")
        FeatureRow(Icons.Filled.BugReport, "Full diagnostics", "Routing tables & event logs")
        FeatureRow(Icons.Filled.Badge, "Multiple identities", "Named, anonymous & temporary")
        FeatureRow(Icons.Filled.FileDownload, "Chat export", "JSON & CSV formats")
    }
}

@Composable
private fun FeatureRow(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlanCard(
    plan: PlanDetails,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    val containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    else MaterialTheme.colorScheme.surface

    val isRecommended = plan.billingPeriod == BillingPeriod.ANNUAL

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            plan.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (plan.hasFreeTrial) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.tertiary
                            ) {
                                Text(
                                    "${plan.freeTrialDays}-DAY TRIAL",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (isRecommended) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    "BEST VALUE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        plan.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        plan.formattedPrice,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "/${plan.billingPeriod.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isSelected) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "Selected",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
