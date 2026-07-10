package com.elysium.softwork.iam.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.elysium.softwork.shared.presentation.theme.PrimarySky

/**
 * Non-dismissible loading modal shown while an IAM network round-trip is in flight
 * ([com.elysium.softwork.iam.application.AuthState.Loading]).
 *
 * Rendered as a [Dialog] with **both** dismissal paths disabled
 * (`dismissOnBackPress = false`, `dismissOnClickOutside = false`) so the worker cannot
 * cancel or tap through it mid-request — the scrim also blocks the underlying form from
 * accepting a second submission. Hosts a single circular indicator; deliberately text-free
 * so it reads identically across the standard sign-in, Google Phase 1
 * (`POST /api/v1/authentication/google`), and Google Phase 2
 * (`POST /api/v1/authentication/sign-up/employee/google`) flows.
 *
 * The caller gates rendering on the [Loading] state, e.g.
 * `if (state is AuthState.Loading) AuthLoadingOverlay()`.
 */
@Composable
fun AuthLoadingOverlay() {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
            tonalElevation = 0.dp,
        ) {
            Box(
                modifier = Modifier.padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = PrimarySky)
            }
        }
    }
}
