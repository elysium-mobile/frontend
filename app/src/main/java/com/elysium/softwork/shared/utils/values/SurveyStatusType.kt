package com.elysium.softwork.shared.utils.values

/**
 * Terminal outcome of a survey submission, driving the `SurveyStatusScreen` layout and carried
 * as the `status_type` navigation argument.
 *
 * Carries a stable [key] so it round-trips cleanly through the nav route string.
 * - [SUCCESS] → HTTP 201: the response was stored.
 * - [ALREADY_ANSWERED] → the backend's unique-constraint `400` ("Employee has already submitted
 *   a response for this survey.").
 */
enum class SurveyStatusType(val key: String) {
    SUCCESS("success"),
    ALREADY_ANSWERED("already_answered");

    companion object {
        /**
         * Resolves a nav-argument [key] into a [SurveyStatusType], defaulting to [SUCCESS] on an
         * unknown/absent value (matching is case-insensitive).
         */
        fun fromKey(key: String?): SurveyStatusType =
            key?.let { raw -> entries.firstOrNull { it.key.equals(raw, ignoreCase = true) } }
                ?: SUCCESS
    }
}
