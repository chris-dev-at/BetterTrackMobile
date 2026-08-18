package at.bettertrack.app.ui.insights

import at.bettertrack.app.data.db.CashMovementEntity
import at.bettertrack.app.data.db.CashSourceEntity
import at.bettertrack.app.data.db.HoldingEntity
import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.data.db.PortfolioTotals
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The twelve builders, and the two rules they exist to keep.
 *
 * 1. **Absence is not zero.** A missing cost basis is reported as missing
 *    coverage, never plotted as a break-even; a portfolio with no tax record for
 *    a year says so rather than claiming a nil return.
 * 2. **The server is the only calculator.** These tests pin the aggregation
 *    boundary: totals come from `PortfolioTotals`, per-position figures come from
 *    the holding rows, and nothing is derived from a price and a quantity.
 */
class InsightsDataTest {

    private val zone: ZoneId = ZoneId.of("Europe/Vienna")
    private val today: LocalDate = LocalDate.of(2026, 8, 18)

    // ── Window resolution ───────────────────────────────────────────────────

    @Test
    fun `a one-year window ends today and starts a year back`() {
        val window = insightWindow(BtInsightPeriod.ONE_YEAR, today)
        assertEquals(today.toEpochDay(), window.toEpochDay)
        assertEquals(today.minusYears(1).toEpochDay(), window.fromEpochDay)
        assertEquals(window.toEpochDay, window.asOfEpochDay)
    }

    @Test
    fun `a calendar year spans January to December of that year`() {
        val window = insightWindow(
            BtInsightPeriod(BtInsightPeriodKind.CALENDAR_YEAR, year = 2025),
            today,
        )
        assertEquals(LocalDate.of(2025, 1, 1).toEpochDay(), window.fromEpochDay)
        assertEquals(LocalDate.of(2025, 12, 31).toEpochDay(), window.toEpochDay)
        assertEquals(2025, window.year)
        assertTrue(window.isCalendarYear)
        assertTrue(windowIsCalendarYear(window))
    }

    /** The current year's window cannot claim data from the future. */
    @Test
    fun `the current calendar year resolves its as-of date to today`() {
        val window = insightWindow(
            BtInsightPeriod(BtInsightPeriodKind.CALENDAR_YEAR, year = 2026),
            today,
        )
        assertEquals(today.toEpochDay(), window.asOfEpochDay)
    }

    /**
     * A stichtag insight stores a zeroed custom period. It must resolve to the
     * frame's end date, not to the epoch.
     */
    @Test
    fun `a zeroed custom period collapses to today`() {
        val window = insightWindow(BtInsightPeriod(BtInsightPeriodKind.CUSTOM), today)
        assertEquals(today.toEpochDay(), window.fromEpochDay)
        assertEquals(today.toEpochDay(), window.toEpochDay)
    }

    @Test
    fun `a reversed custom range is normalised rather than rejected`() {
        val window = insightWindow(
            BtInsightPeriod(BtInsightPeriodKind.CUSTOM, fromEpochDay = 20_000, toEpochDay = 19_000),
            today,
        )
        assertEquals(19_000L, window.fromEpochDay)
        assertEquals(20_000L, window.toEpochDay)
    }

    @Test
    fun `the previous window is the same length, immediately before`() {
        val window = insightWindow(BtInsightPeriod.SIX_MONTHS, today)
        val previous = window.previous
        assertEquals(window.fromEpochDay - 1, previous.toEpochDay)
        assertEquals(
            window.toEpochDay - window.fromEpochDay,
            previous.toEpochDay - previous.fromEpochDay,
        )
    }

    @Test
    fun `a plain twelve-month range is not a calendar year`() {
        assertFalse(windowIsCalendarYear(insightWindow(BtInsightPeriod.ONE_YEAR, today)))
    }

    /**
     * The page has ONE period and twelve cards read it, so a stichtag card can be
     * handed a twelve-month frame. It must collapse that frame to its end date
     * rather than claim to describe the year.
     *
     * Caught on device, 2026-08-18: Anlageklassen and Tagesbewegungen were both
     * printing "18.08.2025 – 18.08.2026" as their own subject line.
     */
    @Test
    fun `a stichtag insight collapses a long frame to its end date`() {
        listOf(
            BtInsight.ASSET_CLASSES,
            BtInsight.DAILY_MOVERS,
            BtInsight.HOLDING_CONCENTRATION,
            BtInsight.UNREALIZED_PL,
            BtInsight.VALUE_VS_BASIS,
            BtInsight.LIQUID_FUNDS,
        ).forEach { insight ->
            val window = insightResolveWindow(insight, BtInsightPeriod.ONE_YEAR, today)
            assertTrue("${insight.name} kept a range", window.isStichtag())
            assertEquals(today.toEpochDay(), window.asOfEpochDay)
        }
    }

    @Test
    fun `a flow insight keeps the whole frame`() {
        listOf(BtInsight.PORTFOLIO_DEVELOPMENT, BtInsight.MONTHLY_CASHFLOW, BtInsight.DIVIDENDS)
            .forEach { insight ->
                val window = insightResolveWindow(insight, BtInsightPeriod.ONE_YEAR, today)
                assertFalse("${insight.name} lost its range", window.isStichtag())
                assertEquals(today.minusYears(1).toEpochDay(), window.fromEpochDay)
            }
    }

    /** A budget is set for a month, so its card resolves to that whole month. */
    @Test
    fun `budgets resolve to the calendar month containing the frame end`() {
        val window = insightResolveWindow(
            BtInsight.BUDGETS_SPENDING,
            BtInsightPeriod.ONE_YEAR,
            today,
        )
        assertEquals(LocalDate.of(2026, 8, 1).toEpochDay(), window.fromEpochDay)
        assertEquals(LocalDate.of(2026, 8, 31).toEpochDay(), window.toEpochDay)
        // Never claims data past today.
        assertEquals(today.toEpochDay(), window.asOfEpochDay)
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private fun portfolio(
        id: String = "p1",
        cash: Double = 4_862.90,
        total: Double = 38_579.23,
        unrealized: Double = 5_218.44,
        dayChange: Double = 170.90,
    ) = PortfolioEntity(
        id = id,
        name = "Depot",
        visibility = "private",
        sortOrder = 0,
        isDefault = true,
        defaultPayFromCash = false,
        archivedAt = null,
        baseCurrency = "EUR",
        totals = PortfolioTotals(
            marketValueEur = total - cash,
            investedEur = 30_000.0,
            unrealizedPnlEur = unrealized,
            unrealizedPnlPct = 14.3,
            dayChangeEur = dayChange,
            dayChangePct = 0.44,
            cashEur = cash,
            totalValueEur = total,
        ),
        detailSyncedAtMs = 0L,
    )

    private fun holding(
        symbol: String,
        type: String = "stock",
        value: Double? = 1_000.0,
        basis: Double? = 800.0,
        unrealized: Double? = 200.0,
        day: Double? = 12.0,
    ) = HoldingEntity(
        portfolioId = "p1",
        assetId = "a-$symbol",
        assetSymbol = symbol,
        assetName = symbol,
        assetExchange = null,
        assetCurrency = "EUR",
        assetType = type,
        assetIsCustom = false,
        quantity = 1.0,
        avgCost = 800.0,
        realizedPnl = 0.0,
        price = value,
        marketValueEur = value,
        costBasisEur = basis,
        unrealizedPnlEur = unrealized,
        unrealizedPnlPct = 25.0,
        dayChangeEur = day,
        dayChangePct = 1.2,
    )

    private fun source(
        holdings: List<HoldingEntity> = emptyList(),
        portfolios: List<PortfolioEntity> = listOf(portfolio()),
        cashSources: List<CashSourceEntity> = emptyList(),
        movements: List<CashMovementEntity> = emptyList(),
        taxYears: List<BtInsightTaxYear> = emptyList(),
        taxPositions: List<BtInsightTaxPosition> = emptyList(),
        valueSeries: Map<String, List<BtInsightPoint>> = emptyMap(),
    ) = BtInsightSource(
        portfolios = portfolios,
        holdings = holdings,
        cashSources = cashSources,
        cashMovements = movements,
        taxYear = taxYears,
        taxPositions = taxPositions,
        valueSeries = valueSeries,
        assetTypeLabel = { it },
        cashLabel = "Cash",
    )

    private fun build(
        insight: BtInsight,
        source: BtInsightSource,
        config: BtInsightConfig = BtInsightConfig.PRISTINE,
        period: BtInsightPeriod = BtInsightPeriod.ONE_YEAR,
    ) = buildInsightSnapshot(
        insight = insight,
        config = config,
        source = source,
        window = insightWindow(period, today),
        zone = zone,
    )

    // ── Empty is an answer ──────────────────────────────────────────────────

    @Test
    fun `every insight renders its designed empty state on an empty account`() {
        // No holdings, no cash, no movements, no tax record: the state a brand
        // new account is actually in.
        val empty = source(portfolios = listOf(portfolio(cash = 0.0, total = 0.0)))
        BtInsight.entries.forEach { insight ->
            val snapshot = build(insight, empty)
            assertTrue("${insight.name} did not report empty", snapshot.isEmpty)
            assertNotNull("${insight.name} has no empty reason", snapshot.empty)
        }
    }

    @Test
    fun `an empty insight carries no headline to print`() {
        val snapshot = build(
            BtInsight.ASSET_CLASSES,
            source(portfolios = listOf(portfolio(cash = 0.0, total = 0.0))),
        )
        assertNull(snapshot.headline)
        assertEquals(BtInsightEmptyReason.NO_ALLOCATION, snapshot.empty)
    }

    /** "Missing basis is never shown as zero." */
    @Test
    fun `holdings without cost basis are reported as coverage, not as break-even`() {
        val snapshot = build(
            BtInsight.UNREALIZED_PL,
            source(
                holdings = listOf(
                    holding("MSFT", unrealized = 1_842.0),
                    holding("BAYN", unrealized = -624.0),
                    holding("XX", basis = null, unrealized = null),
                ),
            ),
        )
        assertFalse(snapshot.isEmpty)
        assertEquals(BtInsightCoverage(2, 3), snapshot.coverage)
        // The uncovered holding is absent from the rows rather than plotted at 0.
        assertEquals(setOf("up:MSFT", "up:BAYN"), snapshot.datums.map { it.key }.toSet())
    }

    @Test
    fun `no cost basis at all is an empty state with its coverage stated`() {
        val snapshot = build(
            BtInsight.UNREALIZED_PL,
            source(holdings = listOf(holding("XX", basis = null, unrealized = null))),
        )
        assertEquals(BtInsightEmptyReason.NO_COST_BASIS, snapshot.empty)
        assertEquals(BtInsightCoverage(0, 1), snapshot.coverage)
    }

    @Test
    fun `a missing tax year is absence, not a nil return`() {
        val snapshot = build(
            BtInsight.TAX_SUMMARY,
            source(taxYears = emptyList()),
            period = BtInsightPeriod(BtInsightPeriodKind.CALENDAR_YEAR, year = 2026),
        )
        assertEquals(BtInsightEmptyReason.NO_TAX_DATA, snapshot.empty)
        assertTrue(snapshot.datums.isEmpty())
    }

    // ── Aggregation boundary ────────────────────────────────────────────────

    @Test
    fun `allocation groups holdings by asset class and adds the server cash slice`() {
        val snapshot = build(
            BtInsight.ASSET_CLASSES,
            source(
                holdings = listOf(
                    holding("MSFT", type = "stock", value = 10_000.0),
                    holding("VUSA", type = "etf", value = 6_000.0),
                ),
            ),
        )
        assertEquals(
            listOf("type:stock", "type:etf", "cash"),
            snapshot.datums.sortedByDescending { it.value }.map { it.key },
        )
        assertEquals(10_000.0 + 6_000.0 + 4_862.90, snapshot.total, 0.01)
    }

    @Test
    fun `hiding cash removes the slice and shrinks the denominator`() {
        val holdings = listOf(holding("MSFT", value = 10_000.0))
        val withCash = build(BtInsight.ASSET_CLASSES, source(holdings = holdings))
        val without = build(
            BtInsight.ASSET_CLASSES,
            source(holdings = holdings),
            config = BtInsightConfig(showCash = false),
        )
        assertTrue(withCash.datums.any { it.key == "cash" })
        assertFalse(without.datums.any { it.key == "cash" })
        assertEquals(10_000.0, without.total, 0.01)
    }

    /**
     * One asset held in several portfolios contributed the SUM of its rows to
     * the session; listing them apart would double-count the day.
     */
    @Test
    fun `daily movers fold one symbol held twice into a single row`() {
        val snapshot = build(
            BtInsight.DAILY_MOVERS,
            source(
                holdings = listOf(
                    holding("ETH", day = 60.0),
                    holding("ETH", day = 61.44),
                    holding("SOL", day = -74.32),
                ),
            ),
        )
        assertEquals(2, snapshot.datums.size)
        assertEquals(121.44, snapshot.datums.first { it.key == "mv:ETH" }.value, 0.01)
    }

    /** The day total is the SERVER's, not a sum of the rows on screen. */
    @Test
    fun `the day headline comes from the server total`() {
        val snapshot = build(
            BtInsight.DAILY_MOVERS,
            source(holdings = listOf(holding("ETH", day = 1.0))),
        )
        assertEquals(BtInsightValue.Money(170.90, signed = true), snapshot.headline)
    }

    @Test
    fun `a holding that moved exactly nothing is dropped rather than drawn on the axis`() {
        val snapshot = build(
            BtInsight.DAILY_MOVERS,
            source(holdings = listOf(holding("ETH", day = 12.0), holding("FLAT", day = 0.0))),
        )
        assertEquals(listOf("mv:ETH"), snapshot.datums.map { it.key })
    }

    @Test
    fun `the unrealized headline is the server total, not the sum of the rows`() {
        val snapshot = build(
            BtInsight.UNREALIZED_PL,
            source(holdings = listOf(holding("MSFT", unrealized = 1.0))),
        )
        assertEquals(BtInsightValue.Money(5_218.44, signed = true), snapshot.headline)
    }

    /**
     * The server already reports the difference as unrealized P/L. Subtracting
     * basis from value here would recompute a number the API sends.
     */
    @Test
    fun `value versus basis takes its delta from the server, not from subtraction`() {
        val snapshot = build(
            BtInsight.VALUE_VS_BASIS,
            source(holdings = listOf(holding("MSFT", value = 4_938.0, basis = 3_096.0, unrealized = 1_800.0))),
        )
        val pair = snapshot.paired.single()
        assertEquals(4_938.0, pair.valueEur, 0.01)
        assertEquals(3_096.0, pair.basisEur, 0.01)
        // 1_800, the server's figure — NOT 4_938 − 3_096 = 1_842.
        assertEquals(1_800.0, pair.deltaEur!!, 0.01)
    }

    // ── Cash flow ───────────────────────────────────────────────────────────

    private fun movement(
        id: String,
        kind: String,
        amount: Double,
        date: LocalDate,
    ) = CashMovementEntity(
        id = id,
        portfolioId = "p1",
        sourceId = "s1",
        kind = kind,
        amountEur = amount,
        transactionId = null,
        transferId = null,
        counterpartSourceId = null,
        executedAt = date.toString(),
        executedAtMs = date.atStartOfDay(zone).toInstant().toEpochMilli(),
        note = null,
        createdAt = date.toString(),
    )

    /** "Transfers alone are not income or spending." */
    @Test
    fun `transfers are excluded from cash flow by default`() {
        val movements = listOf(
            movement("1", "deposit", 1_000.0, today.minusDays(10)),
            movement("2", "transfer_in", 5_000.0, today.minusDays(9)),
            movement("3", "transfer_out", -5_000.0, today.minusDays(9)),
        )
        val snapshot = build(BtInsight.MONTHLY_CASHFLOW, source(movements = movements))
        assertEquals(BtInsightValue.Money(1_000.0, signed = true), snapshot.headline)
    }

    @Test
    fun `including transfers is an explicit choice that changes the answer`() {
        val movements = listOf(
            movement("1", "deposit", 1_000.0, today.minusDays(10)),
            movement("2", "transfer_in", 5_000.0, today.minusDays(9)),
        )
        val snapshot = build(
            BtInsight.MONTHLY_CASHFLOW,
            source(movements = movements),
            config = BtInsightConfig(includeTransfers = true),
        )
        assertEquals(BtInsightValue.Money(6_000.0, signed = true), snapshot.headline)
    }

    @Test
    fun `movements outside the window are not counted`() {
        val movements = listOf(
            movement("1", "deposit", 1_000.0, today.minusDays(10)),
            movement("2", "deposit", 9_999.0, today.minusYears(3)),
        )
        val snapshot = build(BtInsight.MONTHLY_CASHFLOW, source(movements = movements))
        assertEquals(BtInsightValue.Money(1_000.0, signed = true), snapshot.headline)
    }

    // ── Liquid funds ────────────────────────────────────────────────────────

    private fun cashSource(id: String, name: String, kind: String, balance: Double) =
        CashSourceEntity(
            id = id,
            portfolioId = "p1",
            name = name,
            kind = kind,
            isMain = false,
            balanceEur = balance,
            archivedAt = null,
        )

    @Test
    fun `liquid funds rank the sources and total them`() {
        val snapshot = build(
            BtInsight.LIQUID_FUNDS,
            source(
                cashSources = listOf(
                    cashSource("1", "Girokonto", "bank", 2_600.0),
                    cashSource("2", "Wallet", "custom", 900.0),
                ),
            ),
        )
        assertEquals(listOf("Girokonto", "Wallet"), snapshot.datums.map { it.label })
        assertEquals(3_500.0, snapshot.total, 0.01)
    }

    @Test
    fun `excluding broker cash drops those sources from the total`() {
        val sources = listOf(
            cashSource("1", "Girokonto", "bank", 2_600.0),
            cashSource("2", "Depot-Cash", "cash", 1_400.0),
        )
        val without = build(
            BtInsight.LIQUID_FUNDS,
            source(cashSources = sources),
            config = BtInsightConfig(showCash = false),
        )
        assertEquals(2_600.0, without.total, 0.01)
    }

    @Test
    fun `an archived cash source is never counted`() {
        val snapshot = build(
            BtInsight.LIQUID_FUNDS,
            source(
                cashSources = listOf(
                    cashSource("1", "Girokonto", "bank", 2_600.0)
                        .copy(archivedAt = "2026-01-01T00:00:00Z"),
                ),
            ),
        )
        assertEquals(BtInsightEmptyReason.NO_LIQUID_FUNDS, snapshot.empty)
    }

    // ── History ─────────────────────────────────────────────────────────────

    @Test
    fun `a single history point is a stichtag fact and never a line`() {
        val snapshot = build(
            BtInsight.PORTFOLIO_DEVELOPMENT,
            source(valueSeries = mapOf("p1" to listOf(BtInsightPoint(today.toEpochDay(), 100.0)))),
        )
        assertFalse(snapshot.isEmpty)
        assertTrue(snapshot.isSinglePoint)
    }

    /**
     * The server rebases its performance series to the range it was asked for.
     * Re-basing it onto a custom sub-range here would be the app calculating
     * investment performance, so a custom period prints value facts only.
     */
    @Test
    fun `a custom period prints no performance percentage`() {
        val series = (0..10).map { BtInsightPoint(today.minusDays(10L - it).toEpochDay(), 100.0 + it) }
        val src = source(
            valueSeries = mapOf("p1" to series),
        ).copy(performanceSeries = mapOf("p1" to listOf(BtInsightPoint(today.toEpochDay(), 14.3))))

        val yearly = build(BtInsight.PORTFOLIO_DEVELOPMENT, src)
        assertTrue(
            "a server range must be allowed to print performance",
            yearly.facts.any { it.value is BtInsightValue.Percent },
        )

        val custom = build(
            BtInsight.PORTFOLIO_DEVELOPMENT,
            src,
            period = BtInsightPeriod(
                BtInsightPeriodKind.CUSTOM,
                fromEpochDay = today.minusDays(10).toEpochDay(),
                toEpochDay = today.toEpochDay(),
            ),
        )
        assertTrue(
            "a custom period must not print a rebased performance figure",
            custom.facts.none { it.value is BtInsightValue.Percent },
        )
    }

    @Test
    fun `history outside the window is filtered out`() {
        val series = listOf(
            BtInsightPoint(today.minusYears(4).toEpochDay(), 10.0),
            BtInsightPoint(today.minusDays(3).toEpochDay(), 200.0),
        )
        val snapshot = build(
            BtInsight.PORTFOLIO_DEVELOPMENT,
            source(valueSeries = mapOf("p1" to series)),
        )
        assertEquals(1, snapshot.series.size)
        assertEquals(200.0, snapshot.series.single().value, 0.01)
    }

    // ── Captions ────────────────────────────────────────────────────────────

    @Test
    fun `a populated insight always produces a caption and a headline`() {
        val snapshot = build(
            BtInsight.HOLDING_CONCENTRATION,
            source(holdings = listOf(holding("MSFT", value = 5_000.0), holding("ETH", value = 2_000.0))),
        )
        assertNotNull(snapshot.headline)
        assertNotNull(snapshot.caption)
        assertEquals("MSFT", snapshot.caption?.name)
    }

    @Test
    fun `concentration reports top three and top five shares as percentages`() {
        val snapshot = build(
            BtInsight.HOLDING_CONCENTRATION,
            source(
                holdings = (1..6).map { holding("S$it", value = 1_000.0) },
                portfolios = listOf(portfolio(cash = 0.0)),
            ),
        )
        val shares = snapshot.facts.mapNotNull { it.value as? BtInsightValue.Percent }
        assertTrue("expected top-3 and top-5 shares", shares.size >= 2)
        assertEquals(50.0, shares[0].pct, 0.01)
        assertEquals(83.33, shares[1].pct, 0.01)
    }
}
