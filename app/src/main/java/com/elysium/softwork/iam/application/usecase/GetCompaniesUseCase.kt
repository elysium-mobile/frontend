package com.elysium.softwork.iam.application.usecase

import com.elysium.softwork.iam.data.store.AuthStore
import com.elysium.softwork.iam.domain.model.Company

/**
 * Fetches the corporate directory (`GET /api/v1/companies`) for the onboarding
 * company-selection step. Stateless pass-through to [AuthStore.getCompanies].
 *
 * @param store IAM access port.
 */
class GetCompaniesUseCase(private val store: AuthStore) {

    /** @return [Result.success] with the company list, or [Result.failure] on a backend error. */
    suspend operator fun invoke(): Result<List<Company>> = store.getCompanies()
}
