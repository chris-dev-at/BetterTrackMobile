package at.bettertrack.app.ui.home

import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.data.db.PortfolioTotals
import at.bettertrack.app.ui.prices.NetWorthState
import at.bettertrack.app.ui.prices.PriceCoverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Home's hero is allowed to claim.
 *
 * Home is the first screen the app shows, so a number that is quietly wrong here
 * is the number the user remembers. Two ways it could be quietly wrong, both
 * pinned below:
 *
 *  1. **Unsynced portfolios.** `totals` is null until a portfolio's detail has
 *     been fetched once, and `?: 0.0` would silently shrink the sum on every
 *     cold start with no visual difference from a complete figure.
 *  2. **Averaged percentages.** A day change of "+5%" derived by averaging two
 *     portfolios' percentages is not an approximation of the truth, it is an
 *     unrelated number — and it is arbitrarily far from it whenever the
 *     portfolios differ in size.
 */
class HomeLogicTest {

    // ── Fixtures ────────────────────────────────────────────────────────────

    private fun totals(
        total: Double,
        cash: Double = 0.0,
        dayChange: Double = 0.0,
    ) = PortfolioTotals(
        marketValueEur = total - cash,
        investedEur = 0.0,
        unrealizedPnlEur = 0.0,
        unrealizedPnlPct = null,
        dayChangeEur = dayChange,
        // Deliberately absurd per-portfolio percentages in the fixtures: if the
        // implementation ever reads THIS field instead of deriving from sums,
        // the tests below fail loudly rather than plausibly.
        dayChangePct = 999.0,
        cashEur = cash,
        totalValueEur = total,
    )

    private fun portfolio(
        id: String,
        totals: PortfolioTotals?,
        archived: Boolean = false,
    ) = PortfolioEntity(
        id = id,
        name = id,
        visibility = "private",
        sortOrder = 0,
        isDefault = false,
        defaultPayFromCash = false,
        archivedAt = if (archived) "2026-01-01T00:00:00Z" else null,
        baseCurrency = "EUR",
        totals = totals,
        detailSyncedAtMs = totals?.let { 1L },
    )

    /** Everything priced — the coverage that lets a total stand uncaveated. */
    private val fullCoverage = PriceCoverage(priced = 4, unpriced = 0)

    // ── The sum ─────────────────────────────────────────────────────────────

    @Test
    fun `all portfolios synced yields an exact sum over all of them`() {
        val state = homeNetWorth(
            active = listOf(
                portfolio("a", totals(total = 10_000.0, cash = 1_000.0)),
                portfolio("b", totals(total = 2_500.50, cash = 500.0)),
            ),
            coverage = fullCoverage,
        )

        val ready = state as HomeHeroState.Ready
        val worth = ready.netWorth as NetWorthState.Value
        assertEquals(12_500.50, worth.eur, 1e-9)
        assertEquals(2, ready.covered)
        assertEquals(2, ready.active)
        assertFalse("a complete sum must not advertise a scope", ready.partial)
    }

    @Test
    fun `a partial sum always reports the scope it covers`() {
        // The one that matters: two portfolios, one synced. The figure is true
        // about what it covers, and the renderer is obliged to say so.
        val state = homeNetWorth(
            active = listOf(
                portfolio("synced", totals(total = 10_000.0)),
                portfolio("not-yet", totals = null),
            ),
            coverage = fullCoverage,
        )

        val ready = state as HomeHeroState.Ready
        assertEquals(10_000.0, (ready.netWorth as NetWorthState.Value).eur, 1e-9)
        assertEquals(1, ready.covered)
        assertEquals(2, ready.active)
        assertTrue("a partial sum must never render as a bare number", ready.partial)
    }

    @Test
    fun `no portfolio synced yields the skeleton state, never a zero`() {
        val state = homeNetWorth(
            active = listOf(portfolio("a", totals = null), portfolio("b", totals = null)),
            coverage = PriceCoverage.EMPTY,
        )
        assertEquals(HomeHeroState.Loading, state)
    }

    @Test
    fun `no active portfolio is a different state from nothing synced yet`() {
        // "We don't know yet" waits; "there is nothing" invites. Collapsing the
        // two would show a create-a-portfolio prompt to a user who has three.
        assertEquals(HomeHeroState.NoPortfolios, homeNetWorth(emptyList(), PriceCoverage.EMPTY))
    }

    @Test
    fun `archived portfolios are excluded from the active set`() {
        val all = listOf(
            portfolio("live", totals(total = 1_000.0)),
            portfolio("archived", totals(total = 9_000.0), archived = true),
        )
        val active = homeActivePortfolios(all)
        assertEquals(listOf("live"), active.map { it.id })

        val ready = homeNetWorth(active, fullCoverage) as HomeHeroState.Ready
        assertEquals(1_000.0, (ready.netWorth as NetWorthState.Value).eur, 1e-9)
        assertEquals("an archived portfolio is not a missing one", 1, ready.active)
        assertFalse(ready.partial)
    }

    // ── The day change ──────────────────────────────────────────────────────

    @Test
    fun `day change percent comes from the sums, not an average of percents`() {
        // €100,000 up €100 and €1,000 up €100: +€200 on a €200,800 previous
        // close = +0.0996%. Averaging the per-portfolio percentages — which the
        // fixtures set to 999.0 — would give something wildly different, and so
        // would averaging the real ones (0.1% and 11.1% → 5.6%).
        val ready = homeNetWorth(
            active = listOf(
                portfolio("big", totals(total = 100_100.0, dayChange = 100.0)),
                portfolio("small", totals(total = 1_100.0, dayChange = 100.0)),
            ),
            coverage = fullCoverage,
        ) as HomeHeroState.Ready

        assertEquals(200.0, ready.dayChangeEur, 1e-9)
        assertEquals(200.0 / 101_000.0 * 100.0, ready.dayChangePct!!, 1e-9)
        assertTrue("the fixtures' per-portfolio pct must not leak in", ready.dayChangePct!! < 1.0)
    }

    @Test
    fun `a negative day is summed and signed correctly`() {
        val ready = homeNetWorth(
            active = listOf(
                portfolio("a", totals(total = 900.0, dayChange = -100.0)),
                portfolio("b", totals(total = 1_050.0, dayChange = 50.0)),
            ),
            coverage = fullCoverage,
        ) as HomeHeroState.Ready

        assertEquals(-50.0, ready.dayChangeEur, 1e-9)
        // Previous close = 1950 - (-50) = 2000.
        assertEquals(-50.0 / 2_000.0 * 100.0, ready.dayChangePct!!, 1e-9)
    }

    @Test
    fun `a portfolio whose whole value arrived today has no percentage to show`() {
        // total == dayChange ⇒ yesterday's close was zero. There is no percentage
        // change from nothing, and printing one would require inventing a
        // denominator.
        val ready = homeNetWorth(
            active = listOf(portfolio("new", totals(total = 500.0, dayChange = 500.0))),
            coverage = fullCoverage,
        ) as HomeHeroState.Ready

        assertEquals(500.0, ready.dayChangeEur, 1e-9)
        assertNull(ready.dayChangePct)
        assertTrue("the euro figure is still real and still shown", ready.showDayChange)
    }

    @Test
    fun `with nothing priced the day change line is suppressed entirely`() {
        // W6, one line below the hero: a sum of zeroes would render "+0,00 € ·
        // today" and read as "flat" when the truth is "not known".
        val ready = homeNetWorth(
            active = listOf(portfolio("a", totals(total = 250.0, cash = 250.0))),
            coverage = PriceCoverage(priced = 0, unpriced = 3),
        ) as HomeHeroState.Ready

        assertFalse(ready.showDayChange)
        // The cash is real, so the FIGURE still renders — only the day line goes.
        assertTrue(ready.netWorth is NetWorthState.Value)
    }

    @Test
    fun `nothing priced and no cash is unpriceable, not a zero`() {
        val ready = homeNetWorth(
            active = listOf(portfolio("a", totals(total = 0.0, cash = 0.0))),
            coverage = PriceCoverage(priced = 0, unpriced = 2),
        ) as HomeHeroState.Ready

        assertTrue(
            "every candidate number here is a 0 that means 'not known'",
            ready.netWorth is NetWorthState.Unpriceable,
        )
    }

    @Test
    fun `the unpriced-holdings caveat crosses the portfolio boundary`() {
        // Coverage is computed over the UNION of active portfolios' holdings, so
        // an unpriced holding in portfolio B must caveat the Home total even
        // when portfolio A is fully priced.
        val ready = homeNetWorth(
            active = listOf(
                portfolio("a", totals(total = 1_000.0)),
                portfolio("b", totals(total = 2_000.0)),
            ),
            coverage = PriceCoverage(priced = 5, unpriced = 1),
        ) as HomeHeroState.Ready

        val worth = ready.netWorth as NetWorthState.Value
        assertFalse("an incomplete coverage must be carried to the renderer", worth.complete)
        assertEquals(1, worth.coverage.unpriced)
        // Both portfolios ARE synced, so the portfolio-scope caveat stays off:
        // the two caveats are independent and neither substitutes for the other.
        assertFalse(ready.partial)
    }

    @Test
    fun `an empty account with no holdings may honestly show zero`() {
        // A portfolio with nothing in it really is worth its cash, and 0,00 € is
        // the answer rather than a lie — the coverage is complete because there
        // was nothing to cover.
        val ready = homeNetWorth(
            active = listOf(portfolio("empty", totals(total = 0.0))),
            coverage = PriceCoverage.EMPTY,
        ) as HomeHeroState.Ready

        val worth = ready.netWorth as NetWorthState.Value
        assertEquals(0.0, worth.eur, 1e-9)
        assertTrue(worth.complete)
    }
}
