package com.elysium.softwork.shared.utils.constants

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Canonical date-time serialization policy for the whole app.
 *
 * The backend mandates the **extended ISO 8601 local pattern without any zone/offset**
 * (`yyyy-MM-dd'T'HH:mm:ss`, e.g. `2026-12-31T23:59:59`). Every outgoing timestamp string must
 * match it exactly — no fractional seconds, no trailing `Z`, no `±HH:mm` offset. This object is
 * the single source of that pattern so the Gson converter ([ISO_LOCAL_DATE_TIME]) and the
 * client-side formatters ([nowIso], [format]) can never drift.
 *
 * `LocalDateTime` (not `Instant`/`ZonedDateTime`) is used deliberately: it carries no zone, so
 * formatting it emits no offset text. Using `Instant.now().toString()` instead produces
 * `…THH:mm:ss.SSSZ` — the fractional seconds + `Z` the backend rejects.
 */
object DateTimeFormats {

    /** The backend's mandated pattern, exposed for Gson's `setDateFormat` and any ad-hoc use. */
    const val ISO_LOCAL_DATE_TIME: String = "yyyy-MM-dd'T'HH:mm:ss"

    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern(ISO_LOCAL_DATE_TIME)

    /** Current local date-time rendered to [ISO_LOCAL_DATE_TIME] (no zone/offset). */
    fun nowIso(): String = LocalDateTime.now().format(formatter)

    /** Renders [dateTime] to [ISO_LOCAL_DATE_TIME] (no zone/offset). */
    fun format(dateTime: LocalDateTime): String = dateTime.format(formatter)
}
