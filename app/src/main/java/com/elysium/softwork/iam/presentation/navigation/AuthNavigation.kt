package com.elysium.softwork.iam.presentation.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.elysium.softwork.R
import com.elysium.softwork.iam.presentation.views.login.LoginScreen
import com.elysium.softwork.iam.presentation.views.register.RegisterGoogleScreen
import com.elysium.softwork.iam.presentation.views.register.RegisterScreen
import com.elysium.softwork.iam.presentation.views.success.AuthSuccessScreen
import com.elysium.softwork.shared.presentation.navigation.PushEnter
import com.elysium.softwork.shared.presentation.navigation.PushExit
import com.elysium.softwork.shared.presentation.navigation.PushPopEnter
import com.elysium.softwork.shared.presentation.navigation.PushPopExit
import com.elysium.softwork.shared.utils.discriminators.SuccessKind

/**
 * IAM nav host. Mounted directly by `MainActivity` for the unauthenticated branch of the
 * top-level shell. Designed to also be embeddable inside a parent nav graph without
 * changes — the host owns its own `NavController` by default but accepts one as a parameter.
 *
 * Route catalog lives in [AuthRoutes]; the [SuccessKind] discriminator that drives the
 * `auth/success/{kind}` nav-arg lives in `shared/utils/discriminators/`.
 *
 * Window-inset strategy: `Modifier.windowInsetsPadding(WindowInsets.statusBars)` is
 * applied **at this host level** — once, on the structural root — so every screen the host
 * mounts lands below the status bar / notch / camera cutout without any per-screen
 * duplication.  `windowInsetsPadding` also marks the status-bar inset as consumed, so any
 * inner modifier that asks for `systemBars` (e.g. each screen's `systemBars.union(ime)`
 * for IME-aware forms) sees the top portion as already handled and only contributes the
 * remaining bottom navigation-bar / IME padding. This avoids double-padding the top and
 * keeps the inset measurement on a single parent layout pass.
 *
 * @param onAuthComplete invoked when the user reaches the success screen and taps the
 *   primary button. The hosting graph routes to the main app shell from here.
 * @param startDestination the initial route. Defaults to [AuthRoutes.LOGIN]; the host passes
 *   [AuthRoutes.REGISTER_GOOGLE] when the `HTTP 401` trap invalidated a Google-linked session,
 *   so the worker recovers through the Gmail handshake instead of the credentials form.
 */
@Composable
fun AuthNavHost(
    onAuthComplete: () -> Unit,
    navController: NavHostController = rememberNavController(),
    startDestination: String = AuthRoutes.LOGIN,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {

        composable(AuthRoutes.LOGIN) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(AuthRoutes.success(SuccessKind.LOGIN)) {
                        popUpTo(AuthRoutes.LOGIN) { inclusive = false }
                    }
                },
                // Membership interception: skip the success screen and hand control to the
                // host, which mounts the payment onboarding gate when no active membership.
                onMembershipRequired = onAuthComplete,
                onNavigateToRegister = { navController.navigate(AuthRoutes.REGISTER) },
                // "Continue with Google" triggers the native Credential Manager tray (Phase 1)
                // inside LoginScreen. When the account is new (`registered == false`), it routes
                // here to the Google profile-completion form (Phase 2).
                onGoogleSignUpRequired = { navController.navigate(AuthRoutes.REGISTER_GOOGLE) },
                onForgotPassword = { /* Forgot-password flow is not yet implemented. */ },
            )
        }

        composable(
            route = AuthRoutes.REGISTER,
            enterTransition = PushEnter,
            exitTransition = PushExit,
            popEnterTransition = PushPopEnter,
            popExitTransition = PushPopExit,
        ) {
            RegisterScreen(
                onBack = { navController.popBackStack() },
                onRegisterSuccess = {
                    navController.navigate(AuthRoutes.success(SuccessKind.REGISTER)) {
                        popUpTo(AuthRoutes.LOGIN)
                    }
                },
            )
        }

        composable(
            route = AuthRoutes.REGISTER_GOOGLE,
            enterTransition = PushEnter,
            exitTransition = PushExit,
            popEnterTransition = PushPopEnter,
            popExitTransition = PushPopExit,
        ) {
            RegisterGoogleScreen(
                onBack = { navController.popBackStack() },
                // Phase 2 completion authenticates the worker; hand control to the host so the
                // membership gate routes into the main shell or payment onboarding.
                onAuthComplete = onAuthComplete,
            )
        }

        composable(
            route = AuthRoutes.SUCCESS,
            arguments = listOf(navArgument(AuthRoutes.SUCCESS_ARG_KIND) { type = NavType.StringType }),
            enterTransition = PushEnter,
            exitTransition = PushExit,
            popEnterTransition = PushPopEnter,
            popExitTransition = PushPopExit,
        ) { backStackEntry ->
            val raw: String? = backStackEntry.arguments?.getString(AuthRoutes.SUCCESS_ARG_KIND)
            val kind: SuccessKind = SuccessKind.entries.firstOrNull { it.name == raw }
                ?: SuccessKind.LOGIN

            val title: String = stringResource(
                when (kind) {
                    SuccessKind.LOGIN -> R.string.session_started
                    SuccessKind.REGISTER -> R.string.user_registered
                },
            )
            val actionLabel: String = stringResource(
                when (kind) {
                    SuccessKind.LOGIN -> R.string.main_menu
                    SuccessKind.REGISTER -> R.string.login_button
                },
            )

            AuthSuccessScreen(
                title = title,
                actionLabel = actionLabel,
                onAction = {
                    when (kind) {
                        SuccessKind.LOGIN -> onAuthComplete()
                        SuccessKind.REGISTER -> {
                            navController.popBackStack(route = AuthRoutes.LOGIN, inclusive = false)
                        }
                    }
                },
            )
        }
    }
}
