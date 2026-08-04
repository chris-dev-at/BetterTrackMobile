package at.bettertrack.app.data.notifications

import at.bettertrack.app.data.api.dto.ChannelPrefsDto
import at.bettertrack.app.data.api.dto.DeregisterDeviceRequest
import at.bettertrack.app.data.api.dto.DeviceAckResponse
import at.bettertrack.app.data.api.dto.NotificationItemDto
import at.bettertrack.app.data.api.dto.NotificationListResponse
import at.bettertrack.app.data.api.dto.NotificationSettingsResponse
import at.bettertrack.app.data.api.dto.RegisterDeviceRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-contract tests for the Notifications-v2 DTOs against the exact production
 * OpenAPI shapes (probed 2026-07-11). Uses the app's real Json config so encode /
 * decode matches on-device behavior — including the "explicit null crashes a
 * non-null field" trap that the shared Json (no coerceInputValues) does not save.
 */
class NotificationWireTest {

    // Mirror AppGraph.json exactly.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // ── Device-token requests: exact bodies, no stray fields ────────────────

    @Test fun `register device serializes to exactly token + platform`() {
        val body = json.encodeToString(RegisterDeviceRequest(token = "fcm-abc", platform = "android"))
        // Schema is additionalProperties:false — anything extra would 400.
        assertEquals("""{"token":"fcm-abc","platform":"android"}""", body)
    }

    @Test fun `deregister device serializes to exactly token`() {
        val body = json.encodeToString(DeregisterDeviceRequest(token = "fcm-abc"))
        assertEquals("""{"token":"fcm-abc"}""", body)
    }

    @Test fun `device ack decodes the ok true body`() {
        val ack = json.decodeFromString<DeviceAckResponse>("""{"ok":true}""")
        assertTrue(ack.ok)
    }

    // ── Inbox item nullability (the exact bug that crashed alerts once) ──────

    @Test fun `inbox item decodes explicit null readAt and payload without crashing`() {
        val row = """
            {"id":"11111111-1111-1111-1111-111111111111","type":"alert.triggered",
             "title":"Price alert","body":"BTC-EUR passed target","payload":null,
             "readAt":null,"createdAt":"2026-07-11T02:30:00.000Z"}
        """.trimIndent()
        val dto = json.decodeFromString<NotificationItemDto>(row)
        assertNull(dto.readAt)   // null ⇒ unread
        assertNull(dto.payload)
        assertEquals("alert.triggered", dto.type)
    }

    @Test fun `inbox item decodes an omitted payload and a present readAt`() {
        val row = """
            {"id":"2","type":"portfolio.shared","title":"Shared","body":"@a shared X",
             "payload":{"portfolioId":"p1"},"readAt":"2026-07-10T00:00:00.000Z",
             "createdAt":"2026-07-11T00:00:00.000Z"}
        """.trimIndent()
        val dto = json.decodeFromString<NotificationItemDto>(row)
        assertEquals("2026-07-10T00:00:00.000Z", dto.readAt)
        assertTrue(dto.payload != null)
    }

    @Test fun `inbox list decodes a null nextCursor`() {
        val body = """{"items":[],"nextCursor":null,"unreadCount":0}"""
        val resp = json.decodeFromString<NotificationListResponse>(body)
        assertNull(resp.nextCursor)
        assertEquals(0, resp.unreadCount)
        assertTrue(resp.items.isEmpty())
    }

    // ── Settings matrix: 4-channel cells + ignored global fields ────────────

    @Test fun `channel prefs round-trip all four channels`() {
        val dto = ChannelPrefsDto(inapp = true, email = false, push = true, webpush = false)
        val round = json.decodeFromString<ChannelPrefsDto>(json.encodeToString(dto))
        assertEquals(dto, round)
    }

    // ── v4 six-channel compatibility (telegram + discord; PATCH omit-null) ──────

    @Test fun `pre-v4 cell omits telegram and discord (four-key PATCH body)`() {
        // telegram/discord default null → shared Json (explicitNulls=false) drops them,
        // so a pre-v4 strict schema (four required keys, no extras) accepts the body.
        val body = json.encodeToString(ChannelPrefsDto(inapp = true, email = false, push = true, webpush = false))
        assertEquals("""{"inapp":true,"email":false,"push":true,"webpush":false}""", body)
    }

    @Test fun `v4 cell emits all six keys when telegram and discord are present`() {
        val body = json.encodeToString(
            ChannelPrefsDto(inapp = true, email = false, push = true, webpush = false, telegram = true, discord = false),
        )
        assertEquals(
            """{"inapp":true,"email":false,"push":true,"webpush":false,"telegram":true,"discord":false}""",
            body,
        )
    }

    @Test fun `v4 settings response decodes six-channel cells and the channels availability`() {
        val body = """
            {"matrix":{
               "friend.request":{"inapp":true,"email":false,"push":true,"webpush":false,"telegram":true,"discord":false}
             },
             "muted":false,
             "channels":{"inapp":true,"email":true,"telegram":true,"discord":false,"push":true,"webpush":false},
             "webPushPublicKey":null}
        """.trimIndent()
        val resp = json.decodeFromString<NotificationSettingsResponse>(body)
        val fr = resp.matrix.getValue("friend.request")
        assertEquals(true, fr.telegram)
        assertEquals(false, fr.discord)
        // Availability gates the optional columns.
        assertEquals(true, resp.channels?.telegram)
        assertEquals(false, resp.channels?.discord)
    }

    @Test fun `pre-v4 settings response has null channels and null telegram discord`() {
        val body = """{"matrix":{"friend.request":{"inapp":true,"email":true,"push":true,"webpush":true}}}"""
        val resp = json.decodeFromString<NotificationSettingsResponse>(body)
        assertNull(resp.channels)
        val fr = resp.matrix.getValue("friend.request")
        assertNull(fr.telegram)
        assertNull(fr.discord)
    }

    @Test fun `settings response decodes 4-channel cells and ignores global fields`() {
        // The real GET body carries top-level muted / channels / webPushPublicKey
        // and server-only types (watchlist.shared) — all must be tolerated.
        val body = """
            {"matrix":{
               "friend.request":{"inapp":true,"email":false,"push":true,"webpush":false},
               "chat.message":{"inapp":true,"email":true,"push":false,"webpush":true},
               "watchlist.shared":{"inapp":true,"email":true,"push":true,"webpush":true}
             },
             "muted":false,
             "channels":{"inapp":true,"email":true,"push":true,"webpush":true},
             "webPushPublicKey":null}
        """.trimIndent()
        val resp = json.decodeFromString<NotificationSettingsResponse>(body)
        assertEquals(3, resp.matrix.size)
        val fr = resp.matrix.getValue("friend.request")
        assertEquals(true, fr.inapp)
        assertEquals(false, fr.email)
        assertEquals(true, fr.push)
        assertEquals(false, fr.webpush)
        val chat = resp.matrix.getValue("chat.message")
        assertEquals(false, chat.push)
        assertEquals(true, chat.webpush)
    }

    // ── V5 kinds (S2a d / PLATFORM_ASKS #39.2) ───────────────────────────────

    /** FCM/inbox `data` payloads arrive as a JSON object. */
    private fun payload(raw: String) = json.parseToJsonElement(raw)

    @Test
    fun `dividend event lands in the portfolio family and opens its asset`() {
        val kind = NotifKind.fromType("dividend.event")
        assertEquals(NotifKind.DividendEvent, kind)
        assertEquals(NotifChannels.PORTFOLIO, kind.channelId)
        assertEquals(
            NotifDeepLink.Asset("MSFT"),
            resolveDeepLink("dividend.event", payload("""{"assetId":"MSFT"}""")),
        )
    }

    @Test
    fun `a dividend without an assetId falls back to the inbox instead of a dead tap`() {
        assertNull(resolveDeepLink("dividend.event", payload("""{"portfolioId":"p1"}""")))
        assertNull(resolveDeepLink("dividend.event", null))
    }

    @Test
    fun `budget exceeded is portfolio-family and opens the inbox for now`() {
        val kind = NotifKind.fromType("budget.exceeded")
        assertEquals(NotifKind.BudgetExceeded, kind)
        assertEquals(NotifChannels.PORTFOLIO, kind.channelId)
        assertNull(resolveDeepLink("budget.exceeded", payload("""{"categoryId":"c1","period":"2026-08"}""")))
    }

    @Test
    fun `a mirror invite is social and lands on the Social requests area`() {
        val kind = NotifKind.fromType("mirror.invite")
        assertEquals(NotifKind.MirrorInvite, kind)
        assertEquals(NotifChannels.SOCIAL, kind.channelId)
        assertEquals(
            NotifDeepLink.Social,
            resolveDeepLink("mirror.invite", payload("""{"chainId":"c1","inviteId":"i1"}""")),
        )
    }

    @Test
    fun `the seven informational mirror types are registered EXACTLY as documented`() {
        // Pinned against platform docs/mobile-push.md §3.1 after the #1053 refresh.
        // S2a could only prefix-match because the doc predated these names; the
        // spellings matter (underscores, not dots — an earlier app-side guess had
        // "mirror.member.joined", which no dispatcher ever sends).
        val documented = setOf(
            "mirror.member_joined",
            "mirror.member_left",
            "mirror.member_removed",
            "mirror.removed",
            "mirror.ownership_transferred",
            "mirror.chain_dissolved",
            "mirror.sync_stalled",
        )
        assertEquals(documented, NotifKind.MIRROR_EVENT_TYPES)
        documented.forEach { type ->
            val kind = NotifKind.fromType(type)
            assertEquals("wrong kind for $type", NotifKind.MirrorEvent, kind)
            assertEquals("wrong channel for $type", NotifChannels.SOCIAL, kind.channelId)
            assertNull(
                "expected inbox target for $type",
                resolveDeepLink(type, payload("""{"chainId":"c1"}""")),
            )
        }
        // The eighth documented type is the actionable one and must NOT be in the set.
        assertEquals(false, NotifKind.MIRROR_EVENT_TYPES.contains("mirror.invite"))
    }

    @Test
    fun `an undocumented future mirror type still falls back to the mirror family`() {
        // The prefix match is kept ON PURPOSE behind the exact registrations, so a
        // ninth type lands in SOCIAL with a sensible row instead of generic System.
        listOf(
            "mirror.some_type_invented_next_week",
            "mirror.role_changed",
            "mirror.copy_applied",
        ).forEach { type ->
            val kind = NotifKind.fromType(type)
            assertEquals("wrong kind for $type", NotifKind.MirrorEvent, kind)
            assertEquals("wrong channel for $type", NotifChannels.SOCIAL, kind.channelId)
        }
    }

    @Test
    fun `the documented data keys and deep links for the v5 types still hold`() {
        // mobile-push.md §3.1 data keys + §4 route matrix, re-verified after the
        // #1053 doc refresh: dividend.event→asset detail, budget.exceeded→inbox,
        // notifications.digest→inbox.
        assertEquals(
            NotifDeepLink.Asset("a1"),
            resolveDeepLink("dividend.event", payload("""{"assetId":"a1"}""")),
        )
        // §4 fallback: no assetId ⇒ inbox, never a dead tap.
        assertNull(resolveDeepLink("dividend.event", payload("""{}""")))
        assertNull(
            resolveDeepLink("budget.exceeded", payload("""{"categoryId":"c1","period":"2026-08"}""")),
        )
        assertNull(resolveDeepLink("notifications.digest", payload("""{"cadence":"weekly"}""")))
    }

    @Test
    fun `the digest push is general-family and opens the inbox`() {
        val kind = NotifKind.fromType("notifications.digest")
        assertEquals(NotifKind.NotificationsDigest, kind)
        assertEquals(NotifChannels.GENERAL, kind.channelId)
        assertNull(resolveDeepLink("notifications.digest", payload("""{"cadence":"daily"}""")))
    }

    @Test
    fun `the unknown-type fallback is untouched by the mirror prefix rule`() {
        assertEquals(NotifKind.System, NotifKind.fromType("something.brand.new"))
        assertEquals(NotifKind.System, NotifKind.fromType(null))
        // "mirror" without the dot is NOT the mirror family.
        assertEquals(NotifKind.System, NotifKind.fromType("mirrorless.event"))
        assertEquals(NotifKind.System, NotifKind.fromType("mirror"))
    }

    @Test
    fun `none of the new kinds enter the settings PATCH matrix`() {
        listOf(
            NotifKind.DividendEvent,
            NotifKind.BudgetExceeded,
            NotifKind.MirrorInvite,
            NotifKind.MirrorEvent,
            NotifKind.NotificationsDigest,
        ).forEach { assertEquals("$it must not be serverModeled", false, it.serverModeled) }
    }
}
