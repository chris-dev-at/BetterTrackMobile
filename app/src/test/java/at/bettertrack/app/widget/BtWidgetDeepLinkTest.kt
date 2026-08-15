package at.bettertrack.app.widget

import at.bettertrack.app.data.notifications.NotifDeepLink
import at.bettertrack.app.data.notifications.resolveDeepLink
import at.bettertrack.app.navigation.BtTab
import at.bettertrack.app.navigation.owningTab
import org.junit.Assert.assertEquals
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
    fun `an unknown target resolves to nothing rather than guessing`() {
        assertNull(btWidgetDeepLink(null, null))
        assertNull(btWidgetDeepLink("", null))
        assertNull(btWidgetDeepLink("something-a-later-build-added", "AAPL"))
    }

    @Test
    fun `both widget targets land on a tab that renders them`() {
        assertEquals(BtTab.Portfolio, owningTab(NotifDeepLink.Overview))
        assertEquals(BtTab.Markets, owningTab(NotifDeepLink.Asset("AAPL")))
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
}
