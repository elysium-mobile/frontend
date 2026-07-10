package com.elysium.softwork.worker.forum.domain.model

/**
 * A forum container — the annotation-free bean for the `forums` endpoints (the
 * *Bean / Pragmatic Shortcut*).
 *
 * The backend serializes **uniform snake_case**, so every property is snake_case and Gson
 * resolves each by reflection without `@SerializedName`. Request and response share the same
 * keys, so a single field per concept covers both directions.
 *
 * @property forum_id primary key returned by every forum response.
 * @property title forum headline.
 * @property description forum description.
 * @property company_id owning company (request + response). Used client-side to isolate the feed
 *   to the signed-in worker's organization.
 * @property categories response-only nested categories (`ForumResponse.categories`), each in turn
 *   nesting its threads. `null` on the request; Gson populates it by reflection on the response so
 *   the feed can flatten a company's forums → categories → threads in one `GET /forums` round-trip.
 */
data class Forum(
    val forum_id: Long? = null,
    val title: String? = null,
    val description: String? = null,
    val company_id: Long? = null,
    val categories: List<Category>? = null,
)
