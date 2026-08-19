package at.bettertrack.app.ui.insights

import at.bettertrack.app.data.db.HoldingEntity
import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.data.db.PortfolioTotals
import at.bettertrack.app.data.repo.AssetRange
import at.bettertrack.app.data.repo.PricePoint
import at.bettertrack.app.ui.charts.viz.BtVizScope
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The **Bewegungen** card's span vocabulary, and the honesty rails it enforces.
 *
 * The rails, restated as the properties this file pins:
 *
 *  1. **Heute keeps its euros** — `dayChangeEur` is server-computed and nothing
 *     replaces it.
 *  2. **A euro contribution per position over a period does not exist**, because
 *     it needs the quantity held *through* the period and no endpoint states it.
 *     So 1 W / 1 M / 1 J are percentages, and the snapshot says so in its unit
 *     rather than leaving each renderer to guess.
 *  3. **"All time" is not the MAX of a price series.** That series starts when
 *     the instrument's data starts, not when the user bought. Seit Kauf is the
 *     server's `unrealizedPnlEur` and is named after what it is.
 *  4. **An unfetchable row is unavailable, never 0 %.** Zero is an answer;
 *     "we could not fetch it" is not the same answer.
 */
class InsightsMoveRangeTest {

    private val zone: ZoneId = ZoneId.of("Europe/Vienna")
    private val today: LocalDate = LocalDate.of(2026, 8, 18)
    private val asOf: Long = today.toEpochDay()

    // ── 1 · Range → series window ───────────────────────────────────────────

    @Test
    fun `each span maps onto the calendar window it is named after`() {
        assertEquals(
            today.minusWeeks(1).toEpochDay()..asOf,
            insightMoveWindow(BtInsightMoveRange.WEEK, asOf),
        )
        assertEquals(
            today.minusMonths(1).toEpochDay()..asOf,
            insightMoveWindow(BtInsightMoveRange.MONTH, asOf),
        )
        assertEquals(
            today.minusYears(1).toEpochDay()..asOf,
            insightMoveWindow(BtInsightMoveRange.YEAR, asOf),
        )
    }

    /**
     * A session is one day and "since purchase" has no single start date across
     * positions bought on different days. Printing a range for either would be a
     * claim neither number makes.
     */
    @Test
    fun `the two euro spans collapse to a stichtag rather than inventing a range`() {
        listOf(BtInsightMoveRange.DAY, BtInsightMoveRange.SINCE_BUY).forEach { range ->
            val window = insightMoveWindow(range, asOf)
            assertEquals("$range invented a range", asOf, window.first)
            assertEquals(asOf, window.last)
        }
    }

    @Test
    fun `only the price spans map onto a wire range the endpoint accepts`() {
        assertNull(BtInsightMoveRange.DAY.assetRange)
        assertNull(BtInsightMoveRange.SINCE_BUY.assetRange)
        assertEquals(AssetRange.W1, BtInsightMoveRange.WEEK.assetRange)
        assertEquals(AssetRange.M1, BtInsightMoveRange.MONTH.assetRange)
        assertEquals(AssetRange.Y1, BtInsightMoveRange.YEAR.assetRange)
    }

    /**
     * `GET /assets/{id}/history` rejects an unknown `range` with a 400 — there is
     * no fallback — so every wire value this card can send must be one the server
     * enumerates. Verified against the platform contract's `HISTORY_RANGES`.
     */
    @Test
    fun `every wire range this card sends is one the server enumerates`() {
        val accepted = setOf("1D", "1W", "1M", "3M", "6M", "1Y", "5Y", "MAX")
        BtInsightMoveRange.entries.mapNotNull { it.assetRange }.forEach { range ->
            assertTrue("${range.wire} is not an accepted history range", range.wire in accepted)
        }
    }

    @Test
    fun `the euro spans need no price history and therefore no request`() {
        assertFalse(BtInsightMoveRange.DAY.needsPriceHistory)
        assertFalse(BtInsightMoveRange.SINCE_BUY.needsPriceHistory)
        assertTrue(BtInsightMoveRange.WEEK.needsPriceHistory)
        assertTrue(BtInsightMoveRange.MONTH.needsPriceHistory)
        assertTrue(BtInsightMoveRange.YEAR.needsPriceHistory)
    }

    @Test
    fun `isMoney is true for exactly the two server-computed euro spans`() {
        assertEquals(
            listOf(BtInsightMoveRange.DAY, BtInsightMoveRange.SINCE_BUY),
            BtInsightMoveRange.entries.filter { it.isMoney },
        )
    }

    /**
     * The regression this clamp exists for.
     *
     * `NoLivePricesMarketDataSource.assetHistory` ignores `range` and hands back
     * the entire local price cache. A five-year cache reduced without clamping
     * under a `1 Woche` label is exactly the "+4.000 % week" that says the window
     * was never mapped at all.
     */
    @Test
    fun `a source that ignores the requested range is clamped to the named window`() {
        val series = listOf(
            close(today.minusYears(5), 10.0),
            close(today.minusYears(1), 80.0),
            close(today.minusDays(6), 100.0),
            close(today, 110.0),
        )
        val week = insightMoveSeriesWindow(series, BtInsightMoveRange.WEEK, asOf, zone)
        assertEquals(2, week.size)
        assertEquals(100.0, week.first().close, 1e-9)
        assertEquals(110.0, week.last().close, 1e-9)

        // Unclamped this reads +1.000 %. Clamped it reads the week it claims.
        assertEquals(10.0, insightMovePercentIn(series, BtInsightMoveRange.WEEK, asOf, zone)!!, 1e-9)
        assertEquals(1_000.0, insightMovePercent(series)!!, 1e-9)
    }

    @Test
    fun `a well-behaved server series is left untouched by the clamp`() {
        val series = (0..6).map { close(today.minusDays(6L - it), 100.0 + it) }
        val windowed = insightMoveSeriesWindow(series, BtInsightMoveRange.WEEK, asOf, zone)
        assertEquals(series, windowed)
    }

    @Test
    fun `a series with nothing inside the window yields no move at all`() {
        val stale = listOf(close(today.minusYears(3), 50.0), close(today.minusYears(2), 60.0))
        assertTrue(insightMoveSeriesWindow(stale, BtInsightMoveRange.WEEK, asOf, zone).isEmpty())
        assertNull(insightMovePercentIn(stale, BtInsightMoveRange.WEEK, asOf, zone))
    }

    // ── 2 · Percent from two points ─────────────────────────────────────────

    @Test
    fun `the move is last over first, in percent`() {
        val points = listOf(close(today.minusDays(5), 200.0), close(today, 250.0))
        assertEquals(25.0, insightMovePercent(points)!!, 1e-9)
    }

    @Test
    fun `a fall is negative and keeps its magnitude`() {
        val points = listOf(close(today.minusDays(5), 200.0), close(today, 150.0))
        assertEquals(-25.0, insightMovePercent(points)!!, 1e-9)
    }

    @Test
    fun `only the first and last point decide the move`() {
        val straight = listOf(close(today.minusDays(2), 100.0), close(today, 110.0))
        val wobbly = listOf(
            close(today.minusDays(2), 100.0),
            close(today.minusDays(1), 400.0),
            close(today, 110.0),
        )
        assertEquals(insightMovePercent(straight)!!, insightMovePercent(wobbly)!!, 1e-9)
    }

    /**
     * Every degenerate series returns `null`, never `0.0`. The difference is the
     * whole point: a row with `null` is listed as unavailable, and a row with
     * `0.0` is drawn at the origin as a position that did not move.
     */
    @Test
    fun `a series that cannot carry a move returns null rather than zero`() {
        assertNull("empty", insightMovePercent(emptyList()))
        assertNull("one point", insightMovePercent(listOf(close(today, 100.0))))
        assertNull(
            "zero base",
            insightMovePercent(listOf(close(today.minusDays(1), 0.0), close(today, 5.0))),
        )
        assertNull(
            "negative base",
            insightMovePercent(listOf(close(today.minusDays(1), -3.0), close(today, 5.0))),
        )
        assertNull(
            "non-finite",
            insightMovePercent(
                listOf(close(today.minusDays(1), 100.0), close(today, Double.NaN)),
            ),
        )
    }

    // ── 3 · Fetch cost ──────────────────────────────────────────────────────

    @Test
    fun `fetch targets are the largest positions first and stable between renders`() {
        val values = mapOf("a" to 100.0, "b" to 9_000.0, "c" to 3_000.0)
        assertEquals(listOf("b", "c", "a"), insightMoveFetchTargets(values))
    }

    @Test
    fun `equal values break on the id so two renders choose the same assets`() {
        val values = mapOf("z" to 500.0, "a" to 500.0, "m" to 500.0)
        assertEquals(listOf("a", "m", "z"), insightMoveFetchTargets(values))
    }

    @Test
    fun `the fetch list is cut at the cap and the tail is simply absent`() {
        val values = (1..40).associate { "a%02d".format(it) to it.toDouble() }
        val targets = insightMoveFetchTargets(values, cap = BT_INSIGHT_MOVE_FETCH_CAP)
        assertEquals(BT_INSIGHT_MOVE_FETCH_CAP, targets.size)
        assertEquals("a40", targets.first())
    }

    /**
     * An explicit small `Umfang` lowers the fetch count — three bars must not
     * cost twelve round trips. `Automatisch` and `Alle` name no number and take
     * the hard cap; neither may raise above it.
     */
    @Test
    fun `the top-N knob lowers the fetch count but can never raise it`() {
        assertEquals(3, insightMoveFetchCap(BtVizScope.TOP_3))
        assertEquals(5, insightMoveFetchCap(BtVizScope.TOP_5))
        assertEquals(8, insightMoveFetchCap(BtVizScope.TOP_8))
        assertEquals(BT_INSIGHT_MOVE_FETCH_CAP, insightMoveFetchCap(BtVizScope.AUTO))
        assertEquals(BT_INSIGHT_MOVE_FETCH_CAP, insightMoveFetchCap(BtVizScope.ALL))
        assertEquals(BT_INSIGHT_MOVE_FETCH_CAP, insightMoveFetchCap(null))
        BtVizScope.entries.forEach {
            assertTrue("$it raised the cap", insightMoveFetchCap(it) <= BT_INSIGHT_MOVE_FETCH_CAP)
        }
    }

    @Test
    fun `only the spans that fetch have a cache life`() {
        assertEquals(0L, insightMoveCacheTtlMs(BtInsightMoveRange.DAY))
        assertEquals(0L, insightMoveCacheTtlMs(BtInsightMoveRange.SINCE_BUY))
        val week = insightMoveCacheTtlMs(BtInsightMoveRange.WEEK)
        val month = insightMoveCacheTtlMs(BtInsightMoveRange.MONTH)
        val year = insightMoveCacheTtlMs(BtInsightMoveRange.YEAR)
        assertTrue("a fetching span needs a life", week > 0L)
        assertTrue("longer spans are staler for longer", week < month && month < year)
    }

    // ── 4 · The builder's three shapes ──────────────────────────────────────

    @Test
    fun `today still prints server-computed euro contributions`() {
        val snapshot = build(BtInsightMoveRange.DAY, source(holdings = listOf(holding("AAPL", day = 42.0))))
        assertEquals(BtInsightUnit.MONEY, snapshot.datumUnit)
        assertEquals(BtInsightMoveRange.DAY, snapshot.moveRange)
        assertEquals(42.0, snapshot.datums.single().value, 1e-9)
    }

    /**
     * Rail 4. Seit Kauf reads `unrealizedPnlEur`, the same server figure the
     * *Unrealisierte G/V* card prints — deliberately, rather than a second number
     * that would have to disagree with it — and never the MAX of a price series.
     */
    @Test
    fun `since purchase is the server unrealized result, in euro`() {
        val snapshot = build(
            BtInsightMoveRange.SINCE_BUY,
            source(holdings = listOf(holding("AAPL", unrealized = 1_234.50))),
        )
        assertEquals(BtInsightUnit.MONEY, snapshot.datumUnit)
        assertEquals(1_234.50, snapshot.datums.single().value, 1e-9)
        assertTrue(snapshot.headline is BtInsightValue.Money)
    }

    @Test
    fun `a holding with no cost basis is counted as uncovered, not shown at break-even`() {
        val snapshot = build(
            BtInsightMoveRange.SINCE_BUY,
            source(
                holdings = listOf(
                    holding("AAPL", unrealized = 300.0),
                    holding("NOBASIS", unrealized = null),
                ),
            ),
        )
        assertEquals(1, snapshot.datums.size)
        assertEquals(BtInsightCoverage(1, 2), snapshot.coverage)
        assertEquals(listOf("NOBASIS"), snapshot.unavailable)
    }

    /** Rail 2: a price span prints percentages and no euro total, ever. */
    @Test
    fun `a price span prints percent and refuses a portfolio total`() {
        val snapshot = build(
            BtInsightMoveRange.WEEK,
            source(
                holdings = listOf(holding("AAPL"), holding("MSFT")),
                moves = mapOf("a-AAPL" to 5.5, "a-MSFT" to -2.25),
                movesRange = BtInsightMoveRange.WEEK,
            ),
        )
        assertEquals(BtInsightUnit.PERCENT, snapshot.datumUnit)
        assertEquals(0.0, snapshot.total, 1e-9)
        assertEquals(listOf(5.5, -2.25), snapshot.datums.map { it.value })
        // The headline is the strongest move — a value that exists — not a sum of
        // percentages, which would be arithmetic on unrelated denominators.
        assertEquals(BtInsightValue.Percent(5.5, signed = true), snapshot.headline)
    }

    /** Rail 4 again, from the other side: no price span may print money. */
    @Test
    fun `no price span emits a money value anywhere on the snapshot`() {
        listOf(BtInsightMoveRange.WEEK, BtInsightMoveRange.MONTH, BtInsightMoveRange.YEAR).forEach { range ->
            val snapshot = build(
                range,
                source(
                    holdings = listOf(holding("AAPL")),
                    moves = mapOf("a-AAPL" to 7.0),
                    movesRange = range,
                ),
            )
            val values = listOfNotNull(snapshot.headline, snapshot.caption?.value) +
                snapshot.facts.map { it.value }
            values.forEach {
                assertFalse("$range printed money: $it", it is BtInsightValue.Money)
                assertFalse("$range printed money: $it", it is BtInsightValue.MoneyPercent)
            }
        }
    }

    /** Rail 4: an asset whose series never arrived is named, not plotted at 0 %. */
    @Test
    fun `a holding with no fetched series is listed as unavailable`() {
        val snapshot = build(
            BtInsightMoveRange.MONTH,
            source(
                holdings = listOf(holding("AAPL"), holding("ILLIQUID")),
                moves = mapOf("a-AAPL" to 3.0),
                movesRange = BtInsightMoveRange.MONTH,
            ),
        )
        assertEquals(listOf("AAPL"), snapshot.datums.map { it.label })
        assertEquals(listOf("ILLIQUID"), snapshot.unavailable)
        assertEquals(BtInsightCoverage(1, 2), snapshot.coverage)
        assertFalse(snapshot.datums.any { it.value == 0.0 })
    }

    @Test
    fun `not one usable series is reported as absent history, not as a flat chart`() {
        val snapshot = build(
            BtInsightMoveRange.YEAR,
            source(holdings = listOf(holding("AAPL")), movesRange = BtInsightMoveRange.YEAR),
        )
        assertTrue(snapshot.isEmpty)
        assertEquals(BtInsightEmptyReason.NO_PRICE_HISTORY, snapshot.empty)
    }

    /**
     * Numbers fetched for last week are not this year's. While a newly requested
     * span is in flight the card says *loading*, because showing the old span's
     * percentages under the new span's label is the exact mislabelling this
     * feature exists to avoid.
     */
    @Test
    fun `percentages fetched for another span are never shown under this one`() {
        val snapshot = build(
            BtInsightMoveRange.YEAR,
            source(
                holdings = listOf(holding("AAPL")),
                moves = mapOf("a-AAPL" to 3.0),
                movesRange = BtInsightMoveRange.WEEK,
            ),
        )
        assertTrue(snapshot.isEmpty)
        assertEquals(BtInsightEmptyReason.PRICE_HISTORY_LOADING, snapshot.empty)
        assertTrue(snapshot.datums.isEmpty())
    }

    @Test
    fun `an in-flight pass reports loading rather than absent history`() {
        val snapshot = build(
            BtInsightMoveRange.MONTH,
            source(
                holdings = listOf(holding("AAPL")),
                movesRange = BtInsightMoveRange.MONTH,
                movesLoading = true,
            ),
        )
        assertEquals(BtInsightEmptyReason.PRICE_HISTORY_LOADING, snapshot.empty)
    }

    /**
     * One price move belongs to one asset. Two portfolios holding the same stock
     * saw a single move, so the percentage is taken once — summing them would
     * produce a number no market ever printed.
     */
    @Test
    fun `one asset held twice contributes its move once, never doubled`() {
        val snapshot = build(
            BtInsightMoveRange.WEEK,
            source(
                holdings = listOf(
                    holding("AAPL", portfolioId = "p1"),
                    holding("AAPL", portfolioId = "p2"),
                ),
                moves = mapOf("a-AAPL" to 4.0),
                movesRange = BtInsightMoveRange.WEEK,
            ),
        )
        assertEquals(1, snapshot.datums.size)
        assertEquals(4.0, snapshot.datums.single().value, 1e-9)
    }

    @Test
    fun `every span stamps itself on the snapshot so an export can name it`() {
        BtInsightMoveRange.entries.forEach { range ->
            val snapshot = build(
                range,
                source(
                    holdings = listOf(holding("AAPL")),
                    moves = mapOf("a-AAPL" to 1.0),
                    movesRange = range,
                ),
            )
            assertEquals("$range lost its label", range, snapshot.moveRange)
        }
    }

    // ── 4b · The "Andere" bucket a percent set may not have ─────────────────

    /**
     * The device regression, 2026-08-19: the compact rendition drew a mark
     * reading `Andere · 2 … −9,08 %`, which is the SUM of a −4,69 % and a
     * −4,39 % move. That is arithmetic across unrelated denominators and no
     * market printed it. A truncated percent set must simply stop.
     */
    @Test
    fun `a percent chart truncates instead of summing a remainder bucket`() {
        val datums = listOf(
            datum("KO", 3.07),
            datum("BTC-USD", 0.44),
            datum("SIE.DE", -2.02),
            datum("BAYN.DE", -2.33),
            datum("ONDS", -4.69),
            datum("PENMF", -4.39),
        )
        val drawn = insightMoveChartDatums(datums, limit = 4)
        assertEquals(4, drawn.size)
        assertEquals(listOf("KO", "BTC-USD", "SIE.DE", "BAYN.DE"), drawn.map { it.label })
        // The number the bug produced must appear nowhere.
        assertFalse(drawn.any { kotlin.math.abs(it.value - (-9.08)) < 1e-6 })
        // Every drawn value is still one asset's own move, untouched.
        drawn.forEach { d ->
            assertTrue("$d was aggregated", datums.any { it.label == d.label && it.value == d.value })
        }
    }

    @Test
    fun `an unlimited or short percent chart is passed through untouched`() {
        val datums = listOf(datum("A", 5.0), datum("B", -1.0))
        assertEquals(datums, insightMoveChartDatums(datums, limit = 0))
        assertEquals(datums, insightMoveChartDatums(datums, limit = -1))
        assertEquals(datums, insightMoveChartDatums(datums, limit = 5))
    }

    // ── 5 · Config codec ────────────────────────────────────────────────────

    @Test
    fun `the span survives an encode-decode round trip`() {
        BtInsightMoveRange.entries.forEach { range ->
            val encoded = insightConfigEncode(BtInsightConfig(moveRange = range))
            assertEquals(range, insightConfigDecode(encoded).moveRange)
        }
    }

    /**
     * The wire key is stable across the rename. A config written before the span
     * field existed has no seventeenth part, so it decodes to `null` and the card
     * opens on Heute exactly as it did — nobody's saved card silently retargets.
     */
    @Test
    fun `a config written before spans existed still decodes and still means today`() {
        // A real card, encoded, with the span segment amputated exactly as a line
        // written before this field existed would look.
        val card = BtInsightConfig(
            topN = BtVizScope.TOP_5,
            sort = BtInsightSort.PERCENT,
            includeTransfers = true,
            moveRange = BtInsightMoveRange.YEAR,
        )
        val current = insightConfigEncode(card)!!
        val legacy = current.substringBeforeLast('|')
        assertFalse("the span must be the LAST segment", legacy.contains("YEAR"))

        val decoded = insightConfigDecode(legacy)
        assertNull("an old line must not resurrect a span", decoded.moveRange)
        assertEquals(
            BT_INSIGHT_MOVE_RANGE_DEFAULT,
            decoded.moveRange ?: BT_INSIGHT_MOVE_RANGE_DEFAULT,
        )
        // Everything the user HAD configured survives the amputation untouched.
        assertEquals(BtVizScope.TOP_5, decoded.topN)
        assertEquals(BtInsightSort.PERCENT, decoded.sort)
        assertTrue(decoded.includeTransfers)
    }

    @Test
    fun `a pristine card stores nothing and still reads as pristine`() {
        assertNull(BtInsightConfig.PRISTINE.moveRange)
        assertNull(insightConfigEncode(BtInsightConfig.PRISTINE))
    }

    /**
     * `Auf Familienstandard zurücksetzen` clears the `Darstellung`, not the
     * card's subject. Throwing a reader who was studying the year back to today
     * would be a retarget, not a reset.
     */
    @Test
    fun `resetting the presentation keeps the chosen span`() {
        val card = BtInsightConfig(
            moveRange = BtInsightMoveRange.YEAR,
            labels = at.bettertrack.app.ui.charts.viz.BtVizLabels.SHARES,
        )
        val reset = insightResetToFamily(card)
        assertEquals(BtInsightMoveRange.YEAR, reset.moveRange)
        assertNull(reset.labels)
    }

    // ── 6 · Catalog wiring ──────────────────────────────────────────────────

    /**
     * The span control is not [BtInsightPeriodKind] under another name, and the
     * study's "Tagesbewegungen cannot become an arbitrary range" still holds:
     * this card offers no period control, because `dayChangeEur` still has no
     * weekly form. What it offers is a switch between different server facts.
     */
    @Test
    fun `the movements card gains a span control without gaining a period control`() {
        assertTrue(insightPeriodKinds(BtInsight.DAILY_MOVERS).isEmpty())
        assertEquals(
            BtInsightMoveRange.entries,
            BtInsight.DAILY_MOVERS.spec.moveRanges,
        )
    }

    @Test
    fun `no other insight offers a span, because no other insight has one to offer`() {
        BtInsight.entries.filter { it != BtInsight.DAILY_MOVERS }.forEach {
            assertTrue("${it.name} grew a span control", it.spec.moveRanges.isEmpty())
        }
    }

    @Test
    fun `every span has a label and only the euro session needs no disclaimer`() {
        BtInsightMoveRange.entries.forEach { assertNotNull(insightMoveRangeRes(it)) }
        assertNull(insightMoveNoteRes(BtInsightMoveRange.DAY))
        BtInsightMoveRange.entries.filter { it != BtInsightMoveRange.DAY }.forEach {
            assertNotNull("$it has no note", insightMoveNoteRes(it))
        }
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private fun datum(label: String, value: Double) =
        at.bettertrack.app.ui.charts.viz.VizDatum(key = "mv:$label", label = label, value = value)

    private fun close(date: LocalDate, price: Double) =
        PricePoint(date.atStartOfDay(zone).toInstant().toEpochMilli(), price)

    private fun build(
        range: BtInsightMoveRange,
        source: BtInsightSource,
        config: BtInsightConfig = BtInsightConfig.PRISTINE,
    ) = buildInsightSnapshot(
        insight = BtInsight.DAILY_MOVERS,
        config = config.copy(moveRange = range),
        source = source,
        window = insightWindow(BtInsightPeriod.ONE_YEAR, today),
        zone = zone,
    )

    private fun holding(
        symbol: String,
        portfolioId: String = "p1",
        value: Double? = 1_000.0,
        unrealized: Double? = 200.0,
        day: Double? = 12.0,
    ) = HoldingEntity(
        portfolioId = portfolioId,
        assetId = "a-$symbol",
        assetSymbol = symbol,
        assetName = symbol,
        assetExchange = null,
        assetCurrency = "EUR",
        assetType = "stock",
        assetIsCustom = false,
        quantity = 1.0,
        avgCost = 800.0,
        realizedPnl = 0.0,
        price = value,
        marketValueEur = value,
        costBasisEur = 800.0,
        unrealizedPnlEur = unrealized,
        unrealizedPnlPct = 25.0,
        dayChangeEur = day,
        dayChangePct = 1.2,
    )

    private fun source(
        holdings: List<HoldingEntity> = emptyList(),
        moves: Map<String, Double> = emptyMap(),
        movesRange: BtInsightMoveRange? = null,
        movesLoading: Boolean = false,
    ) = BtInsightSource(
        portfolios = listOf(
            PortfolioEntity(
                id = "p1",
                name = "Depot",
                visibility = "private",
                sortOrder = 0,
                isDefault = true,
                defaultPayFromCash = false,
                archivedAt = null,
                baseCurrency = "EUR",
                totals = PortfolioTotals(
                    marketValueEur = 33_716.33,
                    investedEur = 30_000.0,
                    unrealizedPnlEur = 5_218.44,
                    unrealizedPnlPct = 14.3,
                    dayChangeEur = 170.90,
                    dayChangePct = 0.44,
                    cashEur = 4_862.90,
                    totalValueEur = 38_579.23,
                ),
                detailSyncedAtMs = 0L,
            ),
        ),
        holdings = holdings,
        cashSources = emptyList(),
        cashMovements = emptyList(),
        assetMoves = moves,
        assetMovesRange = movesRange,
        assetMovesLoading = movesLoading,
        assetTypeLabel = { it },
        cashLabel = "Cash",
    )
}
