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
 */
data class Category(
    val category_id: Long? = null,
    val title: String? = null,
    val description: String? = null,
    val forum_id: Long? = null,
)
