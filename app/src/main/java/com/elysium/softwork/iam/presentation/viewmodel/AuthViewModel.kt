package com.elysium.softwork.iam.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.elysium.softwork.SoftWorkApplication
import com.elysium.softwork.iam.application.AuthState
import com.elysium.softwork.iam.application.AuthValidation
import com.elysium.softwork.iam.application.usecase.CompleteGoogleSignUpUseCase
import com.elysium.softwork.iam.application.usecase.LoginUseCase
import com.elysium.softwork.iam.application.usecase.RegisterUseCase
import com.elysium.softwork.iam.application.usecase.RegisterWithGoogleUseCase
import com.elysium.softwork.iam.application.usecase.SignInWithGoogleUseCase
import com.elysium.softwork.iam.domain.model.User
import com.elysium.softwork.shared.data.network.BadRequestException
import com.elysium.softwork.shared.utils.constants.DateTimeFormats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state holder for the IAM flows (login, register).
 *
 * The ViewModel owns no business logic: every authentication operation is delegated to an
 * application-layer use case, and this class is limited to (a) buffering the form input,
 * (b) deriving per-field validity flags for the screens, (c) projecting the request
 * lifecycle into the read-only [state] stream that drives navigation and progress UI, and
 * (d) lifting a backend `400` field-validation message onto [FormState.fieldError] so the
 * screen can render it under the offending input.
 *
 * Two streams are exposed:
 * - [state] — request lifecycle (`Idle`, `Loading`, `Success`, `MembershipRequired`, `Error`).
 * - [form] — current form values plus derived validation flags, recomputed per keystroke.
 *
 * @param loginUseCase signs the worker in with corporate credentials.
 * @param registerUseCase registers a new employee account.
 * @param registerWithGoogleUseCase registers a Google-linked employee (same backend endpoint).
 * @param signInWithGoogleUseCase triggers the native Credential Manager Google sign-in tray (Phase 1).
 * @param completeGoogleSignUpUseCase completes the Google profile registration (Phase 2).
 */
class AuthViewModel(
    private val loginUseCase: LoginUseCase,
    private val registerUseCase: RegisterUseCase,
    private val registerWithGoogleUseCase: RegisterWithGoogleUseCase,
    private val signInWithGoogleUseCase: SignInWithGoogleUseCase,
    private val completeGoogleSignUpUseCase: CompleteGoogleSignUpUseCase,
) : ViewModel() {

    /** Snapshot of the current form. Updated via the `on*Change` handlers. */
    data class FormState(
        val username: String = "",
        val email: String = "",
        val password: String = "",
        val confirmPassword: String = "",
        val isPasswordVisible: Boolean = false,
        val isConfirmPasswordVisible: Boolean = false,
        val fieldError: String? = null,
    ) {
        val isEmailFormatValid: Boolean get() = email.isEmpty() || AuthValidation.isEmailValid(email)
        val isCorporateDomain: Boolean get() = AuthValidation.isCorporateDomain(email)
        val isPasswordValid: Boolean get() = AuthValidation.isPasswordValid(password)
        val passwordsMatch: Boolean get() = AuthValidation.doPasswordsMatch(password, confirmPassword)
        val isUsernameValid: Boolean get() = AuthValidation.isUsernameValid(username)
    }

    /**
     * Buffer for the Google Phase-2 profile-completion form. Collects only the fields the backend
     * `sign-up/employee/google` endpoint needs — email/password/anonymous_name are derived from
     * the verified token server-side, so they are never captured here.
     */
    data class GoogleSignUpForm(
        val name: String = "",
        val lastName: String = "",
        val phoneNumber: String = "",
        val dni: String = "",
        val dateStart: String = DateTimeFormats.nowIso(),
        val position: String = "",
        val salary: String = "",
    ) {
        /** Every field required (@NotNull/@NotBlank server-side); salary must be numeric. */
        val isValid: Boolean
            get() = name.isNotBlank() && lastName.isNotBlank() && phoneNumber.isNotBlank() &&
                dni.isNotBlank() && dni.length == 8 && dateStart.isNotBlank() &&
                    position.isNotBlank() && salary.toIntOrNull() != null
    }

    private val _state: MutableStateFlow<AuthState> = MutableStateFlow(AuthState.Idle)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _form: MutableStateFlow<FormState> = MutableStateFlow(FormState())
    val form: StateFlow<FormState> = _form.asStateFlow()

    private val _googleForm: MutableStateFlow<GoogleSignUpForm> = MutableStateFlow(GoogleSignUpForm())

    /** Current Google Phase-2 profile-completion form values. */
    val googleForm: StateFlow<GoogleSignUpForm> = _googleForm.asStateFlow()

    // region Form handlers
    fun onUsernameChange(value: String) {
        _form.value = _form.value.copy(username = value)
    }

    fun onEmailChange(value: String) {
        _form.value = _form.value.copy(email = value)
    }

    fun onPasswordChange(value: String) {
        _form.value = _form.value.copy(password = value)
    }

    fun onConfirmPasswordChange(value: String) {
        _form.value = _form.value.copy(confirmPassword = value)
    }

    fun togglePasswordVisibility() {
        _form.value = _form.value.copy(isPasswordVisible = !_form.value.isPasswordVisible)
    }

    fun toggleConfirmPasswordVisibility() {
        _form.value = _form.value.copy(isConfirmPasswordVisible = !_form.value.isConfirmPasswordVisible)
    }

    /** Resets the request stream back to [AuthState.Idle] and clears any captured field error. */
    fun consumeState() {
        _state.value = AuthState.Idle
        _form.value = _form.value.copy(fieldError = null)
    }
    // endregion

    // region Google Phase-2 form handlers
    fun onGoogleNameChange(value: String) { _googleForm.value = _googleForm.value.copy(name = value) }
    fun onGoogleLastNameChange(value: String) { _googleForm.value = _googleForm.value.copy(lastName = value) }
    fun onGooglePhoneChange(value: String) { _googleForm.value = _googleForm.value.copy(phoneNumber = value) }
    fun onGoogleDniChange(value: String) { _googleForm.value = _googleForm.value.copy(dni = value) }
    fun onGoogleDateStartChange(value: String) { _googleForm.value = _googleForm.value.copy(dateStart = value) }
    fun onGooglePositionChange(value: String) { _googleForm.value = _googleForm.value.copy(position = value) }
    fun onGoogleSalaryChange(value: String) {
        _googleForm.value = _googleForm.value.copy(salary = value.filter { it.isDigit() })
    }
    // endregion

    // region Actions
    /**
     * Submits the login form. Requires both fields to be non-blank; the backend owns
     * credential validity. On success the membership flag is inspected: an active membership
     * yields [AuthState.Success], anything else yields [AuthState.MembershipRequired] so the
     * screen routes the worker into the payment onboarding gate.
     */
    fun submitLogin() {
        val current: FormState = _form.value
        if (current.email.isBlank() || current.password.isBlank()) return
        runRequest(
            onSuccess = { user ->
                if (user.isMembershipActive()) AuthState.Success(user)
                else AuthState.MembershipRequired(user)
            },
        ) { loginUseCase(current.email, current.password) }
    }

    /** Submits the employee registration form. No-ops when validation fails. */
    fun submitRegister() {
        val current: FormState = _form.value
        if (!current.isUsernameValid) return
        if (!current.isCorporateDomain) return
        if (!current.isPasswordValid) return
        if (!current.passwordsMatch) return
        runRequest {
            registerUseCase(
                name = current.username,
                email = current.email,
                password = current.password,
            )
        }
    }

    /** Submits the Gmail registration (display name only; routed through `sign-up/employee`). */
    fun submitRegisterWithGoogle() {
        val current: FormState = _form.value
        if (!current.isUsernameValid) return
        runRequest { registerWithGoogleUseCase(name = current.username) }
    }

    /**
     * **Google Phase 1** — triggers the native Credential Manager tray and validates the token.
     * Branches: an unregistered identity (`registered == false`) yields
     * [AuthState.GoogleSignUpRequired] (route to the profile form); a registered one branches
     * [AuthState.Success] vs [AuthState.MembershipRequired] on the membership flag, like
     * [submitLogin]. A cancellation / credential error lands on [AuthState.Error]. [context] must
     * be an Activity context (supplied by the screen).
     */
    fun submitSignInWithGoogle(context: Context) {
        runRequest(onSuccess = ::resolveGoogleState) { signInWithGoogleUseCase(context) }
    }

    /**
     * **Google Phase 2** — submits the profile-completion form (packed with the stashed
     * `id_token` inside the store). No-ops until the form is valid. Branches like Phase 1's
     * registered path.
     */
    fun submitGoogleSignUp() {
        val f: GoogleSignUpForm = _googleForm.value
        if (!f.isValid) return
        runRequest(onSuccess = ::resolveGoogleState) {
            completeGoogleSignUpUseCase(
                name = f.name,
                lastName = f.lastName,
                phoneNumber = f.phoneNumber,
                dni = f.dni,
                dateStart = f.dateStart,
                position = f.position,
                salary = f.salary.toIntOrNull() ?: 0,
            )
        }
    }
    // endregion

    /** Maps a Google auth result to the terminal state: signup-required, or membership branch. */
    private fun resolveGoogleState(user: User): AuthState = when {
        user.registered == false -> AuthState.GoogleSignUpRequired
        user.isMembershipActive() -> AuthState.Success(user)
        else -> AuthState.MembershipRequired(user)
    }

    /**
     * Runs [block] off the form, projecting the result into [state]. [onSuccess] maps the
     * resolved [User] to the terminal success state (login overrides it to branch on the
     * membership flag). A [BadRequestException] failure additionally lifts its field-level
     * message onto [FormState.fieldError] for inline display.
     */
    private fun runRequest(
        onSuccess: (User) -> AuthState = { AuthState.Success(it) },
        block: suspend () -> Result<User>,
    ) {
        if (_state.value is AuthState.Loading) return
        _state.value = AuthState.Loading
        _form.value = _form.value.copy(fieldError = null)
        viewModelScope.launch {
            val result: Result<User> = block()
            _state.value = result.fold(
                onSuccess = onSuccess,
                onFailure = { throwable ->
                    val message = resolveError(throwable)
                    if (throwable is BadRequestException) {
                        _form.value = _form.value.copy(fieldError = message)
                    }
                    AuthState.Error(message)
                },
            )
        }
    }

    /**
     * Resolves a failure into a user-facing message. A [BadRequestException] yields the
     * parsed `field_errors` message (e.g. the DNI length rule); anything else yields the
     * exception message or a generic fallback.
     */
    private fun resolveError(throwable: Throwable): String = when (throwable) {
        is BadRequestException -> throwable.response.primaryFieldError() ?: GENERIC_ERROR
        else -> throwable.message ?: GENERIC_ERROR
    }

    companion object {
        private const val GENERIC_ERROR: String = "Unexpected error"

        /**
         * Factory that assembles the IAM use cases from the application service locator.
         *
         * Use it inside Composables:
         * ```
         * val viewModel: AuthViewModel = viewModel(factory = AuthViewModel.Factory)
         * ```
         */
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as SoftWorkApplication
                val store = application.serviceLocator.authStore
                return AuthViewModel(
                    loginUseCase = LoginUseCase(store),
                    registerUseCase = RegisterUseCase(store),
                    registerWithGoogleUseCase = RegisterWithGoogleUseCase(store),
                    signInWithGoogleUseCase = SignInWithGoogleUseCase(store),
                    completeGoogleSignUpUseCase = CompleteGoogleSignUpUseCase(store),
                ) as T
            }
        }
    }
}
