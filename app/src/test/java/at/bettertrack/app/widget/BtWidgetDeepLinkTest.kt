package at.bettertrack.app.widget

import at.bettertrack.app.data.notifications.NotifDeepLink
import at.bettertrack.app.data.notifications.resolveDeepLink
import at.bettertrack.app.navigation.BtTab
import at.bettertrack.app.navigation.owningTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where a widget tap lands, and the two properties that make it land there.
 *
 * A widget reuses the app's existing deep-link machinery — [NotifDeepLink],
 * `AppGraph.pendingDeepLink`, `AppShell`'s landing discipline — rather than
 * navigating on its own, so what needs pinning here is the small pure mapping
 * that joins the two, plus the PendingIntent uniqueness rule that a list of rows
 * lives or dies by.
 */
class BtWidgetDeepLinkTest {

    @Test
    fun `the net worth widget opens the Overview`() {
        assertEquals(NotifDeepLink.Overview, btWidgetDeepLink(BT_WIDGET_TARGET_OVERVIEW, null))
    }

    @Test
    fun `a watchlist row opens its asset`() {
        assertEquals(
            NotifDeepLink.Asset("AAPL"),
            btWidgetDeepLink(BT_WIDGET_TARGET_ASSET, "AAPL"),
        )
    }

    @Test
    fun `an asset target with no usable id still opens something`() {
        // A tap that appears to do nothing is the bug NotifDeepLink.Inbox was
        // added to fix on the push path; the widget does not get to reintroduce it.
        assertEquals(NotifDeepLink.Overview, btWidgetDeepLink(BT_WIDGET_TARGET_ASSET, null))
        assertEquals(NotifDeepLink.Overview, btWidgetDeepLink(BT_WIDGET_TARGET_ASSET, "   "))
    }

    @Test
    fun `the budget widget opens the Cash screen for its portfolio`() {
        assertEquals(
            NotifDeepLink.Cash("pf-1"),
            btWidgetDeepLink(BT_WIDGET_TARGET_CASH, null, "pf-1"),
        )
    }

    @Test
    fun `a cash target with no portfolio still opens the Cash screen`() {
        // NotifDeepLink.Cash carries a nullable id; CashScreen resolves the
        // selected portfolio itself, so a blank id is a valid "open cash", not a
        // dead tap.
        assertEquals(NotifDeepLink.Cash(null), btWidgetDeepLink(BT_WIDGET_TARGET_CASH, null, null))
        assertEquals(NotifDeepLink.Cash(null), btWidgetDeepLink(BT_WIDGET_TARGET_CASH, null, "   "))
    }

    @Test
    fun `a portfolio widget opens its portfolio, selected`() {
        assertEquals(
            NotifDeepLink.Portfolio("pf-1"),
            btWidgetDeepLink(BT_WIDGET_TARGET_PORTFOLIO, null, "pf-1"),
        )
    }

    @Test
    fun `a portfolio target with no usable id falls back to the Overview`() {
        // The one place every portfolio is visible — never a dead tap.
        assertEquals(
            NotifDeepLink.Overview,
            btWidgetDeepLink(BT_WIDGET_TARGET_PORTFOLIO, null, null),
        )
        assertEquals(
            NotifDeepLink.Overview,
            btWidgetDeepLink(BT_WIDGET_TARGET_PORTFOLIO, null, "   "),
        )
    }

    @Test
    fun `the quick-action tiles resolve to their forms`() {
        assertEquals(
            NotifDeepLink.AddTransaction,
            btWidgetDeepLink(BT_WIDGET_TARGET_ADD_TRANSACTION, null),
        )
        assertEquals(NotifDeepLink.AddCashEntry, btWidgetDeepLink(BT_WIDGET_TARGET_ADD_CASH, null))
        assertEquals(NotifDeepLink.MarketSearch, btWidgetDeepLink(BT_WIDGET_TARGET_SEARCH, null))
    }

    @Test
    fun `the quick-action targets are not something the push wire can produce`() {
        // Widget-only, like Overview/Cash/Portfolio: a push that started opening
        // ENTRY FORMS would be putting words in the user's mouth.
        listOf("alert.triggered", "dividend.event", "portfolio.shared", "budget.exceeded")
            .forEach { type ->
                val resolved = resolveDeepLink(type, null)
                assertFalse(
                    "$type must not resolve to a widget-only quick action",
                    resolved == NotifDeepLink.AddTransaction ||
                        resolved == NotifDeepLink.AddCashEntry ||
                        resolved == NotifDeepLink.MarketSearch,
                )
            }
    }

    @Test
    fun `an unknown target resolves to nothing rather than guessing`() {
        assertNull(btWidgetDeepLink(null, null))
        assertNull(btWidgetDeepLink("", null))
        assertNull(btWidgetDeepLink("something-a-later-build-added", "AAPL"))
    }

    @Test
    fun `every widget target lands on a tab that renders it`() {
        assertEquals(BtTab.Portfolio, owningTab(NotifDeepLink.Overview))
        assertEquals(BtTab.Markets, owningTab(NotifDeepLink.Asset("AAPL")))
        assertEquals(BtTab.Portfolio, owningTab(NotifDeepLink.Cash("pf-1")))
        assertEquals(BtTab.Portfolio, owningTab(NotifDeepLink.Portfolio("pf-1")))
    }

    @Test
    fun `each row gets a distinct intent action`() {
        // PendingIntent equality ignores extras. Without a distinct action every
        // row in the watchlist collapses onto whichever target was registered
        // first — the whole list opens one asset.
        val actions = listOf("AAPL", "MSFT", "TSLA")
            .map { btWidgetIntentAction(BT_WIDGET_TARGET_ASSET, it) }
        assertEquals("row actions must be distinct", actions.size, actions.toSet().size)
        assertNotEquals(
            btWidgetIntentAction(BT_WIDGET_TARGET_OVERVIEW, null),
            btWidgetIntentAction(BT_WIDGET_TARGET_ASSET, "AAPL"),
        )
    }

    @Test
    fun `Overview is not something the push wire can produce`() {
        // It exists for the widget. A server notification kind that started
        // resolving to it would be silently retargeting pushes at the front door.
        listOf("alert.triggered", "dividend.event", "portfolio.shared", "budget.exceeded")
            .forEach { type ->
                assertNotEquals(NotifDeepLink.Overview, resolveDeepLink(type, null))
            }
    }

    @Test
    fun `Cash is not something the push wire can produce`() {
        // budget.exceeded is the kind that would seem to want it, but its payload
        // has no portfolioId so it deliberately lands on the inbox (see that kind's
        // branch). Cash exists for the budget widget, which HAS the portfolio.
        listOf("budget.exceeded", "alert.triggered", "dividend.event", "portfolio.shared")
            .forEach { type ->
                assertFalse(
                    "$type must not resolve to the widget-only Cash target",
                    resolveDeepLink(type, null) is NotifDeepLink.Cash,
                )
            }
    }

    @Test
    fun `Portfolio is not something the push wire can produce`() {
        // Selecting the user's portfolio is a WIDGET tap's meaning; a server push
        // that started doing it would be silently rewriting the switcher state.
        listOf("portfolio.shared", "alert.triggered", "dividend.event", "budget.exceeded")
            .forEach { type ->
                assertFalse(
                    "$type must not resolve to the widget-only Portfolio target",
                    resolveDeepLink(type, null) is NotifDeepLink.Portfolio,
                )
            }
    }
}
