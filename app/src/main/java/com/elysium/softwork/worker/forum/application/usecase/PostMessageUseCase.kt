package com.elysium.softwork.worker.forum.application.usecase

import com.elysium.softwork.shared.data.local.SharedPrefsManager
import com.elysium.softwork.worker.forum.data.store.ForumStore
import com.elysium.softwork.worker.forum.domain.model.Message

/**
 * Posts a message (reply) to a thread.
 *
 * Owns the request-assembly rules: the content is trimmed and the author's `user_account_id`
 * is resolved **dynamically** from [SharedPrefsManager] (cached during the post-login
 * sequential profile sync) and bound to the body. The snake_case request keys
 * (`user_account_id`, `content_message`, `thread_id`) are populated per the backend contract.
 *
 * Stateless; safe to share a single instance process-wide.
 *
 * @param store forum data port that performs the network call and caches the result.
 * @param prefs session storage holding the cached `user_account_id`.
 */
class PostMessageUseCase(
    private val store: ForumStore,
    private val prefs: SharedPrefsManager,
) {

    /**
     * Assembles and posts the message.
     *
     * @param threadId owning thread id.
     * @param content message body; trimmed before dispatch.
     * @return [Result.success] with the created [Message] or [Result.failure] (a `400` arrives
     *   as a [com.elysium.softwork.shared.data.network.BadRequestException]).
     */
    suspend operator fun invoke(threadId: Long, content: String): Result<Message> {
        val accountId: Long = prefs.getLong(SharedPrefsManager.KEY_USER_ACCOUNT_ID)
        return store.postMessage(
            Message(
                thread_id = threadId,
                user_account_id = accountId.takeIf { it != SharedPrefsManager.DEFAULT_LONG },
                content_message = content.trim(),
            ),
        )
    }
}
