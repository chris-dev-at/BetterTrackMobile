package at.bettertrack.app.data.api.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire DTOs for the notification endpoints (Step 16 → LIVE on Notifications-v2,
 * platform PR #427), matching the production OpenAPI (`api.bettertrack.at/openapi.json`,
 * probed 2026-07-11):
 *  - GET   /notifications              → [NotificationListResponse]
 *  - POST  /notifications/mark-read    → [MarkReadIdsRequest] | [MarkReadAllRequest]
 *  - POST  /notifications/devices      → [RegisterDeviceRequest]  → [DeviceAckResponse]
 *  - DELETE /notifications/devices     → [DeregisterDeviceRequest] → [DeviceAckResponse]
 *  - GET   /settings/notifications     → [NotificationSettingsResponse]
 *  - PATCH /settings/notifications     → [UpdateNotificationSettingsRequest]
 *
 * All bearer-auth (`notifications:read` for GETs, `notifications:write` for the
 * writes) — both in the mobile client's granted ceiling.
 *
 * ⚠️ Nullability rule: the shared Json has NO `coerceInputValues`, so any field
 * the server can send as an explicit `null` MUST be nullable here — a non-null
 * default does NOT rescue an explicit `null`. Verified field-by-field against the
 * live schema: inbox `readAt` + `payload` are nullable; `nextCursor` is nullable.
 */

@Serializable
data class NotificationListResponse(
    val items: List<NotificationItemDto> = emptyList(),
    /** Nullable in the schema (null ⇒ no further page). */
    val nextCursor: String? = null,
    val unreadCount: Int = 0,
)

@Serializable
data class NotificationItemDto(
    val id: String,
    val type: String,
    val title: String,
    val body: String,
    /** Nullable + optional in the schema (server sends `null` or omits it). */
    val payload: JsonElement? = null,
    /** ISO-8601; nullable in the schema ⇒ `null` means unread. */
    val readAt: String? = null,
    /**
     * ISO-8601; nullable ⇒ `null` means the row is still ACTIVE (Notifications-v3
     * #437). Present when the server auto-archived a read item or the user archived
     * it. Optional so an older/`view=active` response that omits it decodes fine.
     */
    val archivedAt: String? = null,
    val createdAt: String,
)

/** Mark specific notifications read (1–200 ids). */
@Serializable
data class MarkReadIdsRequest(val ids: List<String>)

/** Mark ALL notifications read. */
@Serializable
data class MarkReadAllRequest(val all: Boolean = true)

// ── Device-token registration (Notifications-v2 §1) ──────────────────────────

/**
 * Upsert this install's FCM token against the account.
 * `platform` is the OpenAPI enum `android | ios | web` — we always send "android".
 * The schema is `additionalProperties:false`, so send EXACTLY these two fields.
 */
@Serializable
data class RegisterDeviceRequest(val token: String, val platform: String)

/** Remove this install's FCM token (logout). Schema body is `{ token }` only. */
@Serializable
data class DeregisterDeviceRequest(val token: String)

/** Both device routes return `{ ok: true }`; we only care that it decodes 2xx. */
@Serializable
data class DeviceAckResponse(val ok: Boolean = false)

// ── Settings matrix (Notifications-v2 §3) ────────────────────────────────────

/**
 * Per-type channel preferences. The pre-v4 schema modelled FOUR required channels
 * per type (`inapp`, `email`, `push`, `webpush`); the v4 schema (platform v4 drop,
 * `settings.ts` `notificationTypeRoutingSchema.strict()`) adds `telegram` +
 * `discord`, making SIX required booleans per cell.
 *
 * Compatibility contract (round-trip rule): the app ECHOES back exactly what the
 * server sent. [telegram] + [discord] are nullable and default `null` — a pre-v4
 * GET returns four keys, so they stay `null` and, because the shared Json runs
 * `explicitNulls = false`, they are OMITTED from the PATCH body (a pre-v4 strict
 * schema would reject unknown keys). A v4 GET returns six keys → they are carried
 * verbatim → the PATCH echoes six. We never invent telegram/discord values the
 * server didn't send. `webpush` stays a browser-only channel the app never
 * surfaces but must echo (see [inapp]/[email]/[push] which the app does surface).
 */
@Serializable
data class ChannelPrefsDto(
    val inapp: Boolean,
    val email: Boolean,
    val push: Boolean,
    val webpush: Boolean,
    /** v4-only; `null` on a pre-v4 server → omitted from PATCH (explicitNulls=false). */
    val telegram: Boolean? = null,
    /** v4-only; `null` on a pre-v4 server → omitted from PATCH (explicitNulls=false). */
    val discord: Boolean? = null,
)

/**
 * Which channels the deployment can actually deliver on (v4 `channels` object,
 * `notificationChannelAvailabilitySchema`). The app renders the Telegram/Discord
 * settings columns ONLY when the matching flag is `true` (SMTP pattern — an
 * unconfigured channel never surfaces). All fields are nullable/optional: an
 * absent `channels` object (pre-v4 GET) decodes to `null` here and the app treats
 * every extra column as hidden.
 */
@Serializable
data class NotificationChannelsDto(
    val inapp: Boolean? = null,
    val email: Boolean? = null,
    val telegram: Boolean? = null,
    val discord: Boolean? = null,
    val push: Boolean? = null,
    val webpush: Boolean? = null,
)

/**
 * `GET /settings/notifications`. The live schema also returns a global `muted`
 * flag and `webPushPublicKey` — neither of which the app surfaces (there is no
 * app-side global-mute or web-push UI), so they are left unmodeled and skipped by
 * `ignoreUnknownKeys`. The per-type app matrix (in-app / email / push / telegram /
 * discord) round-trips through [matrix]; [channels] gates which optional columns
 * (telegram / discord) the settings screen shows (absent ⇒ pre-v4 ⇒ hidden).
 */
@Serializable
data class NotificationSettingsResponse(
    val matrix: Map<String, ChannelPrefsDto> = emptyMap(),
    val channels: NotificationChannelsDto? = null,
)

/**
 * `PATCH /settings/notifications`. We send only [matrix] (a subset of types is
 * allowed; each cell echoes exactly the channel keys the last GET carried — four
 * pre-v4, six on v4). We deliberately never send the global `muted` — the app's
 * per-type mute is local and must not clobber the account-wide server mute.
 */
@Serializable
data class UpdateNotificationSettingsRequest(
    val matrix: Map<String, ChannelPrefsDto>,
)
