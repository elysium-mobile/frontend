package com.elysium.softwork.feedback.data.store

import com.elysium.softwork.feedback.data.network.SurveyWebService
import com.elysium.softwork.feedback.domain.model.QuestionSurvey
import com.elysium.softwork.feedback.domain.model.Survey
import com.elysium.softwork.feedback.domain.model.SurveyResponse
import com.elysium.softwork.shared.data.network.BadRequestException
import com.elysium.softwork.shared.data.network.BadRequestResponse
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import retrofit2.Response

/**
 * Concrete [SurveyStore] backed by the live FlowWork Spring Boot API.
 *
 * No mock harness: every method drives [SurveyWebService]. The pending list is a single-shot
 * cold [Flow] (Retrofit suspend calls are main-safe, so the request runs off the main
 * thread); the detail operations are suspending and wrap their result in [Result], parsing a
 * `400` into a [BadRequestException] so the presentation layer can read the `field_errors`.
 *
 * Questions are filtered client-side by `survey_id` because the backend exposes only the
 * unfiltered `GET /question-surveys` list.
 *
 * @param webService Retrofit contract for the feedback endpoints.
 * @param gson deserializer for the structured `400` validation payload.
 */
class SurveyStoreImpl(
    private val webService: SurveyWebService,
    private val gson: Gson,
) : SurveyStore {

    override fun getPendingSurveys(): Flow<List<Survey>> = flow {
        emit(unwrapList(webService.getSurveys()))
    }

    override suspend fun getSurveyQuestions(surveyId: Long): Result<List<QuestionSurvey>> =
        runCatching {
            unwrapList(webService.getQuestionSurveys())
                .filter { it.survey_id == surveyId }
        }

    override suspend fun getSurveyResponses(surveyId: Long): Result<List<SurveyResponse>> =
        runCatching { unwrapList(webService.getSurveyResponsesBySurvey(surveyId)) }

    override suspend fun submitSurveyResponse(response: SurveyResponse): Result<SurveyResponse> =
        runCatching { unwrap(webService.createSurveyResponse(response)) }

    /** Unwraps a single-object [response]; a `400` becomes a [BadRequestException]. */
    private fun <T> unwrap(response: Response<T>): T {
        if (response.isSuccessful) {
            return response.body() ?: error("Empty response body")
        }
        throwTyped(response)
    }

    /** Unwraps a list [response], tolerating an empty body as an empty list. */
    private fun <T> unwrapList(response: Response<List<T>>): List<T> {
        if (response.isSuccessful) {
            return response.body().orEmpty()
        }
        throwTyped(response)
    }

    /**
     * Converts a non-2xx [response] into a typed failure: a `400` into a [BadRequestException]
     * carrying the parsed [BadRequestResponse], anything else into an [IllegalStateException].
     */
    private fun throwTyped(response: Response<*>): Nothing {
        val rawError: String? = runCatching { response.errorBody()?.string() }.getOrNull()
        if (response.code() == HTTP_BAD_REQUEST) {
            val parsed: BadRequestResponse = rawError
                ?.let { runCatching { gson.fromJson(it, BadRequestResponse::class.java) }.getOrNull() }
                ?: BadRequestResponse(message = rawError)
            throw BadRequestException(parsed)
        }
        error("HTTP ${response.code()} ${response.message().ifBlank { rawError ?: "request failed" }}")
    }

    private companion object {
        const val HTTP_BAD_REQUEST: Int = 400
    }
}
