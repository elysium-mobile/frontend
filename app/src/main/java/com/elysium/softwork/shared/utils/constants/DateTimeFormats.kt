package com.elysium.softwork.shared.utils.constants

import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Canonical date-time serialization policy for the whole app.
 *
 * The backend mandates the **full ISO 8601 extended UTC instant** with a trailing `Z` zone
 * designator (`java.time.format.DateTimeFormatter.ISO_INSTANT`, e.g. `2026-07-10T14:54:23.879Z`).
 * Every outgoing timestamp string must be an absolute UTC instant — always normalized to zone
 * `Z`, never a local wall-clock value and never a bare `±HH:mm` offset. This object is the single
 * source of that policy so the Gson converter and the client-side producers ([nowIso], [format])
 * can never drift.
 *
 * `Instant` (not `LocalDateTime`) is used deliberately: it is an absolute point on the timeline,
 * so [DateTimeFormatter.ISO_INSTANT] always renders it in UTC with the `Z` suffix — exactly the
 * shape the backend expects.
 */
object DateTimeFormats {

    /**
     * SimpleDateFormat pattern equivalent for Gson's `setDateFormat`, which governs legacy
     * `java.util.Date` fields (`java.time.Instant` is handled by a dedicated ISO_INSTANT type
     * adapter in `ApiClient`, since Gson cannot serialize `Instant` natively).
     */
    const val ISO_UTC_MILLIS: String = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"

    /** Current instant rendered to ISO 8601 UTC (`…THH:mm:ss(.SSS)Z`). */
    fun nowIso(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

    /** Renders [instant] to ISO 8601 UTC (`…THH:mm:ss(.SSS)Z`). */
    fun format(instant: Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant)
}
