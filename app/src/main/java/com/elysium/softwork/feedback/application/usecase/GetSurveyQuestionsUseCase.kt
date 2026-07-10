package com.elysium.softwork.feedback.application.usecase

import com.elysium.softwork.feedback.data.store.SurveyStore
import com.elysium.softwork.feedback.domain.model.QuestionSurvey

/**
 * Fetches the question set for a single survey.
 *
 * Backs the take-survey screen: the store hits `GET /api/v1/question-surveys` and filters the
 * unfiltered list client-side by `survey_id` (the backend exposes no per-survey question route).
 * Stateless; safe to share a single instance process-wide.
 *
 * @param store survey data port that performs the network call and the client-side filter.
 */
class GetSurveyQuestionsUseCase(private val store: SurveyStore) {

    /**
     * @param surveyId survey whose questions to load.
     * @return [Result.success] with the (possibly empty) filtered questions, or [Result.failure]
     *   on transport error (a `400` arrives as a
     *   [com.elysium.softwork.shared.data.network.BadRequestException]).
     */
    suspend operator fun invoke(surveyId: Long): Result<List<QuestionSurvey>> =
        store.getSurveyQuestions(surveyId)
}
