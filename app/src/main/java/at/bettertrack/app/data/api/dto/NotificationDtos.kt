package at.bettertrack.app.data.api.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire DTOs for the notification endpoints (Step 16, §6.11), mirroring the
 * production OpenAPI:
 *  - GET  /notifications                → [NotificationListResponse]
 *  - POST /notifications/mark-read      → [MarkReadIdsRequest] | [MarkReadAllRequest]
 *  - GET  /settings/notifications       → [NotificationSettingsResponse]
 *  - PATCH /settings/notifications      → [UpdateNotificationSettingsRequest]
 *
 * The server matrix models only the `inapp` + `email` channels per type; the
 * app's Push + Mute columns are local-only (no server push-channel yet). Runtime
 * auth uses the OAuth bearer (the OpenAPI `security` sessionCookie annotation is
 * the known docs bug); these routes additionally need a notifications read scope
 * the mobile client does not yet hold — see docs/TODO.md.
 */

@Serializable
data class NotificationListResponse(
    val items: List<NotificationItemDto> = emptyList(),
    val nextCursor: String? = null,
    val unreadCount: Int = 0,
)

@Serializable
data class NotificationItemDto(
    val id: String,
    val type: String,
    val title: String,
    val body: String,
    val payload: JsonElement? = null,
    /** ISO-8601; null ⇒ unread. */
    val readAt: String? = null,
    val createdAt: String,
)

/** Mark specific notifications read (1–200 ids). */
@Serializable
data class MarkReadIdsRequest(val ids: List<String>)

/** Mark ALL notifications read. */
@Serializable
data class MarkReadAllRequest(val all: Boolean = true)

/** Per-type in-app/email channel preference (the two channels the server models). */
@Serializable
data class ChannelPrefsDto(val inapp: Boolean, val email: Boolean)

@Serializable
data class NotificationSettingsResponse(
    val matrix: Map<String, ChannelPrefsDto> = emptyMap(),
)

@Serializable
data class UpdateNotificationSettingsRequest(
    val matrix: Map<String, ChannelPrefsDto>,
)
