package com.elysium.softwork.worker.forum.application.usecase

import com.elysium.softwork.worker.forum.data.store.ForumStore
import com.elysium.softwork.worker.forum.domain.model.Forum

/**
 * Fetches the signed-in worker's company forum (with its nested categories) for the
 * category-selection step. The store applies the `company_id` filter, so the result is already
 * organization-scoped. Stateless; safe to share a single instance process-wide.
 *
 * @param store forum data port that performs the network call and the company filter.
 */
class GetCompanyForumUseCase(private val store: ForumStore) {

    /** @return [Result.success] with the company [Forum] (or `null` if none), else failure. */
    suspend operator fun invoke(): Result<Forum?> = store.getCompanyForum()
}
