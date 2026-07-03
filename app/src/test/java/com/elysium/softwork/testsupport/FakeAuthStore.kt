package com.elysium.softwork.testsupport

import com.elysium.softwork.iam.data.store.AuthStore
import com.elysium.softwork.iam.domain.model.User
import com.elysium.softwork.shared.utils.discriminators.SessionRecovery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory test double for [AuthStore].
 *
 * Designed for ViewModel unit tests where the production Retrofit-backed implementation
 * would require a live network or a mock web server. The fake is fully deterministic —
 * every call returns the value programmed via the public `next*` properties — and records
 * the arguments of the last successful invocation so tests can assert that the ViewModel
 * forwarded the right values to the store.
 *
 * The class is `open` so individual tests can override a method to inject delays, throw
 * exceptions during the suspend call, or simulate concurrent invocations without rewriting
 * the whole double.
 *
 * Manual DI fit: pass an instance of this fake straight into a ViewModel constructor.
 * The production code never reaches for the `ServiceLocator` once the ViewModel is
 * instantiated, so swapping the store implementation here is a complete isolation
 * boundary — no Hilt, no reflection, no Robolectric.
 *
 * @property nextLoginResult value returned by the next [login] call.
 * @property nextRegisterResult value returned by the next [register] call.
 * @property nextRegisterWithGoogleResult value returned by the next [registerWithGoogle] call.
 * @property nextReauthenticateResult value returned by the next [reauthenticate] call.
 */
open class FakeAuthStore(
    var nextLoginResult: Result<User> = Result.success(DEFAULT_USER),
    var nextRegisterResult: Result<User> = Result.success(DEFAULT_USER),
    var nextRegisterWithGoogleResult: Result<User> = Result.success(DEFAULT_USER),
    var nextReauthenticateResult: Result<User> = Result.success(DEFAULT_USER),
) : AuthStore {

    /** Number of times each method has been invoked. Useful for "called exactly once" assertions. */
    var loginInvocations: Int = 0
        private set

    var registerInvocations: Int = 0
        private set

    var registerWithGoogleInvocations: Int = 0
        private set

    var reauthenticateInvocations: Int = 0
        private set

    var clearSessionInvocations: Int = 0
        private set

    var invalidateSessionInvocations: Int = 0
        private set

    /** Recovery route [invalidateSession] will emit. Set to [SessionRecovery.GOOGLE] to exercise the Gmail path. */
    var nextInvalidationRecovery: SessionRecovery = SessionRecovery.CREDENTIALS

    private val _sessionRecovery: MutableStateFlow<SessionRecovery> =
        MutableStateFlow(SessionRecovery.NONE)
    override val sessionRecovery: StateFlow<SessionRecovery> = _sessionRecovery.asStateFlow()

    /** Arguments of the most recent [login] call, or `null` when [login] has never been invoked. */
    var lastLoginArgs: Pair<String, String>? = null
        private set

    /** Arguments of the most recent [register] call. */
    var lastRegisterArgs: RegisterArgs? = null
        private set

    /** Display name passed to the most recent [registerWithGoogle] call. */
    var lastRegisterWithGoogleArg: String? = null
        private set

    /** Token returned by [activeToken]. Defaults to `null` so the auth gate routes to LOGIN. */
    var storedToken: String? = null

    override suspend fun login(email: String, password: String): Result<User> {
        loginInvocations += 1
        lastLoginArgs = email to password
        return nextLoginResult
    }

    override suspend fun register(name: String, email: String, password: String): Result<User> {
        registerInvocations += 1
        lastRegisterArgs = RegisterArgs(name, email, password)
        return nextRegisterResult
    }

    override suspend fun registerWithGoogle(name: String): Result<User> {
        registerWithGoogleInvocations += 1
        lastRegisterWithGoogleArg = name
        return nextRegisterWithGoogleResult
    }

    override suspend fun reauthenticate(): Result<User> {
        reauthenticateInvocations += 1
        return nextReauthenticateResult
    }

    override fun activeToken(): String? = storedToken

    override fun clearSession() {
        clearSessionInvocations += 1
        storedToken = null
    }

    override fun invalidateSession() {
        invalidateSessionInvocations += 1
        clearSession()
        _sessionRecovery.value = nextInvalidationRecovery
    }

    override fun consumeSessionInvalidation() {
        _sessionRecovery.value = SessionRecovery.NONE
    }

    /** Captured argument tuple for [register], grouped so assertions stay legible. */
    data class RegisterArgs(
        val name: String,
        val email: String,
        val password: String,
    )

    companion object {
        /** Default user surfaced on success unless a test overrides [nextLoginResult] etc. */
        val DEFAULT_USER: User = User(
            id = 1L,
            name = "test",
            gmail = "test@elysium.com",
            token = "TEST_TOKEN",
        )
    }
}
