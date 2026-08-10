package at.bettertrack.app.ui.market

import at.bettertrack.app.R
import at.bettertrack.app.data.api.dto.FundamentalsPeriodDto
import at.bettertrack.app.data.api.dto.FundamentalsRatiosDto
import at.bettertrack.app.data.api.dto.FundamentalsResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Pure-logic tests for the fundamentals card (platform arc f, board #76 item 1).
 *
 * The properties worth pinning are the ones a screenshot cannot catch:
 *
 *  - the wire is **most-recent-first** and the chart is **oldest-first**, so a
 *    silent loss of that reversal would draw a company's history backwards and
 *    still look like a plausible chart;
 *  - the cap has to bite the FAR end of history, not the near one, or "last 8
 *    years" quietly becomes "the 8 years before the ones you wanted";
 *  - a `null` figure must never become a `0.0` bar or a printed "null";
 *  - `profitMargin`/`returnOnEquity` are FRACTIONS and `debtToEquity` is NOT,
 *    which is the one unit trap on this surface.
 */
class FundamentalsSectionTest {

    private val de = Locale.GERMAN
    private val en = Locale.ENGLISH

    private fun period(
        fiscalPeriod: String = "FY",
        year: Int? = null,
        revenue: Double? = null,
        netIncome: Double? = null,
    ) = FundamentalsPeriodDto(
        fiscalPeriod = fiscalPeriod,
        fiscalYear = year,
        revenue = revenue,
        netIncome = netIncome,
    )

    private fun response(
        available: Boolean = true,
        currency: String? = "USD",
        period: String = "annual",
        periods: List<FundamentalsPeriodDto> = emptyList(),
        ratios: FundamentalsRatiosDto = FundamentalsRatiosDto(),
    ) = FundamentalsResponse(available, currency, period, periods, ratios)

    // ── Bars: order, cap, gaps ──────────────────────────────────────────────

    @Test
    fun `bars come out oldest-first from a most-recent-first wire`() {
        val r = response(
            periods = listOf(
                period(year = 2025, revenue = 3.0),
                period(year = 2024, revenue = 2.0),
                period(year = 2023, revenue = 1.0),
            ),
        )
        val bars = fundamentalsChartBars(r)
        assertEquals(listOf(2023, 2024, 2025), bars.map { it.fiscalYear })
        assertEquals(1.0, bars.first().revenue!!, 0.0)
    }

    @Test
    fun `the cap keeps the most recent periods, not the oldest`() {
        val r = response(
            periods = (2025 downTo 2010).map { period(year = it, revenue = it.toDouble()) },
        )
        val bars = fundamentalsChartBars(r, cap = 4)
        // Newest four (2022..2025), rendered oldest-first.
        assertEquals(listOf(2022, 2023, 2024, 2025), bars.map { it.fiscalYear })
    }

    @Test
    fun `a period with neither figure is dropped rather than drawn as zero`() {
        val r = response(
            periods = listOf(
                period(year = 2025, revenue = 3.0, netIncome = 1.0),
                period(year = 2024, revenue = null, netIncome = null),
                period(year = 2023, revenue = 1.0),
            ),
        )
        val bars = fundamentalsChartBars(r)
        assertEquals(listOf(2023, 2025), bars.map { it.fiscalYear })
        // The surviving one-sided period keeps its null, it does not gain a zero.
        assertNull(bars.first().netIncome)
    }

    @Test
    fun `a non-finite provider figure is treated as absent`() {
        val bars = fundamentalsChartBars(
            response(periods = listOf(period(year = 2025, revenue = Double.NaN, netIncome = 5.0))),
        )
        assertNull(bars.single().revenue)
        assertEquals(5.0, bars.single().netIncome!!, 0.0)
    }

    // ── Scale ───────────────────────────────────────────────────────────────

    @Test
    fun `the scale is anchored at zero so bar lengths stay comparable`() {
        val bars = fundamentalsChartBars(
            response(
                periods = listOf(
                    period(year = 2025, revenue = 100.0, netIncome = 20.0),
                    period(year = 2024, revenue = 80.0, netIncome = 10.0),
                ),
            ),
        )
        val scale = fundamentalsChartScale(bars)
        assertEquals(0.0, scale.start, 0.0)
        assertTrue(scale.endInclusive > 100.0)
    }

    @Test
    fun `a loss-making year pushes the window below zero`() {
        val bars = fundamentalsChartBars(
            response(
                periods = listOf(
                    period(year = 2025, revenue = 100.0, netIncome = -40.0),
                    period(year = 2024, revenue = 80.0, netIncome = 10.0),
                ),
            ),
        )
        val scale = fundamentalsChartScale(bars)
        assertTrue("loss must be inside the window", scale.start < -40.0)
        assertTrue(scale.endInclusive > 100.0)
    }

    @Test
    fun `an all-zero series still gets a drawable window`() {
        val scale = fundamentalsChartScale(
            listOf(FundamentalsBar("FY", 2025, 0.0, 0.0)),
        )
        assertTrue(scale.start < scale.endInclusive)
    }

    @Test
    fun `one period is a number with a rectangle around it, not a trend`() {
        assertFalse(
            fundamentalsChartWorthDrawing(listOf(FundamentalsBar("FY", 2025, 1.0, 1.0))),
        )
        assertTrue(
            fundamentalsChartWorthDrawing(
                listOf(FundamentalsBar("FY", 2024, 1.0, null), FundamentalsBar("FY", 2025, 2.0, null)),
            ),
        )
        assertFalse(fundamentalsChartWorthDrawing(emptyList()))
    }

    // ── Axis labels + hit testing ───────────────────────────────────────────

    @Test
    fun `axis labels are a short year annually and the bare quarter quarterly`() {
        assertEquals("'25", fundamentalsAxisLabel(FundamentalsBar("FY", 2025, null, null)))
        assertEquals("'09", fundamentalsAxisLabel(FundamentalsBar("FY", 2009, null, null)))
        assertEquals("Q3", fundamentalsAxisLabel(FundamentalsBar("Q3", 2025, null, null)))
        // No fiscal year to abbreviate: say the period rather than nothing.
        assertEquals("FY", fundamentalsAxisLabel(FundamentalsBar("FY", null, null, null)))
    }

    @Test
    fun `a touch maps to the group under it and clamps at both edges`() {
        assertEquals(0, fundamentalsIndexAt(x = 0f, width = 400f, count = 4))
        assertEquals(1, fundamentalsIndexAt(x = 150f, width = 400f, count = 4))
        assertEquals(3, fundamentalsIndexAt(x = 399f, width = 400f, count = 4))
        // Past the right edge (a drag that overshoots) must not index out of range.
        assertEquals(3, fundamentalsIndexAt(x = 900f, width = 400f, count = 4))
        assertEquals(0, fundamentalsIndexAt(x = -20f, width = 400f, count = 4))
        assertEquals(0, fundamentalsIndexAt(x = 10f, width = 400f, count = 0))
    }

    // ── Emptiness ───────────────────────────────────────────────────────────

    @Test
    fun `available with nothing in it is empty, and one ratio is not`() {
        assertTrue(fundamentalsEmpty(response()))
        assertFalse(fundamentalsEmpty(response(periods = listOf(period(year = 2025, revenue = 1.0)))))
        assertFalse(
            fundamentalsEmpty(response(ratios = FundamentalsRatiosDto(trailingPe = 12.0))),
        )
    }

    @Test
    fun `an all-null ratio block counts as no ratios`() {
        assertFalse(fundamentalsHasAnyRatio(FundamentalsRatiosDto()))
        assertTrue(fundamentalsHasAnyRatio(FundamentalsRatiosDto(marketCap = 1.0)))
        // A provider that answered with NaN has told us nothing either.
        assertFalse(fundamentalsHasAnyRatio(FundamentalsRatiosDto(marketCap = Double.NaN)))
    }

    // ── Ratios: units and collapsing ────────────────────────────────────────

    @Test
    fun `fractions become percent units exactly once`() {
        assertEquals(26.9, fundamentalsFractionPercent(0.269)!!, 1e-9)
        assertNull(fundamentalsFractionPercent(null))
        assertNull(fundamentalsFractionPercent(Double.NaN))
    }

    @Test
    fun `every absent ratio is dropped instead of rendered as a dash`() {
        val stats = fundamentalsRatioStats(
            FundamentalsRatiosDto(trailingPe = 38.2),
            currency = "USD",
            locale = de,
        )
        assertEquals(1, stats.size)
        assertEquals(R.string.bt_fundamentals_pe, stats.single().labelRes)
        assertEquals("38,20", stats.single().value)
        assertTrue(fundamentalsRatioStats(FundamentalsRatiosDto(), "USD", de).isEmpty())
    }

    @Test
    fun `the full ratio set renders in reading order with the right units`() {
        val stats = fundamentalsRatioStats(
            FundamentalsRatiosDto(
                marketCap = 3_900_000_000_000.0,
                trailingPe = 38.2,
                profitMargin = 0.269,
                returnOnEquity = 1.497,
                debtToEquity = 145.0,
                trailingEps = 6.9,
            ),
            currency = "USD",
            locale = de,
        )
        assertEquals(
            listOf(
                R.string.bt_fundamentals_market_cap,
                R.string.bt_fundamentals_pe,
                R.string.bt_fundamentals_profit_margin,
                R.string.bt_fundamentals_roe,
                R.string.bt_fundamentals_debt_equity,
                R.string.bt_fundamentals_trailing_eps,
            ),
            stats.map { it.labelRes },
        )
        assertEquals("3,9 Bio. $", stats[0].value)
        assertEquals("38,20", stats[1].value)
        // A FRACTION, scaled once.
        assertEquals("26,90 %", stats[2].value)
        assertEquals("149,70 %", stats[3].value)
        // NOT a fraction — already percent units, so it must NOT become 14.500 %.
        assertEquals("145,00 %", stats[4].value)
        assertEquals("6,90", stats[5].value)
    }

    @Test
    fun `English renders the same ratio set in its own conventions`() {
        val stats = fundamentalsRatioStats(
            FundamentalsRatiosDto(marketCap = 3_900_000_000_000.0, profitMargin = 0.269),
            currency = "USD",
            locale = en,
        )
        assertEquals("3.9T $", stats[0].value)
        assertEquals("26.90%", stats[1].value)
    }

    @Test
    fun `a market cap with no currency shows the magnitude without inventing a symbol`() {
        // Stamping the app's default € on a US market cap is exactly the
        // mislabelling `intelAmountRenderable` exists to prevent — but the
        // magnitude itself is still true, so it is shown bare rather than dropped.
        val stats = fundamentalsRatioStats(
            FundamentalsRatiosDto(marketCap = 3_900_000_000_000.0),
            currency = null,
            locale = de,
        )
        assertEquals("3,9 Bio.", stats.single().value)
    }

    @Test
    fun `the period toggle carries the contract's own query values`() {
        assertEquals("annual", FundamentalsPeriodType.ANNUAL.wire)
        assertEquals("quarterly", FundamentalsPeriodType.QUARTERLY.wire)
        assertEquals(2, FUNDAMENTALS_PERIOD_TYPES.size)
    }

    @Test
    fun `the period toggle hides when there is nothing to toggle between`() {
        // Device-found (2026-08-10): a crypto asset serves a market cap and NO
        // statements in either granularity, and was rendering an annual/quarterly
        // switch whose only possible effect was to swap one empty view for
        // another.
        assertFalse(
            fundamentalsShowPeriodToggle(
                hasChart = false,
                period = FundamentalsPeriodType.ANNUAL,
                loading = false,
            ),
        )
        // A real series: the toggle is the whole point.
        assertTrue(
            fundamentalsShowPeriodToggle(true, FundamentalsPeriodType.ANNUAL, loading = false),
        )
        // Already off the default and the answer came back empty — the toggle must
        // survive, or there is no way back to the granularity that had data.
        assertTrue(
            fundamentalsShowPeriodToggle(false, FundamentalsPeriodType.QUARTERLY, loading = false),
        )
        // Mid-refetch it stays put rather than vanishing under the finger that
        // just pressed it.
        assertTrue(
            fundamentalsShowPeriodToggle(false, FundamentalsPeriodType.ANNUAL, loading = true),
        )
    }

    @Test
    fun `the requested cap stays inside the contract's server-side clamp`() {
        // The service clamps to 1..12; asking for more would silently return 12
        // and make the chart's own cap a lie.
        assertTrue(FUNDAMENTALS_CHART_CAP in 1..12)
    }
}
