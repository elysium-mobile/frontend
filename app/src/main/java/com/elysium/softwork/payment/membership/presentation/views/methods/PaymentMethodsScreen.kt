package com.elysium.softwork.payment.membership.presentation.views.methods

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.elysium.softwork.R
import com.elysium.softwork.payment.membership.presentation.viewmodel.MembershipViewModel
import com.elysium.softwork.payment.membership.domain.model.MembershipPlan
import com.elysium.softwork.payment.membership.domain.model.PaymentMethod
import com.elysium.softwork.payment.membership.presentation.navigation.PaymentRoutes
import com.elysium.softwork.shared.presentation.components.SoftWorkButton
import com.elysium.softwork.shared.presentation.components.SoftWorkCard
import com.elysium.softwork.shared.presentation.theme.AccentDark
import com.elysium.softwork.shared.presentation.theme.AccentMint
import com.elysium.softwork.shared.presentation.theme.AccentWhite
import com.elysium.softwork.shared.presentation.theme.Danger
import com.elysium.softwork.shared.presentation.theme.PrimaryNavy
import com.elysium.softwork.shared.presentation.theme.PrimarySky
import com.elysium.softwork.shared.presentation.theme.PrimaryTeal

/**
 * Payment methods screen — second step of the subscription flow and also the destination
 * reached when the worker taps "Payment methods" inside the profile settings.
 *
 * Layout:
 *  - Header with a back arrow and the localized title.
 *  - Scrolling region (LazyColumn) hosting the gradient "next charge" recap, the list of
 *    saved cards (or an empty-state placeholder), an outlined "Add payment method" row,
 *    and — only when [fromSettings] is `true` — a danger-tinted "Cancel subscription"
 *    text link.
 *  - Bottom band pinned outside the scrolling region with the primary "Pay membership"
 *    CTA. The band consumes the navigation-bar inset, so the CTA always clears the system
 *    gesture pill on devices that render one.
 *
 * Reactive payment hand-off: when the ViewModel's [MembershipViewModel.PaymentState]
 * flips to `Succeeded`, a [LaunchedEffect] fires [onPaymentSucceeded] exactly once and
 * resets the stream so re-entering the screen never auto-navigates on a stale flag.
 *
 * @param planKey stable [MembershipPlan.key] selected on the previous step. When equal to
 *   [PaymentRoutes.CURRENT_PLAN_SENTINEL] the screen resolves the plan from
 *   [MembershipViewModel.currentPlanKey] instead — the path used by the settings entry,
 *   where the worker already has an active subscription.
 * @param fromSettings `true` when the screen was reached from the profile settings entry;
 *   controls the visibility of the "Cancel subscription" action.
 * @param onBack invoked when the worker taps the header back arrow.
 * @param onAddPaymentMethod invoked when the worker taps the "Add payment method" row.
 * @param onPaymentSucceeded invoked once the mocked payment completes; carries the
 *   resolved [MembershipPlan.key] forward to the success screen.
 * @param onSubscriptionCancelled invoked after [MembershipViewModel.cancelSubscription]
 *   has cleared the persisted flags; the caller pops the back stack so the worker is not
 *   left on a now-defunct settings screen while the host shell swaps.
 * @param viewModel provider for the saved-card list and the payment state machine.
 *   Resolved through the manual service locator via the factory exposed on the ViewModel
 *   companion.
 */
@Composable
fun PaymentMethodsScreen(
    planKey: String,
    fromSettings: Boolean,
    onBack: () -> Unit,
    onAddPaymentMethod: () -> Unit,
    onPaymentSucceeded: (String) -> Unit,
    onSubscriptionCancelled: () -> Unit,
    viewModel: MembershipViewModel = viewModel(factory = MembershipViewModel.Factory),
) {
    val cards: List<PaymentMethod> by viewModel.paymentMethods.collectAsStateWithLifecycle()
    val paymentState: MembershipViewModel.PaymentState by viewModel.paymentState
        .collectAsStateWithLifecycle()
    val activePlanKey: String? by viewModel.currentPlanKey.collectAsStateWithLifecycle()
    val plans: List<MembershipPlan> by viewModel.availablePlans.collectAsStateWithLifecycle()
    val errorMessage: String? by viewModel.errorMessage.collectAsStateWithLifecycle()

    val resolvedPlanKey: String =
        if (planKey == PaymentRoutes.CURRENT_PLAN_SENTINEL) activePlanKey.orEmpty() else planKey
    val plan: MembershipPlan? = remember(resolvedPlanKey, plans) {
        plans.firstOrNull { (it.plan_id ?: 0L).toString() == resolvedPlanKey }
    }

    LaunchedEffect(paymentState, plan) {
        if (paymentState is MembershipViewModel.PaymentState.Succeeded && plan != null) {
            onPaymentSucceeded((plan.plan_id ?: 0L).toString())
            viewModel.consumePaymentState()
        }
    }

    val onCancelSubscription: () -> Unit = remember(viewModel, onSubscriptionCancelled) {
        {
            viewModel.cancelSubscription()
            onSubscriptionCancelled()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AccentWhite),
    ) {
        MethodsHeader(onBack = onBack)

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "next-charge") { NextChargeCard(plan = plan) }

            if (cards.isEmpty()) {
                item(key = "empty-cards") { EmptyCardsPlaceholder() }
            } else {
                items(items = cards, key = { card -> card.id }) { card -> SavedCardRow(card = card) }
            }

            item(key = "add-method") { AddMethodRow(onClick = onAddPaymentMethod) }

            if (fromSettings) {
                item(key = "cancel-subscription") {
                    CancelSubscriptionRow(onCancel = onCancelSubscription)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            errorMessage?.let { message ->
                Text(
                    text = message,
                    color = Danger,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            SoftWorkButton(
                text = stringResource(R.string.payment_pay_membership),
                onClick = { plan?.let { viewModel.payMembership(it) } },
                enabled = cards.isNotEmpty() &&
                    plan != null &&
                    paymentState !is MembershipViewModel.PaymentState.Processing,
            )
        }
    }
}

/**
 * Sticky screen header. Left-aligned back arrow and a centered title both rendered in
 * the brand navy weight.
 *
 * @param onBack invoked when the worker taps the back arrow.
 */
@Composable
private fun MethodsHeader(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_arrow_back),
            contentDescription = stringResource(R.string.cd_back),
            tint = PrimaryNavy,
            modifier = Modifier
                .size(28.dp)
                .clickable(onClick = onBack),
        )
        Text(
            text = stringResource(R.string.payment_methods_title),
            color = PrimaryNavy,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 44.dp),
        )
    }
}

/**
 * Gradient recap card showing the next charge associated with the selected plan.
 *
 * Renders the localized "Next charge" caption, the plan's pre-formatted price, and the
 * plan name. The gradient runs horizontally from [PrimaryTeal] to [PrimaryNavy] so the
 * card reads as a premium accent against the white screen surface.
 *
 * @param plan plan whose price and name are surfaced. Empty strings render when `null`
 *   so the layout height stays constant while data resolves.
 */
@Composable
private fun NextChargeCard(plan: MembershipPlan?) {
    val price: String = plan?.price?.let { stringResource(R.string.payment_price_format, it) }.orEmpty()
    val name: String = plan?.plan_name.orEmpty()
    val captionColor: Color = Color.White.copy(alpha = 0.85f)
    val subtitleColor: Color = Color.White.copy(alpha = 0.9f)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.horizontalGradient(listOf(PrimaryTeal, PrimaryNavy)),
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(20.dp),
        ) {
            Column {
                Text(
                    text = stringResource(R.string.payment_next_charge),
                    color = captionColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = price,
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = name,
                    color = subtitleColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * Single saved-card row inside the methods list.
 *
 * Renders a small brand pill on the left and the card's masked label and expiry on the
 * right. Only the last four digits are ever displayed — the type carries nothing else.
 *
 * @param card payment method to render. Only [PaymentMethod.brand], [PaymentMethod.last4]
 *   and [PaymentMethod.expiryMonthYear] surface to the UI.
 */
@Composable
private fun SavedCardRow(card: PaymentMethod) {
    SoftWorkCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandBadge(brand = card.brand)
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.payment_card_label, card.brand, card.last4),
                    color = PrimaryNavy,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.payment_expiry_label, card.expiryMonthYear),
                    color = AccentDark,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * Compact teal pill rendering the card brand label in uppercase.
 *
 * The label is clipped to four characters because the badge is sized for short brand
 * strings ("VISA", "MAST", "AMEX"). Longer values are truncated visually rather than
 * stretching the layout.
 *
 * @param brand brand label produced by the BIN heuristic in the ViewModel.
 */
@Composable
private fun BrandBadge(brand: String) {
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 26.dp)
            .background(color = PrimaryTeal, shape = RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = brand.uppercase().take(4),
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Structural placeholder rendered in place of the card list when the worker has no
 * saved cards yet. Kept as its own component so the empty state can be redesigned
 * without touching the surrounding LazyColumn.
 */
@Composable
private fun EmptyCardsPlaceholder() {
    SoftWorkCard(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.payment_no_cards),
                color = AccentDark,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
            )
        }
    }
}

/**
 * Outlined "Add payment method" row anchored beneath the saved-card list. Surface colour
 * is sourced from the Material theme so a future palette swap propagates here.
 *
 * @param onClick invoked when the worker taps the row.
 */
@Composable
private fun AddMethodRow(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        onClick = onClick,
        border = BorderStroke(1.dp, AccentMint),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = null,
                tint = PrimarySky,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.size(10.dp))
            Text(
                text = stringResource(R.string.payment_add_method),
                color = PrimarySky,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Danger-tinted text link surfaced only on the settings entry. The caller's [onCancel]
 * lambda is expected to clear the membership flags in the store and then pop the back
 * stack so the host shell can swap the worker into the payment graph without leaving
 * them on a stale settings screen.
 *
 * @param onCancel invoked when the worker taps the link.
 */
@Composable
private fun CancelSubscriptionRow(onCancel: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCancel)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.payment_cancel_subscription),
            color = Danger,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
