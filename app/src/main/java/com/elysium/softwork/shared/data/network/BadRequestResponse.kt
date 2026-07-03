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
 *   "fieldErrors": { "argument": "[CreateUserCommand] dni must be 8 characters long" }
 * }
 * ```
 *
 * Framework-agnostic by design: property names match the wire keys exactly so Gson resolves
 * them by reflection without `@SerializedName`.
 *
 * **Wire asymmetry.** The live Spring Boot `GlobalExceptionHandler` serializes the validation
 * map under the camelCase key **`fieldErrors`**. Older/alternate handler shapes have emitted
 * the same map under snake_case `field_errors`. Rather than annotate (forbidden) or map, both
 * spellings coexist as nullable fields and [primaryFieldError] consults whichever Gson filled.
 *
 * @property status numeric HTTP status echoed in the body (always `400` for this shape).
 * @property error short reason phrase (`"Bad Request"`).
 * @property message human-readable summary of the rejection.
 * @property fieldErrors map of offending field → validation message, camelCase wire key used
 *   by the live backend. Nullable because some 400s carry only [message] with no per-field detail.
 * @property field_errors snake_case alias of the same map for handler variants that emit it
 *   under the legacy key. Coexists with [fieldErrors] to absorb the asymmetry annotation-free.
 */
data class BadRequestResponse(
    val status: Int = 400,
    val error: String? = null,
    val message: String? = null,
    val fieldErrors: Map<String, String>? = null,
    val field_errors: Map<String, String>? = null,
) {

    /** Whichever validation map the wire supplied (camelCase preferred, snake_case fallback). */
    private val errors: Map<String, String>? get() = fieldErrors ?: field_errors

    /**
     * Best-effort single user-facing message extracted from this payload.
     *
     * Prefers the conventional `"argument"` key the backend uses for command-level
     * validation (e.g. the DNI length rule), then falls back to the first field error, then
     * to the top-level [message]. Returns `null` only when the payload is entirely empty.
     */
    fun primaryFieldError(): String? =
        errors?.get(ARGUMENT_KEY)
            ?: errors?.values?.firstOrNull()
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
