package com.elysium.softwork.shared.data.network

/**
 * Client-side mirror of the backend's `GlobalExceptionHandler` 400 validation payload.
 *
 * The Spring Boot API returns this shape for `MethodArgumentNotValidException` and internal
 * business-validation failures:
 *
 * ```json
 * {
 *   "status": 400,
 *   "error": "Bad Request",
 *   "message": "JSON validation failed",
 *   "field_errors": { "dni": "must not be blank" }
 * }
 * ```
 *
 * Framework-agnostic by design: property names match the wire keys exactly so Gson resolves
 * them by reflection without `@SerializedName`. The backend's `GlobalExceptionHandler` records
 * are annotated `@JsonNaming(SnakeCaseStrategy)`, so **uniform snake_case** applies to both the
 * envelope (`field_errors`) and — since the 2026-07-03 migration — the dynamic keys inside the
 * map (e.g. `last_name`, `user_account_id`), which mirror the offending DTO field names.
 *
 * @property status numeric HTTP status echoed in the body (always `400` for this shape).
 * @property error short reason phrase (`"Bad Request"`).
 * @property message human-readable summary of the rejection.
 * @property field_errors map of offending field → validation message. Nullable because some
 *   400s (internal `IllegalArgumentException`) carry only [message] with no per-field detail.
 */
data class BadRequestResponse(
    val status: Int = 400,
    val error: String? = null,
    val message: String? = null,
    val field_errors: Map<String, String>? = null,
) {

    /**
     * Best-effort single user-facing message extracted from this payload.
     *
     * Prefers the conventional `"argument"` key the backend uses for command-level
     * validation (e.g. the DNI length rule), then falls back to the first field error, then
     * to the top-level [message]. Returns `null` only when the payload is entirely empty.
     */
    fun primaryFieldError(): String? =
        field_errors?.get(ARGUMENT_KEY)
            ?: field_errors?.values?.firstOrNull()
            ?: message

    companion object {
        /** Conventional `field_errors` key the backend uses for command-argument rules. */
        const val ARGUMENT_KEY: String = "argument"
    }
}

/**
 * Typed failure raised by the data layer when the backend answers `400 Bad Request`.
 *
 * Carries the already-deserialized [response] so the presentation layer can pull the
 * offending-field message straight onto the form state without re-touching the raw HTTP
 * error stream. Surfaced through `Result.failure` by the IAM store.
 *
 * @property response the parsed validation payload (never null; an unparseable body yields a
 *   [BadRequestResponse] with only [BadRequestResponse.message] populated).
 */
class BadRequestException(
    val response: BadRequestResponse,
) : RuntimeException(response.primaryFieldError() ?: "Bad Request")

/**
 * Typed failure raised by the data layer when the backend answers `401 Unauthorized` on an
 * authenticated call.
 *
 * Most `401`s are session-invalidation cases handled globally by `AuthInterceptor`'s trap
 * (which wipes the session and routes to login). A **business-gate** `401` — e.g.
 * `GET /api/v1/membership-plans` for a worker with no active membership — is exempted from that
 * trap and instead surfaced as this typed failure through the `Result` channel, so the
 * membership ViewModel can map it to a "membership expired" UI state and route to payment
 * onboarding rather than logging the (validly authenticated) worker out.
 *
 * @param rawBody the raw error body, when present, for diagnostics.
 */
class UnauthorizedException(
    rawBody: String? = null,
) : RuntimeException(rawBody?.ifBlank { null } ?: "Unauthorized")
