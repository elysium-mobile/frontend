package com.elysium.softwork.feedback.data.network

import com.elysium.softwork.feedback.domain.model.AssistantMessage
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit contract for the live Employee Assistant (AI) endpoint of the FlowWork Spring Boot API.
 *
 * The endpoint is powered by Google Gemini and is **authenticated** (JWT required — it is not on
 * `AuthInterceptor`'s public allowlist), so the interceptor attaches `Authorization: Bearer <token>`
 * and `Content-Type: application/json` automatically. The path is **relative**; the host + `/` base
 * lives in `BuildConfig.BACKEND_BASE_URL` (resolved by `ApiClient`).
 *
 * The same annotation-free [AssistantMessage] bean carries both the request body and the response
 * payload (the Bean shortcut) — no DTOs.
 *
 * **Route note**: the deployed contract exposes this at `api/v1/employee-assistant` (employee-facing,
 * distinct from the RRHH-facing `/api/v1/dashboard-assistant`). This supersedes the earlier
 * `/feedback-assistant` path documented in `ELYSIUM-API_DOCUMENTATION.md` §5.5 — that doc entry is
 * now stale and should be refreshed to match the deployment.
 */
interface FeedbackAssistantWebService {

    /**
     * Asks the Employee Assistant a question.
     *
     * @param request an [AssistantMessage] carrying the required `prompt` and an optional
     *   `company_id` for organizational-grouping context.
     * @return the assistant's reply in [AssistantMessage.content_answer].
     */
    @POST("api/v1/employee-assistant")
    suspend fun askAssistant(@Body request: AssistantMessage): Response<AssistantMessage>
}
