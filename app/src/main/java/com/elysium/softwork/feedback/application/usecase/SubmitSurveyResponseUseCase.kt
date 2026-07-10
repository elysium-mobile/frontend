package com.elysium.softwork.feedback.application.usecase

import com.elysium.softwork.feedback.data.store.SurveyStore
import com.elysium.softwork.feedback.domain.model.SurveyResponse
import com.elysium.softwork.shared.data.local.SharedPrefsManager
import com.elysium.softwork.shared.utils.constants.DateTimeFormats

/**
 * Submits a worker's survey response to `POST /api/v1/survey-responses`.
 *
 * Owns the request-assembly business rules:
 * - resolves the author's `employee_profile_id` **dynamically** from [SharedPrefsManager]
 *   (cached during the post-login sequential profile sync) and binds it to the body;
 * - trims the free-text fields;
 * - defaults `submitted_at` to now in the backend's uniform ISO 8601 local pattern
 *   (`yyyy-MM-dd'T'HH:mm:ss`, no zone/offset) via [DateTimeFormats] when the caller omits it.
 *
 * The snake_case request keys (`survey_id`, `employee_profile_id`, `submitted_at`) are
 * populated per the backend contract. Stateless; safe to share a single instance process-wide.
 *
 * @param store survey data port that performs the network call.
 * @param prefs session storage holding the cached `employee_profile_id`.
 */
class SubmitSurveyResponseUseCase(
    private val store: SurveyStore,
    private val prefs: SharedPrefsManager,
) {

    /**
     * Assembles and submits the response.
     *
     * @param surveyId target survey id.
     * @param commentary free-text feedback; trimmed before dispatch.
     * @param cause categorized reason; trimmed before dispatch.
     * @param submittedAt ISO 8601 local date-time (`yyyy-MM-dd'T'HH:mm:ss`); defaults to now when blank.
     * @return [Result.success] with the stored [SurveyResponse] or [Result.failure] (a
     *   `400` arrives as a [com.elysium.softwork.shared.data.network.BadRequestException]).
     */
    suspend operator fun invoke(
        surveyId: Long,
        commentary: String,
        cause: String,
        submittedAt: String = DateTimeFormats.nowIso(),
    ): Result<SurveyResponse> {
        val profileId: Long = prefs.getLong(SharedPrefsManager.KEY_EMPLOYEE_PROFILE_ID)
        val response = SurveyResponse(
            survey_id = surveyId,
            employee_profile_id = profileId.takeIf { it != SharedPrefsManager.DEFAULT_LONG },
            submitted_at = submittedAt.ifBlank { DateTimeFormats.nowIso() },
            commentary = commentary.trim(),
            cause = cause.trim(),
        )
        return store.submitSurveyResponse(response)
    }
}
