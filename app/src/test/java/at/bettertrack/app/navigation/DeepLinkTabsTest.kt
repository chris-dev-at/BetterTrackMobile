package at.bettertrack.app.navigation

import at.bettertrack.app.data.notifications.NotifDeepLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6 P1-8. The shell used to give ONE deep link (Social) bottom-bar tab
 * semantics and plain-`navigate` the other nine, so a notification tap pushed
 * its detail onto whichever tab happened to be selected — and the next bottom-bar
 * tap saved it there and bounced the user back into it.
 *
 * The fix hinges on one rule: every link that belongs to a tab names it. That
 * rule is a pure function precisely so it can be pinned here, on the JVM, with no
 * NavController and no device.
 *
 * R-arc R1 rewrote the tab set (four → five, three renamed) without touching the
 * deep-link CONTRACT. The owner IA change (2026-08-05) took it back to four by
 * retiring the Home TAB — Home's content is now Overview, a selection inside the
 * Portfolio tab. `NotifDeepLink` is still unchanged, and so is every route these
 * links push; what moved is that the account-level trio now maps to NO tab
 * instead of to Home. That is the one behavioural change this file exists to
 * hold still.
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

    // ── Account-level: pushed over the current tab, never a forced switch ─────

    @Test
    fun `account level destinations are owned by no tab`() {
        // The owner's rule: settings / security / notification settings push over
        // whatever is selected and do not force a switch. `null` is what the shell
        // reads as "skip the tab switch"; mapping them to Portfolio (the new start
        // destination) would yank a user off Workbench or People and strand them
        // there when they backed out of settings.
        assertNull(owningTab(NotifDeepLink.Settings))
        assertNull(owningTab(NotifDeepLink.Security))
        assertNull(owningTab(NotifDeepLink.NotificationSettings))
    }

    @Test
    fun `every link that is not account level names a real tab`() {
        val accountLevel = setOf(
            NotifDeepLink.Settings,
            NotifDeepLink.Security,
            NotifDeepLink.NotificationSettings,
        )
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
        ) + accountLevel
        // If a future link type is added, `owningTab`'s exhaustive `when` fails to
        // compile — this list keeps the runtime side honest for the cases that
        // exist today.
        assertEquals("all twelve deep-link targets are covered", 12, all.size)
        val (unowned, owned) = all.partition { it in accountLevel }
        assertTrue("no non-account link may be unowned", owned.all { owningTab(it) in BtTab.entries })
        assertTrue("account-level links are unowned", unowned.all { owningTab(it) == null })
    }

    // ── Structural guards ──────────────────────────────────────────────────────

    @Test
    fun `each tab carries its own distinct typed route`() {
        val routes = BtTab.entries.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
        assertEquals(PortfolioTabRoute, BtTab.Portfolio.route)
        assertEquals(WorkbenchTabRoute, BtTab.Workbench.route)
        assertEquals(MarketsTabRoute, BtTab.Markets.route)
        assertEquals(PeopleTabRoute, BtTab.People.route)
    }

    @Test
    fun `the bar is four destinations, Portfolio first`() {
        // Declaration order IS bar order, and the shell reads it rather than
        // keeping a second list — so this is the only place the order lives.
        // Four, not five: the owner's verdict was that the bar had too many items.
        assertEquals(
            listOf(BtTab.Portfolio, BtTab.Workbench, BtTab.Markets, BtTab.People),
            BtTab.entries.toList(),
        )
    }

    @Test
    fun `the start destination is the first tab in the bar`() {
        // Portfolio is the NavHost's start destination (see BtNavHost) and the tab
        // that hosts Overview. Both `popUpTo(findStartDestination())` call sites —
        // the bottom-bar tap and the deep-link tab switch — land there, so it must
        // be a tab the user can actually see: first in the bar, and `FULL` in
        // every storage mode.
        assertEquals(BtTab.Portfolio, BtTab.entries.first())
        assertEquals(PortfolioTabRoute, BtTab.entries.first().route)
    }
}
