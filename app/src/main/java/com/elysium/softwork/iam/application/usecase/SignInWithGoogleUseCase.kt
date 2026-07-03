package com.elysium.softwork.iam.application.usecase

import android.content.Context
import com.elysium.softwork.iam.data.store.AuthStore
import com.elysium.softwork.iam.domain.model.User

/**
 * Triggers the native Google (Credential Manager) sign-in and routes the resolved identity
 * through the backend dual-auth sequence.
 *
 * Thin pass-through to [AuthStore.signInWithGoogle] — the credential-tray orchestration and the
 * backend call live in the store; this use case only exists so the ViewModel depends on the
 * application layer, consistent with [LoginUseCase] / [RegisterWithGoogleUseCase].
 *
 * @param store IAM access port.
 */
class SignInWithGoogleUseCase(private val store: AuthStore) {

    /**
     * @param context an **Activity** context used to display the account-picker tray.
     * @return [Result.success] with the authenticated [User], or [Result.failure] on a user
     *   cancellation / credential error.
     */
    suspend operator fun invoke(context: Context): Result<User> = store.signInWithGoogle(context)
}
