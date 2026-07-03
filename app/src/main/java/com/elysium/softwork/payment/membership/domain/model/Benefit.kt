package com.elysium.softwork.payment.membership.domain.model

/**
 * A benefit included in a [MembershipPlan] — the annotation-free bean for the `benefits`
 * endpoints (the *Bean / Pragmatic Shortcut*).
 *
 * The backend serializes **uniform snake_case**, so every property is snake_case and Gson
 * resolves each by reflection without `@SerializedName`. Request and response share the same
 * keys, so a single field per concept covers both directions. The plan's
 * `benefit_response_list` nests these, and the UI renders [title] as a feature bullet.
 *
 * @property benefit_id primary key returned by every benefit response.
 * @property title short benefit headline (rendered as a plan feature row).
 * @property description benefit description.
 * @property membership_plan_id owning plan (request + response).
 */
data class Benefit(
    val benefit_id: Long? = null,
    val title: String? = null,
    val description: String? = null,
    val membership_plan_id: Long? = null,
)
