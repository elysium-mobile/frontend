package com.elysium.softwork.iam.presentation.views.register

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.elysium.softwork.R
import com.elysium.softwork.iam.application.AuthState
import com.elysium.softwork.iam.presentation.viewmodel.AuthViewModel
import com.elysium.softwork.iam.presentation.components.BackTopBar
import com.elysium.softwork.shared.utils.discriminators.ButtonVariant
import com.elysium.softwork.shared.presentation.components.SoftWorkButton
import com.elysium.softwork.shared.presentation.components.SoftWorkTextField
import com.elysium.softwork.shared.presentation.theme.AccentDark
import com.elysium.softwork.shared.presentation.theme.PrimaryNavy

/**
 * Google **Phase 2** — profile-completion form for a Google-authenticated worker whose account
 * does not yet exist (Phase 1 returned `registered == false`). Collects the real profile data the
 * backend `sign-up/employee/google` endpoint requires; the verified `id_token` is already held in
 * the store, and email/password/anonymous_name are derived server-side, so none are collected here.
 *
 * On success the worker is authenticated (session persisted), so the screen hands control to
 * [onAuthComplete] for both [AuthState.Success] and [AuthState.MembershipRequired] — the host's
 * membership gate then decides between the main shell and payment onboarding.
 *
 * @param onBack pops back to the login screen (abandons the pending Google sign-up).
 * @param onAuthComplete invoked once registration completes and a session exists.
 */
@Composable
fun RegisterGoogleScreen(
    onBack: () -> Unit,
    onAuthComplete: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthViewModel = viewModel(factory = AuthViewModel.Factory),
) {
    val state: AuthState by viewModel.state.collectAsStateWithLifecycle()
    val form: AuthViewModel.GoogleSignUpForm by viewModel.googleForm.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        when (state) {
            is AuthState.Success, is AuthState.MembershipRequired -> {
                onAuthComplete()
                viewModel.consumeState()
            }
            else -> Unit
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
            .verticalScroll(rememberScrollState()),
    ) {
        BackTopBar(onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = stringResource(R.string.create_account),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = PrimaryNavy,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.google_signup_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = AccentDark,
            )

            Spacer(Modifier.height(24.dp))

            SoftWorkTextField(
                value = form.name,
                onValueChange = viewModel::onGoogleNameChange,
                label = stringResource(R.string.profile_first_name_label),
            )
            Spacer(Modifier.height(12.dp))
            SoftWorkTextField(
                value = form.lastName,
                onValueChange = viewModel::onGoogleLastNameChange,
                label = stringResource(R.string.profile_last_name_label),
            )
            Spacer(Modifier.height(12.dp))
            SoftWorkTextField(
                value = form.phoneNumber,
                onValueChange = viewModel::onGooglePhoneChange,
                label = stringResource(R.string.profile_phone_label),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
            Spacer(Modifier.height(12.dp))
            SoftWorkTextField(
                value = form.dni,
                onValueChange = viewModel::onGoogleDniChange,
                label = stringResource(R.string.profile_dni_label),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.height(12.dp))
            SoftWorkTextField(
                value = form.position,
                onValueChange = viewModel::onGooglePositionChange,
                label = stringResource(R.string.profile_position_label),
            )
            Spacer(Modifier.height(12.dp))
            SoftWorkTextField(
                value = form.salary,
                onValueChange = viewModel::onGoogleSalaryChange,
                label = stringResource(R.string.profile_salary_label),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            Spacer(Modifier.height(28.dp))

            SoftWorkButton(
                text = stringResource(R.string.create_account),
                onClick = viewModel::submitGoogleSignUp,
                enabled = state !is AuthState.Loading && form.isValid,
                variant = ButtonVariant.EMPLOYEE,
            )

            if (state is AuthState.Error) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = (state as AuthState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
