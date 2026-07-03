package com.elysium.softwork.iam.data.store

import com.elysium.softwork.iam.domain.model.User
import com.elysium.softwork.shared.utils.discriminators.SessionRecovery
import kotlinx.coroutines.flow.StateFlow

/**
 * IAM access port. Use cases and ViewModels depend on this contract; the concrete
 * implementation in [AuthStoreImpl] orchestrates the Retrofit WebService and local
 * session storage.
 *
 * The interface returns [Result] so call sites get a single, predictable error channel —
 * HTTP failures and thrown exceptions are converted to [Result.failure] by the impl. A
 * `400 Bad Request` surfaces as a
 * [com.elysium.softwork.shared.data.network.BadRequestException] carrying the parsed
 * field-level validation payload.
 */
interface AuthStore {

    /**
     * Process-wide signal describing how to recover after the current session was rejected
     * mid-flight (an `HTTP 401` on an authenticated call — e.g. `GET /api/v1/membership-plans` —
     * despite a freshly attached bearer token, caused by a `sub`-claim mismatch, unlinked
     * account constraints, or missing role authorizations on the backend).
     *
     * Emits [SessionRecovery.CREDENTIALS] or [SessionRecovery.GOOGLE] when [invalidateSession]
     * fires — the latter for a Google-linked session that carries no local password and must be
     * renewed through the Gmail handshake. The top-level router
     * ([com.elysium.softwork.MainActivity]) collects it, drops the worker back to the matching
     * authentication surface, then resets it to [SessionRecovery.NONE] via
     * [consumeSessionInvalidation]. [SessionRecovery.NONE] in every other state (fresh process,
     * active session, already handled).
     */
    val sessionRecovery: StateFlow<SessionRecovery>

    /**
     * Signs in with corporate credentials. On success the JWT, the user-account id, and the
     * credentials are persisted, and a sequential `employee-profile` lookup resolves and
     * stores the `employee_profile_id`.
     */
    suspend fun login(email: String, password: String): Result<User>

    /** Registers a new employee account (employee sign-up endpoint). */
    suspend fun register(name: String, email: String, password: String): Result<User>

    /**
     * Registers a Google-linked employee. The backend has no dedicated Google endpoint, so
     * this funnels through the **same** `sign-up/employee` path — the Google identity
     * supplies the email server-side, and only the display [name] is collected on-device.
     */
    suspend fun registerWithGoogle(name: String): Result<User>

    /**
     * Re-runs `sign-in` with the credentials persisted at the last successful login to obtain
     * a fresh token. The backend exposes no refresh endpoint, so this is the only way to renew
     * the session — invoked after a successful membership payment. Fails when no credentials
     * are stored.
     */
    suspend fun reauthenticate(): Result<User>

    /** Returns the locally-cached JWT, or `null` when no session exists. */
    fun activeToken(): String?

    /** Clears the persisted session (token, account/profile ids, and stored credentials). */
    fun clearSession()

    /**
     * Graceful-degradation entry point for the `HTTP 401` trap. Reads the auth method of the
     * dying session, wipes it via [clearSession], and raises [sessionRecovery] with the matching
     * recovery route so the presentation layer re-authenticates cleanly instead of crashing or
     * looping on a dead token. Safe to call from any thread (invoked by the OkHttp interceptor
     * off the dispatcher thread); idempotent — repeated calls collapse to a single distinct
     * emission.
     */
    fun invalidateSession()

    /**
     * Resets [sessionRecovery] back to [SessionRecovery.NONE] after the router has handled it,
     * so a later session can arm the trap again. Called once by the router after it drops to the
     * authentication surface.
     */
    fun consumeSessionInvalidation()
}
