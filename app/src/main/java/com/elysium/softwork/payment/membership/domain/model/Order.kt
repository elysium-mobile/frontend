package com.elysium.softwork.payment.membership.domain.model

/**
 * A purchase order for a membership — the annotation-free bean for the `orders` endpoints
 * (the *Bean / Pragmatic Shortcut*).
 *
 * The backend serializes **uniform snake_case**, so every property is snake_case and Gson
 * resolves each by reflection without `@SerializedName`. Request and response share the same
 * keys, so a single field per concept covers both directions.
 *
 * @property order_id primary key returned by every order response.
 * @property user_account_id buyer account (request + response).
 * @property amount order amount.
 * @property membership_id purchased membership (request + response).
 */
data class Order(
    val order_id: Long? = null,
    val user_account_id: Long? = null,
    val amount: Int? = null,
    val membership_id: Long? = null,
)
