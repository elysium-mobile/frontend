package com.elysium.softwork

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elysium.softwork.iam.presentation.navigation.AuthNavHost
import com.elysium.softwork.payment.membership.presentation.navigation.NoPaymentGraphExit
import com.elysium.softwork.payment.membership.presentation.navigation.PaymentOnboardingHost
import com.elysium.softwork.shared.core.ServiceLocator
import com.elysium.softwork.shared.presentation.navigation.MainNavHost
import com.elysium.softwork.shared.presentation.theme.SoftWorkTheme
import com.elysium.softwork.shared.utils.discriminators.SessionRecovery

/**
 * Single Activity hosting the entire Compose UI tree.
 *
 * Inherits from [AppCompatActivity] so that [androidx.appcompat.app.AppCompatDelegate.setApplicationLocales]
 * automatically recreates the activity with the new configuration on API 29-32 (the AppCompat
 * back-port path). On API 33+ the platform `LocaleManager` handles recreation transparently;
 * the AppCompat dependency is harmless on those versions.
 *
 * The Activity hands off all routing to [AppRoot], which is what inspects the
 * authentication and membership flags and chooses between the auth host, the payment
 * onboarding host, and the main shell. Keeping the routing in a child composable means
 * the outer [Surface] measures and paints the brand background before any state-flow
 * collector is attached — avoiding a transient black frame on slower devices where the
 * collector would otherwise compete with the initial layout pass.
 */
class MainActivity : AppCompatActivity() {

    /**
     * Service locator resolved once on first access against [SoftWorkApplication].
     *
     * Held at the Activity scope rather than inside a composable so the cast and
     * dereference happen exactly once — not on every recomposition of [AppRoot]. The
     * `by lazy` defers the first read until [AppRoot]'s first composition; the
     * [SoftWorkApplication] field `serviceLocator` is itself populated synchronously by
     * the Application's `onCreate`, so this access is non-blocking.
     */
    private val locator: ServiceLocator by lazy {
        (application as SoftWorkApplication).serviceLocator
    }

    /**
     * Activity entry point.
     *
     * Statement order is significant:
     *  1. [enableEdgeToEdge] is invoked **before** [AppCompatActivity.onCreate] so the
     *     transparent system-bar configuration is installed on the window decor before
     *     the platform attaches the Activity's content view. Calling it later allows the
     *     platform to paint one frame with the theme's opaque status / navigation bar
     *     over a yet-undrawn Compose tree.
     *  2. `super.onCreate(savedInstanceState)` runs immediately after, so the rest of
     *     the Activity lifecycle proceeds normally.
     *  3. [setContent] installs the Compose tree with [SoftWorkTheme] at the root and a
     *     full-screen [Surface] as the **immediate** child. The surface guarantees the
     *     window paints the brand background on the very first frame, even if the
     *     downstream routing composable takes a frame to settle its state collectors.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SoftWorkTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot()
                }
            }
        }
    }

    /**
     * Top-level routing composable.
     *
     * Lifted out of [onCreate]'s `setContent` block so the initial layout pass of the
     * outer [Surface] is not blocked by the seeded `SharedPreferences` read used to
     * derive the initial value of the [rememberSaveable] authentication flag or by the
     * attachment of the [collectAsStateWithLifecycle] collector on the membership flow.
     * Both happen inside this child composable's first composition, by which point the
     * parent surface has already been measured and painted.
     *
     * The routing fans out into one of three top-level hosts based on two boolean
     * flags:
     *  - [AuthNavHost] when the worker is unauthenticated.
     *  - [PaymentOnboardingHost] when authenticated without an active membership.
     *  - [MainNavHost] when authenticated AND a membership is active.
     *
     * **No recomposition loop is possible here.** The two flags are never mutated from
     * inside a composable body — only from inside lambdas passed to children
     * ([onAuthComplete], [onLogout]) and from inside a `LaunchedEffect` scheduled by
     * `PaymentSuccessScreen` after membership activation. Both mechanisms write the
     * state *after* composition completes, so no branch of the `when` below can
     * trigger an immediate re-entry into itself.
     *
     * The membership flag is collected from the application-wide membership store. The
     * host swap on cancel / activation therefore happens reactively without any
     * explicit `popUpTo`.
     */
    @Composable
    private fun AppRoot() {
        // `rememberSaveable`'s lambda runs only when no saved state exists, so the
        // SharedPreferences read is not on the recomposition hot path. Subsequent
        // compositions read the snapshotted boolean.
        var isAuthenticated: Boolean by rememberSaveable {
            mutableStateOf(locator.authStore.activeToken() != null)
        }
        // StateFlow.collectAsStateWithLifecycle reads the flow's current value
        // synchronously on first composition, then attaches a collector tied to the
        // host Activity's lifecycle. The initial value is therefore always available
        // for the very first paint — no suspension and no transient default state.
        val hasMembership: Boolean by locator.membershipStore
            .hasMembership
            .collectAsStateWithLifecycle()

        // HTTP 401 unified trap: a mid-session token rejection (e.g. GET /membership-plans)
        // raises this signal from the OkHttp interceptor. React by dropping to the auth host and
        // immediately consuming the signal so it does not re-fire on the next recomposition. The
        // interceptor already wiped the persisted session, so re-auth starts from a clean slate.
        // Both credentials and Google sessions recover through the LoginScreen — a Google worker
        // simply re-taps "Continue with Google" (Phase 1), so no distinct start destination is
        // needed (SessionRecovery.GOOGLE vs CREDENTIALS no longer changes the route).
        val sessionRecovery: SessionRecovery by locator.authStore
            .sessionRecovery
            .collectAsStateWithLifecycle()
        LaunchedEffect(sessionRecovery) {
            if (sessionRecovery != SessionRecovery.NONE) {
                isAuthenticated = false
                locator.authStore.consumeSessionInvalidation()
            }
        }

        // Membership-resolution gate. Guards against the payment-onboarding flicker: without it,
        // the instant `isAuthenticated` flips true the `when` below would evaluate against the
        // *stale* local `hasMembership` flag (false for a fresh login) and mount
        // `PaymentOnboardingHost` for a frame, before the async validation confirms an active
        // subscription and swaps to the main shell. `membershipChecked` starts false and only
        // becomes true once `validateMembershipUseCase()` has synced the gate from the server —
        // until then the routing is held on a neutral loader (see the `when`), never on payment.
        // Deliberately a plain `remember` (not `rememberSaveable`): a fresh composition — cold
        // start or Activity recreation — must re-validate against the server rather than trust a
        // restored flag, so the gate is authoritative every time.
        var membershipChecked: Boolean by remember { mutableStateOf(false) }

        // Session-authorization membership check: on cold-start and immediately after login,
        // validate the worker's subscription (cached `membership_id` → GET /memberships/{id},
        // status + validity window). The use case syncs the reactive `hasMembership` gate; only
        // once it returns is `membershipChecked` flipped so the router may leave the loader. On
        // logout (`isAuthenticated` false) the gate resets so the next login re-validates cleanly.
        LaunchedEffect(isAuthenticated) {
            if (isAuthenticated) {
                // `finally` guarantees the router leaves the loader even if validation throws
                // unexpectedly — the use case is best-effort (a failure closes the gate), so a
                // thrown error must still resolve to a route rather than strand the spinner.
                try {
                    locator.validateMembershipUseCase()
                } finally {
                    membershipChecked = true
                }
            } else {
                membershipChecked = false
            }
        }

        // Stable callback references — cached so child hosts do not re-bind their
        // handlers on every parent recomposition.
        val onAuthComplete: () -> Unit = remember { { isAuthenticated = true } }
        val onLogout: () -> Unit = remember {
            {
                locator.authStore.clearSession()
                isAuthenticated = false
            }
        }
        val userName: String = stringResource(R.string.home_user_name_placeholder)

        when {
            !isAuthenticated -> AuthNavHost(onAuthComplete = onAuthComplete)
            // Hold on a neutral loader while the just-authenticated session's membership is being
            // validated. This bypasses the intermediate payment-screen frame entirely: routing
            // resumes only once `membershipChecked` is true, by which point `hasMembership` is the
            // server-confirmed value, so the very next branch lands on the correct final host.
            !membershipChecked -> AuthResolvingScreen()
            !hasMembership -> PaymentOnboardingHost(onExitToMainShell = NoPaymentGraphExit)
            else -> MainNavHost(userName = userName, onLogout = onLogout)
        }
    }

    /**
     * Full-screen neutral loader shown during the post-authentication membership-resolution
     * window. Renders on the brand background (the parent [Surface] already painted it) with a
     * single centred indicator, so the transition from the auth screen's loading overlay to the
     * final destination reads as one continuous spinner — no payment-onboarding flicker.
     */
    @Composable
    private fun AuthResolvingScreen() {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
}
