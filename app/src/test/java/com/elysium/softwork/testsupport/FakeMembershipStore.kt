package com.elysium.softwork.testsupport

import com.elysium.softwork.payment.membership.data.store.MembershipStore
import com.elysium.softwork.payment.membership.domain.model.Benefit
import com.elysium.softwork.payment.membership.domain.model.Membership
import com.elysium.softwork.payment.membership.domain.model.MembershipPlan
import com.elysium.softwork.payment.membership.domain.model.Order
import com.elysium.softwork.payment.membership.domain.model.Payment
import com.elysium.softwork.payment.membership.domain.model.PaymentMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory test double for [MembershipStore].
 *
 * The membership gate flags and saved-cards list are held in [MutableStateFlow]s so a test
 * can drive the public mutators ([activateMembership], [cancelSubscription], [addPaymentMethod])
 * like production callers, or pre-seed state through [seedMembership] / [seedPaymentMethods].
 * The backend reads/writes ([getPlans], [createOrder], [createPayment]) return the programmable
 * `next*` results so both happy- and failure-path tests are deterministic.
 */
open class FakeMembershipStore : MembershipStore {

    private val _hasMembership: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val hasMembership: StateFlow<Boolean> = _hasMembership.asStateFlow()

    private val _currentPlanKey: MutableStateFlow<String?> = MutableStateFlow(null)
    override val currentPlanKey: StateFlow<String?> = _currentPlanKey.asStateFlow()

    private val _paymentMethods: MutableStateFlow<List<PaymentMethod>> = MutableStateFlow(emptyList())
    override val paymentMethods: StateFlow<List<PaymentMethod>> = _paymentMethods.asStateFlow()

    /** Value returned by the next [getPlans] call. */
    var nextPlansResult: Result<List<MembershipPlan>> = Result.success(DEFAULT_PLANS)

    /** Value returned by the next [getMembership] (subscription-status validation) call. */
    var nextMembershipResult: Result<Membership> =
        Result.success(Membership(membership_id = 1L, membership_status = "ACTIVE"))

    /** Value returned by the next [createMembership] (Phase 1 of the purchase chain) call. */
    var nextCreatedMembershipResult: Result<Membership> =
        Result.success(Membership(membership_id = 1L, membership_status = "ACTIVE"))

    /** Most recent [Membership] passed to [createMembership], or `null` if never invoked. */
    var lastCreatedMembership: Membership? = null
        private set

    /** Value returned by the next [createOrder] call. */
    var nextOrderResult: Result<Order> = Result.success(Order(order_id = 1L))

    /** Value returned by the next [createPayment] call. */
    var nextPaymentResult: Result<Payment> = Result.success(Payment(payment_id = 1L))

    /** Most recent argument passed to [activateMembership], or `null` if never invoked. */
    var lastActivatedPlanKey: String? = null
        private set

    /** Tally of [cancelSubscription] invocations. */
    var cancelInvocations: Int = 0
        private set

    /** Most recent [PaymentMethod] passed to [addPaymentMethod], or `null` if never invoked. */
    var lastAddedPaymentMethod: PaymentMethod? = null
        private set

    /** Most recent [Order] passed to [createOrder], or `null` if never invoked. */
    var lastCreatedOrder: Order? = null
        private set

    override suspend fun getPlans(): Result<List<MembershipPlan>> = nextPlansResult

    override suspend fun getMembership(id: Long): Result<Membership> = nextMembershipResult

    override suspend fun createMembership(membership: Membership): Result<Membership> {
        lastCreatedMembership = membership
        return nextCreatedMembershipResult
    }

    override suspend fun createOrder(order: Order): Result<Order> {
        lastCreatedOrder = order
        return nextOrderResult
    }

    /** Result returned by the next [createStripeCheckout] call. Defaults to a stub URL. */
    var nextStripeCheckoutResult: Result<String?> = Result.success("https://checkout.stripe.test/session")

    /** Most recent (orderId, currency) passed to [createStripeCheckout], or `null` if never invoked. */
    var lastStripeCheckoutArgs: Pair<Long, String>? = null
        private set

    override suspend fun createStripeCheckout(orderId: Long, currency: String): Result<String?> {
        lastStripeCheckoutArgs = orderId to currency
        return nextStripeCheckoutResult
    }

    override suspend fun createPayment(payment: Payment): Result<Payment> = nextPaymentResult

    override suspend fun addPaymentMethod(method: PaymentMethod) {
        lastAddedPaymentMethod = method
        _paymentMethods.value += method
    }

    override suspend fun activateMembership(planKey: String) {
        lastActivatedPlanKey = planKey
        _currentPlanKey.value = planKey
        _hasMembership.value = true
    }

    override suspend fun cancelSubscription() {
        cancelInvocations += 1
        _currentPlanKey.value = null
        _hasMembership.value = false
    }

    /** Pre-seeds the membership flags without going through the public activation path. */
    fun seedMembership(active: Boolean, planKey: String?) {
        _hasMembership.value = active
        _currentPlanKey.value = planKey
    }

    /** Pre-seeds the saved-cards list without going through the public mutator. */
    fun seedPaymentMethods(methods: List<PaymentMethod>) {
        _paymentMethods.value = methods
    }

    companion object {
        /** Default catalogue using the live `MembershipPlan` wire shape. */
        val DEFAULT_PLANS: List<MembershipPlan> = listOf(
            MembershipPlan(
                plan_id = 1L,
                plan_name = "Basic",
                price = 59,
                membership_id = 1L,
                benefit_response_list = listOf(Benefit(benefit_id = 1L, title = "Surveys")),
            ),
            MembershipPlan(
                plan_id = 2L,
                plan_name = "Plan Pro",
                price = 99,
                membership_id = 2L,
                benefit_response_list = listOf(
                    Benefit(benefit_id = 2L, title = "Workplace forum"),
                    Benefit(benefit_id = 3L, title = "Encrypted reports"),
                ),
            ),
        )
    }
}
