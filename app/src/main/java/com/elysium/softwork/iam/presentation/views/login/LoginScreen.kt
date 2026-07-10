package com.elysium.softwork.iam.presentation.views.login

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.elysium.softwork.R
import com.elysium.softwork.iam.application.AuthState
import com.elysium.softwork.iam.presentation.viewmodel.AuthViewModel
import com.elysium.softwork.iam.presentation.components.AuthLoadingOverlay
import com.elysium.softwork.iam.presentation.components.GoogleOutlineButton
import com.elysium.softwork.iam.presentation.components.PasswordVisibilityToggle
import com.elysium.softwork.shared.utils.discriminators.ButtonVariant
import com.elysium.softwork.shared.presentation.components.SoftWorkButton
import com.elysium.softwork.shared.presentation.components.SoftWorkTextField
import com.elysium.softwork.shared.presentation.theme.AccentDark
import com.elysium.softwork.shared.presentation.theme.PrimaryNavy
import com.elysium.softwork.shared.presentation.theme.PrimarySky

/**
 * Login surface for the SoftWork Employee app.
 *
 * Layout: brand logo + product name → "Welcome back" subtitle → email/password
 * inputs → forgot-password link (right-aligned) → primary "Sign in" button →
 * outline Google button → footer link to register.
 *
 * @param onLoginSuccess invoked when the worker authenticates with an active membership.
 * @param onMembershipRequired invoked when authentication succeeds but the membership is not
 *   active — the host routes straight into the payment onboarding gate.
 * @param onNavigateToRegister opens the standard register flow.
 * @param onGoogleSignUpRequired opens the Google profile-completion form (Phase 1 returned
 *   `registered == false`; the verified token is held in the store for Phase 2).
 * @param onForgotPassword opens the forgot-password flow (currently a no-op placeholder).
 */
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onMembershipRequired: () -> Unit,
    onNavigateToRegister: () -> Unit,
    onGoogleSignUpRequired: () -> Unit,
    onForgotPassword: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthViewModel = viewModel(factory = AuthViewModel.Factory),
) {
    val state: AuthState by viewModel.state.collectAsStateWithLifecycle()
    val form: AuthViewModel.FormState by viewModel.form.collectAsStateWithLifecycle()
    // Activity context for the Credential Manager account-picker tray. `LocalContext.current`
    // may be a themed `ContextWrapper` rather than the Activity itself; the tray is a window,
    // so it must receive the real root `Activity` or rendering drops. `findActivity()` walks the
    // wrapper chain to unwrap it.
    val activity: Activity? = LocalContext.current.findActivity()

    LaunchedEffect(state) {
        when (state) {
            is AuthState.Success -> {
                onLoginSuccess()
                viewModel.consumeState()
            }
            is AuthState.MembershipRequired -> {
                onMembershipRequired()
                viewModel.consumeState()
            }
            is AuthState.GoogleSignUpRequired -> {
                onGoogleSignUpRequired()
                viewModel.consumeState()
            }
            else -> Unit
        }
    }

    // Non-dismissible spinner covering the network round-trip of standard sign-in and Google
    // Phase 1 (`POST /authentication/google`). It also blocks a second submission while the
    // request is in flight, and stays up until the state resolves — so after a successful login
    // control passes straight to the host's membership-resolution loader with no idle frame.
    if (state is AuthState.Loading) AuthLoadingOverlay()

    // Inset consumption strategy for the login form:
    //  - `WindowInsets.systemBars.union(WindowInsets.ime)` produces a per-edge max of the
    //    status/navigation-bar insets and the IME inset. The top is always the status-bar
    //    height; the bottom is whichever is larger at the moment: the navigation-bar inset
    //    when the keyboard is hidden, or the keyboard height when it is shown.
    //  - Applying the union via `windowInsetsPadding` BEFORE `verticalScroll` ensures the
    //    insets stay outside the scrollable area, so form fields and the primary "Sign in"
    //    button stay reachable above the keyboard and clear of the system gesture pill.
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))

        // Brand mark sourced from the adaptive launcher foreground so the same vector
        // asset is shared between the launcher icon and the in-app branding. Rendered via
        // `Image` (not `Icon`) so the vector's native gradients and multi-colour fills
        // survive — `Icon` would flatten the asset to the current `LocalContentColor`.
        //
        // The bounding box is sized for the adaptive-icon safe zone: the launcher
        // foreground vector reserves roughly an 18 % transparent margin on each edge for
        // the system's icon mask, so the visible mark only occupies ~64 % of the box.
        // 160.dp here yields a visible mark close to 100.dp, which matches the visual
        // weight of the wordmark below.
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = stringResource(R.string.cd_logo),
            modifier = Modifier.size(160.dp),
        )
        // The launcher-foreground's internal safe-zone padding already provides visual
        // separation below the mark, so the spacer is small to keep the wordmark tight.
        Spacer(Modifier.height(0.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = PrimaryNavy,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.login_welcome_back),
            style = MaterialTheme.typography.bodyLarge,
            color = AccentDark,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(40.dp))

        SoftWorkTextField(
            value = form.email,
            onValueChange = viewModel::onEmailChange,
            label = stringResource(R.string.corporate_email_label),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            isError = form.email.isNotEmpty() && !form.isEmailFormatValid,
        )

        Spacer(Modifier.height(16.dp))

        SoftWorkTextField(
            value = form.password,
            onValueChange = viewModel::onPasswordChange,
            label = stringResource(R.string.password_label),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (form.isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                PasswordVisibilityToggle(
                    isVisible = form.isPasswordVisible,
                    onToggle = viewModel::togglePasswordVisibility,
                )
            },
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.forgot_password),
            color = PrimarySky,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 4.dp)
                .clickableText(onClick = onForgotPassword),
            textAlign = TextAlign.End,
        )

        Spacer(Modifier.height(20.dp))

        // Validation relaxed for the mock-auth testing phase — both fields just need a
        // value. Real format/length checks return once the backend is integrated; the
        // helpers in AuthValidation are preserved for that switch-back.
        SoftWorkButton(
            text = stringResource(R.string.login_button),
            onClick = viewModel::submitLogin,
            enabled = state !is AuthState.Loading &&
                form.email.isNotBlank() &&
                form.password.isNotBlank(),
            variant = ButtonVariant.EMPLOYEE,
        )

        Spacer(Modifier.height(12.dp))

        GoogleOutlineButton(
            onClick = { activity?.let(viewModel::submitSignInWithGoogle) },
            enabled = state !is AuthState.Loading,
        )

        if (state is AuthState.Error) {
            Spacer(Modifier.height(12.dp))
            ErrorText(message = (state as AuthState.Error).message)
        }

        Spacer(Modifier.height(28.dp))

        Text(
            text = stringResource(R.string.register_link),
            color = AccentDark,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .clickableText(onClick = onNavigateToRegister),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ErrorText(message: String) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Tiny helper to keep clickable text declarations terse. */
private fun Modifier.clickableText(onClick: () -> Unit): Modifier =
    this.padding(vertical = 4.dp).clickable(onClick = onClick)

/**
 * Unwraps a Compose [Context] down to its host [Activity]. `LocalContext.current` is often a
 * themed [ContextWrapper] rather than the Activity itself; the Credential Manager tray is a
 * window and must be anchored to the real Activity or it silently fails to render. Walks the
 * `baseContext` chain and returns `null` if no Activity is found (defensive — should not happen
 * inside a normal Activity-hosted composable).
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
