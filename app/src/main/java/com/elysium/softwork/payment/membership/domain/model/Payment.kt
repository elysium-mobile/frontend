package com.elysium.softwork.payment.membership.domain.model

/**
 * A payment record settling an [Order] — the annotation-free bean for the `payments`
 * endpoints (the *Bean / Pragmatic Shortcut*).
 *
 * The backend serializes **uniform snake_case**, so every property is snake_case and Gson
 * resolves each by reflection without `@SerializedName`. Request and response share the same
 * keys, so a single field per concept covers both directions.
 *
 * @property payment_id primary key returned by every payment response.
 * @property order_id settled order (request + response).
 * @property transaction_id processor transaction id (request + response).
 * @property payment_date payment date (request + response).
 */
data class Payment(
    val payment_id: Long? = null,
    val order_id: Long? = null,
    val transaction_id: String? = null,
    val payment_date: String? = null,
)
