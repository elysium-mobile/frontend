package com.elysium.softwork.shared.data.network

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * OkHttp [Interceptor] that attaches the session JWT to every authenticated request.
 *
 * The token is read **per request** through [tokenProvider] (not captured once) so the very
 * next call after a fresh `sign-in` — including the sequential employee-profile lookup —
 * already carries the new credential, and a logout that clears the token immediately stops
 * authorizing outgoing traffic.
 *
 * **Standard headers.** Every authenticated request gets `Accept: application/json` and
 * `Authorization: Bearer <token>`. `Content-Type: application/json` is added for all normal
 * (JSON) writes but **deliberately skipped for `multipart/ *` bodies** (the `POST /assets`
 * Cloudinary upload) — OkHttp derives `multipart/form-data; boundary=…` from the body, and
 * overwriting that with `application/json` would strip the boundary and corrupt the upload.
 *
 * Public authentication endpoints are skipped: `sign-in` and `sign-up/employee` are reachable
 * without a token (the worker has none yet), so sending a stale/blank `Authorization` header
 * there is at best noise and at worst a 401 from an over-strict gateway. Matching is by path
 * suffix so it is independent of the configured `BACKEND_BASE_URL` host/prefix.
 *
 * **HTTP 401 unified trap.** This is the single choke-point every *authenticated* request
 * passes through, so it also observes the response: when an authenticated call returns
 * `401 Unauthorized` — despite carrying a freshly attached bearer token (a `sub`-claim
 * mismatch, unlinked account, or missing role authorization on the backend) — it fires
 * [onUnauthorized] so the session is wiped and the worker is routed back to authentication.
 * Two categories are deliberately exempt so the trap never fights the login handshake:
 *  - **Public endpoints** return before the authorized branch, so a `401` from wrong
 *    credentials on `sign-in` is handled by the login screen, not the trap.
 *  - **[TRAP_EXEMPT_SUFFIXES]** (the post-login `employee-profile` sync) are authenticated
 *    but best-effort; a `401` there must not tear down the session that was just created.
 *  - **[RETRY_GATE_SUFFIXES]** (the payment routes `membership-plans` / `memberships` / `orders`)
 *    return `401` as a *business* gate — the session is valid, the worker simply has no active
 *    membership. `MembershipViewModel` handles these (the catalogue flows into the UI freely,
 *    while an enrolment-status or order-push failure is mapped to a "membership expired" state
 *    routed to payment onboarding), so none of them may trigger a session-invalidation logout.
 *
 * @param tokenProvider supplies the current JWT (or `null`/blank when no session exists).
 *   Wired by `ServiceLocator` to read `SharedPrefsManager.KEY_AUTH_TOKEN`.
 * @param onUnauthorized invoked (off the OkHttp dispatcher thread) when an authenticated,
 *   non-exempt request comes back `401`. Wired by `ServiceLocator` to `AuthStore.invalidateSession()`.
 *   Defaults to a no-op so an un-wired build degrades to plain header injection.
 */
class AuthInterceptor(
    private val tokenProvider: () -> String?,
    private val onUnauthorized: () -> Unit = {},
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath

        if (isPublicEndpoint(path)) {
            return chain.proceed(request)
        }

        val token: String? = tokenProvider()
        if (token.isNullOrBlank()) {
            return chain.proceed(request)
        }

        val builder = request.newBuilder()
            .header(ACCEPT, APPLICATION_JSON)
            .header(HEADER_AUTHORIZATION, "$BEARER_PREFIX$token")

        // Content-Type is injected for every JSON write, but MUST NOT touch multipart uploads
        // (POST /api/v1/assets). OkHttp derives `multipart/form-data; boundary=…` from the
        // request body itself; overriding it with application/json strips the boundary and the
        // server can no longer parse the parts. Detect it from the actual body type so the guard
        // covers the assets endpoint and any future multipart route without a path allow-list.
        if (!request.isMultipart()) {
            builder.header(CONTENT_TYPE, APPLICATION_JSON)
        }

        val authorized = builder.build()
        val response = chain.proceed(authorized)

        if (response.code == HTTP_UNAUTHORIZED && !isTrapExempt(path) && !isRetryGate(path)) {
            onUnauthorized()
        }
        return response
    }

    /** `true` when [path] targets one of the token-free authentication endpoints. */
    private fun isPublicEndpoint(path: String): Boolean =
        PUBLIC_ENDPOINT_SUFFIXES.any { path.endsWith(it) }

    /** `true` when a `401` on [path] must NOT tear down the session (login-handshake calls). */
    private fun isTrapExempt(path: String): Boolean =
        TRAP_EXEMPT_SUFFIXES.any { path.endsWith(it) }

    /**
     * `true` when a `401` on [path] is a membership business-gate signal (valid session, no
     * active membership) that `MembershipViewModel` handles by routing to payment onboarding —
     * so the global session-invalidation trap must skip it.
     */
    private fun isRetryGate(path: String): Boolean =
        RETRY_GATE_SUFFIXES.any { path.endsWith(it) }

    /**
     * `true` when this request carries a `multipart/ *` body (e.g. the `POST /assets` upload),
     * so the `Content-Type` header must be left to OkHttp's body-derived value (which includes
     * the multipart boundary) rather than overwritten with `application/json`.
     */
    private fun Request.isMultipart(): Boolean =
        body?.contentType()?.type.equals(MULTIPART_TYPE, ignoreCase = true)

    companion object {
        private const val HEADER_AUTHORIZATION = "Authorization"
        private const val BEARER_PREFIX = "Bearer "
        private const val ACCEPT = "Accept"
        private const val CONTENT_TYPE = "Content-Type"
        private const val APPLICATION_JSON = "application/json"
        private const val MULTIPART_TYPE = "multipart"
        private const val HTTP_UNAUTHORIZED = 401

        /**
         * Path suffixes that must never receive an `Authorization` header. Kept as suffixes
         * so the check holds regardless of the API version prefix in `BACKEND_BASE_URL`.
         */
        private val PUBLIC_ENDPOINT_SUFFIXES: List<String> = listOf(
            "/authentication/sign-in",
            "/authentication/sign-up/employee",
        )

        /**
         * Authenticated path suffixes excluded from the `401` session-invalidation trap. The
         * post-login `employee-profile` sync is best-effort (the store already swallows its
         * failures); a `401` there during the login handshake must not wipe the session we
         * just established and bounce the worker straight back to the login screen.
         */
        private val TRAP_EXEMPT_SUFFIXES: List<String> = listOf(
            "/employee-profile",
        )

        /**
         * Authenticated path suffixes whose `401` is a **business gate**, not a dead session:
         * the JWT is valid but the worker has no active membership. The membership ViewModel
         * catches these and routes to the payment-onboarding screen, so they are excluded from
         * the session-invalidation trap (no logout, no login bounce). Covers the payment context:
         *  - `/membership-plans` — the public catalogue, which must always flow into the UI;
         *  - `/memberships` — enrolment-status validation (the actual gate signal);
         *  - `/orders` — the order-push transaction (a gated business route).
         */
        private val RETRY_GATE_SUFFIXES: List<String> = listOf(
            "/membership-plans",
            "/memberships",
            "/orders",
        )
    }
}
