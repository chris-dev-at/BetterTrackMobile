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

    @Test fun `blank payload values are ignored`() {
        val payload = buildJsonObject { put("assetId", "") }
        // Empty assetId ⇒ not a usable target ⇒ null (no crash, no bogus route).
        assertNull(resolveDeepLink("alert.triggered", payload))
    }

    // ── decideDelivery (mute/channel rule) ──────────────────────────────────

    @Test fun `muting a type suppresses it entirely`() {
        val d = decideDelivery(TypePrefs(inApp = true, push = true, muted = true))
        assertTrue(d.suppressedEntirely)
        assertEquals(false, d.addToInbox)
        assertEquals(false, d.showPush)
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

    @Test fun `NotifMatrix returns defaults for an absent kind`() {
        val matrix = NotifMatrix(emptyMap())
        assertEquals(TypePrefs(), matrix.prefs(NotifKind.FriendRequest))
    }

    // ── Shipped-flag tripwire (Notifications-v2 go-live) ────────────────────

    @Test fun `device-token registration ships LIVE, not stubbed`() {
        // If this fails, the app is not registering FCM tokens against the account.
        assertFalse(NotificationFlags.stubDeviceRegistration)
    }

    @Test fun `chat message is server-modeled so it round-trips in the matrix`() {
        assertTrue(NotifKind.ChatMessage.serverModeled)
    }

    // ── Matrix merge / PATCH shape (push now syncs; mute stays local) ───────

    @Test fun `mergedFrom takes all channels from the server but keeps local mute`() {
        // Local row muted with everything on; server says every channel OFF.
        val local = TypePrefs(inApp = true, email = true, push = true, webpush = true, muted = true)
        val server = ChannelPrefsDto(inapp = false, email = false, push = false, webpush = false)
        val merged = local.mergedFrom(server)

        // Server wins on the four channels — including PUSH (migrated off local).
        assertFalse(merged.inApp)
        assertFalse(merged.email)
        assertFalse(merged.push)
        assertFalse(merged.webpush)
        // Per-type mute is app-local — the server has no per-type mute to sync.
        assertTrue(merged.muted)
    }

    @Test fun `toChannelPrefs echoes all four channels and never leaks mute`() {
        val prefs = TypePrefs(inApp = true, email = false, push = true, webpush = false, muted = true)
        val dto = prefs.toChannelPrefs()
        assertEquals(ChannelPrefsDto(inapp = true, email = false, push = true, webpush = false), dto)
    }
}
