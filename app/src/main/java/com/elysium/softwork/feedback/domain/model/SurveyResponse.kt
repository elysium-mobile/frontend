package com.elysium.softwork.feedback.domain.model

/**
 * A worker's submission to a [Survey] — the annotation-free bean for the `survey-responses`
 * endpoints (the *Bean / Pragmatic Shortcut*).
 *
 * The backend serializes **uniform snake_case**, so every property is snake_case and Gson maps
 * each by reflection without `@SerializedName`. Request and response share the same keys, so a
 * single field per concept covers both directions.
 *
 * When building a POST body, populate [survey_id], [employee_profile_id], [submitted_at],
 * [commentary] and [cause]; [survey_response_id] is filled on the way back.
 *
 * @property survey_response_id primary key returned for a stored submission.
 * @property survey_id target survey (request + response).
 * @property employee_profile_id author profile (request + response).
 * @property submitted_at submission timestamp as a full ISO 8601 UTC instant (`…THH:mm:ss(.SSS)Z`,
 *   trailing `Z`) (request + response). Produced via `DateTimeFormats`.
 * @property commentary free-text feedback (request + response).
 * @property cause categorized reason (request + response).
 */
data class SurveyResponse(
    val survey_response_id: Long? = null,
    val survey_id: Long? = null,
    val employee_profile_id: Long? = null,
    val submitted_at: String? = null,
    val commentary: String? = null,
    val cause: String? = null,
)
