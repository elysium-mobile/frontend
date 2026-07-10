package com.elysium.softwork.feedback.data.store

import com.elysium.softwork.feedback.data.network.FeedbackAssistantWebService
import com.elysium.softwork.feedback.domain.model.AssistantMessage
import com.elysium.softwork.feedback.domain.model.ChatMessage
import com.elysium.softwork.shared.data.network.BadRequestException
import com.elysium.softwork.shared.data.network.BadRequestResponse
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.Response
import java.util.UUID

/**
 * [FeedbackStore] backed by the live FlowWork Employee Assistant endpoint
 * (`POST /api/v1/employee-assistant`, Google Gemini).
 *
 * The backend assistant is **stateless per request** — it echoes a single answer and keeps no
 * server-side conversation — so the conversation log remains a client-side concern held in an
 * in-memory [MutableStateFlow] for the lifetime of the process (not persisted across cold starts;
 * a `FeedbackWebService`-backed history would land in a later phase without changing this contract).
 *
 * [send] appends the worker's outgoing message immediately (optimistic UI), POSTs the prompt via
 * [FeedbackAssistantWebService.askAssistant] — tagged with the worker's `company_id` resolved live
 * from [companyIdProvider] for organizational-grouping context — and appends the returned
 * `content_answer` as the AI reply. A `400` is parsed into a [BadRequestException] (same
 * `unwrap`/`throwTyped` pattern as the other live stores) and surfaced through the failed [Result];
 * the optimistic worker bubble is left in place so the message the worker sent is not lost.
 *
 * @param webService Retrofit contract for the assistant endpoint.
 * @param gson deserializer for the structured `400` validation payload.
 * @param companyIdProvider supplies the signed-in worker's `company_id` (cached during the
 *   post-login `user_accounts` sync); read **per send** so it always reflects the current session.
 *   Returns `null` when no company is cached, in which case the request goes out unscoped (Gson
 *   drops the null key).
 */
class FeedbackStoreImpl(
    private val webService: FeedbackAssistantWebService,
    private val gson: Gson,
    private val companyIdProvider: () -> Long?,
) : FeedbackStore {

    private val _conversation: MutableStateFlow<List<ChatMessage>> =
        MutableStateFlow(emptyList())

    override val conversation: StateFlow<List<ChatMessage>> = _conversation.asStateFlow()

    override suspend fun send(content: String): Result<Unit> = runCatching {
        val trimmed: String = content.trim()
        if (trimmed.isEmpty()) return@runCatching

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            content = trimmed,
            isFromUser = true,
            timestamp = System.currentTimeMillis(),
        )
        _conversation.value = _conversation.value + userMessage

        val request = AssistantMessage(company_id = companyIdProvider(), prompt = trimmed)
        val answer: AssistantMessage = unwrap(webService.askAssistant(request))

        val aiMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            content = answer.content_answer.orEmpty(),
            isFromUser = false,
            timestamp = System.currentTimeMillis(),
        )
        _conversation.value = _conversation.value + aiMessage
    }

    /**
     * Unwraps a single-object [response]; a `400` becomes a [BadRequestException] carrying the
     * parsed [BadRequestResponse] (e.g. the `"Prompt must not be empty."` rule), anything else an
     * [IllegalStateException].
     */
    private fun <T> unwrap(response: Response<T>): T {
        if (response.isSuccessful) {
            return response.body() ?: error("Empty response body")
        }
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
