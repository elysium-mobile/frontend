package com.elysium.softwork.worker.forum.application.usecase

import com.elysium.softwork.shared.data.local.SharedPrefsManager
import com.elysium.softwork.worker.forum.domain.ForumReportStore
import com.elysium.softwork.worker.forum.domain.model.Report
import java.time.LocalDate

/**
 * Submits a content/conduct report to `POST /api/v1/reports`.
 *
 * Owns the request-assembly rules: the free-text fields are trimmed, the reporting
 * `user_account_id` is resolved **dynamically** from [SharedPrefsManager] (cached during the
 * post-login sequential profile sync) and bound to the body, and the report date defaults to
 * today (ISO `yyyy-MM-dd`). The snake_case request keys (`user_account_id`, `report_date`,
 * `area_company_id`) are populated per the backend contract.
 *
 * Stateless; safe to share a single instance process-wide.
 *
 * @param store report data port that performs the network call.
 * @param prefs session storage holding the cached `user_account_id`.
 */
class SubmitForumReportUseCase(
    private val store: ForumReportStore,
    private val prefs: SharedPrefsManager,
) {

    /**
     * Builds and submits the report.
     *
     * @param reason short categorized reason for the report; trimmed before dispatch.
     * @param description detailed explanation; trimmed before dispatch.
     * @param reportDate report date; defaults to today when blank.
     * @param areaCompanyId reported area id, when known.
     * @return [Result.success] with the server-acknowledged [Report] or [Result.failure] (a
     *   `400` arrives as a [com.elysium.softwork.shared.data.network.BadRequestException]).
     */
    suspend operator fun invoke(
        reason: String,
        description: String,
        reportDate: String = LocalDate.now().toString(),
        areaCompanyId: Long? = null,
    ): Result<Report> {
        val accountId: Long = prefs.getLong(SharedPrefsManager.KEY_USER_ACCOUNT_ID)
        return store.submit(
            Report(
                reason = reason.trim(),
                description = description.trim(),
                user_account_id = accountId.takeIf { it != SharedPrefsManager.DEFAULT_LONG },
                report_date = reportDate.ifBlank { LocalDate.now().toString() },
                area_company_id = areaCompanyId,
            ),
        )
    }
}
