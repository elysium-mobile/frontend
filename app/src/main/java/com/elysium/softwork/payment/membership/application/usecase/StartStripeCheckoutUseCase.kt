package com.elysium.softwork.payment.membership.application.usecase

import com.elysium.softwork.payment.membership.data.store.MembershipStore
import com.elysium.softwork.payment.membership.domain.model.Membership
import com.elysium.softwork.payment.membership.domain.model.MembershipPlan
import com.elysium.softwork.payment.membership.domain.model.Order
import com.elysium.softwork.shared.utils.constants.DateTimeFormats
import java.time.Instant
import java.time.ZoneOffset

/**
 * Starts the hosted Stripe Checkout flow for a plan and yields the `checkout_url` to open in the
 * external browser.
 *
 * The backend enforces a **strict sequential creation chain**: an order cannot be registered
 * without referencing a pre-existing, active, within-date-range membership record. This use case
 * runs all three phases in order and short-circuits on the first failure:
 *
 *  1. **Create the membership** (`POST /api/v1/memberships`) — binds the plan's `membership_plan_id`,
 *     an `ACTIVE` status, and the `[membership_start, membership_over]` validity window (a one-month
 *     enrolment period starting now). Captures the generated `membership_id`.
 *  2. **Create the order** (`POST /api/v1/orders`) — binds the worker's `user_account_id` (resolved
 *     from prefs via [accountIdProvider]), the plan [price][MembershipPlan.price], and the freshly
 *     created `membership_id`. Captures the generated `order_id`.
 *  3. **Create the Stripe Checkout Session** (`POST /api/v1/payments/stripe/checkout`) for that order
 *     and returns its `checkout_url`.
 *
 * Unlike the deprecated native-card [PayMembershipUseCase], this does **not** register a payment or
 * re-authenticate — the hosted page completes the charge, and the backend settles it via its Stripe
 * webhook; the app re-validates the membership gate on its next resume/login. Stateless.
 *
 * @param store payment data port (memberships + orders + Stripe checkout).
 * @param accountIdProvider supplies the signed-in `user_account_id` (resourced from prefs).
 */
class StartStripeCheckoutUseCase(
    private val store: MembershipStore,
    private val accountIdProvider: () -> Long?,
) {

    /**
     * @param plan the tier the worker chose.
     * @param currency ISO 4217 code; defaults to `usd`.
     * @return [Result.success] with the hosted checkout URL, or [Result.failure] on any phase's
     *   `400`/business-rule error, or a missing `membership_id` / `order_id` / `checkout_url`.
     */
    suspend operator fun invoke(plan: MembershipPlan, currency: String = "usd"): Result<String> =
        runCatching {
            // Phase 1 — create the membership enrolment the order must reference. The validity
            // window is rendered through the canonical UTC-instant policy (DateTimeFormats).
            val start: Instant = Instant.now()
            val over: Instant = start.atZone(ZoneOffset.UTC).plusMonths(ENROLMENT_MONTHS).toInstant()
            val membership: Membership = store.createMembership(
                Membership(
                    membership_plan_id = plan.plan_id,
                    membership_start = DateTimeFormats.format(start),
                    membership_over = DateTimeFormats.format(over),
                    membership_status = Membership.STATUS_ACTIVE,
                ),
            ).getOrThrow()
            val membershipId: Long =
                membership.membership_id ?: error("membership_id missing from the created membership")

            // Phase 2 — create the order under the freshly created membership.
            val order: Order = store.createOrder(
                Order(
                    user_account_id = accountIdProvider(),
                    amount = plan.price,
                    membership_id = membershipId,
                ),
            ).getOrThrow()
            val orderId: Long = order.order_id ?: error("order_id missing from the created order")

            // Phase 3 — open a hosted Stripe Checkout Session for the order.
            store.createStripeCheckout(orderId, currency).getOrThrow()
                ?: error("checkout_url missing from the Stripe checkout response")
        }

    private companion object {
        /** Membership enrolment period, in months, applied to the validity window. */
        const val ENROLMENT_MONTHS: Long = 1L
    }
}
