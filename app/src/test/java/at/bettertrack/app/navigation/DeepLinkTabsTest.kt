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
    fun `a tab's identity is its position in the bar, and nothing else`() {
        // This used to assert that each tab carried its own distinct typed
        // `...TabRoute`. The four route objects are deleted (architecture change
        // 2026-08-08): the tabs are pages in [at.bettertrack.app.ui.shell.BtTabPager],
        // not destinations, and a route object nothing registers is exactly the
        // drift BtRoutes' own header warns about.
        //
        // What replaces the guarantee is stricter, not weaker: a tab IS its index,
        // so the enum being distinct and ordered is the whole contract that the
        // pager, the bottom bar and the shared header all index against.
        assertEquals(BtTab.entries.size, BtTab.entries.toSet().size)
        assertEquals(0, BtTab.entries.indexOf(BtTab.Portfolio))
        assertEquals(1, BtTab.entries.indexOf(BtTab.Markets))
        assertEquals(2, BtTab.entries.indexOf(BtTab.Workbench))
        assertEquals(3, BtTab.entries.indexOf(BtTab.People))
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

    // ── Bar order (owner ask 2026-08-07, re-pinned 2026-08-08) ───────────────
    //
    // `tabNeighbour` used to live here and answered "which tab does a swipe in
    // this direction land on", including the no-wrap rule and the
    // resolve-against-the-VISIBLE-bar rule. It is gone: the four tabs are pages
    // in a `HorizontalPager` built from the visible bar, so a swipe cannot leave
    // the page range and cannot reach a tab the bar does not render — both rules
    // are enforced by construction now rather than by a lookup. See
    // `BtTabPagerTest` for the geometry that replaced it.
    //
    // What still has to be pinned HERE is the thing the pager takes on trust: the
    // ORDER, because a page index is only a tab because this enum says so.

    @Test
    fun `the bar reads Portfolio, Markets, Workbench, People`() {
        // Declaration order IS bar order IS page order. The pager indexes into
        // this list, so reordering the enum reorders the swipe, the bottom bar and
        // the shared header's crossfade together — which is the point of there
        // being one list.
        assertEquals(
            listOf(BtTab.Portfolio, BtTab.Markets, BtTab.Workbench, BtTab.People),
            BtTab.entries.toList(),
        )
    }

    @Test
    fun `Portfolio is the page a cold start lands on`() {
        // It hosts Overview, the app's front door, and it is `FULL` in every
        // storage mode — so it is the one page that is guaranteed to exist for the
        // pager's initial page and for system back to fall back to.
        assertEquals(BtTab.Portfolio, BtTab.entries.first())
    }
}
