package com.elysium.softwork.iam.application.usecase

import com.elysium.softwork.iam.data.store.AuthStore
import com.elysium.softwork.iam.domain.model.User

/**
 * Google Phase 2 — completes registration for a Google-authenticated worker, packing the profile
 * fields alongside the `id_token` stashed by [SignInWithGoogleUseCase] (Phase 1).
 *
 * Pass-through to [AuthStore.completeGoogleSignUp]; the store owns the pending-token bookkeeping
 * and the `POST /sign-up/employee/google` call. No email/password/anonymous_name are collected —
 * the backend derives them from the verified token.
 *
 * @param store IAM access port.
 */
class CompleteGoogleSignUpUseCase(private val store: AuthStore) {

    /** @return [Result.success] with the authenticated [User], or [Result.failure] (e.g. a `400`). */
    suspend operator fun invoke(
        name: String,
        lastName: String,
        phoneNumber: String,
        dni: String,
        dateStart: String,
        position: String,
        salary: Int,
    ): Result<User> = store.completeGoogleSignUp(
        name = name,
        lastName = lastName,
        phoneNumber = phoneNumber,
        dni = dni,
        dateStart = dateStart,
        position = position,
        salary = salary,
    )
}
