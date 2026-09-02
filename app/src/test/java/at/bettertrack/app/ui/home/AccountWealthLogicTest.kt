package at.bettertrack.app.ui.home

import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.data.db.PortfolioTotals
import at.bettertrack.app.data.repo.HistoryPoint
import at.bettertrack.app.data.repo.HistoryRange
import at.bettertrack.app.data.repo.PerformancePoint
import at.bettertrack.app.data.repo.PortfolioHistory
import at.bettertrack.app.ui.portfolio.PORTFOLIO_RANGES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the account-wealth view is allowed to draw.
 *
 * The interesting failures on this surface are all silent ones — a curve labelled
 * as the whole account when it is one portfolio of four, an archived portfolio
 * contributing a curve the hero above it does not count, a range segment the
 * client cannot actually request, a "chart" drawn through a single point. None of
 * them look wrong in a screenshot, so they are pinned here instead.
 */
class AccountWealthLogicTest {

    // ── Fixtures ────────────────────────────────────────────────────────────

    private fun totals(total: Double) = PortfolioTotals(
        marketValueEur = total,
        investedEur = 0.0,
        unrealizedPnlEur = 0.0,
        unrealizedPnlPct = null,
        dayChangeEur = 0.0,
        dayChangePct = null,
        cashEur = 0.0,
        totalValueEur = total,
    )

    private fun portfolio(
        id: String,
        name: String = id,
        total: Double? = null,
        archived: Boolean = false,
    ) = PortfolioEntity(
        id = id,
        name = name,
        visibility = "private",
        sortOrder = 0,
        isDefault = false,
        defaultPayFromCash = false,
        archivedAt = if (archived) "2026-01-01T00:00:00Z" else null,
        baseCurrency = "EUR",
        totals = total?.let { totals(it) },
        detailSyncedAtMs = total?.let { 1L },
    )

    private fun history(
        id: String = "p1",
        points: Int,
        pct: Double? = 4.2,
        syncedAtMs: Long = 1_000L,
    ) = PortfolioHistory(
        portfolioId = id,
        range = HistoryRange.M1,
        baseCurrency = "EUR",
        points = (0 until points).map { HistoryPoint(it * 86_400_000L, 100.0 + it) },
        performance = pct?.let { listOf(PerformancePoint(0L, it)) }.orEmpty(),
        syncedAtMs = syncedAtMs,
    )

    // ── The single-vs-multi decision ────────────────────────────────────────

    @Test
    fun `no portfolios means there is no account history to draw`() {
        assertEquals(AccountWealthScope.None, accountWealthScope(emptyList()))
        assertEquals(emptyList<String>(), accountWealthScope(emptyList()).chartedIds())
    }

    @Test
    fun `exactly one active portfolio is the whole account, not a sample of it`() {
        val only = portfolio("p1", total = 100.0)
        val scope = accountWealthScope(listOf(only))
        assertEquals(AccountWealthScope.Single(only), scope)
        assertEquals(listOf("p1"), scope.chartedIds())
    }

    @Test
    fun `two active portfolios are small multiples, never one curve`() {
        val scope = accountWealthScope(
            listOf(portfolio("p1", total = 100.0), portfolio("p2", total = 50.0)),
        )
        val multi = scope as AccountWealthScope.Multi
        assertEquals(listOf("p1", "p2"), multi.charted.map { it.id })
        assertEquals(0, multi.omitted)
    }

    // ── The active/archived filter ──────────────────────────────────────────

    @Test
    fun `archived portfolios never contribute a curve`() {
        // The hero above this view sums ACTIVE portfolios only. A view that
        // charted an archived one would show a shape the headline does not count.
        val scope = accountWealthScope(
            listOf(
                portfolio("live", total = 100.0),
                portfolio("old", total = 900.0, archived = true),
            ),
        )
        assertEquals(AccountWealthScope.Single(portfolio("live", total = 100.0)), scope)
        assertEquals(listOf("live"), scope.chartedIds())
    }

    @Test
    fun `an account whose every portfolio is archived has nothing to draw`() {
        val scope = accountWealthScope(
            listOf(
                portfolio("a", total = 1.0, archived = true),
                portfolio("b", total = 2.0, archived = true),
            ),
        )
        assertEquals(AccountWealthScope.None, scope)
    }

    @Test
    fun `archiving all but one collapses the view to the single-portfolio case`() {
        val scope = accountWealthScope(
            listOf(
                portfolio("keep", total = 10.0),
                portfolio("gone1", total = 20.0, archived = true),
                portfolio("gone2", total = 30.0, archived = true),
            ),
        )
        assertTrue("three portfolios, one active ⇒ Single", scope is AccountWealthScope.Single)
    }

    // ── Ordering and the fan-out cap ────────────────────────────────────────

    @Test
    fun `curves are ordered by server total value, biggest first`() {
        val scope = accountWealthScope(
            listOf(
                portfolio("small", total = 10.0),
                portfolio("big", total = 900.0),
                portfolio("mid", total = 100.0),
            ),
        ) as AccountWealthScope.Multi
        assertEquals(listOf("big", "mid", "small"), scope.charted.map { it.id })
    }

    @Test
    fun `an unsynced portfolio sorts last rather than as a zero`() {
        // `totals == null` means "not fetched yet", not "worth nothing". It must
        // not outrank a portfolio the server has actually valued at 0.
        val scope = accountWealthScope(
            listOf(
                portfolio("unknown", total = null),
                portfolio("zero", total = 0.0),
                portfolio("some", total = 5.0),
            ),
        ) as AccountWealthScope.Multi
        assertEquals(listOf("some", "zero", "unknown"), scope.charted.map { it.id })
    }

    @Test
    fun `equal totals break by name then id, so the list cannot reshuffle`() {
        val scope = accountWealthScope(
            listOf(
                portfolio("z", name = "Alpha", total = 5.0),
                portfolio("a", name = "Alpha", total = 5.0),
                portfolio("m", name = "Beta", total = 5.0),
            ),
        ) as AccountWealthScope.Multi
        assertEquals(listOf("a", "z", "m"), scope.charted.map { it.id })
    }

    @Test
    fun `portfolios past the cap are counted, never silently dropped`() {
        val all = (1..15).map { portfolio("p%02d".format(it), total = it.toDouble()) }
        val scope = accountWealthScope(all) as AccountWealthScope.Multi
        assertEquals(ACCOUNT_WEALTH_MAX_SERIES, scope.charted.size)
        assertEquals(15 - ACCOUNT_WEALTH_MAX_SERIES, scope.omitted)
        assertEquals(ACCOUNT_WEALTH_MAX_SERIES, scope.chartedIds().size)
        // The cap keeps the money, not the alphabet.
        assertEquals("p15", scope.charted.first().id)
    }

    @Test
    fun `a cap below one still charts one curve rather than none`() {
        val scope = accountWealthScope(
            listOf(portfolio("a", total = 2.0), portfolio("b", total = 1.0)),
            maxSeries = 0,
        ) as AccountWealthScope.Multi
        assertEquals(listOf("a"), scope.charted.map { it.id })
        assertEquals(1, scope.omitted)
    }

    // ── Range mapping ───────────────────────────────────────────────────────

    @Test
    fun `the view offers exactly the windows the portfolio hero offers`() {
        // Aliased, not copied: both are `GET /portfolios/{id}/history` calls, so
        // they must never disagree about which windows exist.
        assertEquals(PORTFOLIO_RANGES, ACCOUNT_WEALTH_RANGES)
    }

    @Test
    fun `every offered window is one the client can actually request`() {
        ACCOUNT_WEALTH_RANGES.forEach { range ->
            assertEquals(
                "wire value must round-trip through the client's own enum",
                range,
                HistoryRange.fromWire(range.wire),
            )
        }
        assertEquals(
            listOf("1D", "1W", "1M", "1Y", "MAX"),
            ACCOUNT_WEALTH_RANGES.map { it.wire },
        )
    }

    @Test
    fun `the default window is one of the offered ones`() {
        assertTrue(ACCOUNT_WEALTH_DEFAULT_RANGE in ACCOUNT_WEALTH_RANGES)
        assertEquals(HistoryRange.DEFAULT, ACCOUNT_WEALTH_DEFAULT_RANGE)
    }

    @Test
    fun `a window this view does not offer falls back to the default`() {
        // 6M is a real `HistoryRange` the client can send, but the rail draws no
        // segment for it — selecting it would leave the control with no winner.
        assertEquals(ACCOUNT_WEALTH_DEFAULT_RANGE, accountWealthRangeOrDefault(HistoryRange.M6))
        assertEquals(ACCOUNT_WEALTH_DEFAULT_RANGE, accountWealthRangeOrDefault(null))
    }

    @Test
    fun `an offered window is kept exactly as asked`() {
        ACCOUNT_WEALTH_RANGES.forEach { range ->
            assertEquals(range, accountWealthRangeOrDefault(range))
        }
    }

    // ── The empty / one-point series guards ─────────────────────────────────

    @Test
    fun `nothing cached and no verdict yet is loading, not an empty chart`() {
        assertEquals(AccountSeriesState.Loading, accountSeriesState(null, failed = false))
    }

    @Test
    fun `nothing cached after a failed fetch says so`() {
        assertEquals(AccountSeriesState.Failed, accountSeriesState(null, failed = true))
    }

    @Test
    fun `an empty series is empty, not a flat line`() {
        assertEquals(AccountSeriesState.Empty, accountSeriesState(history(points = 0)))
    }

    @Test
    fun `one point is not a short curve`() {
        // A chart through a single point has to invent either a second point or a
        // direction. The honest statement is that this window has no shape yet.
        assertEquals(AccountSeriesState.Empty, accountSeriesState(history(points = 1)))
    }

    @Test
    fun `two points draw, and the percentage is the server's own`() {
        val state = accountSeriesState(history(points = 2, pct = -3.5)) as AccountSeriesState.Curve
        assertEquals(2, state.points.size)
        assertEquals(-3.5, state.rangePerformancePct!!, 1e-9)
        assertEquals(1_000L, state.syncedAtMs)
    }

    @Test
    fun `a server series with no performance points carries no percentage`() {
        val state = accountSeriesState(history(points = 3, pct = null)) as AccountSeriesState.Curve
        assertNull(state.rangePerformancePct)
    }

    @Test
    fun `a cached curve keeps drawing when the refresh fails`() {
        // Offline-first: the banner already says how old the data is, and blanking
        // a real curve to announce a failed request is strictly less information.
        val state = accountSeriesState(history(points = 5), failed = true)
        assertTrue(state is AccountSeriesState.Curve)
    }

    // ── Never a void: the skeleton has to be able to end (defect #3) ────────

    @Test
    fun `a settled window with nothing cached is empty, not a permanent skeleton`() {
        // Device 2026-09-01: on 1D the portfolio *Main* rendered a bare dark
        // rectangle — the loading skeleton — with no curve, no words and no
        // percentage, while its neighbours drew. The fan-out had already finished;
        // that id's series simply never reached Room, and `history == null` looked
        // exactly like "the first Room emission has not arrived yet".
        assertEquals(
            AccountSeriesState.Empty,
            accountSeriesState(null, failed = false, settled = true),
        )
    }

    @Test
    fun `an unsettled window with nothing cached is still loading`() {
        assertEquals(
            AccountSeriesState.Loading,
            accountSeriesState(null, failed = false, settled = false),
        )
    }

    @Test
    fun `a failed slot names its failure even once the window has settled`() {
        // "Couldn't load this curve" outranks "nothing to draw": the user can retry
        // the first and can do nothing about the second.
        assertEquals(
            AccountSeriesState.Failed,
            accountSeriesState(null, failed = true, settled = true),
        )
    }

    @Test
    fun `a series whose values are not finite is empty rather than an unpainted canvas`() {
        // A NaN maps to a NaN pixel and a path with a NaN vertex rasterises to
        // nothing, so this would otherwise take the Curve branch and then draw the
        // very void the empty state exists to replace.
        val nan = history(points = 2).copy(
            points = listOf(HistoryPoint(0L, Double.NaN), HistoryPoint(1L, Double.NaN)),
        )
        assertEquals(AccountSeriesState.Empty, accountSeriesState(nan, settled = true))
    }

    @Test
    fun `non-finite points are dropped from a curve that still has two real ones`() {
        val mixed = history(points = 3).copy(
            points = listOf(
                HistoryPoint(0L, 100.0),
                HistoryPoint(1L, Double.POSITIVE_INFINITY),
                HistoryPoint(2L, 120.0),
            ),
        )
        val state = accountSeriesState(mixed, settled = true) as AccountSeriesState.Curve
        assertEquals(2, state.points.size)
        assertTrue(state.points.all { it.valueEur.isFinite() })
    }

    @Test
    fun `an all-finite series is handed through untouched`() {
        val h = history(points = 4)
        val state = accountSeriesState(h) as AccountSeriesState.Curve
        assertEquals(h.points, state.points)
    }

    // ── Defect #2: the percentage is the SERVER's, even when it looks wrong ──

    @Test
    fun `a rising curve still reports the server's negative performance`() {
        // Device 2026-09-01: portfolio *penischain* drew a flat-then-rising MAX
        // curve and labelled it −32,73 %. This test pins WHERE that figure comes
        // from, so the next reader does not "fix" it into a client-side
        // (last − first) / first, which would print +9 110 891 % here.
        //
        // Both readings are legitimate arithmetic and they disagree by design: the
        // points series is net worth (deposits move it) and the performance series
        // is a chain-linked time-weighted return (deposits do not). §7.1 says the
        // server owns the second one, so the app reads it and never derives it.
        val rising = PortfolioHistory(
            portfolioId = "penischain",
            range = HistoryRange.MAX,
            baseCurrency = "EUR",
            points = listOf(
                HistoryPoint(0L, 1.0),
                HistoryPoint(86_400_000L, 2.0),
                HistoryPoint(172_800_000L, 91_109.91),
            ),
            performance = listOf(
                PerformancePoint(0L, 0.0),
                PerformancePoint(86_400_000L, -50.0),
                PerformancePoint(172_800_000L, -32.73),
            ),
            syncedAtMs = 1_000L,
        )
        val state = accountSeriesState(rising, settled = true) as AccountSeriesState.Curve
        assertEquals(-32.73, state.rangePerformancePct!!, 1e-9)
        // …and specifically the LAST performance point, not the first and not an
        // average of them.
        assertEquals(rising.performance.last().pct, state.rangePerformancePct!!, 1e-9)
    }

    // ── The "as of" stamp ───────────────────────────────────────────────────

    @Test
    fun `the as-of stamp is the oldest sync among the drawn series`() {
        val stamp = accountWealthAsOfMs(
            listOf(
                history(id = "a", points = 2, syncedAtMs = 12_000L),
                history(id = "b", points = 2, syncedAtMs = 10_000L),
                history(id = "c", points = 2, syncedAtMs = 11_000L),
            ),
        )
        // "as of 12:00" would be false about the series synced at 10:00.
        assertEquals(10_000L, stamp)
    }

    @Test
    fun `no cached series means no as-of stamp`() {
        assertNull(accountWealthAsOfMs(emptyList()))
    }
}
