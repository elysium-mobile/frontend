package com.elysium.softwork.payment.membership.data.store

import com.elysium.softwork.payment.membership.data.network.MembershipWebService
import com.elysium.softwork.payment.membership.domain.model.Membership
import com.elysium.softwork.payment.membership.domain.model.MembershipPlan
import com.elysium.softwork.payment.membership.domain.model.Order
import com.elysium.softwork.payment.membership.domain.model.Payment
import com.elysium.softwork.payment.membership.domain.model.PaymentMethod
import com.elysium.softwork.payment.membership.domain.model.StripeCheckout
import com.elysium.softwork.shared.data.local.SharedPrefsManager
import com.elysium.softwork.shared.data.network.BadRequestException
import com.elysium.softwork.shared.data.network.BadRequestResponse
import com.elysium.softwork.shared.data.network.UnauthorizedException
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.Response

/**
 * Concrete [MembershipStore] — backend-backed catalogue/orders/payments plus the local
 * membership gate.
 *
 * No mock harness: the hardcoded `PlanCatalogue`, the mock `delay`, and the fake in-memory
 * plan list are deleted. [getPlans] / [createOrder] / [createPayment] drive
 * [MembershipWebService] and parse a `400` into a [BadRequestException]. The membership gate
 * flags ([hasMembership] / [currentPlanKey]) and the client-side saved cards remain in
 * `SharedPrefsManager` / memory because the backend models neither a "gate" nor a stored card.
 *
 * @param prefs persistent key-value store backing the membership gate flags.
 * @param webService Retrofit contract for the payment-service endpoints.
 * @param gson deserializer for the structured `400` validation payload.
 */
class MembershipStoreImpl(
    private val prefs: SharedPrefsManager,
    private val webService: MembershipWebService,
    private val gson: Gson,
) : MembershipStore {

    private val _hasMembership: MutableStateFlow<Boolean> =
        MutableStateFlow(prefs.getBoolean(SharedPrefsManager.KEY_HAS_MEMBERSHIP, default = false))
    override val hasMembership: StateFlow<Boolean> = _hasMembership.asStateFlow()

    private val _currentPlanKey: MutableStateFlow<String?> =
        MutableStateFlow(prefs.getString(SharedPrefsManager.KEY_CURRENT_PLAN))
    override val currentPlanKey: StateFlow<String?> = _currentPlanKey.asStateFlow()

    private val _paymentMethods: MutableStateFlow<List<PaymentMethod>> =
        MutableStateFlow(emptyList())
    override val paymentMethods: StateFlow<List<PaymentMethod>> = _paymentMethods.asStateFlow()

    override suspend fun getPlans(): Result<List<MembershipPlan>> =
        runCatching { unwrapList(webService.getMembershipPlans()) }

    override suspend fun getMembership(id: Long): Result<Membership> =
        runCatching { unwrap(webService.getMembership(id)) }

    override suspend fun createMembership(membership: Membership): Result<Membership> =
        runCatching { unwrap(webService.createMembership(membership)) }

    override suspend fun createOrder(order: Order): Result<Order> =
        runCatching { unwrap(webService.createOrder(order)) }

    override suspend fun createStripeCheckout(orderId: Long, currency: String): Result<String?> =
        runCatching {
            unwrap(
                webService.createStripeCheckout(
                    StripeCheckout(order_id = orderId, currency = currency),
                ),
            ).checkout_url
        }

    override suspend fun createPayment(payment: Payment): Result<Payment> =
        runCatching { unwrap(webService.createPayment(payment)) }

    override suspend fun addPaymentMethod(method: PaymentMethod) {
        _paymentMethods.value += method
    }

    override suspend fun activateMembership(planKey: String) {
        prefs.putBoolean(SharedPrefsManager.KEY_HAS_MEMBERSHIP, value = true)
        prefs.putString(SharedPrefsManager.KEY_CURRENT_PLAN, value = planKey)
        _currentPlanKey.value = planKey
        _hasMembership.value = true
    }

    override suspend fun cancelSubscription() {
        prefs.putBoolean(SharedPrefsManager.KEY_HAS_MEMBERSHIP, value = false)
        prefs.remove(SharedPrefsManager.KEY_CURRENT_PLAN)
        _currentPlanKey.value = null
        _hasMembership.value = false
    }

    /** Unwraps a single-object [response]; a `400` becomes a [BadRequestException]. */
    private fun <T> unwrap(response: Response<T>): T {
        if (response.isSuccessful) {
            return response.body() ?: error("Empty response body")
        }
        throwTyped(response)
    }

    /** Unwraps a list [response], tolerating an empty body as an empty list. */
    private fun <T> unwrapList(response: Response<List<T>>): List<T> {
        if (response.isSuccessful) {
            return response.body().orEmpty()
        }
        throwTyped(response)
    }

    /**
     * Converts a non-2xx [response] into a typed failure: a `400` into a [BadRequestException]
     * carrying the parsed [BadRequestResponse], a `401` into an [UnauthorizedException] (the
     * membership business-gate — the ViewModel maps it to the "membership expired" state), and
     * anything else into an [IllegalStateException] (covers the backend's business-rule `500`s —
     * out-of-range date, etc. — whose message the UI surfaces verbatim).
     */
    private fun throwTyped(response: Response<*>): Nothing {
        val rawError: String? = runCatching { response.errorBody()?.string() }.getOrNull()
        if (response.code() == HTTP_BAD_REQUEST) {
            val parsed: BadRequestResponse = rawError
                ?.let { runCatching { gson.fromJson(it, BadRequestResponse::class.java) }.getOrNull() }
                ?: BadRequestResponse(message = rawError)
            throw BadRequestException(parsed)
        }
        if (response.code() == HTTP_UNAUTHORIZED) {
            throw UnauthorizedException(rawError)
        }
        error("HTTP ${response.code()} ${response.message().ifBlank { rawError ?: "request failed" }}")
    }

    private companion object {
        const val HTTP_BAD_REQUEST: Int = 400
        const val HTTP_UNAUTHORIZED: Int = 401
    }
}
