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
    fun `the bar is four destinations, Portfolio first, Workbench third`() {
        // Declaration order IS bar order. The shell keeps a parallel list of UI
        // specs (AppShell.Tabs) whose ORDER is pinned against this one by
        // `TopBarNavigationTest.the shell's tab list is in BtTab declaration
        // order` — before 2026-08-07 the two were held together only by a comment
        // here that claimed the shell read the enum, which it never did.
        //
        // Four, not five: the owner's verdict was that the bar had too many items.
        // Workbench third, not second: owner order 2026-08-07 — holdings and the
        // market are the two tabs reached for constantly, so they sit together.
        assertEquals(
            listOf(BtTab.Portfolio, BtTab.Markets, BtTab.Workbench, BtTab.People),
            BtTab.entries.toList(),
        )
    }

    // ── Bottom-bar re-tap (owner directive 2026-08-07) ─────────────────────────

    @Test
    fun `re-tapping Portfolio while on Portfolio opens the switcher`() {
        assertEquals(
            TabTap.OpenPortfolioSwitcher,
            tabTapAction(BtTab.Portfolio, exactTab = BtTab.Portfolio),
        )
    }

    @Test
    fun `tapping Portfolio from another tab just switches`() {
        listOf(BtTab.Markets, BtTab.Workbench, BtTab.People).forEach { from ->
            assertEquals(
                "coming from $from",
                TabTap.Switch,
                tabTapAction(BtTab.Portfolio, exactTab = from),
            )
        }
    }

    @Test
    fun `re-tapping Portfolio from a PUSHED screen switches rather than opening a sheet`() {
        // `exactTab` is null while a holding detail (or any pushed screen) is
        // showing. The tap means "take me back to the tab" — putting a sheet on
        // top of the screen the user is leaving would be the opposite.
        assertEquals(TabTap.Switch, tabTapAction(BtTab.Portfolio, exactTab = null))
    }

    @Test
    fun `no other tab gains a re-tap behaviour`() {
        // One gesture, one meaning, everywhere except the one place the owner
        // asked for an exception.
        listOf(BtTab.Markets, BtTab.Workbench, BtTab.People).forEach { tab ->
            assertEquals("re-tap on $tab", TabTap.Switch, tabTapAction(tab, exactTab = tab))
        }
    }

    // ── Swipe neighbours (owner ask 2026-08-07) ────────────────────────────────

    @Test
    fun `swiping forward walks the bar left to right`() {
        // "forward" is the finger travelling LEFT, which reveals the tab to the
        // right — the owner's "portfolio swipe left goes to assets".
        assertEquals(BtTab.Markets, tabNeighbour(BtTab.Portfolio, forward = true))
        assertEquals(BtTab.Workbench, tabNeighbour(BtTab.Markets, forward = true))
        assertEquals(BtTab.People, tabNeighbour(BtTab.Workbench, forward = true))
    }

    @Test
    fun `swiping back walks the bar right to left`() {
        assertEquals(BtTab.Workbench, tabNeighbour(BtTab.People, forward = false))
        assertEquals(BtTab.Markets, tabNeighbour(BtTab.Workbench, forward = false))
        assertEquals(BtTab.Portfolio, tabNeighbour(BtTab.Markets, forward = false))
    }

    @Test
    fun `the bar does not wrap around at either end`() {
        // Wrapping would make the bar a carousel, which contradicts a bottom bar
        // whose selection is a position rather than a cycle.
        assertNull(tabNeighbour(BtTab.Portfolio, forward = false))
        assertNull(tabNeighbour(BtTab.People, forward = true))
    }

    @Test
    fun `neighbours are resolved against the VISIBLE bar, not the full enum`() {
        // A Drive-only install renders Portfolio + Markets only. Swiping off the
        // end of THAT bar must stop, not land on a tab with no button.
        val driveBar = listOf(BtTab.Portfolio, BtTab.Markets)
        assertEquals(BtTab.Markets, tabNeighbour(BtTab.Portfolio, forward = true, visible = driveBar))
        assertNull(tabNeighbour(BtTab.Markets, forward = true, visible = driveBar))
        // A tab that is not in the visible bar has no neighbours at all.
        assertNull(tabNeighbour(BtTab.Workbench, forward = true, visible = driveBar))
        assertNull(tabNeighbour(BtTab.Workbench, forward = false, visible = driveBar))
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
