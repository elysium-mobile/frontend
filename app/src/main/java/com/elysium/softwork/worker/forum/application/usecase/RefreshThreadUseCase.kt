package com.elysium.softwork.worker.forum.application.usecase

import com.elysium.softwork.worker.forum.data.store.ForumStore
import com.elysium.softwork.worker.forum.domain.model.Thread

/**
 * Refreshes a single thread from the thread-detail route (`GET /api/v1/threads/{id}`).
 *
 * Backs the pull-to-refresh gesture on the thread screen: one round-trip updates the cached
 * thread header **and** the nested `message_responses`, so the observed message stream re-emits
 * the latest replies. Stateless; safe to share a single instance process-wide.
 *
 * @param store forum data port that owns the network call and the cache writes.
 */
class RefreshThreadUseCase(private val store: ForumStore) {

    /**
     * @param threadId thread to refresh.
     * @return [Result.success] with the refreshed [Thread], [Result.failure] on transport error
     *   (a backend `400` arrives as a `BadRequestException`).
     */
    suspend operator fun invoke(threadId: Long): Result<Thread> = store.refreshThread(threadId)
}
