package com.elysium.softwork.worker.forum.domain.model

/**
 * A forum category — the annotation-free bean for the `categories` endpoints (the
 * *Bean / Pragmatic Shortcut*).
 *
 * The backend serializes **uniform snake_case**, so every property is snake_case and Gson
 * resolves each by reflection without `@SerializedName`. Request and response share the same
 * keys, so a single field per concept covers both directions.
 *
 * @property category_id primary key returned by every category response.
 * @property title category headline.
 * @property description category description.
 * @property forum_id owning forum (request + response).
 * @property threads response-only nested threads (`CategoryResponse.threads`). `null` on the
 *   request; Gson populates it on the response so the feed can flatten a company's threads without
 *   a second round-trip.
 */
data class Category(
    val category_id: Long? = null,
    val title: String? = null,
    val description: String? = null,
    val forum_id: Long? = null,
    val threads: List<Thread>? = null,
)
