package com.elysium.softwork.worker.forum.application.usecase

import com.elysium.softwork.worker.forum.domain.ForumReportStore
import com.elysium.softwork.worker.forum.domain.model.Report

/**
 * Fetches the submitted reports for the report-status screen, scoped to the signed-in worker.
 *
 * `GET /api/v1/reports` is a system-wide, unfiltered collection, so this use case enforces
 * client-side privacy scoping: only reports whose `user_account_id` equals the current session's
 * cached account id are returned. The account id is read **per call** through [accountIdProvider]
 * (wired to `SharedPrefsManager.KEY_USER_ACCOUNT_ID`), keeping the use case unit-testable without
 * an Android `Context`. A `null` account id (no session resolved yet) yields an empty list rather
 * than leaking other employees' tickets.
 *
 * Stateless; safe to share a single instance process-wide.
 *
 * @param store report data port that performs the network call.
 * @param accountIdProvider supplies the signed-in worker's `user_account_id`, or `null`.
 */
class GetForumReportsUseCase(
    private val store: ForumReportStore,
    private val accountIdProvider: () -> Long?,
) {

    /**
     * @return [Result.success] with the worker's own reports (possibly empty), or
     *   [Result.failure] on a transport error (a `400` arrives as a `BadRequestException`).
     */
    suspend operator fun invoke(): Result<List<Report>> =
        store.list().map { reports ->
            val accountId: Long = accountIdProvider() ?: return@map emptyList()
            reports.filter { it.user_account_id == accountId }
        }
}
