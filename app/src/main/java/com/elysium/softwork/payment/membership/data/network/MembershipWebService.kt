package com.elysium.softwork.payment.membership.data.network

import com.elysium.softwork.payment.membership.domain.model.Benefit
import com.elysium.softwork.payment.membership.domain.model.Membership
import com.elysium.softwork.payment.membership.domain.model.MembershipPlan
import com.elysium.softwork.payment.membership.domain.model.Order
import com.elysium.softwork.payment.membership.domain.model.Payment
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit contract for the live Payment-Service endpoints of the FlowWork Spring Boot API.
 *
 * The annotation-free domain beans ([Membership], [Order], [Payment], [MembershipPlan],
 * [Benefit]) carry both request bodies and response payloads (the bean shortcut) — no DTOs.
 * All paths are **relative**; the host + `/` base lives in `BuildConfig.BACKEND_BASE_URL`
 * (resolved by `ApiClient`), and `AuthInterceptor` attaches the bearer token automatically.
 */
interface MembershipWebService {

    // region Memberships
    /** Creates a membership record. */
    @POST("api/v1/memberships")
    suspend fun createMembership(@Body membership: Membership): Response<Membership>

    /** Lists every membership. */
    @GET("api/v1/memberships")
    suspend fun getMemberships(): Response<List<Membership>>

    /** Fetches a single membership by its `membership_id`. */
    @GET("api/v1/memberships/{id}")
    suspend fun getMembership(@Path("id") id: Long): Response<Membership>
    // endregion

    // region Orders
    /** Creates a purchase order (cross-validates account + membership + status server-side). */
    @POST("api/v1/orders")
    suspend fun createOrder(@Body order: Order): Response<Order>

    /** Lists every order. */
    @GET("api/v1/orders")
    suspend fun getOrders(): Response<List<Order>>

    /** Lists the orders belonging to a user account. */
    @GET("api/v1/orders/userAccount/{userAccountId}")
    suspend fun getOrdersByUserAccount(@Path("userAccountId") userAccountId: Long): Response<List<Order>>
    // endregion

    // region Payments
    /** Registers a payment settling an order. */
    @POST("api/v1/payments")
    suspend fun createPayment(@Body payment: Payment): Response<Payment>

    /** Fetches a single payment by its `payment_id`. */
    @GET("api/v1/payments/{id}")
    suspend fun getPayment(@Path("id") id: Long): Response<Payment>
    // endregion

    // region Membership Plans & Benefits
    /** Lists the available plan catalogue (with nested benefits). */
    @GET("api/v1/memberships-plans")
    suspend fun getMembershipPlans(): Response<List<MembershipPlan>>

    /** Links a benefit to a plan. */
    @POST("api/v1/memberships-plans/{id}/benefits")
    suspend fun addBenefitToPlan(
        @Path("id") membershipPlanId: Long,
        @Body benefit: Benefit,
    ): Response<MembershipPlan>
    // endregion
}
