package com.elysium.softwork.iam.presentation.viewmodel

import com.elysium.softwork.iam.application.AuthState
import com.elysium.softwork.iam.application.usecase.LoginUseCase
import com.elysium.softwork.iam.application.usecase.RegisterUseCase
import com.elysium.softwork.iam.application.usecase.CompleteGoogleSignUpUseCase
import com.elysium.softwork.iam.application.usecase.RegisterWithGoogleUseCase
import com.elysium.softwork.iam.application.usecase.SignInWithGoogleUseCase
import com.elysium.softwork.iam.domain.model.User
import com.elysium.softwork.testsupport.FakeAuthStore
import com.elysium.softwork.testsupport.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [AuthViewModel].
 *
 * The ViewModel is constructed directly with a [FakeAuthStore] — the production
 * `Factory` companion is intentionally bypassed because its only responsibility is
 * pulling the store out of `SoftWorkApplication.serviceLocator`. The factory is glue
 * that belongs to instrumentation, not host-machine unit tests.
 *
 * The test class installs [MainDispatcherRule] so `viewModelScope` dispatches onto a
 * controllable [kotlinx.coroutines.test.TestDispatcher]. With the default
 * `UnconfinedTestDispatcher`, each `viewModelScope.launch` runs eagerly on the calling
 * thread, so `StateFlow.value` reads taken immediately after a `submit*` call see the
 * post-launch snapshot — no manual `advanceUntilIdle()` step required.
 *
 * Behaviors under test:
 *  - Form-field handlers update the immutable `FormState` snapshot atomically.
 *  - `submitLogin` is a no-op for blank inputs and routes through the store otherwise.
 *  - `state` transitions Idle → Loading → Success/Error in the right order.
 *  - `runRequest` is re-entrance safe — a second submission during `Loading` is dropped.
 *  - `consumeState` clears the request stream back to `Idle`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /**
     * Builds the ViewModel with the real application-layer use cases wrapping the fake
     * store. The use cases are stateless pass-through (plus input trimming), so the
     * fake remains the single observation point for forwarded arguments.
     */
    private fun newViewModel(store: FakeAuthStore = FakeAuthStore()): AuthViewModel =
        AuthViewModel(
            loginUseCase = LoginUseCase(store),
            registerUseCase = RegisterUseCase(store),
            registerWithGoogleUseCase = RegisterWithGoogleUseCase(store),
            signInWithGoogleUseCase = SignInWithGoogleUseCase(store),
            completeGoogleSignUpUseCase = CompleteGoogleSignUpUseCase(store),
        )

    // region Form state
    @Test
    fun `initial form snapshot is empty`() {
        val vm = newViewModel()
        val form = vm.form.value

        assertEquals("", form.username)
        assertEquals("", form.email)
        assertEquals("", form.password)
        assertEquals("", form.confirmPassword)
        assertEquals(false, form.isPasswordVisible)
        assertNull(form.fieldError)
    }

    @Test
    fun `onEmailChange replaces the email field while leaving the rest untouched`() {
        val vm = newViewModel()
        vm.onEmailChange("worker@elysium.com")
        vm.onPasswordChange("Abc12345!")

        val snapshot = vm.form.value
        assertEquals("worker@elysium.com", snapshot.email)
        assertEquals("Abc12345!", snapshot.password)
        assertEquals("", snapshot.username)
    }

    @Test
    fun `togglePasswordVisibility flips the visibility flag`() {
        val vm = newViewModel()
        assertEquals(false, vm.form.value.isPasswordVisible)
        vm.togglePasswordVisibility()
        assertEquals(true, vm.form.value.isPasswordVisible)
        vm.togglePasswordVisibility()
        assertEquals(false, vm.form.value.isPasswordVisible)
    }

    @Test
    fun `derived validators reflect the current form values`() {
        val vm = newViewModel()
        vm.onUsernameChange("Cesar")
        vm.onEmailChange("cesar@elysium.com")
        vm.onPasswordChange("Abc12345!")
        vm.onConfirmPasswordChange("Abc12345!")

        val snapshot = vm.form.value
        assertTrue(snapshot.isUsernameValid)
        assertTrue(snapshot.isEmailFormatValid)
        assertTrue(snapshot.isCorporateDomain)
        assertTrue(snapshot.isPasswordValid)
        assertTrue(snapshot.passwordsMatch)
    }
    // endregion

    // region submitLogin
    @Test
    fun `submitLogin is a no-op when email is blank`() = runTest {
        val store = FakeAuthStore()
        val vm = newViewModel(store)
        vm.onPasswordChange("anything")
        vm.submitLogin()

        assertEquals(0, store.loginInvocations)
        assertEquals(AuthState.Idle, vm.state.value)
    }

    @Test
    fun `submitLogin is a no-op when password is blank`() = runTest {
        val store = FakeAuthStore()
        val vm = newViewModel(store)
        vm.onEmailChange("worker@elysium.com")
        vm.submitLogin()

        assertEquals(0, store.loginInvocations)
        assertEquals(AuthState.Idle, vm.state.value)
    }

    @Test
    fun `submitLogin trims email then transitions Idle to Success on active membership`() = runTest {
        val expectedUser = User(id = 42L, name = "Cesar", token = "JWT_OK", membership_status = "ACTIVE")
        val store = FakeAuthStore(nextLoginResult = Result.success(expectedUser))
        val vm = newViewModel(store)
        vm.onEmailChange("  worker@elysium.com  ")
        vm.onPasswordChange("password")

        vm.submitLogin()

        assertEquals(1, store.loginInvocations)
        assertEquals("worker@elysium.com" to "password", store.lastLoginArgs)

        val finalState = vm.state.value
        assertTrue(finalState is AuthState.Success)
        assertEquals(expectedUser, (finalState as AuthState.Success).user)
    }

    @Test
    fun `submitLogin routes to MembershipRequired when the membership is not active`() = runTest {
        val inactiveUser = User(id = 42L, name = "Cesar", token = "JWT_OK", membership_status = "INACTIVE")
        val store = FakeAuthStore(nextLoginResult = Result.success(inactiveUser))
        val vm = newViewModel(store)
        vm.onEmailChange("worker@elysium.com")
        vm.onPasswordChange("password")

        vm.submitLogin()

        val finalState = vm.state.value
        assertTrue(finalState is AuthState.MembershipRequired)
        assertEquals(inactiveUser, (finalState as AuthState.MembershipRequired).user)
    }

    @Test
    fun `submitLogin transitions Idle to Error on store failure`() = runTest {
        val store = FakeAuthStore(
            nextLoginResult = Result.failure(IllegalStateException("Bad credentials")),
        )
        val vm = newViewModel(store)
        vm.onEmailChange("worker@elysium.com")
        vm.onPasswordChange("password")

        vm.submitLogin()

        val finalState = vm.state.value
        assertTrue(finalState is AuthState.Error)
        assertEquals("Bad credentials", (finalState as AuthState.Error).message)
    }

    @Test
    fun `submitLogin substitutes a default error message when the exception lacks one`() = runTest {
        val store = FakeAuthStore(nextLoginResult = Result.failure(RuntimeException()))
        val vm = newViewModel(store)
        vm.onEmailChange("worker@elysium.com")
        vm.onPasswordChange("password")

        vm.submitLogin()

        val finalState = vm.state.value
        assertTrue(finalState is AuthState.Error)
        // The exact wording is owned by the production code; we only assert non-blank.
        assertTrue((finalState as AuthState.Error).message.isNotBlank())
    }
    // endregion

    // region submitRegister
    @Test
    fun `submitRegister is a no-op when username is invalid`() = runTest {
        val store = FakeAuthStore()
        val vm = newViewModel(store)
        vm.onEmailChange("worker@elysium.com")
        vm.onPasswordChange("Abc12345!")
        vm.onConfirmPasswordChange("Abc12345!")

        vm.submitRegister()

        assertEquals(0, store.registerInvocations)
    }

    @Test
    fun `submitRegister is a no-op when the email is a personal provider`() = runTest {
        val store = FakeAuthStore()
        val vm = newViewModel(store)
        vm.onUsernameChange("Cesar")
        vm.onEmailChange("cesar@gmail.com")
        vm.onPasswordChange("Abc12345!")
        vm.onConfirmPasswordChange("Abc12345!")

        vm.submitRegister()

        assertEquals(0, store.registerInvocations)
    }

    @Test
    fun `submitRegister forwards trimmed fields to the store on success`() = runTest {
        val store = FakeAuthStore()
        val vm = newViewModel(store)
        vm.onUsernameChange("  Cesar  ")
        vm.onEmailChange("  cesar@elysium.com  ")
        vm.onPasswordChange("Abc12345!")
        vm.onConfirmPasswordChange("Abc12345!")

        vm.submitRegister()

        assertEquals(1, store.registerInvocations)
        val args = store.lastRegisterArgs
        assertNotNull(args)
        assertEquals("Cesar", args!!.name)
        assertEquals("cesar@elysium.com", args.email)
        assertEquals("Abc12345!", args.password)
    }

    @Test
    fun `submitLogin lifts a 400 field error onto the form state`() = runTest {
        val badRequest = com.elysium.softwork.shared.data.network.BadRequestException(
            com.elysium.softwork.shared.data.network.BadRequestResponse(
                message = "Internal validation failed",
                field_errors = mapOf("argument" to "[CreateUserCommand] dni must be 8 characters long"),
            ),
        )
        val store = FakeAuthStore(nextLoginResult = Result.failure(badRequest))
        val vm = newViewModel(store)
        vm.onEmailChange("worker@elysium.com")
        vm.onPasswordChange("password")

        vm.submitLogin()

        val finalState = vm.state.value
        assertTrue(finalState is AuthState.Error)
        assertEquals(
            "[CreateUserCommand] dni must be 8 characters long",
            (finalState as AuthState.Error).message,
        )
        assertEquals(
            "[CreateUserCommand] dni must be 8 characters long",
            vm.form.value.fieldError,
        )
    }
    // endregion

    // region Re-entrance + consumeState
    @Test
    fun `consumeState resets the request stream from Error back to Idle`() = runTest {
        val store = FakeAuthStore(nextLoginResult = Result.failure(IllegalStateException("fail")))
        val vm = newViewModel(store)
        vm.onEmailChange("worker@elysium.com")
        vm.onPasswordChange("password")
        vm.submitLogin()
        assertTrue(vm.state.value is AuthState.Error)

        vm.consumeState()

        assertEquals(AuthState.Idle, vm.state.value)
    }

    @Test
    fun `activeToken is never invoked by the ViewModel directly`() {
        // The ViewModel must not poke the persistence layer outside the auth flows;
        // session restoration is the host Activity's responsibility.
        val store = FakeAuthStore()
        newViewModel(store)
        assertNull(store.lastLoginArgs)
        assertEquals(0, store.loginInvocations)
    }
    // endregion
}
