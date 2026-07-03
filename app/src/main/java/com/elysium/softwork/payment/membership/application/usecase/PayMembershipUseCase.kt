package com.elysium.softwork.payment.membership.application.usecase

import com.elysium.softwork.iam.data.store.AuthStore
import com.elysium.softwork.payment.membership.data.store.MembershipStore
import com.elysium.softwork.payment.membership.domain.model.MembershipPlan
import com.elysium.softwork.payment.membership.domain.model.Order
import com.elysium.softwork.payment.membership.domain.model.Payment
import java.time.LocalDate
import java.util.UUID

/**
 * Drives the checkout-completion pipeline against the live backend.
 *
 * Steps, in order:
 *  1. **Create the order** (`POST /api/v1/orders`), binding the worker's `user_account_id`
 *     resolved **dynamically** from [SharedPrefsManager][com.elysium.softwork.shared.data.local.SharedPrefsManager]
 *     via [accountIdProvider], the plan [price][MembershipPlan.price], and the plan's
 *     `membership_id`. The backend cross-validates the account, the membership, and that the
 *     membership is `ACTIVE` and in range — a violation comes back as a typed failure.
 *  2. **Register the payment** (`POST /api/v1/payments`) with a generated transaction id and
 *     today's date.
 *  3. **Programmatic re-authentication**: on a successful payment, re-invoke `sign-in` from
 *     the cached credentials ([AuthStore.reauthenticate]) to obtain a fresh token — the
 *     backend exposes no refresh endpoint. Best-effort: a re-auth hiccup does not fail the
 *     checkout (the payment already succeeded).
 *
 * The membership gate is flipped to `ACTIVE` separately on the success screen (so the
 * confirmation renders before the root routing stack hot-swaps into the main shell).
 *
 * Stateless; safe to share a single instance process-wide.
 *
 * @param store payment data port (orders + payments).
 * @param authStore IAM port used for the post-payment re-authentication.
 * @param accountIdProvider supplies the signed-in `user_account_id` (resourced from prefs).
 */
class PayMembershipUseCase(
    private val store: MembershipStore,
    private val authStore: AuthStore,
    private val accountIdProvider: () -> Long?,
) {

    /**
     * Executes the checkout for [plan].
     *
     * @return [Result.success] when the order + payment succeed (re-auth is best-effort), or
     *   [Result.failure] carrying a
     *   [com.elysium.softwork.shared.data.network.BadRequestException] / business-rule error.
     */
    suspend operator fun invoke(plan: MembershipPlan): Result<Unit> = runCatching {
        val order = store.createOrder(
            Order(
                userAccountId = accountIdProvider(),
                amount = plan.price,
                membershipId = plan.membership_id ?: plan.membershipId,
            ),
        ).getOrThrow()

        store.createPayment(
            Payment(
                orderId = order.order_id,
                transactionId = "TXN-${UUID.randomUUID()}",
                paymentDate = LocalDate.now().toString(),
            ),
        ).getOrThrow()

        // Synchronous token refresh from the cached credentials (no refresh endpoint exists).
        authStore.reauthenticate()
    }
}
