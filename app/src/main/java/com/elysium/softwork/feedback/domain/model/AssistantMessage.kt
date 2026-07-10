package com.elysium.softwork.feedback.domain.model

/**
 * Single annotation-free bean spanning both the request and the response of the Employee
 * Assistant endpoint (`POST /api/v1/employee-assistant`), per the project's Bean shortcut —
 * no DTOs, no mappers. Property names mirror the backend wire keys 1:1 (uniform snake_case),
 * so Gson resolves them by reflection without `@SerializedName` (the backend owns the Jackson
 * `@JsonNaming(SnakeCaseStrategy)` side of the contract).
 *
 * Two wire shapes flow through this one class, so every field is nullable with a default:
 *  - **Request** (`AskAssistantRequest`) fills [prompt] (required, non-blank server-side) and
 *    optionally [company_id] (the organizational grouping identifier; supersedes the deprecated
 *    `survey_id`).
 *  - **Response** (`AssistantAnswerResponse`) fills [content_answer] (Gemini's reply, always in
 *    Spanish per the backend system prompt).
 *
 * @property company_id optional organizational grouping identifier; omitted (dropped by Gson when
 *   `null`) for a free-form question. Replaced the deprecated `survey_id` in the deployed contract.
 * @property prompt the worker's question; the only required request field.
 * @property content_answer the assistant's generated reply, present only on the response.
 */
data class AssistantMessage(
    val company_id: Long? = null,
    val prompt: String? = null,
    val content_answer: String? = null,
)
