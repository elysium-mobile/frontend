package com.elysium.softwork.worker.forum.domain.model

/**
 * A content/conduct report — the annotation-free bean for the `reports` endpoints (the
 * *Bean / Pragmatic Shortcut*). Replaces the former flat `ForumReport`.
 *
 * The backend serializes **uniform snake_case**, so every property is snake_case and Gson
 * resolves each by reflection without `@SerializedName`. Request and response share the same
 * keys, so a single field per concept covers both directions.
 *
 * @property report_id primary key returned by every report response.
 * @property reason short categorized reason for the report.
 * @property description detailed explanation.
 * @property user_account_id reporting account (request + response).
 * @property report_date incident/report date (request + response).
 * @property area_company_id reported area (request + response).
 */
data class Report(
    val report_id: Long? = null,
    val reason: String? = null,
    val description: String? = null,
    val user_account_id: Long? = null,
    val report_date: String? = null,
    val area_company_id: Long? = null,
)
