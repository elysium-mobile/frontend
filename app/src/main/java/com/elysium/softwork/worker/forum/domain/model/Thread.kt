package com.elysium.softwork.worker.forum.domain.model

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey

/**
 * A forum discussion thread — the core topic entity, and the offline-first cache row.
 *
 * Serializer-agnostic: the backend serializes **uniform snake_case**, so every property is
 * snake_case and Gson resolves each by reflection without `@SerializedName`. Request and
 * response share the same keys, so a single field per concept covers both directions.
 *
 * **Documented Room exception (mirrors the former `Post`).** This entity doubles as the
 * `threads` cache row so the feed renders offline-first without a per-emission mapper. Per
 * the established cache convention, the primary key [thread_id] is a **non-null** `Long`
 * defaulting to `0L` (Room rejects nullable primary keys); Gson overwrites the default with
 * the real id during deserialization. All other columns stay nullable to match the wire.
 *
 * @property thread_id primary key (cache row id + backend `thread_id`).
 * @property title thread headline shown in the feed.
 * @property area_company_id owning area (request + response).
 * @property last_message last-activity date (request + response).
 * @property category_id owning category (request + response).
 * @property message_count reply count (request + response).
 * @property message_responses response-only nested reply list returned by the thread-detail
 *   route (`GET /api/v1/threads/{id}` → `ThreadResponse`). `@Ignore`-d from Room (a
 *   `List<Message>` has no column type without a converter, and replies are persisted in the
 *   separate `messages` table); Gson still populates it from the `message_responses` wire key so
 *   a thread refresh can upsert the nested replies into the message cache in one round-trip.
 */
@Entity(tableName = "threads")
data class Thread(
    @PrimaryKey
    val thread_id: Long = 0L,
    val title: String? = null,
    val area_company_id: Long? = null,
    val last_message: String? = null,
    val category_id: Long? = null,
    val message_count: Int? = null,
) {
    @Ignore
    var message_responses: List<Message>? = null
}
