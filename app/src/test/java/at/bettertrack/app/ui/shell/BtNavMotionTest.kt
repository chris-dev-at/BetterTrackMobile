package at.bettertrack.app.ui.shell

import at.bettertrack.app.navigation.AssetPageRoute
import at.bettertrack.app.navigation.HoldingDetailRoute
import at.bettertrack.app.navigation.MarketsTabRoute
import at.bettertrack.app.navigation.PeopleTabRoute
import at.bettertrack.app.navigation.PortfolioTabRoute
import at.bettertrack.app.navigation.SettingsRoute
import at.bettertrack.app.navigation.TransactionFormRoute
import at.bettertrack.app.navigation.WorkbenchTabRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3 §1 — the screen-transition spec's decision layer.
 *
 * [BtNavMotion.isLateral] is the whole reason the graph can carry two motion
 * shapes without each destination getting a vote, so it is pinned here rather
 * than left to be judged by eye on a device: a regression would not crash, it
 * would just make tab hops slide as if the tabs had an order.
 */
class BtNavMotionTest {

    private val allTabs = listOf(
        PortfolioTabRoute::class.qualifiedName!!,
        WorkbenchTabRoute::class.qualifiedName!!,
        MarketsTabRoute::class.qualifiedName!!,
        PeopleTabRoute::class.qualifiedName!!,
    )

    // ── routeKey ────────────────────────────────────────────────────────────

    @Test
    fun `a route without arguments is its own key`() {
        val route = SettingsRoute::class.qualifiedName!!
        assertEquals(route, BtNavMotion.routeKey(route))
    }

    @Test
    fun `a required argument is stripped`() {
        val base = HoldingDetailRoute::class.qualifiedName!!
        assertEquals(base, BtNavMotion.routeKey("$base/{holdingId}"))
        assertEquals(base, BtNavMotion.routeKey("$base/AAPL"))
    }

    @Test
    fun `optional arguments are stripped`() {
        val base = TransactionFormRoute::class.qualifiedName!!
        assertEquals(
            base,
            BtNavMotion.routeKey("$base?transactionId={transactionId}&portfolioId={portfolioId}"),
        )
    }

    @Test
    fun `a null or empty route has no key`() {
        assertNull(BtNavMotion.routeKey(null))
        assertNull(BtNavMotion.routeKey(""))
        // A route that is nothing but an argument separator is not a
        // destination identity either — it must not be mistaken for one.
        assertNull(BtNavMotion.routeKey("/foo"))
    }

    // ── the tab set ─────────────────────────────────────────────────────────

    @Test
    fun `all four tabs and only the four tabs are lateral destinations`() {
        // Four since the owner IA change retired the Home tab. The count is
        // asserted separately from the set so a future tab added to BtTab but
        // forgotten here fails loudly instead of quietly widening the set.
        assertEquals(4, BtNavMotion.TAB_ROUTE_KEYS.size)
        assertEquals(allTabs.toSet(), BtNavMotion.TAB_ROUTE_KEYS)
    }

    // ── isLateral ───────────────────────────────────────────────────────────

    @Test
    fun `every tab pair is lateral in both directions`() {
        for (from in allTabs) {
            for (to in allTabs) {
                assertTrue(
                    "$from -> $to should be lateral",
                    BtNavMotion.isLateral(from, to),
                )
            }
        }
    }

    @Test
    fun `leaving a tab for a pushed screen is hierarchical`() {
        val holding = HoldingDetailRoute::class.qualifiedName!! + "/{holdingId}"
        val asset = AssetPageRoute::class.qualifiedName!! + "/{assetId}"
        // The two pairs the mandate names by hand (§4).
        assertFalse(BtNavMotion.isLateral(PortfolioTabRoute::class.qualifiedName, holding))
        assertFalse(BtNavMotion.isLateral(MarketsTabRoute::class.qualifiedName, asset))
        // …and their returns, which arrive through the pop lambdas.
        assertFalse(BtNavMotion.isLateral(holding, PortfolioTabRoute::class.qualifiedName))
        assertFalse(BtNavMotion.isLateral(asset, MarketsTabRoute::class.qualifiedName))
    }

    @Test
    fun `a push between two non-tab screens is hierarchical`() {
        assertFalse(
            BtNavMotion.isLateral(
                HoldingDetailRoute::class.qualifiedName!! + "/AAPL",
                TransactionFormRoute::class.qualifiedName!! + "?assetId={assetId}",
            ),
        )
    }

    @Test
    fun `an unknown route on either side is never lateral`() {
        assertFalse(BtNavMotion.isLateral(null, PeopleTabRoute::class.qualifiedName))
        assertFalse(BtNavMotion.isLateral(PeopleTabRoute::class.qualifiedName, null))
        assertFalse(BtNavMotion.isLateral(null, null))
    }

    // ── the rhythm ──────────────────────────────────────────────────────────

    @Test
    fun `both idioms share one 300ms rhythm with a 90-210 fade split`() {
        // The two shapes are allowed to differ; their timing is not — that is
        // what keeps the app feeling like one app across 40 destinations.
        assertEquals(
            BtNavMotion.DURATION_TOTAL_MS,
            BtNavMotion.DURATION_EXIT_MS + BtNavMotion.DURATION_ENTER_MS,
        )
        assertEquals(90, BtNavMotion.DURATION_EXIT_MS)
        assertEquals(210, BtNavMotion.DURATION_ENTER_MS)
    }
}
