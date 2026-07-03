package com.elysium.softwork.payment.membership.application.usecase

import com.elysium.softwork.payment.membership.data.store.MembershipStore

/**
 * Session-authorization use case: validates the worker's subscription against the live backend
 * and syncs the local membership gate that drives top-level routing.
 *
 * The sequence, run immediately after a successful login and on application cold-start:
 *  1. Read the cached `membership_id` foreign key (resolved from the worker's `user_accounts`
 *     record during the post-login sync) via [membershipIdProvider].
 *  2. Query `GET /api/v1/memberships/{id}` through [MembershipStore.getMembership].
 *  3. Evaluate the returned subscription with [com.elysium.softwork.payment.membership.domain.model.Membership.isActiveNow]
 *     — `membership_status == "ACTIVE"` **and** the current date within
 *     `[membership_start, membership_over]`.
 *  4. Sync the reactive gate: [MembershipStore.activateMembership] when valid (so the main shell
 *     mounts), [MembershipStore.cancelSubscription] otherwise (so `MainActivity` suspends the
 *     main layout and routes the worker straight to the payment-onboarding screen).
 *
 * A missing id, a `401`/`404`, or any fetch failure is treated as **not active** — the gate
 * closes and the worker is routed to onboarding rather than into a shell they cannot use. This
 * never tears the session down (the `/memberships` route is exempt from the `401` logout trap).
 *
 * Stateless; safe to share a single instance process-wide.
 *
 * @param store membership data port (single-membership read + gate mutators).
 * @param membershipIdProvider supplies the cached `membership_id`, or `null` when none is known.
 */
class ValidateMembershipUseCase(
    private val store: MembershipStore,
    private val membershipIdProvider: () -> Long?,
) {

    /**
     * Runs the validation and syncs the gate.
     *
     * @return `true` when the subscription is active and within its validity window.
     */
    suspend operator fun invoke(): Boolean {
        val membershipId: Long = membershipIdProvider() ?: run {
            store.cancelSubscription()
            return false
        }
        val active: Boolean = store.getMembership(membershipId).getOrNull()?.isActiveNow() == true
        if (active) {
            store.activateMembership(membershipId.toString())
        } else {
            store.cancelSubscription()
        }
        return active
    }
}
