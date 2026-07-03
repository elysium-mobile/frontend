package com.elysium.softwork.feedback.domain.model

/**
 * An HR survey the worker can answer — the single annotation-free bean for the `surveys`
 * endpoints (the *Bean / Pragmatic Shortcut*).
 *
 * Framework-agnostic by design: the backend serializes **uniform snake_case**
 * (`@JsonNaming(SnakeCaseStrategy)`), so every property is snake_case and Gson resolves each
 * by reflection — no `@SerializedName`. Request and response now share the same keys (the old
 * `expirationType` request/response mismatch was fixed backend-side to `expiration_time`), so
 * a single field per concept covers both directions.
 *
 * @property survey_id primary key returned by every survey response.
 * @property title survey headline rendered in the card (request + response).
 * @property description one-line context shown beneath the title (request + response).
 * @property target_type audience selector (request + response).
 * @property expiration_time expiration date (request + response).
 */
data class Survey(
    val survey_id: Long? = null,
    val title: String? = null,
    val description: String? = null,
    val target_type: String? = null,
    val expiration_time: String? = null,
)
