package com.elysium.softwork.iam.data.store

import com.elysium.softwork.iam.data.network.AuthWebService
import com.elysium.softwork.iam.domain.model.User
import com.elysium.softwork.shared.data.local.SharedPrefsManager
import com.elysium.softwork.shared.data.network.BadRequestException
import com.elysium.softwork.shared.data.network.BadRequestResponse
import com.elysium.softwork.shared.utils.discriminators.SessionRecovery
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.Response

/**
 * Concrete [AuthStore] backed by the live FlowWork Spring Boot API.
 *
 * Responsibilities:
 * 1. Drive the real [AuthWebService] (`sign-in`, `sign-up/employee`, `employee-profile`) —
 *    there is **no** mock harness here.
 * 2. On a successful authentication, persist the session (token, user-account id, and the
 *    credentials needed for [reauthenticate]) and then run the **sequential** employee-profile
 *    sync that resolves and stores `employee_profile_id`.
 * 3. Convert transport/HTTP failures into a single [Result] error channel; a `400` is parsed
 *    into a [BadRequestException] so the presentation layer can surface field-level messages.
 *
 * @param webService Retrofit contract for the IAM endpoints.
 * @param prefs persistent session storage (token, ids, credentials).
 * @param gson deserializer for the structured `400` validation payload.
 */
class AuthStoreImpl(
    private val webService: AuthWebService,
    private val prefs: SharedPrefsManager,
    private val gson: Gson,
) : AuthStore {

    /** Backing state for [sessionRecovery]. Set by [invalidateSession] on a mid-session 401. */
    private val _sessionRecovery: MutableStateFlow<SessionRecovery> =
        MutableStateFlow(SessionRecovery.NONE)
    override val sessionRecovery: StateFlow<SessionRecovery> = _sessionRecovery.asStateFlow()

    override fun invalidateSession() {
        // Capture how the dying session authenticated BEFORE wiping it — a Google-linked session
        // stores no local password and must recover through the Gmail handshake, not the
        // credentials form.
        val recovery =
            if (prefs.getBoolean(SharedPrefsManager.KEY_GOOGLE_SESSION)) SessionRecovery.GOOGLE
            else SessionRecovery.CREDENTIALS
        // Wipe the dead session first so no subsequent request replays the rejected token, then
        // raise the signal the router observes. StateFlow dedups equal values, so a burst of
        // concurrent 401s (multiple in-flight authenticated calls) collapses to one emission.
        clearSession()
        _sessionRecovery.value = recovery
    }

    override fun consumeSessionInvalidation() {
        _sessionRecovery.value = SessionRecovery.NONE
    }

    override suspend fun login(email: String, password: String): Result<User> = runCatching {
        val user = unwrap(webService.signIn(User(email = email, password = password)))
        persistSessionAndSyncProfile(user, email, password, googleLinked = false)
        user
    }

    override suspend fun register(name: String, email: String, password: String): Result<User> =
        runCatching {
            val user = unwrap(
                webService.signUpEmployee(User(name = name, email = email, password = password)),
            )
            persistSessionAndSyncProfile(user, email, password, googleLinked = false)
            user
        }

    override suspend fun registerWithGoogle(name: String): Result<User> = runCatching {
        // Same `sign-up/employee` endpoint; the Google identity provides the email
        // server-side, so the device sends only the display name. The response echoes the
        // resolved address under `email`. No local password exists for a Google-linked account,
        // so the persisted credential is left blank — a later session renewal goes through
        // Google, not [reauthenticate].
        val user = unwrap(webService.signUpEmployee(User(name = name)))
        persistSessionAndSyncProfile(
            user,
            email = user.email.orEmpty(),
            password = "",
            googleLinked = true,
        )
        user
    }

    override suspend fun reauthenticate(): Result<User> {
        val email = prefs.getString(SharedPrefsManager.KEY_USER_EMAIL)
        val password = prefs.getString(SharedPrefsManager.KEY_USER_PASSWORD)
        if (email.isNullOrBlank() || password.isNullOrBlank()) {
            return Result.failure(IllegalStateException("No stored credentials to re-authenticate with"))
        }
        return login(email, password)
    }

    /**
     * Returns the active JWT, or `null` when there is no valid session.
     *
     * **Cold-launch validation + wipe.** A persisted token is only returned when it is a
     * structurally valid JWT carrying a real subject. A malformed token, or one whose `sub`
     * claim is the placeholder `"string"` (a stale/invalid signature the backend rejects with
     * `401`), is treated as no session: the whole session is purged via [clearSession] and
     * `null` is returned, so `MainActivity` routes the worker back to the `LoginScreen` to
     * re-authenticate with real credentials instead of replaying the bad token.
     */
    override fun activeToken(): String? {
        val token = prefs.getString(SharedPrefsManager.KEY_AUTH_TOKEN)
        if (!isValidSessionToken(token)) {
            clearSession()
            return null
        }
        return token
    }

    override fun clearSession() {
        // Single-commit purge of the whole IAM session. The interceptor re-reads KEY_AUTH_TOKEN
        // live, so the next request is unauthenticated immediately without touching the network.
        prefs.clearSession()
    }

    /**
     * Structural JWT validation used by the cold-launch session check.
     *
     * Decodes the token's payload segment and rejects:
     *  - blank tokens and anything that is not a three-segment `header.payload.signature` JWT;
     *  - the Swagger/placeholder subject `"sub":"string"`, which is not a real employee
     *    identity and is rejected server-side.
     *
     * Uses [java.util.Base64] URL decoding (API 26+, within `minSdk = 29`) so it stays free of
     * Android framework imports and remains unit-testable.
     */
    private fun isValidSessionToken(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val parts = token.split(".")
        if (parts.size != 3 || parts.any { it.isBlank() }) return false
        return runCatching {
            val payload = String(java.util.Base64.getUrlDecoder().decode(parts[1]))
            !payload.contains(PLACEHOLDER_SUBJECT)
        }.getOrDefault(false)
    }

    /**
     * Persists the authenticated session and runs the post-login routine the integration
     * contract requires:
     *  1. Store the JWT (so [com.elysium.softwork.shared.data.network.AuthInterceptor]
     *     authorizes the very next call), the user-account id, and the credentials for
     *     [reauthenticate].
     *  2. Sequentially call `GET /api/v1/employee-profile`, match this account's row, and
     *     persist its `employee_profile_id`.
     *
     * The token is mandatory; its absence aborts the flow as a failure. The profile sync is
     * best-effort — a profile lookup hiccup must not invalidate an otherwise good session.
     */
    private suspend fun persistSessionAndSyncProfile(
        user: User,
        email: String,
        password: String,
        googleLinked: Boolean,
    ) {
        val token = user.token ?: error("Authentication response is missing the token")
        // Persist the JWT to KEY_AUTH_TOKEN. The OkHttp AuthInterceptor reads this key live on
        // every subsequent request, so the sequential employee-profile lookup and the later
        // membership endpoints all carry `Authorization: Bearer <token>` — no cached reference.
        prefs.putString(SharedPrefsManager.KEY_AUTH_TOKEN, token)
        prefs.putString(SharedPrefsManager.KEY_USER_EMAIL, email)
        prefs.putString(SharedPrefsManager.KEY_USER_PASSWORD, password)
        prefs.putBoolean(SharedPrefsManager.KEY_GOOGLE_SESSION, googleLinked)
        user.id?.let { prefs.putLong(SharedPrefsManager.KEY_USER_ACCOUNT_ID, it) }

        // A brand-new session re-arms the 401 trap: clear any invalidation left over from a
        // prior session before the first authenticated call goes out. The employee-profile sync
        // below is intentionally trap-exempt (see AuthInterceptor), so it cannot re-raise this.
        _sessionRecovery.value = SessionRecovery.NONE

        user.id?.let { accountId ->
            syncEmployeeProfile(accountId)
            syncUserAccount(accountId)
        }
    }

    /**
     * Resolves and persists the worker's `employee_profile_id`.
     *
     * The list endpoint returns every profile, so the worker's row is found by matching
     * [User.user_account_id] against [accountId]. Wrapped so any failure (network, empty
     * list, profile not yet provisioned) is swallowed — the session remains valid even when
     * the profile id cannot be resolved this round.
     */
    private suspend fun syncEmployeeProfile(accountId: Long) {
        runCatching {
            val response = webService.getEmployeeProfiles()
            if (!response.isSuccessful) return
            val profileId = response.body()
                ?.firstOrNull { it.user_account_id == accountId }
                ?.employee_profile_id
                ?: return
            prefs.putLong(SharedPrefsManager.KEY_EMPLOYEE_PROFILE_ID, profileId)
        }
    }

    /**
     * Resolves and persists the worker's `membership_id` foreign key.
     *
     * `GET /api/v1/user_accounts` returns every account, so the worker's row is found by
     * matching [User.user_account_id] against [accountId]; its [User.membership_id] is then
     * cached so the session-authorization gate can query `GET /api/v1/memberships/{id}`.
     * Best-effort: any failure is swallowed — a missing membership id simply leaves the gate
     * to treat the worker as not-active (routing them to payment onboarding).
     */
    private suspend fun syncUserAccount(accountId: Long) {
        runCatching {
            val response = webService.getUserAccounts()
            if (!response.isSuccessful) return
            val membershipId = response.body()
                ?.firstOrNull { it.user_account_id == accountId }
                ?.membership_id
                ?: return
            prefs.putLong(SharedPrefsManager.KEY_MEMBERSHIP_ID, membershipId)
        }
    }

    /**
     * Unwraps a Retrofit [response] into its body or throws a typed failure.
     *
     * - `2xx` with a body → the body.
     * - `400` → [BadRequestException] carrying the parsed [BadRequestResponse] (so the
     *   `field_errors` map reaches the form state).
     * - any other non-2xx / empty body → [IllegalStateException] with the status line.
     */
    private fun unwrap(response: Response<User>): User {
        if (response.isSuccessful) {
            return response.body() ?: error("Empty response body")
        }
        val rawError: String? = runCatching { response.errorBody()?.string() }.getOrNull()
        if (response.code() == HTTP_BAD_REQUEST) {
            throw parseBadRequest(rawError)
        }
        error("HTTP ${response.code()} ${response.message().ifBlank { rawError ?: "request failed" }}")
    }

    /**
     * Deserializes a `400` body into a [BadRequestException]. Falls back to wrapping the raw
     * text in [BadRequestResponse.message] when the payload is absent or not the expected
     * shape, so the caller always receives a usable message.
     */
    private fun parseBadRequest(rawError: String?): BadRequestException {
        val parsed: BadRequestResponse = rawError
            ?.let { runCatching { gson.fromJson(it, BadRequestResponse::class.java) }.getOrNull() }
            ?: BadRequestResponse(message = rawError)
        return BadRequestException(parsed)
    }

    private companion object {
        const val HTTP_BAD_REQUEST: Int = 400

        /**
         * Marker for the placeholder/invalid JWT subject the backend rejects with `401`
         * (`"sub":"string"`). A token whose decoded payload contains this is purged on cold
         * launch so the worker re-authenticates with real credentials.
         */
        const val PLACEHOLDER_SUBJECT: String = "\"sub\":\"string\""
    }
}
