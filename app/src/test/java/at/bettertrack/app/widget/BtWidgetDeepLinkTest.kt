package at.bettertrack.app.widget

import at.bettertrack.app.data.notifications.NotifDeepLink
import at.bettertrack.app.data.notifications.resolveDeepLink
import at.bettertrack.app.navigation.BtTab
import at.bettertrack.app.navigation.owningTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
        // The generic cash tile stays parameterless: it means "open cash", not
        // "book something", so it must NOT preselect a direction.
        assertEquals(
            NotifDeepLink.AddCashEntry(),
            btWidgetDeepLink(BT_WIDGET_TARGET_ADD_CASH, null),
        )
        assertEquals(NotifDeepLink.MarketSearch, btWidgetDeepLink(BT_WIDGET_TARGET_SEARCH, null))
    }

    @Test
    fun `the quick-links catalog resolves its three additions`() {
        // A launcher tile opens the chat LIST, never someone's thread.
        assertEquals(NotifDeepLink.Chat(null), btWidgetDeepLink(BT_WIDGET_TARGET_CHAT, null))
        assertEquals(NotifDeepLink.Social, btWidgetDeepLink(BT_WIDGET_TARGET_SOCIAL, null))
        assertEquals(NotifDeepLink.Watchlist, btWidgetDeepLink(BT_WIDGET_TARGET_WATCHLIST, null))
    }

    @Test
    fun `every quick-links catalog entry has a resolvable target`() {
        // The catalog's whole promise is that a tile cannot point at a screen
        // the app does not have. That promise is only true if EVERY entry
        // resolves — a new catalog row whose target string was never added to
        // btWidgetDeepLink would render a perfectly good icon that does nothing.
        BtQuickLink.entries.forEach { link ->
            assertNotNull(
                "${link.key} resolves to no deep link",
                btWidgetDeepLink(link.target, assetId = null, portfolioId = "pf-1"),
            )
        }
    }

    @Test
    fun `a wallet posting carries its source and its direction`() {
        assertEquals(
            NotifDeepLink.AddCashEntry(portfolioId = "pf-1", sourceId = "src-1", inflow = false),
            btWidgetDeepLink(
                BT_WIDGET_TARGET_CASH_ENTRY,
                assetId = null,
                portfolioId = "pf-1",
                sourceId = "src-1",
                inflow = false,
            ),
        )
        assertEquals(
            NotifDeepLink.AddCashEntry(portfolioId = "pf-1", sourceId = "src-1", inflow = true),
            btWidgetDeepLink(
                BT_WIDGET_TARGET_CASH_ENTRY,
                assetId = null,
                portfolioId = "pf-1",
                sourceId = "src-1",
                inflow = true,
            ),
        )
    }

    @Test
    fun `a wallet posting with no direction opens cash rather than a blank sheet`() {
        // A button labelled "Bezahlt" that opened something neutral would have
        // lied about what it does; the honest degradation is the wallet's own
        // screen. A blank SOURCE is fine — the sheet's primary-source default is
        // a correct answer, just not a preselected one.
        assertEquals(
            NotifDeepLink.Cash("pf-1"),
            btWidgetDeepLink(BT_WIDGET_TARGET_CASH_ENTRY, null, "pf-1", "src-1", inflow = null),
        )
        assertEquals(
            NotifDeepLink.AddCashEntry(portfolioId = "pf-1", sourceId = null, inflow = true),
            btWidgetDeepLink(BT_WIDGET_TARGET_CASH_ENTRY, null, "pf-1", "   ", inflow = true),
        )
    }

    @Test
    fun `the two posting buttons are distinct PendingIntents`() {
        // PendingIntent equality ignores extras, so without the direction in the
        // ACTION string the launcher collapses Bezahlt and Erhalten onto
        // whichever was registered first — and every "Erhalten" tap books an
        // outflow. This is the money-shaped version of the per-row asset rule.
        val paid = btWidgetIntentAction(BT_WIDGET_TARGET_CASH_ENTRY, "pf-1", "src-1.out")
        val received = btWidgetIntentAction(BT_WIDGET_TARGET_CASH_ENTRY, "pf-1", "src-1.in")
        assertNotEquals(paid, received)
        // …and two wallets' same-direction buttons are distinct too.
        assertNotEquals(
            btWidgetIntentAction(BT_WIDGET_TARGET_CASH_ENTRY, "pf-1", "src-1.out"),
            btWidgetIntentAction(BT_WIDGET_TARGET_CASH_ENTRY, "pf-1", "src-2.out"),
        )
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
                        resolved is NotifDeepLink.AddCashEntry ||
                        resolved == NotifDeepLink.MarketSearch ||
                        resolved == NotifDeepLink.Watchlist,
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
