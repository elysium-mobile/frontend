package com.elysium.softwork.feedback.domain.model

/**
 * A single question belonging to a [Survey] — the annotation-free bean for the
 * `question-surveys` endpoints (the *Bean / Pragmatic Shortcut*).
 *
 * The backend serializes **uniform snake_case**, so every property is snake_case and Gson maps
 * each by reflection without `@SerializedName`. Request and response share the same keys, so a
 * single field per concept covers both directions.
 *
 * @property question_survey_id primary key returned by every question response.
 * @property text_question question text (request + response).
 * @property question_type answer type (request + response).
 * @property survey_id owning survey (request + response); used to filter the question set for a
 *   given survey client-side.
 */
data class QuestionSurvey(
    val question_survey_id: Long? = null,
    val text_question: String? = null,
    val question_type: String? = null,
    val survey_id: Long? = null,
)
