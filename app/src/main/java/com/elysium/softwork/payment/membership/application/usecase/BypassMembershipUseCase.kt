package com.elysium.softwork.payment.membership.application.usecase

import com.elysium.softwork.payment.membership.data.store.MembershipStore
import com.elysium.softwork.payment.membership.domain.model.Membership

/**
 * **Development-only** single-phase membership bypass for continuous demos.
 *
 * Skips the full purchase chain ([StartStripeCheckoutUseCase]'s membership → order → Stripe
 * checkout): it issues **only** Phase 1 (`POST /api/v1/memberships`) with a hardcoded, long-lived
 * `ACTIVE` window so the paywall gate can be opened without touching the financial gateway.
 *
 * Not wired into any production path — invoked exclusively by the "Bypass Payment [Demo Mode]"
 * trigger in `MembershipSelectionScreen`. Delete this class (and its ViewModel/UI hooks) before a
 * production build.
 *
 * @param store payment data port (only [MembershipStore.createMembership] is used).
 */
class BypassMembershipUseCase(
    private val store: MembershipStore,
) {

    /**
     * Creates the demo membership enrolment.
     *
     * @param planId tier to enrol; defaults to [DEMO_PLAN_ID].
     * @return [Result.success] with the created [Membership] (carrying the generated
     *   `membership_id`), or [Result.failure] on a `400`/business-rule error.
     */
    suspend operator fun invoke(planId: Long = DEMO_PLAN_ID): Result<Membership> =
        store.createMembership(
            Membership(
                membership_plan_id = planId,
                membership_start = DEMO_MEMBERSHIP_START,
                membership_over = DEMO_MEMBERSHIP_OVER,
                membership_status = Membership.STATUS_ACTIVE,
            ),
        )

    companion object {
        /** Default demo tier id. */
        const val DEMO_PLAN_ID: Long = 1L

        /** Local plan key stored in the gate after the bypass activates. */
        const val DEMO_PLAN_KEY: String = "1"

        /**
         * Demo validity window, rendered as ISO 8601 **UTC instants** (trailing `Z`) per the
         * app-wide `DateTimeFormats` policy — a wide 3-year span so the gate stays open through
         * repeated demo runs.
         */
        const val DEMO_MEMBERSHIP_START: String = "2026-07-10T00:00:00Z"
        const val DEMO_MEMBERSHIP_OVER: String = "2029-07-10T00:00:00Z"
    }
}
