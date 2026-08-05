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
 *
 * R-arc R1 rewrote the tab set (four → five, three renamed) without touching the
 * deep-link CONTRACT: `NotifDeepLink` is unchanged, and so is every route these
 * links push. Only the owning-tab constants moved, which is exactly what this
 * file exists to hold still.
 */
class DeepLinkTabsTest {

    // ── People and everything people share ─────────────────────────────────────

    @Test
    fun `social family owns the People tab`() {
        assertEquals(BtTab.People, owningTab(NotifDeepLink.Social))
        assertEquals(BtTab.People, owningTab(NotifDeepLink.SharedPortfolio("pf-1")))
        assertEquals(BtTab.People, owningTab(NotifDeepLink.FriendOverview("u-1", "alice")))
        assertEquals(BtTab.People, owningTab(NotifDeepLink.PublicProfile("bob")))
        assertEquals(BtTab.People, owningTab(NotifDeepLink.SharedConglomerate("cg-1")))
    }

    @Test
    fun `chat is people, with or without a conversation id`() {
        assertEquals(BtTab.People, owningTab(NotifDeepLink.Chat(null)))
        assertEquals(BtTab.People, owningTab(NotifDeepLink.Chat("c-1")))
    }

    // ── Market vs portfolio: the distinction the old code lost ────────────────

    @Test
    fun `a market asset belongs to Markets but a held position belongs to Portfolio`() {
        assertEquals(BtTab.Markets, owningTab(NotifDeepLink.Asset("AAPL")))
        assertEquals(BtTab.Portfolio, owningTab(NotifDeepLink.Holding("AAPL")))
    }

    // ── Workbench ─────────────────────────────────────────────────────────────

    @Test
    fun `the alerts manager belongs to the Workbench tab that hosts it`() {
        assertEquals(BtTab.Workbench, owningTab(NotifDeepLink.Alerts))
    }

    // ── Account-level: deterministic parent, never "wherever you were" ────────

    @Test
    fun `account level destinations all land on the start destination tab`() {
        assertEquals(BtTab.Home, owningTab(NotifDeepLink.Settings))
        assertEquals(BtTab.Home, owningTab(NotifDeepLink.Security))
        assertEquals(BtTab.Home, owningTab(NotifDeepLink.NotificationSettings))
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
        assertEquals("all twelve deep-link targets are covered", 12, all.size)
        assertTrue(all.all { owningTab(it) in BtTab.entries })
    }

    @Test
    fun `each tab carries its own distinct typed route`() {
        val routes = BtTab.entries.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
        assertEquals(HomeTabRoute, BtTab.Home.route)
        assertEquals(PortfolioTabRoute, BtTab.Portfolio.route)
        assertEquals(WorkbenchTabRoute, BtTab.Workbench.route)
        assertEquals(MarketsTabRoute, BtTab.Markets.route)
        assertEquals(PeopleTabRoute, BtTab.People.route)
    }

    @Test
    fun `the bar is the mandate's five destinations in the mandate's order`() {
        // Declaration order IS bar order, and the shell reads it rather than
        // keeping a second list — so this is the only place the order lives.
        assertEquals(
            listOf(BtTab.Home, BtTab.Portfolio, BtTab.Workbench, BtTab.Markets, BtTab.People),
            BtTab.entries.toList(),
        )
    }

    @Test
    fun `the start destination tab is the one account level links fall back to`() {
        // Guards the reasoning in `owningTab`'s KDoc: settings has no tab of its
        // own, so it must resolve to the graph's start destination (Home).
        assertEquals(BtTab.Home.route, HomeTabRoute)
        assertEquals(BtTab.Home, owningTab(NotifDeepLink.Settings))
        // …and the start destination is the FIRST tab in the bar, so the tab the
        // fallback lands on is never one the user has to go looking for.
        assertEquals(BtTab.Home, BtTab.entries.first())
    }
}
