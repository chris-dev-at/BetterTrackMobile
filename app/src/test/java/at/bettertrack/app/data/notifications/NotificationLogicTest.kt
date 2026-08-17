package at.bettertrack.app.data.notifications

import at.bettertrack.app.data.api.dto.ChannelPrefsDto
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for Step 16 notifications: type→kind mapping, deep-link
 * resolution (with/without payload), the mute/channel delivery rule, and the
 * per-type preference accessors. No Android dependencies.
 */
class NotificationLogicTest {

    // ── NotifKind.fromType ──────────────────────────────────────────────────

    @Test fun `maps every server type to its kind`() {
        assertEquals(NotifKind.FriendRequest, NotifKind.fromType("friend.request"))
        assertEquals(NotifKind.FriendAccepted, NotifKind.fromType("friend.accepted"))
        assertEquals(NotifKind.PortfolioShared, NotifKind.fromType("portfolio.shared"))
        assertEquals(NotifKind.AlertTriggered, NotifKind.fromType("alert.triggered"))
        assertEquals(NotifKind.AccountInvite, NotifKind.fromType("account.invite"))
        assertEquals(NotifKind.AccountTempPassword, NotifKind.fromType("account.temp_password"))
        assertEquals(NotifKind.ChatMessage, NotifKind.fromType("chat.message"))
    }

    @Test fun `unknown or null type falls back to System`() {
        assertEquals(NotifKind.System, NotifKind.fromType("something.new"))
        assertEquals(NotifKind.System, NotifKind.fromType(null))
    }

    @Test fun `maps the v4 follow-graph and announcement types to their kinds`() {
        assertEquals(NotifKind.FollowPublished, NotifKind.fromType("follow.published"))
        assertEquals(NotifKind.FollowAlertCreated, NotifKind.fromType("follow.alert.created"))
        assertEquals(NotifKind.FollowAlertFired, NotifKind.fromType("follow.alert.fired"))
        assertEquals(NotifKind.AccountNotice, NotifKind.fromType("account.notice"))
    }

    // ── resolveDeepLink ─────────────────────────────────────────────────────

    @Test fun `friend types deep-link to Social`() {
        assertEquals(NotifDeepLink.Social, resolveDeepLink("friend.request", null))
        assertEquals(NotifDeepLink.Social, resolveDeepLink("friend.accepted", null))
    }

    @Test fun `portfolio share with payload opens that portfolio`() {
        val payload = buildJsonObject { put("portfolioId", "pf-123") }
        assertEquals(NotifDeepLink.SharedPortfolio("pf-123"), resolveDeepLink("portfolio.shared", payload))
    }

    @Test fun `portfolio share without payload falls back to Social`() {
        assertEquals(NotifDeepLink.Social, resolveDeepLink("portfolio.shared", null))
    }

    @Test fun `price alert with assetId opens the asset page`() {
        val payload = buildJsonObject { put("assetId", "AAPL") }
        assertEquals(NotifDeepLink.Asset("AAPL"), resolveDeepLink("alert.triggered", payload))
    }

    @Test fun `price alert scoped to a portfolio opens the holding`() {
        val payload = buildJsonObject {
            put("assetId", "AAPL")
            put("portfolioId", "pf-9")
        }
        assertEquals(NotifDeepLink.Holding("AAPL"), resolveDeepLink("alert.triggered", payload))
    }

    @Test fun `account and security types route to settings`() {
        assertEquals(NotifDeepLink.Settings, resolveDeepLink("account.invite", null))
        assertEquals(NotifDeepLink.Security, resolveDeepLink("account.temp_password", null))
    }

    @Test fun `chat carries the conversation id when present`() {
        val payload = buildJsonObject { put("conversationId", "c-1") }
        assertEquals(NotifDeepLink.Chat("c-1"), resolveDeepLink("chat.message", payload))
        assertEquals(NotifDeepLink.Chat(null), resolveDeepLink("chat.message", null))
    }

    @Test fun `system notifications have no deep link`() {
        assertNull(resolveDeepLink("system", null))
        assertNull(resolveDeepLink("totally.unknown", null))
    }

    // ── V4-P0c deep-link matrix (mobile-push.md §4) ─────────────────────────────

    @Test fun `friend activity with id and username opens the friend overview`() {
        val payload = buildJsonObject { put("userId", "u1"); put("username", "bob") }
        assertEquals(NotifDeepLink.FriendOverview("u1", "bob"), resolveDeepLink("friend.activity", payload))
    }

    @Test fun `friend activity from FCM (username only, no userId) opens the public profile`() {
        // FCM friend.activity carries data.username but NO userId → the nav layer
        // resolves it against the friends list (username-only PublicProfile).
        val payload = buildJsonObject { put("username", "bob") }
        assertEquals(NotifDeepLink.PublicProfile("bob"), resolveDeepLink("friend.activity", payload))
    }

    @Test fun `friend activity with neither id nor username falls back to Social`() {
        assertEquals(NotifDeepLink.Social, resolveDeepLink("friend.activity", null))
    }

    @Test fun `follow published opens the actor public profile by username`() {
        val payload = buildJsonObject { put("username", "alice") }
        assertEquals(NotifDeepLink.PublicProfile("alice"), resolveDeepLink("follow.published", payload))
    }

    @Test fun `follow published without a username falls back to Social`() {
        assertEquals(NotifDeepLink.Social, resolveDeepLink("follow.published", null))
    }

    @Test fun `followed alert created and fired open the watched asset`() {
        val payload = buildJsonObject { put("assetId", "MSFT") }
        assertEquals(NotifDeepLink.Asset("MSFT"), resolveDeepLink("follow.alert.created", payload))
        assertEquals(NotifDeepLink.Asset("MSFT"), resolveDeepLink("follow.alert.fired", payload))
    }

    @Test fun `followed alert without an assetId is not a dead tap`() {
        // No standalone Alerts screen in-app → null (the inbox surface), never a bogus route.
        assertNull(resolveDeepLink("follow.alert.created", null))
        assertNull(resolveDeepLink("follow.alert.fired", null))
    }

    @Test fun `account notice deep-links to the notification-settings screen`() {
        assertEquals(NotifDeepLink.NotificationSettings, resolveDeepLink("account.notice", null))
    }

    @Test fun `shared and social types without their id land on the type surface (never a dead tap)`() {
        // §4 fallback table: missing id ⇒ the type's landing surface, not a no-op.
        assertEquals(NotifDeepLink.Social, resolveDeepLink("portfolio.shared", null))
        assertEquals(NotifDeepLink.Social, resolveDeepLink("watchlist.shared", null))
        assertEquals(NotifDeepLink.Social, resolveDeepLink("conglomerate.shared", null))
        assertEquals(NotifDeepLink.Social, resolveDeepLink("friend.request", null))
        assertEquals(NotifDeepLink.Social, resolveDeepLink("friend.accepted", null))
        assertEquals(NotifDeepLink.Chat(null), resolveDeepLink("chat.message", null))
    }

    @Test fun `blank payload values are ignored`() {
        val payload = buildJsonObject { put("assetId", "") }
        // Empty assetId ⇒ not a usable target ⇒ null (no crash, no bogus route).
        assertNull(resolveDeepLink("alert.triggered", payload))
    }

    // ── decideDelivery (channel rule) ───────────────────────────────────────
    //
    // The old first test here asserted that a per-type `muted` flag suppressed a
    // notification entirely. That flag is gone (web-parity ruling 2026-08-08 — the
    // platform has exactly one mute and it is account-wide), so what replaces the
    // test is the guarantee that nothing local suppresses any more: the two server
    // channel flags are the whole rule.

    @Test fun `both channels off is the only way a type is suppressed entirely`() {
        assertTrue(decideDelivery(TypePrefs(inApp = false, push = false)).suppressedEntirely)
        assertFalse(decideDelivery(TypePrefs(inApp = true, push = false)).suppressedEntirely)
        assertFalse(decideDelivery(TypePrefs(inApp = false, push = true)).suppressedEntirely)
    }

    @Test fun `in-app and push govern their own channels`() {
        assertEquals(DeliveryDecision(addToInbox = true, showPush = true), decideDelivery(TypePrefs(inApp = true, push = true)))
        assertEquals(DeliveryDecision(addToInbox = false, showPush = true), decideDelivery(TypePrefs(inApp = false, push = true)))
        assertEquals(DeliveryDecision(addToInbox = true, showPush = false), decideDelivery(TypePrefs(inApp = true, push = false)))
    }

    @Test fun `email channel has no local delivery effect`() {
        // Email off but in-app/push on ⇒ still delivered locally.
        val d = decideDelivery(TypePrefs(inApp = true, email = false, push = true))
        assertEquals(DeliveryDecision(addToInbox = true, showPush = true), d)
    }

    // ── TypePrefs / NotifMatrix accessors ───────────────────────────────────

    @Test fun `TypePrefs get and set are channel-correct`() {
        val p = TypePrefs(inApp = true, email = false, push = true)
        assertEquals(true, p.get(NotifChannel.InApp))
        assertEquals(false, p.get(NotifChannel.Email))
        assertEquals(true, p.get(NotifChannel.Push))
        assertEquals(false, p.set(NotifChannel.Push, false).push)
        assertEquals(true, p.set(NotifChannel.Email, true).email)
    }

    @Test fun `NotifMatrix returns defaults for an absent type`() {
        val matrix = NotifMatrix(emptyMap())
        assertEquals(TypePrefs(), matrix.prefs("friend.request"))
    }

    @Test fun `the matrix honours EVERY wire type, not just the seven with display kinds`() {
        // The regression this pins: while the matrix was keyed by `NotifKind`, any
        // type outside the app's seven presentation kinds fell through to
        // all-defaults, so the app showed a push for `dividend.event` and
        // `budget.exceeded` no matter what the account said. Keying by the wire
        // string is what makes the server's routing authoritative for all of them.
        val silenced = TypePrefs(inApp = false, email = false, push = false, webpush = false)
        val matrix = NotifMatrix(
            mapOf("dividend.event" to silenced, "budget.exceeded" to silenced),
        )
        assertTrue(decideDelivery(matrix.prefs("dividend.event")).suppressedEntirely)
        assertTrue(decideDelivery(matrix.prefs("budget.exceeded")).suppressedEntirely)
    }

    // ── Shipped-flag tripwire (Notifications-v2 go-live) ────────────────────

    @Test fun `device-token registration ships LIVE, not stubbed`() {
        // If this fails, the app is not registering FCM tokens against the account.
        assertFalse(NotificationFlags.stubDeviceRegistration)
    }

    @Test fun `chat message is server-modeled so it round-trips in the matrix`() {
        assertTrue(NotifKind.ChatMessage.serverModeled)
    }

    // ── Matrix merge / PATCH shape (every channel is the server's) ──────────

    @Test fun `mergedFrom takes all channels from the server, with nothing app-local left`() {
        // Local row with everything on; server says every channel OFF.
        val local = TypePrefs(inApp = true, email = true, push = true, webpush = true)
        val server = ChannelPrefsDto(inapp = false, email = false, push = false, webpush = false)
        val merged = local.mergedFrom(server)

        // Server wins on the four channels — including PUSH (migrated off local).
        assertFalse(merged.inApp)
        assertFalse(merged.email)
        assertFalse(merged.push)
        assertFalse(merged.webpush)
        // Nothing survives the merge that the server did not send.
        assertEquals(TypePrefs(inApp = false, email = false, push = false, webpush = false), merged)
    }

    @Test fun `toChannelPrefs echoes all four channels`() {
        val prefs = TypePrefs(inApp = true, email = false, push = true, webpush = false)
        val dto = prefs.toChannelPrefs()
        assertEquals(ChannelPrefsDto(inapp = true, email = false, push = true, webpush = false), dto)
    }

    // ── v4 telegram/discord round-trip (echo exactly what the server sent) ──────

    @Test fun `mergedFrom echoes v4 telegram and discord verbatim, and the echo round-trips`() {
        val server = ChannelPrefsDto(inapp = true, email = true, push = true, webpush = true, telegram = false, discord = true)
        val merged = TypePrefs().mergedFrom(server)
        assertEquals(false, merged.telegram)
        assertEquals(true, merged.discord)
        // The PATCH cell carries the same six keys back.
        assertEquals(false, merged.toChannelPrefs().telegram)
        assertEquals(true, merged.toChannelPrefs().discord)
    }

    @Test fun `a pre-v4 four-key GET leaves telegram and discord null so the echo omits them`() {
        val server = ChannelPrefsDto(inapp = true, email = true, push = true, webpush = true) // telegram/discord default null
        val merged = TypePrefs(telegram = true, discord = true).mergedFrom(server)
        // Server state wins on load — it never modeled telegram/discord, so they go null.
        assertNull(merged.telegram)
        assertNull(merged.discord)
        // Null ⇒ the PATCH cell omits the keys (never invents a value the server didn't send).
        assertNull(merged.toChannelPrefs().telegram)
        assertNull(merged.toChannelPrefs().discord)
    }

    @Test fun `toggling a telegram or discord channel makes the echo carry that key`() {
        val tg = TypePrefs().set(NotifChannel.Telegram, true)
        assertEquals(true, tg.get(NotifChannel.Telegram))
        assertEquals(true, tg.toChannelPrefs().telegram)
        val dc = TypePrefs().set(NotifChannel.Discord, false)
        assertEquals(false, dc.get(NotifChannel.Discord))
        assertEquals(false, dc.toChannelPrefs().discord)
    }
}
