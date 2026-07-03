package com.elysium.softwork.worker.forum.application.usecase

import com.elysium.softwork.worker.forum.data.store.ForumStore
import com.elysium.softwork.worker.forum.domain.model.Thread
import java.time.LocalDate

/**
 * Creates a new discussion thread.
 *
 * Owns the request-assembly rules: the title is trimmed, the message count seeds at `0`, and
 * `last_message` defaults to today (ISO `yyyy-MM-dd`). The snake_case request keys are bound
 * per the backend contract.
 *
 * Note: the backend `thread` create contract carries no worker identifier (it keys off
 * `area_company_id` / `category_id`), so — unlike [PostMessageUseCase] and
 * [SubmitForumReportUseCase] — there is no `user_account_id` / `employee_profile_id` to bind
 * here. The owning area/category are forwarded when the caller can supply them.
 *
 * Stateless; safe to share a single instance process-wide.
 *
 * @param store forum data port that performs the network call and caches the result.
 */
class CreateThreadUseCase(private val store: ForumStore) {

    /**
     * Assembles and creates the thread.
     *
     * @param title thread headline; trimmed before dispatch.
     * @param categoryId owning category id, when known.
     * @param areaCompanyId owning area id, when known.
     * @return [Result.success] with the created [Thread] or [Result.failure] (a `400` arrives
     *   as a [com.elysium.softwork.shared.data.network.BadRequestException]).
     */
    suspend operator fun invoke(
        title: String,
        categoryId: Long? = null,
        areaCompanyId: Long? = null,
    ): Result<Thread> = store.createThread(
        Thread(
            title = title.trim(),
            category_id = categoryId,
            area_company_id = areaCompanyId,
            last_message = LocalDate.now().toString(),
            message_count = 0,
        ),
    )
}
