package at.bettertrack.app.widget

import at.bettertrack.app.data.db.HoldingEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chart widgets' data half: grouping, folding, fractions and scaling.
 *
 * These functions decide everything a diagram CLAIMS — which slice exists, how
 * big it reads, how full a ring is — while the raster half only draws what it
 * is handed. So the claims are pinned here, on the JVM, where a wrong fraction
 * is a red test instead of a plausible-looking ring on a launcher.
 */
class BtWidgetChartDataTest {

    private fun holding(
        assetId: String,
        type: String = "stock",
        marketValueEur: Double? = 100.0,
        dayPct: Double? = null,
        dayEur: Double? = null,
        portfolioId: String = "p1",
    ) = HoldingEntity(
        portfolioId = portfolioId,
        assetId = assetId,
        assetSymbol = assetId,
        assetName = "$assetId Inc.",
        assetExchange = null,
        assetCurrency = "USD",
        assetType = type,
        assetIsCustom = false,
        quantity = 1.0,
        avgCost = 1.0,
        realizedPnl = 0.0,
        price = 1.0,
        marketValueEur = marketValueEur,
        costBasisEur = null,
        unrealizedPnlEur = null,
        unrealizedPnlPct = null,
        dayChangeEur = dayEur,
        dayChangePct = dayPct,
    )

    // ── Spending slices ───────────────────────────────────────────────────────

    @Test
    fun `spending ranks tags by outflow and folds the tail`() {
        val slices = btWidgetSpendingSlices(
            tags = listOf(
                BtWidgetTagSpend("Food", 50.0),
                BtWidgetTagSpend("Rent", 900.0),
                BtWidgetTagSpend("Fun", 20.0),
                BtWidgetTagSpend("Inflow-only", 0.0),
            ),
            maxSlots = 2,
        )
        assertEquals(listOf("Rent", "Food", ""), slices.map { it.label })
        assertEquals(listOf(0, 1, BT_SLICE_REST), slices.map { it.colorIndex })
        assertEquals(20.0, slices.last().value, 0.0)
    }

    @Test
    fun `a month with no outflow yields no spending slices`() {
        assertTrue(btWidgetSpendingSlices(listOf(BtWidgetTagSpend("x", 0.0))).isEmpty())
    }

    @Test
    fun `slice fractions are proportions of the shown ring and survive an empty ring`() {
        val slices = listOf(
            BtWidgetSlice("a", 75.0, 0),
            BtWidgetSlice("b", 25.0, 1),
        )
        assertEquals(0.75, btWidgetSliceFraction(slices[0], slices), 1e-9)
        assertEquals(0.0, btWidgetSliceFraction(BtWidgetSlice("x", 0.0, 0), emptyList()), 0.0)
    }

    // ── Allocation slices (reinstated round 2) ────────────────────────────────

    private fun portfolio(id: String, cashEur: Double?, archivedAt: String? = null) =
        at.bettertrack.app.data.db.PortfolioEntity(
            id = id,
            name = "PF $id",
            visibility = "private",
            sortOrder = 0,
            isDefault = false,
            defaultPayFromCash = false,
            archivedAt = archivedAt,
            baseCurrency = "EUR",
            totals = cashEur?.let {
                at.bettertrack.app.data.db.PortfolioTotals(
                    marketValueEur = 100.0,
                    investedEur = 100.0,
                    unrealizedPnlEur = 0.0,
                    unrealizedPnlPct = null,
                    dayChangeEur = 0.0,
                    dayChangePct = null,
                    cashEur = it,
                    totalValueEur = 100.0 + it,
                )
            },
            detailSyncedAtMs = null,
        )

    @Test
    fun `allocation groups by class, ranks by summed value, folds the tail, appends cash`() {
        val slices = btWidgetAllocationSlices(
            holdings = listOf(
                holding("A", type = "stock", marketValueEur = 50.0),
                holding("B", type = "stock", marketValueEur = 30.0),
                holding("C", type = "crypto", marketValueEur = 100.0),
                holding("D", type = "etf", marketValueEur = 10.0),
            ),
            portfolios = listOf(portfolio("p1", cashEur = 25.0)),
            maxSlots = 2,
        )
        // crypto 100 > stock 80, etf folded, cash last.
        assertEquals(listOf("crypto", "stock", "", ""), slices.map { it.label })
        assertEquals(listOf(0, 1, BT_SLICE_REST, BT_SLICE_CASH), slices.map { it.colorIndex })
        assertEquals(listOf(100.0, 80.0, 10.0, 25.0), slices.map { it.value })
    }

    @Test
    fun `allocation can group by portfolio NAME and by currency`() {
        val holdings = listOf(
            holding("A", marketValueEur = 60.0, portfolioId = "p1"),
            holding("B", marketValueEur = 40.0, portfolioId = "p2"),
        )
        val portfolios = listOf(portfolio("p1", 0.0), portfolio("p2", 0.0))
        val byPortfolio = btWidgetAllocationSlices(
            holdings, portfolios,
            group = BtWidgetAllocationGroup.PORTFOLIO,
            includeCash = false,
        )
        assertEquals(listOf("PF p1", "PF p2"), byPortfolio.map { it.label })
        val byCurrency = btWidgetAllocationSlices(
            holdings, portfolios,
            group = BtWidgetAllocationGroup.CURRENCY,
            includeCash = false,
        )
        // Both sample holdings are USD ⇒ one merged slice.
        assertEquals(listOf("USD"), byCurrency.map { it.label })
        assertEquals(100.0, byCurrency.single().value, 0.0)
    }

    @Test
    fun `cash can be excluded, and an unsynced holding contributes nothing`() {
        val slices = btWidgetAllocationSlices(
            holdings = listOf(
                holding("A", type = "stock", marketValueEur = 50.0),
                holding("B", type = "crypto", marketValueEur = null),
            ),
            portfolios = listOf(portfolio("p1", cashEur = 25.0)),
            includeCash = false,
        )
        assertEquals(listOf("stock"), slices.map { it.label })
        assertTrue(slices.none { it.colorIndex == BT_SLICE_CASH })
    }

    @Test
    fun `allocation cash sums the ACTIVE covered portfolios only`() {
        val cash = btWidgetAllocationCash(
            listOf(
                portfolio("a", cashEur = 10.0),
                portfolio("b", cashEur = 5.0, archivedAt = "2026-01-01T00:00:00Z"),
                portfolio("c", cashEur = null), // never synced ⇒ contributes nothing
                portfolio("d", cashEur = 2.5),
            ),
        )
        assertEquals(12.5, cash, 0.0)
    }

    @Test
    fun `the asset-history merge keeps configured series and the honest clock`() {
        val previous = BtWidgetAssetHistoryCache(
            cachedAtMs = 1_000L,
            series = mapOf(
                "a" to BtWidgetAssetSeries("3M", listOf(1.0, 2.0)),
                "gone" to BtWidgetAssetSeries("3M", listOf(9.0)),
            ),
        )
        val merged = btWidgetMergeAssetHistory(
            previous = previous,
            fetched = mapOf("b" to BtWidgetAssetSeries("3M", listOf(5.0, 6.0))),
            keep = setOf("a", "b"),
            nowMs = 2_000L,
        )
        assertEquals(setOf("a", "b"), merged.series.keys)
        assertEquals(2_000L, merged.cachedAtMs)
        // A pass that fetched nothing keeps the clock — "as of" stays honest.
        assertEquals(
            1_000L,
            btWidgetMergeAssetHistory(previous, emptyMap(), setOf("a"), 9_000L).cachedAtMs,
        )
    }

    // ── Series-derived figures (round 2) ─────────────────────────────────────

    @Test
    fun `the range delta is the series' two endpoints and guards a zero base`() {
        val delta = btWidgetSeriesDelta(listOf(100.0, 90.0, 110.0))!!
        assertEquals(10.0, delta.eur, 1e-9)
        assertEquals(10.0, delta.pct!!, 1e-9)
        assertEquals(null, btWidgetSeriesDelta(listOf(0.0, 50.0))!!.pct)
        assertEquals(null, btWidgetSeriesDelta(listOf(42.0)))
    }

    @Test
    fun `low and high are the series' own extremes`() {
        assertEquals(90.0 to 110.0, btWidgetSeriesLowHigh(listOf(100.0, 90.0, 110.0)))
        assertEquals(null, btWidgetSeriesLowHigh(emptyList()))
    }

    // ── The row family (round 2) ─────────────────────────────────────────────

    @Test
    fun `holding rows merge per asset and sum the value for sorting`() {
        val rows = btWidgetHoldingRows(
            listOf(
                holding("AAPL", marketValueEur = 30.0, dayPct = 2.0, portfolioId = "p1"),
                holding("AAPL", marketValueEur = 20.0, dayPct = 2.0, portfolioId = "p2"),
                holding("MSFT", marketValueEur = 40.0, dayPct = -1.0),
            ),
        )
        assertEquals(2, rows.size)
        assertEquals(50.0, rows.first { it.symbol == "AAPL" }.valueEur)
    }

    @Test
    fun `movement sort ranks by absolute move and sinks the unknowns`() {
        val rows = btWidgetSortRows(
            listOf(
                BtWidgetRow("a", "A", "", 1.0, "EUR", dayChangePct = 1.0),
                BtWidgetRow("b", "B", "", 1.0, "EUR", dayChangePct = -9.0),
                BtWidgetRow("c", "C", "", 1.0, "EUR", dayChangePct = null),
            ),
            BtWidgetRowSort.MOVEMENT,
        )
        assertEquals(listOf("B", "A", "C"), rows.map { it.symbol })
    }

    @Test
    fun `value sort ranks by merged value, manual keeps the given order`() {
        val given = listOf(
            BtWidgetRow("a", "A", "", 1.0, "EUR", dayChangePct = null, valueEur = 10.0),
            BtWidgetRow("b", "B", "", 1.0, "EUR", dayChangePct = null, valueEur = null),
            BtWidgetRow("c", "C", "", 1.0, "EUR", dayChangePct = null, valueEur = 40.0),
        )
        assertEquals(
            listOf("C", "A", "B"),
            btWidgetSortRows(given, BtWidgetRowSort.VALUE).map { it.symbol },
        )
        assertEquals(
            listOf("A", "B", "C"),
            btWidgetSortRows(given, BtWidgetRowSort.MANUAL).map { it.symbol },
        )
    }

    @Test
    fun `direction filters keep their side and exact zero belongs to neither`() {
        val rows = listOf(
            BtWidgetRow("a", "UP", "", 1.0, "EUR", dayChangePct = 1.0),
            BtWidgetRow("b", "DN", "", 1.0, "EUR", dayChangePct = -1.0),
            BtWidgetRow("c", "FLAT", "", 1.0, "EUR", dayChangePct = 0.0),
        )
        assertEquals(
            listOf("UP"),
            btWidgetFilterRows(rows, BtWidgetRowDirection.WINNERS).map { it.symbol },
        )
        assertEquals(
            listOf("DN"),
            btWidgetFilterRows(rows, BtWidgetRowDirection.LOSERS).map { it.symbol },
        )
        assertEquals(3, btWidgetFilterRows(rows, BtWidgetRowDirection.ALL).size)
        val counts = btWidgetRowCounts(rows)
        assertEquals(1, counts.up)
        assertEquals(1, counts.down)
        assertEquals(2, counts.moved)
        assertEquals(3, counts.total)
    }

    // ── Monthly flow (round 2) ───────────────────────────────────────────────

    @Test
    fun `flow bars share one scale across both directions`() {
        val bars = btWidgetCashflowBars(
            listOf(
                BtWidgetCashflowPoint("2026-06", inflow = 100.0, outflow = 50.0),
                BtWidgetCashflowPoint("2026-07", inflow = 25.0, outflow = 200.0),
            ),
        )
        // Peak is the July outflow (200): everything scales against it.
        assertEquals(0.5f, bars[0].inflowFrac)
        assertEquals(0.25f, bars[0].outflowFrac)
        assertEquals(1f, bars[1].outflowFrac)
        assertEquals(0.125f, bars[1].inflowFrac)
    }

    @Test
    fun `an all-zero window renders zero bars rather than dividing by zero`() {
        val bars = btWidgetCashflowBars(listOf(BtWidgetCashflowPoint("2026-08", 0.0, 0.0)))
        assertEquals(0f, bars.single().inflowFrac)
        assertEquals(0f, bars.single().outflowFrac)
    }

    @Test
    fun `the flow window's net is the sanctioned sum, absent for an empty window`() {
        val net = btWidgetFlowNet(
            listOf(
                BtWidgetCashflowPoint("2026-06", inflow = 100.0, outflow = 40.0),
                BtWidgetCashflowPoint("2026-07", inflow = 10.0, outflow = 30.0),
            ),
        )!!
        assertEquals(40.0, net, 1e-9)
        assertEquals(null, btWidgetFlowNet(emptyList()))
    }

    // ── Budget progress ring ──────────────────────────────────────────────────

    @Test
    fun `the budget ring fills to the spend and the two arcs always sum to one`() {
        val (fill, rest) = btWidgetRingFractions(120.0, 300.0)
        assertEquals(0.4f, fill)
        assertEquals(0.6f, rest, 1e-6f)
    }

    @Test
    fun `an over-spent budget saturates the ring rather than lapping it`() {
        // The TRUE percent is text; the ring, like the bar, can only fill to full.
        assertEquals(1f to 0f, btWidgetRingFractions(390.0, 300.0))
    }

    @Test
    fun `a degenerate limit renders an empty ring, not a divide by zero`() {
        assertEquals(0f to 1f, btWidgetRingFractions(10.0, 0.0))
    }

    // ── Winners and losers (the movers widget's wide layout) ─────────────────

    @Test
    fun `winners and losers rank from each extreme and exclude exact zero`() {
        val split = btWidgetWinnersLosers(
            listOf(
                holding("UP1", marketValueEur = 10.0, dayPct = 1.0, dayEur = 1.0),
                holding("UP9", marketValueEur = 10.0, dayPct = 9.0, dayEur = 9.0),
                holding("DN4", marketValueEur = 10.0, dayPct = -4.0, dayEur = -4.0),
                holding("FLAT", marketValueEur = 10.0, dayPct = 0.0, dayEur = 0.0),
                holding("NOMOVE", marketValueEur = 10.0, dayPct = null),
            ),
        )
        assertEquals(listOf("UP9", "UP1"), split.winners.map { it.symbol })
        assertEquals(listOf("DN4"), split.losers.map { it.symbol })
    }

    @Test
    fun `each side is capped and a one-sided day stays one-sided`() {
        val holdings = (1..9).map {
            holding("W$it", marketValueEur = 10.0, dayPct = it.toDouble(), dayEur = 1.0)
        }
        val split = btWidgetWinnersLosers(holdings)
        assertEquals(BT_WIDGET_WINLOSE_PER_SIDE, split.winners.size)
        assertEquals(listOf("W9", "W8", "W7"), split.winners.map { it.symbol })
        assertTrue("no invented losers", split.losers.isEmpty())
    }

    @Test
    fun `the same asset in two portfolios is one mover, not two`() {
        // homeMovers' own merge rule, inherited — pinned here because this widget
        // renders both ends and a duplicate would show one asset twice.
        val split = btWidgetWinnersLosers(
            listOf(
                holding("AAPL", marketValueEur = 30.0, dayPct = 2.0, dayEur = 1.0, portfolioId = "p1"),
                holding("AAPL", marketValueEur = 20.0, dayPct = 2.0, dayEur = 1.0, portfolioId = "p2"),
            ),
        )
        assertEquals(1, split.winners.size)
        assertEquals(2.0, split.winners.single().dayChangeEur!!, 0.0)
    }

    // ── Sparkline scaling ─────────────────────────────────────────────────────

    @Test
    fun `spark normalisation maps min to 0 and max to 1`() {
        val norm = btWidgetSparkNormalize(listOf(100.0, 150.0, 125.0))
        assertEquals(listOf(0f, 1f, 0.5f), norm)
    }

    @Test
    fun `a flat series draws a midline, not a divide by zero`() {
        assertEquals(listOf(0.5f, 0.5f), btWidgetSparkNormalize(listOf(7.0, 7.0)))
        assertTrue(btWidgetSparkNormalize(emptyList()).isEmpty())
    }

    @Test
    fun `thinning keeps the endpoints and caps the count`() {
        val values = (0..999).map { it.toDouble() }
        val thin = btWidgetSparkThin(values, BT_WIDGET_SPARK_MAX_POINTS)
        assertEquals(BT_WIDGET_SPARK_MAX_POINTS, thin.size)
        assertEquals(0.0, thin.first(), 0.0)
        assertEquals(999.0, thin.last(), 0.0)
        // A short series passes through untouched.
        assertEquals(listOf(1.0, 2.0), btWidgetSparkThin(listOf(1.0, 2.0), 100))
    }

    // ── Bitmap sizing ─────────────────────────────────────────────────────────

    @Test
    fun `bitmap size renders REAL pixels and caps only a pathological edge`() {
        // Device-review round 3: a 4x2 chart on a 3x panel must come out at its
        // true ~990px, not squeezed and upscaled into a staircase.
        assertEquals(300 to 150, btWidgetBitmapSize(100f, 50f, 3f))
        assertEquals(990 to 330, btWidgetBitmapSize(330f, 110f, 3f))
        // Only a pathological size hits the rail, aspect kept.
        val (w, h) = btWidgetBitmapSize(700f, 110f, 3f)
        assertEquals(BT_WIDGET_BITMAP_MAX_EDGE_PX, w)
        assertEquals((330 * BT_WIDGET_BITMAP_MAX_EDGE_PX.toFloat() / 2100f).toInt(), h)
        // Degenerate input never drops below one pixel.
        assertEquals(1 to 1, btWidgetBitmapSize(0f, 0f, 3f))
    }

    // ── Size classes (device QA 2026-08-16) ──────────────────────────────────

    @Test
    fun `row classes bucket both measured launcher grids identically`() {
        // One UI (measured on the owner's Note20 Ultra): 1 row = 120dp,
        // 2 rows = 250dp, 3 rows = 380dp, 4 rows = 510dp.
        assertEquals(BtWidgetSizeClass.ROW1, btWidgetRowClass(120f))
        assertEquals(BtWidgetSizeClass.ROW2, btWidgetRowClass(250f))
        assertEquals(BtWidgetSizeClass.ROW3, btWidgetRowClass(380f))
        assertEquals(BtWidgetSizeClass.ROW4, btWidgetRowClass(510f))
        // Pixel-style grids: 1 row ≈ 92dp, 2 rows ≈ 190dp, 3 rows ≈ 290dp.
        assertEquals(BtWidgetSizeClass.ROW1, btWidgetRowClass(92f))
        assertEquals(BtWidgetSizeClass.ROW2, btWidgetRowClass(190f))
        assertEquals(BtWidgetSizeClass.ROW3, btWidgetRowClass(300f))
        // Anything denser than a real launcher row is the safety strip.
        assertEquals(BtWidgetSizeClass.STRIP, btWidgetRowClass(40f))
        assertEquals(BtWidgetSizeClass.STRIP, btWidgetRowClass(89f))
    }

    @Test
    fun `wide starts at four launcher cells on both grids`() {
        assertFalse(btWidgetIsWide(160f)) // Pixel 2-cell
        assertFalse(btWidgetIsWide(181f)) // One UI 2-cell
        assertTrue(btWidgetIsWide(322f)) // Pixel 4-cell
        assertTrue(btWidgetIsWide(366f)) // One UI 4-cell
    }

    @Test
    fun `row fill height divides the area exactly and never distorts a row`() {
        // 5 rows + 4 hairlines in 189dp → 37dp rows, edge-to-edge.
        assertEquals(37f, btWidgetRowFillHeight(189f, 5), 0.01f)
        // A sparse list must not inflate rows into banners…
        assertEquals(BT_ROW_HEIGHT_DP + 10f, btWidgetRowFillHeight(400f, 2), 0.01f)
        // …and a miscounted budget must not shrink below the two-line minimum.
        assertEquals(BT_ROW_HEIGHT_DP, btWidgetRowFillHeight(30f, 3), 0.01f)
        assertEquals(BT_ROW_HEIGHT_DP, btWidgetRowFillHeight(100f, 0), 0.01f)
    }

    // ── Palette slots ─────────────────────────────────────────────────────────

    @Test
    fun `the widget donut never uses more identity slots than the theme has`() {
        assertTrue(BT_WIDGET_SLICE_SLOTS <= BtGlanceChartPalette.slotCount)
    }

    @Test
    fun `semantic slots resolve to their own colours, out-of-range folds to rest`() {
        for (night in listOf(false, true)) {
            val series = BtGlanceChartPalette.series(night)
            assertEquals(series[0].toInt(), BtGlanceChartPalette.slice(0, night))
            assertEquals(
                (if (night) BtGlanceChartPalette.CASH_NIGHT else BtGlanceChartPalette.CASH_DAY).toInt(),
                BtGlanceChartPalette.slice(BT_SLICE_CASH, night),
            )
            val rest = (if (night) BtGlanceChartPalette.REST_NIGHT else BtGlanceChartPalette.REST_DAY).toInt()
            assertEquals(rest, BtGlanceChartPalette.slice(BT_SLICE_REST, night))
            assertEquals("a bad slot must degrade, not crash", rest, BtGlanceChartPalette.slice(99, night))
        }
    }
}
