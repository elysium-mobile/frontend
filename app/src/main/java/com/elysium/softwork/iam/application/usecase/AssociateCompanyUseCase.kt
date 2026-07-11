package com.elysium.softwork.iam.application.usecase

import com.elysium.softwork.iam.data.store.AuthStore
import com.elysium.softwork.iam.domain.model.User

/**
 * Associates the signed-in worker's account with a membership + company
 * (`PUT /api/v1/user_accounts/{id}`) and commits the new context to local session storage.
 *
 * Stateless pass-through to [AuthStore.associateCompany], which handles retaining the account's
 * existing identity fields and persisting `company_id` / `membership_id` / `user_account_id`.
 *
 * @param store IAM access port.
 */
class AssociateCompanyUseCase(private val store: AuthStore) {

    /**
     * @param membershipId the membership just created (Phase 1 of the bypass / purchase chain).
     * @param companyId the company the worker picked.
     * @return [Result.success] with the updated account, or [Result.failure] on a backend error.
     */
    suspend operator fun invoke(membershipId: Long, companyId: Long): Result<User> =
        store.associateCompany(membershipId, companyId)
}
