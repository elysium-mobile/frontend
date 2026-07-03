package com.elysium.softwork.payment.membership.domain.model

/**
 * A subscription plan tier — the annotation-free bean for the `membership-plans` endpoints
 * (the *Bean / Pragmatic Shortcut*).
 *
 * The backend serializes **uniform snake_case**, so every property is snake_case and Gson
 * resolves each by reflection without `@SerializedName`. Request and response share the same
 * keys, so a single field per concept covers both directions. [price] is the backend's integer
 * amount (the UI formats the currency for display); [benefit_response_list] nests the plan's
 * benefits, whose titles render as feature rows.
 *
 * @property plan_id primary key returned by every plan response; the stable identifier the
 *   membership gate persists as the active plan.
 * @property plan_name plan name (request + response).
 * @property price plan price (the UI formats the currency for display).
 * @property membership_id owning membership (request + response); forwarded when creating the
 *   purchase order.
 * @property benefit_response_list nested benefits; their titles render as plan feature rows.
 */
data class MembershipPlan(
    val plan_id: Long? = null,
    val plan_name: String? = null,
    val price: Int? = null,
    val membership_id: Long? = null,
    val benefit_response_list: List<Benefit>? = null,
)
