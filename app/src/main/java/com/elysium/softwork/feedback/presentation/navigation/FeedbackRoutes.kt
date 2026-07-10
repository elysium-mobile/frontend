package com.elysium.softwork.feedback.presentation.navigation

/**
 * Route catalog for the Feedback bounded context. Kept in its own file so other navigation
 * hosts can import the constants without pulling in the `NavGraphBuilder` extension defined
 * in `FeedbackNavigation.kt`.
 */
object FeedbackRoutes {
    const val PENDING_SURVEYS: String = "feedback/pending_surveys"

    /** FlowWork AI chat surface reached from the Home action card. */
    const val AI_CHAT: String = "feedback/ai_chat"

    /** Nav-argument key carrying the target `survey_id` into the take-survey destination. */
    const val ARG_SURVEY_ID: String = "survey_id"

    /**
     * Answer-a-survey destination. Parameterized by the backend `survey_id`; the screen fetches
     * the question set and filters it to that survey. Registered with a `LongType` argument.
     */
    const val TAKE_SURVEY: String = "feedback/take_survey/{$ARG_SURVEY_ID}"

    /** Builds a concrete [TAKE_SURVEY] path for [surveyId]. */
    fun takeSurvey(surveyId: Long): String = "feedback/take_survey/$surveyId"

    /** Nav-argument key carrying the terminal outcome into the survey-status destination. */
    const val ARG_STATUS_TYPE: String = "status_type"

    /**
     * Terminal survey-outcome destination. Parameterized by a `SurveyStatusType.key`
     * (`success` / `already_answered`); the screen switches its layout on the resolved type.
     */
    const val SURVEY_STATUS: String = "feedback/survey_status/{$ARG_STATUS_TYPE}"

    /** Builds a concrete [SURVEY_STATUS] path for [statusKey] (a `SurveyStatusType.key`). */
    fun surveyStatus(statusKey: String): String = "feedback/survey_status/$statusKey"
}
