package com.elysium.softwork.shared.utils.values

/**
 * Answer-input discriminator for a survey question, resolved from the `question_type` wire
 * string on `QuestionSurvey`.
 *
 * Carries a stable [key] so the backend can round-trip the value as a string. The take-survey
 * screen switches the rendered input on the resolved constant:
 * - [RATING] → a 1–5 numeric selection row.
 * - [OPEN_SURVEY] → a free-form text field.
 * - [MULTIPLE_CHOICE] → single-select option rows (default option set, since the backend
 *   `QuestionSurvey` bean carries no per-question option list).
 *
 * Any unrecognized `question_type` (e.g. the backend's `SCALE`) resolves to `null`; the screen
 * treats that as a free-form text input so an unknown type still captures an answer rather than
 * rendering nothing.
 */
enum class SurveyQuestionType(val key: String) {
    RATING("RATING"),
    OPEN_SURVEY("OPEN_SURVEY"),
    MULTIPLE_CHOICE("MULTIPLE_CHOICE");

    companion object {
        /**
         * Resolves a wire key into a [SurveyQuestionType], returning `null` on unknown input.
         * Matching is **case-insensitive** so the backend may send any casing.
         */
        fun fromKey(key: String?): SurveyQuestionType? =
            key?.let { raw -> entries.firstOrNull { it.key.equals(raw, ignoreCase = true) } }
    }
}
