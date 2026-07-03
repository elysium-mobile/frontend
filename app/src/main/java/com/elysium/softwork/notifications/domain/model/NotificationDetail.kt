package com.elysium.softwork.notifications.domain.model

/**
 * The human-readable payload of a [Notification] — the annotation-free bean for the
 * `notification-details` endpoints (the *Bean / Pragmatic Shortcut*).
 *
 * The backend serializes **uniform snake_case**, so every property is snake_case and Gson maps
 * each by reflection without `@SerializedName`. The parent link uses the same `notification_id`
 * key on both request and response, so a single field covers both directions.
 *
 * @property notification_detail_id primary key of the detail row.
 * @property title short headline rendered on the notification card.
 * @property content one-line body of the notification.
 * @property notification_id parent link (request + response); used to join the detail to its
 *   owning [Notification].
 */
data class NotificationDetail(
    val notification_detail_id: Long? = null,
    val title: String? = null,
    val content: String? = null,
    val notification_id: Long? = null,
)
