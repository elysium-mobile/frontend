package com.elysium.softwork.payment.membership.domain.model

import java.time.LocalDate

/**
 * A membership lifecycle record — the annotation-free bean for the `memberships` endpoints
 * (the *Bean / Pragmatic Shortcut*).
 *
 * The backend serializes **uniform snake_case**, so every property is snake_case and Gson
 * resolves each by reflection without `@SerializedName`. Request and response share the same
 * keys, so a single field per concept covers both directions.
 *
 * @property membership_id primary key returned by every membership response.
 * @property membership_plan_id selected plan tier the membership is created for (request); the
 *   backend cross-references it to derive the enrolment. Absent on the plain read response.
 * @property membership_start start date (request + response), ISO `yyyy-MM-dd`.
 * @property membership_over end date (request + response), ISO `yyyy-MM-dd`.
 * @property membership_status status (request + response): `ACTIVE` / `PENDING` / `INACTIVE`.
 */
data class Membership(
    val membership_id: Long? = null,
    val membership_plan_id: Long? = null,
    val membership_start: String? = null,
    val membership_over: String? = null,
    val membership_status: String? = null,
) {

    /**
     * `true` only when this subscription is **operationally valid right now**: the status is
     * `ACTIVE` **and** [today] falls within the `[membership_start, membership_over]` window.
     *
     * A non-`ACTIVE` status is never valid. The window bounds are enforced only when they parse
     * as ISO dates — an absent or unparseable bound is treated as open on that side, so a
     * malformed date never falsely locks the worker out (the status check still gates).
     *
     * @param today injectable clock for deterministic tests; defaults to the device date.
     */
    fun isActiveNow(today: LocalDate = LocalDate.now()): Boolean {
        if (!membership_status.equals(STATUS_ACTIVE, ignoreCase = true)) return false
        val start: LocalDate? = membership_start.toIsoDateOrNull()
        val over: LocalDate? = membership_over.toIsoDateOrNull()
        if (start != null && today.isBefore(start)) return false
        if (over != null && today.isAfter(over)) return false
        return true
    }

    private fun String?.toIsoDateOrNull(): LocalDate? =
        this?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    companion object {
        /** Wire value of the active subscription status. */
        const val STATUS_ACTIVE: String = "ACTIVE"
    }
}
