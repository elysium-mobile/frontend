package com.elysium.softwork.payment.membership.presentation.views.selection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.elysium.softwork.shared.presentation.components.SoftWorkCard
import com.elysium.softwork.shared.presentation.theme.AccentDark
import com.elysium.softwork.shared.presentation.theme.AccentMint
import com.elysium.softwork.shared.presentation.theme.AccentWhite
import com.elysium.softwork.shared.presentation.theme.Danger
import com.elysium.softwork.shared.presentation.theme.PrimaryNavy
import com.elysium.softwork.shared.presentation.theme.PrimarySky

/**
 * Membership selection screen — first step of the subscription flow.
 *
 * Renders the backend plan catalogue ([MembershipViewModel.availablePlans], fetched from
 * `GET /api/v1/membership-plans`) as a scrolling list of cards. Adapted to the live
 * `MembershipPlan`: the price is formatted from the integer `price`, the feature list is the
 * nested `benefit_response_list`, and the stable `plan_id` is forwarded as the selection key.
 * (The former client-side `isRecommended` accent has no backend equivalent, so every card
 * uses the neutral sky accent.)
 *
 * Tapping a plan CTA no longer opens the native card composer (deprecated): it starts a **hosted
 * Stripe Checkout Session** and, on success, hands off to the external device browser via
 * `Intent.ACTION_VIEW` so the secure hosted page completes the payment.
 */
@Composable
fun MembershipSelectionScreen(
    onNavigateToCompanySelection: (Long) -> Unit = {},
    viewModel: MembershipViewModel = viewModel(factory = MembershipViewModel.Factory),
) {
    val plans: List<MembershipPlan> by viewModel.availablePlans.collectAsStateWithLifecycle()
    val errorMessage: String? by viewModel.errorMessage.collectAsStateWithLifecycle()
    val isMembershipExpired: Boolean by viewModel.isMembershipExpired.collectAsStateWithLifecycle()
    val checkoutUrl: String? by viewModel.checkoutUrl.collectAsStateWithLifecycle()
    val bypassMembershipId: Long? by viewModel.bypassMembershipId.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Demo bypass: once the membership is created, route to the company-selection step and consume
    // the event so a recomposition can't re-navigate.
    LaunchedEffect(bypassMembershipId) {
        bypassMembershipId?.let { membershipId ->
            onNavigateToCompanySelection(membershipId)
            viewModel.consumeBypassMembershipId()
        }
    }

    // Hosted checkout redirect: once the backend returns the session URL, open it in the external
    // browser and consume the event so a recomposition can't relaunch it. FLAG_ACTIVITY_NEW_TASK
    // keeps it safe even if `context` is not an Activity.
    LaunchedEffect(checkoutUrl) {
        checkoutUrl?.let { url ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            viewModel.consumeCheckoutUrl()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AccentWhite),
    ) {
        SelectionHeader(onBypass = viewModel::bypassMembership)

        // Membership business-gate: driven by enrolment-status validation (`/memberships`) or an
        // order-push failure — NOT by the plan catalogue, which always renders below. When the
        // worker has no active membership, this prompt sits atop the (freely browsable) upgrade
        // tiers rather than degrading or logging out.
        if (isMembershipExpired) {
            MembershipRequiredBanner()
        }

        errorMessage?.let { message ->
            Text(
                text = message,
                color = Danger,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = WindowInsets.navigationBars
                .add(WindowInsets(left = 20.dp, top = 16.dp, right = 20.dp, bottom = 16.dp))
                .asPaddingValues(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items = plans, key = { plan -> plan.plan_id ?: 0L }) { plan ->
                PlanCard(plan = plan, onSelect = viewModel::startCheckout)
            }
        }
    }
}

/**
 * Selection header: screen title plus a **development-only** "Bypass Payment [Demo Mode]" trigger
 * pinned to the trailing edge. The bypass runs the single-phase membership demo shortcut
 * ([MembershipViewModel.bypassMembership]); remove it (and its ViewModel/use-case hooks) before a
 * production build.
 *
 * @param onBypass invoked when the demo bypass trigger is tapped.
 */
@Composable
private fun SelectionHeader(onBypass: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.payment_memberships_title),
            color = PrimaryNavy,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.payment_demo_bypass),
            color = Danger,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clickable(onClick = onBypass)
                .padding(start = 12.dp),
        )
    }
}

@Composable
private fun MembershipRequiredBanner() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        color = AccentMint,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.payment_membership_required),
                color = PrimaryNavy,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.payment_membership_required_detail),
                color = AccentDark,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun PlanCard(plan: MembershipPlan, onSelect: (MembershipPlan) -> Unit) {
    val accent: Color = PrimarySky
    val planName: String = plan.plan_name.orEmpty()
    val priceLabel: String = plan.price?.let { stringResource(R.string.payment_price_format, it) }.orEmpty()
    val features: List<String> = plan.benefit_response_list?.mapNotNull { it.title }.orEmpty()
    val onClick: () -> Unit = { onSelect(plan) }

    SoftWorkCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = planName,
                color = PrimaryNavy,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = priceLabel,
                    color = accent,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.payment_price_suffix),
                    color = AccentDark,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            features.forEach { feature ->
                FeatureRow(text = feature, tint = accent)
                Spacer(modifier = Modifier.height(6.dp))
            }

            Spacer(modifier = Modifier.height(14.dp))

            SelectPlanButton(accent = accent, onClick = onClick)
        }
    }
}

@Composable
private fun FeatureRow(text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(R.drawable.ic_check),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = text,
            color = AccentDark,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
        )
    }
}

@Composable
private fun SelectPlanButton(accent: Color, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(width = 1.dp, color = AccentMint),
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.payment_select_plan),
                color = accent,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
