package at.bettertrack.app.navigation

import at.bettertrack.app.data.notifications.NotifDeepLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6 P1-8. The shell used to give ONE deep link (Social) bottom-bar tab
 * semantics and plain-`navigate` the other nine, so a notification tap pushed
 * its detail onto whichever tab happened to be selected — and the next bottom-bar
 * tap saved it there and bounced the user back into it.
 *
 * The fix hinges on one rule: every link has an owning tab. That rule is a pure
 * function precisely so it can be pinned here, on the JVM, with no NavController
 * and no device.
 */
class DeepLinkTabsTest {

    // ── People and everything people share ─────────────────────────────────────

    @Test
    fun `social family owns the Social tab`() {
        assertEquals(BtTab.Social, owningTab(NotifDeepLink.Social))
        assertEquals(BtTab.Social, owningTab(NotifDeepLink.SharedPortfolio("pf-1")))
        assertEquals(BtTab.Social, owningTab(NotifDeepLink.FriendOverview("u-1", "alice")))
        assertEquals(BtTab.Social, owningTab(NotifDeepLink.PublicProfile("bob")))
        assertEquals(BtTab.Social, owningTab(NotifDeepLink.SharedConglomerate("cg-1")))
    }

    @Test
    fun `chat is social, with or without a conversation id`() {
        assertEquals(BtTab.Social, owningTab(NotifDeepLink.Chat(null)))
        assertEquals(BtTab.Social, owningTab(NotifDeepLink.Chat("c-1")))
    }

    // ── Market vs portfolio: the distinction the old code lost ────────────────

    @Test
    fun `a market asset belongs to Assets but a held position belongs to Portfolio`() {
        assertEquals(BtTab.Assets, owningTab(NotifDeepLink.Asset("AAPL")))
        assertEquals(BtTab.Portfolio, owningTab(NotifDeepLink.Holding("AAPL")))
    }

    // ── Workboard ──────────────────────────────────────────────────────────────

    @Test
    fun `the alerts manager belongs to the Workboard tab that hosts it`() {
        assertEquals(BtTab.Workboard, owningTab(NotifDeepLink.Alerts))
    }

    // ── Account-level: deterministic parent, never "wherever you were" ────────

    @Test
    fun `account level destinations all land on the start destination tab`() {
        assertEquals(BtTab.Portfolio, owningTab(NotifDeepLink.Settings))
        assertEquals(BtTab.Portfolio, owningTab(NotifDeepLink.Security))
        assertEquals(BtTab.Portfolio, owningTab(NotifDeepLink.NotificationSettings))
    }

    // ── Structural guards ──────────────────────────────────────────────────────

    @Test
    fun `every deep link target maps to a tab`() {
        // If a future link type is added, `owningTab`'s exhaustive `when` fails to
        // compile — this list keeps the runtime side honest for the cases that
        // exist today, so no link can quietly regress to a null-ish default.
        val all = listOf(
            NotifDeepLink.Social,
            NotifDeepLink.SharedPortfolio("p"),
            NotifDeepLink.FriendOverview("u", "n"),
            NotifDeepLink.PublicProfile("n"),
            NotifDeepLink.SharedConglomerate("c"),
            NotifDeepLink.Chat(null),
            NotifDeepLink.Asset("a"),
            NotifDeepLink.Holding("a"),
            NotifDeepLink.Alerts,
            NotifDeepLink.Settings,
            NotifDeepLink.Security,
            NotifDeepLink.NotificationSettings,
        )
        assertTrue(all.all { owningTab(it) in BtTab.entries })
    }

    @Test
    fun `each tab carries its own distinct typed route`() {
        val routes = BtTab.entries.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
        assertEquals(PortfolioTabRoute, BtTab.Portfolio.route)
        assertEquals(AssetsTabRoute, BtTab.Assets.route)
        assertEquals(SocialTabRoute, BtTab.Social.route)
        assertEquals(WorkboardTabRoute, BtTab.Workboard.route)
    }

    @Test
    fun `the start destination tab is the one account level links fall back to`() {
        // Guards the reasoning in `owningTab`'s KDoc: settings has no tab of its
        // own, so it must resolve to the graph's start destination (Portfolio).
        assertEquals(BtTab.Portfolio.route, PortfolioTabRoute)
        assertEquals(BtTab.Portfolio, owningTab(NotifDeepLink.Settings))
    }
}
