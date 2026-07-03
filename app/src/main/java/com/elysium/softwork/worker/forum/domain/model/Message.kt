package com.elysium.softwork.worker.forum.domain.model

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey

/**
 * An individual message within a [Thread] — and the offline-first cache row for replies.
 *
 * Serializer-agnostic: the backend serializes **uniform snake_case**, so every property is
 * snake_case and Gson resolves each by reflection without `@SerializedName`. Request and
 * response share the same keys, so a single field per concept covers both directions.
 *
 * **Room exception + full wire nullability.** This entity doubles as the `messages` cache row,
 * but every field — including the primary key [message_id] — is **nullable and defaults to
 * `null`**. This matters for the write path: on `POST /api/v1/messages` the client does not
 * know the id yet, and a non-null fallback (the former `= 0L`) was serialized as
 * `"message_id": 0`, which the backend rejects (`"Message ID must be a positive number."`). A
 * `null` field is dropped by Gson during reflection, so the create request omits the key
 * entirely. To let Room accept a **nullable** primary key (a plain nullable `@PrimaryKey` is
 * rejected), the key is declared `@PrimaryKey(autoGenerate = true)`: autogeneration stays
 * dormant because every cache write carries the real server id parsed from the response, while
 * the create request simply leaves it `null`.
 *
 * @property message_id primary key; `null` on the create request, filled from the response.
 * @property user_account_id author (request + response).
 * @property content_message body (request + response).
 * @property thread_id owning thread (request + response); used to filter the message set for a
 *   given thread.
 * @property attachments response-only asset list. `@Ignore`-d from Room (a `List<Asset>` has no
 *   column type without a converter); Gson still populates it from the `attachments` wire key.
 */
@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true)
    val message_id: Long? = null,
    val user_account_id: Long? = null,
    val content_message: String? = null,
    val thread_id: Long? = null,
) {
    @Ignore
    var attachments: List<Asset>? = null
}
