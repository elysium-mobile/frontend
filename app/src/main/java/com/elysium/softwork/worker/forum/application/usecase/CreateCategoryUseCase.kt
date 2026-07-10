package com.elysium.softwork.worker.forum.application.usecase

import com.elysium.softwork.worker.forum.data.store.ForumStore
import com.elysium.softwork.worker.forum.domain.model.Category

/**
 * Creates a new category under the worker's company forum (`POST /api/v1/categories`).
 *
 * Owns the request-assembly rules: the title/description are trimmed and the owning `forum_id`
 * is bound per the backend contract. Stateless; safe to share a single instance process-wide.
 *
 * @param store forum data port that performs the network call.
 */
class CreateCategoryUseCase(private val store: ForumStore) {

    /**
     * @param title category headline; trimmed before dispatch.
     * @param forumId owning forum id.
     * @param description optional detail; trimmed, defaults to blank.
     * @return [Result.success] with the created [Category] or [Result.failure] (a `400` arrives
     *   as a [com.elysium.softwork.shared.data.network.BadRequestException]).
     */
    suspend operator fun invoke(
        title: String,
        forumId: Long,
        description: String = "",
    ): Result<Category> = store.createCategory(
        Category(
            title = title.trim(),
            description = description.trim(),
            forum_id = forumId,
        ),
    )
}
