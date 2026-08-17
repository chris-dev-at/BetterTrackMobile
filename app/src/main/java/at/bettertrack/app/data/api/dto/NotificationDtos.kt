package at.bettertrack.app.data.api.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

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
 * The deployment kill-switch for the two optional channels. Absent (or both false)
 * ⇒ the app renders no Telegram/Discord setup at all, because the routes 404.
 */
@Serializable
data class NotificationChannelsConfigurableDto(
    val telegram: Boolean? = null,
    val discord: Boolean? = null,
)

// ── Telegram linking (`/settings/telegram*`, scope social:read | social:write) ──

/**
 * `GET|POST /settings/telegram…` state.
 *
 * ⚠️ [pendingCode] is populated on the `/link` response and **nowhere else** — a
 * plain GET always returns `null` for it (the server does not re-issue a code it
 * already handed out). The deep link `https://t.me/{botUsername}?start={code}`
 * therefore cannot be rebuilt from a refetch: the app has to hold the code from the
 * POST in memory for the ten minutes it lives.
 */
@Serializable
data class TelegramSettingsDto(
    /** Deployment: the kill-switch is on AND a bot token is configured. */
    val available: Boolean = false,
    val linked: Boolean = false,
    /** An unexpired link code exists for this user. */
    val pending: Boolean = false,
    /** `…1234` — the server masks it to the last four characters. Never the raw id. */
    val chatIdMasked: String? = null,
    val botUsername: String? = null,
    /** ≤24 chars, 10-minute TTL. Only ever non-null on the `/link` response. */
    val pendingCode: String? = null,
    val pendingExpiresAt: String? = null,
)

/**
 * `POST /settings/telegram/confirm`.
 *
 * Confirm is an on-demand POLL: the server asks the Bot API for updates and looks
 * for the `/start <code>` message. A miss is **not an error** — it is
 * `200 { linked: false }`, meaning "not yet, press it again once you've hit Start".
 * Treating that as a failure is the single easiest way to get this flow wrong.
 */
@Serializable
data class TelegramConfirmResponse(
    val linked: Boolean = false,
    val settings: TelegramSettingsDto? = null,
)

// ── Discord webhook (`/settings/discord*`, scope social:read | social:write) ────

/**
 * `GET|POST|DELETE /settings/discord…` state. The webhook URL is encrypted at rest
 * and **never** read back — [webhookIdMasked] (`…abcd`, the last four characters of
 * the webhook snowflake) is all the server will ever return.
 */
@Serializable
data class DiscordSettingsDto(
    /** True whenever the deployment kill-switch is on. */
    val available: Boolean = false,
    /** Per user: a webhook row exists. */
    val linked: Boolean = false,
    val webhookIdMasked: String? = null,
    val configuredAt: String? = null,
)

/**
 * `POST /settings/discord/webhook` — `{ url }`, 1..2048 chars.
 *
 * The server validates in two layers and the second one matters: after the schema
 * check (https, a discord.com-family host, a `/api/webhooks/` path) it **actually
 * posts a test message to the candidate URL** and only persists it if Discord
 * accepts. So a 400 here can mean "that is not a webhook URL" or "Discord rejected
 * a URL that looks fine", and the two get different copy.
 */
@Serializable
data class DiscordWebhookRequest(val url: String)

/** `POST /settings/discord/test` → `{ ok: true }`. */
@Serializable
data class DiscordTestResponse(val ok: Boolean = false)

/**
 * Quiet hours as the v5 GET returns them (`quietHoursSchema`): an outbound-only
 * delivery window.
 *
 * Every field is nullable/optional because a v5 GET always carries all four with
 * [timezone] possibly an explicit `null` (⇒ the server evaluates the window in
 * UTC), and the shared Json has no `coerceInputValues`.
 *
 * Minutes are 0..1439 minute-of-day. `startMinute > endMinute` is an OVERNIGHT
 * window (the default is 1320→420, i.e. 22:00→07:00). [timezone] is an IANA id
 * validated server-side.
 *
 * ⚠️ This is the READ shape only. The PATCH shape is [QuietHoursPatchDto] — see
 * its KDoc for why they cannot be the same type.
 */
@Serializable
data class QuietHoursDto(
    val enabled: Boolean? = null,
    val startMinute: Int? = null,
    val endMinute: Int? = null,
    val timezone: String? = null,
)

/**
 * Quiet hours as the app WRITES them. `quietHours` is the one field-partial object
 * in the settings schema (`{ enabled?, startMinute?, endMinute?, timezone? }`),
 * unlike `matrix`/`cadence` whose maps are strict — so a DTO built with only the
 * CHANGED fields serializes, under `explicitNulls = false`, to exactly those keys.
 * That is how the app never restates a quiet-hours field it does not mean to
 * change.
 *
 * ## Why [timezone] is a [JsonElement] and not a `String?`
 *
 * There are THREE things the app needs to say about the zone, and a `String?`
 * can only say two of them under `explicitNulls = false`:
 *
 *  - **"leave it alone"** → Kotlin `null` → the key is DROPPED from the body.
 *  - **"set it to this IANA id"** → `JsonPrimitive("Europe/Vienna")` → `"…"`.
 *  - **"CLEAR it"** → [JsonNull] → the key is present with the literal value
 *    `null`, which is what tells the server to fall back to UTC.
 *
 * With a `String?` the third case is indistinguishable from the first: the
 * encoder drops any Kotlin `null`, so a "clear the zone" patch would silently
 * become a no-op body (and an all-null body is an empty `{}`, itself a 400).
 * [JsonNull] is a non-null Kotlin value, so the null-dropping rule does not
 * apply to it and the explicit `null` actually reaches the wire.
 * `DeliverySettingsTest` pins both bytes.
 */
@Serializable
data class QuietHoursPatchDto(
    val enabled: Boolean? = null,
    val startMinute: Int? = null,
    val endMinute: Int? = null,
    /** Omitted / [JsonNull] / a `JsonPrimitive` id — see the class KDoc. */
    val timezone: JsonElement? = null,
)

/**
 * `GET /settings/notifications`. The live schema also returns `webPushPublicKey`,
 * which the app does not surface (no web-push UI), so it stays unmodeled and is
 * skipped by `ignoreUnknownKeys`. The per-type matrix (in-app / email / push /
 * telegram / discord) round-trips through [matrix] and still governs LOCAL push
 * display even though the app no longer edits it (that moved to the web);
 * [channels] gates which optional columns exist server-side.
 *
 * [cadence], [quietHours] and [muted] follow the same echo-verbatim rule as
 * telegram/discord: NULLABLE, so a server that does not model one leaves it
 * `null` — the app then HIDES the matching control entirely and can never put
 * that key in a PATCH body (a `.strict()` schema would 400 on an unknown
 * top-level key).
 */
@Serializable
data class NotificationSettingsResponse(
    val matrix: Map<String, ChannelPrefsDto> = emptyMap(),
    /** v5-only: `{<type>: "instant"|"daily"|"weekly"}` for EVERY type. `null` ⇒ pre-v5. */
    val cadence: Map<String, String>? = null,
    /** v5-only outbound quiet window. `null` ⇒ pre-v5. */
    val quietHours: QuietHoursDto? = null,
    val channels: NotificationChannelsDto? = null,
    /**
     * The DEPLOYMENT kill-switch for the two optional channels
     * (`BT_TELEGRAM_DISCORD_ENABLED`, default **off**). Distinct from [channels] in
     * a way that is load-bearing and easy to get wrong:
     *
     *  - [channels] `telegram`/`discord` are **per user** — true only once this user
     *    has actually confirmed a chat / saved a webhook. They gate the matrix
     *    COLUMN, because an unlinked channel cannot deliver anything.
     *  - [channelsConfigurable] is the deployment switch. It gates whether the
     *    SETUP UI exists at all.
     *
     * Gating setup on [channels] would be a chicken-and-egg: the card that links
     * Telegram would only appear once Telegram was already linked. The web gates
     * its Channels group on this field, and so does the app. When the switch is
     * off, every route under `/settings/telegram` and `/settings/discord` answers
     * a bare 404, so an app that showed the cards anyway would be offering
     * controls that cannot work.
     */
    val channelsConfigurable: NotificationChannelsConfigurableDto? = null,
    /**
     * VAPID key for BROWSER push. Modelled only so its presence can be read; the
     * app never subscribes (that needs a service worker and a `PushManager`, i.e. a
     * browser). Android's push channel is `push` (FCM), registered through
     * `POST /notifications/devices`.
     */
    val webPushPublicKey: String? = null,
    /**
     * The ACCOUNT-WIDE mute — the web's single "silence everything" switch. It has
     * always been in the live GET body; the app used to read past it because it had
     * no analogue to show. It now drives the one mute switch the app has, and the
     * tri-state is load-bearing: `null` ⇒ this server does not model an account
     * mute ⇒ the switch is not rendered at all (never a fabricated `false`).
     */
    val muted: Boolean? = null,
)

/**
 * `PATCH /settings/notifications` — the body is `.strict()` at EVERY level, and an
 * empty `{}` is a 400, so the app sends exactly the keys it means to change and
 * never fires a no-op patch.
 *
 * Every field is nullable and defaults to `null`: with the shared Json's
 * `explicitNulls = false` an untouched field is simply DROPPED from the body. That
 * is what makes one request type serve a matrix-only patch (pre-v4/v4/v5 servers
 * alike), a cadence-only patch, a quiet-hours-only patch and a mute-only patch.
 *
 * [matrix] cells must carry ALL channel keys the server modelled (six on v4+);
 * [cadence] is a strict map, so only types the last GET actually carried may
 * appear.
 */
@Serializable
data class UpdateNotificationSettingsRequest(
    val matrix: Map<String, ChannelPrefsDto>? = null,
    val cadence: Map<String, String>? = null,
    val quietHours: QuietHoursPatchDto? = null,
    /**
     * The account-wide mute, sent ONLY when the user flips the switch — and the
     * switch only exists when the last GET actually carried a `muted` flag, so a
     * server that does not model it can never see this key.
     */
    val muted: Boolean? = null,
)
