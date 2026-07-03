package com.elysium.softwork.payment.membership.data.store

import com.elysium.softwork.payment.membership.domain.model.Membership
import com.elysium.softwork.payment.membership.domain.model.MembershipPlan
import com.elysium.softwork.payment.membership.domain.model.Order
import com.elysium.softwork.payment.membership.domain.model.Payment
import com.elysium.softwork.payment.membership.domain.model.PaymentMethod
import kotlinx.coroutines.flow.StateFlow

/**
 * Payment & membership data port.
 *
 * Two responsibilities:
 *  - the **local membership gate** ([hasMembership] / [currentPlanKey]) backed by
 *    `SharedPrefsManager` — `MainActivity` collects [hasMembership] to swap between the main
 *    shell and the payment graph, so this stays client-side;
 *  - the **live backend resources** — the plan catalogue, orders, and payments, fetched from
 *    the Spring Boot API.
 *
 * Network reads/writes return [Result] so callers get a single error channel — a `400 Bad
 * Request` surfaces as a [com.elysium.softwork.shared.data.network.BadRequestException].
 * Saved cards remain a client-side affordance (the backend models payment as a transaction,
 * not a stored instrument).
 */
interface MembershipStore {

    /** Reactive membership gate flag (`KEY_HAS_MEMBERSHIP`). Collected by `MainActivity`. */
    val hasMembership: StateFlow<Boolean>

    /** Active plan identifier (`KEY_CURRENT_PLAN`), or `null` when no membership is active. */
    val currentPlanKey: StateFlow<String?>

    /** Reactive list of saved (client-side) payment methods. */
    val paymentMethods: StateFlow<List<PaymentMethod>>

    /**
     * Fetches the **public plan catalogue** (`GET /api/v1/membership-plans`) — the product tiers
     * with `price` + `benefit_response_list`. This is a browse-only read: it must never gate
     * navigation or tear down the session (an expired worker still needs to see upgrade tiers).
     */
    suspend fun getPlans(): Result<List<MembershipPlan>>

    /**
     * Fetches a single membership by its `membership_id` (`GET /api/v1/memberships/{id}`) — the
     * operational subscription instance with validity range + status, resolved from the
     * `membership_id` foreign key cached off the worker's `user_accounts` record. This is the
     * access-validation read that drives the session-authorization gate.
     */
    suspend fun getMembership(id: Long): Result<Membership>

    /** Creates a purchase order (`POST /api/v1/orders`). */
    suspend fun createOrder(order: Order): Result<Order>

    /** Registers a payment settling an order (`POST /api/v1/payments`). */
    suspend fun createPayment(payment: Payment): Result<Payment>

    /** Persists [method] into the client-side saved-cards list. */
    suspend fun addPaymentMethod(method: PaymentMethod)

    /** Flips [hasMembership] to `true` and stores [planKey] as the active tier. */
    suspend fun activateMembership(planKey: String)

    /** Clears the membership flags (cards preserved). */
    suspend fun cancelSubscription()
}
