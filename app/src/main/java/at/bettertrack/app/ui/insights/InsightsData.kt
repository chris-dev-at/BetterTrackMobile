package at.bettertrack.app.ui.insights

import at.bettertrack.app.R
import at.bettertrack.app.data.db.CashMovementEntity
import at.bettertrack.app.data.db.CashSourceEntity
import at.bettertrack.app.data.db.HoldingEntity
import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.ui.charts.viz.VizDatum
import at.bettertrack.app.ui.charts.viz.VizRole
import at.bettertrack.app.ui.charts.viz.withStableColorIndices
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs

/**
 * Turns the cached, server-computed rows the app already holds into the twelve
 * [BtInsightSnapshot]s the catalog promises.
 *
 * ## The one rule this file exists to keep
 *
 * **The server is the only calculator.** Nothing below derives a money value the
 * server did not send. What it does do — and the distinction matters — is
 * *aggregate* server-computed values into the total whose parts are being drawn,
 * and *select* which of those values fall inside a window. Both are the same
 * operations the shipped allocation card and the shipped net-worth hero already
 * perform (`HomeLogic.summarize` sums `totals.totalValueEur` across portfolios;
 * `AllocationSummary` sums `marketValueEur` for its denominator), so this file
 * follows an established, owner-accepted pattern rather than inventing one.
 *
 * Concretely, this file never:
 *  - multiplies a price by a quantity,
 *  - derives a euro move from a percentage,
 *  - rebases a performance series onto a start date the server did not use,
 *  - nets two amounts into a third that the API also reports.
 *
 * The third of those is why a **custom** period on [BtInsight.PORTFOLIO_DEVELOPMENT]
 * shows a windowed value curve but no performance percentage: the server's
 * performance series is rebased to *its* range start, and re-basing it here
 * would be the app calculating investment performance. The card says so rather
 * than printing a number it made up.
 *
 * ## Empty is an answer
 *
 * Every builder returns a snapshot with an [BtInsightSnapshot.empty] reason
 * instead of a chart of zeros when its data is absent. "Absence is not 0,00 €" —
 * a missing cost basis is reported as missing coverage, never as a zero basis,
 * and a portfolio with no tax record for a year says so instead of claiming a
 * nil return.
 */

/** Everything the twelve builders may read. Assembled once per page render. */
data class BtInsightSource(
    /** The portfolios in scope, already filtered by the page/report frame. */
    val portfolios: List<PortfolioEntity>,
    /** Visible holdings across [portfolios]. */
    val holdings: List<HoldingEntity>,
    val cashSources: List<CashSourceEntity>,
    val cashMovements: List<CashMovementEntity>,
    /** Tag id → display name, for the spending insight. */
    val tagNames: Map<String, String> = emptyMap(),
    /** Tag id → the colour the user painted it, ARGB. */
    val tagColors: Map<String, Int> = emptyMap(),
    /** Movement id → the tag ids on it. */
    val movementTags: Map<String, List<String>> = emptyMap(),
    /** Server-computed budget progress for the resolved budget month. */
    val budgets: List<BtInsightBudget> = emptyList(),
    /** Server value series per portfolio id, already range-fetched. */
    val valueSeries: Map<String, List<BtInsightPoint>> = emptyMap(),
    /** Server performance series per portfolio id, rebased by the SERVER. */
    val performanceSeries: Map<String, List<BtInsightPoint>> = emptyMap(),
    /**
     * Asset id → percent price move over [assetMovesRange], derived from the
     * server's own close series (see [insightMovePercent]).
     *
     * An asset absent from this map has an UNKNOWN move, not a zero one — either
     * its history call failed or it fell outside the fetch cap — and the movers
     * builder lists it as unavailable rather than plotting it at the origin.
     */
    val assetMoves: Map<String, Double> = emptyMap(),
    /**
     * The range [assetMoves] was fetched for.
     *
     * Carried so the builder can refuse to label a week's numbers as a year's
     * while a newly requested range is still in flight. Mismatched means "not
     * fetched yet", never "close enough".
     */
    val assetMovesRange: BtInsightMoveRange? = null,
    /** True while a price-history pass for the requested range is in flight. */
    val assetMovesLoading: Boolean = false,
    /** Server tax-year totals for the resolved calendar year, per portfolio. */
    val taxYear: List<BtInsightTaxYear> = emptyList(),
    /** Server per-position realized/dividend rows for the resolved year. */
    val taxPositions: List<BtInsightTaxPosition> = emptyList(),
    /** Localized asset-class name for a server `assetType`. */
    val assetTypeLabel: (String) -> String = { it },
    /** Localized "Cash" label for the allocation slice. */
    val cashLabel: String = "Cash",
    /** Localized "Andere"/bucket label for a source with no name. */
    val unnamedLabel: String = "—",
    /**
     * Localized short month name for a `YYYY-MM` bucket key.
     *
     * Supplied by the surface for the same reason [assetTypeLabel] is: this file
     * is pure and locale-free, and a month rendered as its ordinal ("8") is not
     * a label a reader can use. Caught on device 2026-08-18, where the cash-flow
     * card's "Stärkster Monat" read "8".
     */
    val monthLabel: (String) -> String = { it },
)

/** Server budget progress: what the limit is and what the server counted against it. */
data class BtInsightBudget(
    val tagId: String,
    val tagName: String,
    val limitEur: Double,
    val spentEur: Double,
    val colorArgb: Int? = null,
)

/** One portfolio's server tax totals for one calendar year. */
data class BtInsightTaxYear(
    val portfolioId: String,
    val year: Int,
    val realizedPnlEur: Double,
    val dividendsGrossEur: Double,
    val taxWithheldEur: Double,
    val taxRefundedEur: Double,
    val taxNetEur: Double,
)

/** One position's server-computed realized/dividend contribution inside a year. */
data class BtInsightTaxPosition(
    val portfolioId: String,
    val symbol: String,
    val realizedPnlEur: Double,
    val dividendsGrossEur: Double,
    val taxEur: Double,
    /** Individual sells, so a sub-year window can select without recomputing. */
    val sells: List<BtInsightTaxEvent> = emptyList(),
    val dividends: List<BtInsightTaxEvent> = emptyList(),
)

/** One dated, server-valued tax event. */
data class BtInsightTaxEvent(val epochDay: Long, val amountEur: Double)

// ---------------------------------------------------------------------------
// Window resolution
// ---------------------------------------------------------------------------

/** The resolved time window an insight renders against. */
data class BtInsightWindow(
    val fromEpochDay: Long,
    val toEpochDay: Long,
    /** The date printed as `Stand {date}`. Always the window's end. */
    val asOfEpochDay: Long,
    val kind: BtInsightPeriodKind,
    /** The calendar year, when [kind] is CALENDAR_YEAR. */
    val year: Int,
) {
    val isCalendarYear: Boolean get() = kind == BtInsightPeriodKind.CALENDAR_YEAR

    /** The equal-length window immediately before this one. */
    val previous: BtInsightWindow
        get() {
            val span = toEpochDay - fromEpochDay
            return copy(
                fromEpochDay = fromEpochDay - span - 1,
                toEpochDay = fromEpochDay - 1,
                asOfEpochDay = fromEpochDay - 1,
            )
        }

    fun contains(epochDay: Long): Boolean = epochDay in fromEpochDay..toEpochDay
}

/**
 * Resolve [period] against [today].
 *
 * [earliestEpochDay] bounds `MAX`; when the caller does not know it, `MAX`
 * starts at the epoch, which is harmless because every selection then simply
 * includes everything.
 */
fun insightWindow(
    period: BtInsightPeriod,
    today: LocalDate,
    earliestEpochDay: Long? = null,
): BtInsightWindow {
    val end = today.toEpochDay()
    return when (period.kind) {
        BtInsightPeriodKind.ONE_MONTH -> window(today.minusMonths(1).toEpochDay(), end, period.kind)
        BtInsightPeriodKind.SIX_MONTHS -> window(today.minusMonths(6).toEpochDay(), end, period.kind)
        BtInsightPeriodKind.ONE_YEAR -> window(today.minusYears(1).toEpochDay(), end, period.kind)
        BtInsightPeriodKind.MAX -> window(earliestEpochDay ?: 0L, end, period.kind)
        BtInsightPeriodKind.CUSTOM -> {
            // A zeroed custom period is what a snapshot insight stores: it has no
            // range of its own and simply resolves to the frame's end date.
            val from = if (period.fromEpochDay == 0L) end else period.fromEpochDay
            val to = if (period.toEpochDay == 0L) end else period.toEpochDay
            window(minOf(from, to), maxOf(from, to), period.kind)
        }
        BtInsightPeriodKind.CALENDAR_YEAR -> {
            val year = if (period.year > 0) period.year else today.year
            BtInsightWindow(
                fromEpochDay = LocalDate.of(year, 1, 1).toEpochDay(),
                toEpochDay = LocalDate.of(year, 12, 31).toEpochDay(),
                asOfEpochDay = minOf(LocalDate.of(year, 12, 31).toEpochDay(), end),
                kind = period.kind,
                year = year,
            )
        }
    }
}

private fun window(from: Long, to: Long, kind: BtInsightPeriodKind) =
    BtInsightWindow(from, to, to, kind, LocalDate.ofEpochDay(to).year)

/** True when [window] is exactly one whole calendar year — gates the tax section. */
fun windowIsCalendarYear(window: BtInsightWindow): Boolean {
    if (window.isCalendarYear) return true
    val from = LocalDate.ofEpochDay(window.fromEpochDay)
    val to = LocalDate.ofEpochDay(window.toEpochDay)
    return from.dayOfYear == 1 && to.month.value == 12 && to.dayOfMonth == 31 &&
        from.year == to.year
}

/**
 * The window an insight ACTUALLY renders against, given a frame period.
 *
 * This is the second half of the "absent, not disabled" rule, and it is the one
 * that bites in practice: a stichtag insight can be handed a year-long frame
 * — the page has exactly one period and twelve cards read it — and it must not
 * then claim to describe that year.
 *
 * So the timing collapses the frame:
 *
 *  - [BtInsightTiming.SNAPSHOT] and [BtInsightTiming.SESSION] resolve to the
 *    frame's END DATE only. An allocation is a fact about a moment; labelling it
 *    "1. Sep. 2025 – 18. Aug. 2026" would be a claim the data cannot support.
 *  - [BtInsightTiming.BUDGET_MONTH] resolves to the whole calendar month
 *    containing that end date, because that is the period a budget is set for.
 *  - Everything else keeps the frame as given.
 *
 * Found on device, 2026-08-18: the Anlageklassen and Tagesbewegungen cards were
 * printing the page's twelve-month range as their own subject line.
 */
fun insightResolveWindow(
    insight: BtInsight,
    period: BtInsightPeriod,
    today: LocalDate,
    earliestEpochDay: Long? = null,
): BtInsightWindow {
    val frame = insightWindow(period, today, earliestEpochDay)
    return when (insight.spec.timing) {
        BtInsightTiming.SNAPSHOT, BtInsightTiming.SESSION -> frame.copy(
            fromEpochDay = frame.toEpochDay,
            asOfEpochDay = frame.toEpochDay,
        )
        BtInsightTiming.BUDGET_MONTH -> {
            val month = YearMonth.from(LocalDate.ofEpochDay(frame.toEpochDay))
            frame.copy(
                fromEpochDay = month.atDay(1).toEpochDay(),
                toEpochDay = month.atEndOfMonth().toEpochDay(),
                asOfEpochDay = minOf(month.atEndOfMonth().toEpochDay(), frame.toEpochDay),
            )
        }
        BtInsightTiming.PERIOD,
        BtInsightTiming.MONTHS,
        BtInsightTiming.CALENDAR_YEAR,
        -> frame
    }
}

/** True when this window is a single day — a stichtag rather than a range. */
fun BtInsightWindow.isStichtag(): Boolean = fromEpochDay == toEpochDay

// ---------------------------------------------------------------------------
// The builder
// ---------------------------------------------------------------------------

/**
 * Build one insight's snapshot.
 *
 * Pure: same inputs, same output, no clock and no I/O — [window] already carries
 * the resolved dates, which is what lets the report freeze a snapshot and render
 * it minutes later without the numbers drifting under it.
 */
fun buildInsightSnapshot(
    insight: BtInsight,
    config: BtInsightConfig,
    source: BtInsightSource,
    window: BtInsightWindow,
    zone: ZoneId = ZoneId.systemDefault(),
): BtInsightSnapshot = when (insight) {
    BtInsight.PORTFOLIO_DEVELOPMENT -> buildDevelopment(config, source, window)
    BtInsight.ASSET_CLASSES -> buildAssetClasses(config, source, window)
    BtInsight.DAILY_MOVERS -> buildMovers(config, source, window)
    BtInsight.MONTHLY_CASHFLOW -> buildCashflow(config, source, window, zone)
    BtInsight.BUDGETS_SPENDING -> buildSpending(config, source, window, zone)
    BtInsight.HOLDING_CONCENTRATION -> buildConcentration(config, source, window)
    BtInsight.UNREALIZED_PL -> buildUnrealized(config, source, window)
    BtInsight.VALUE_VS_BASIS -> buildValueVsBasis(config, source, window)
    BtInsight.REALIZED_FEES -> buildRealizedFees(config, source, window)
    BtInsight.DIVIDENDS -> buildDividends(config, source, window)
    BtInsight.TAX_SUMMARY -> buildTaxSummary(source, window)
    BtInsight.LIQUID_FUNDS -> buildLiquidFunds(config, source, window)
}

private fun empty(
    insight: BtInsight,
    window: BtInsightWindow,
    reason: BtInsightEmptyReason,
    coverage: BtInsightCoverage? = null,
) = BtInsightSnapshot(
    insight = insight,
    asOfEpochDay = window.asOfEpochDay,
    fromEpochDay = window.fromEpochDay,
    toEpochDay = window.toEpochDay,
    empty = reason,
    coverage = coverage,
    signed = insight.spec.signed,
)

private fun base(
    insight: BtInsight,
    window: BtInsightWindow,
) = BtInsightSnapshot(
    insight = insight,
    asOfEpochDay = window.asOfEpochDay,
    fromEpochDay = window.fromEpochDay,
    toEpochDay = window.toEpochDay,
    signed = insight.spec.signed,
)

// ── 1 · Portfolioentwicklung ────────────────────────────────────────────────

/**
 * The server's value/performance series, windowed to the card's period.
 *
 * Two honesty rails live here:
 *
 *  - **A single point is a Stichtag fact, never a fabricated line.** One point
 *    is reported through [BtInsightSnapshot.isSinglePoint] so the renderer draws
 *    a dot and a date rather than interpolating a trend between one value and
 *    itself.
 *  - **Performance is only printed for a server range.** The performance series
 *    is rebased by the server to the range it was requested for; windowing it to
 *    a custom sub-range would leave the first point at a non-zero percentage
 *    that means nothing. So a custom period prints value facts only.
 */
private fun buildDevelopment(
    config: BtInsightConfig,
    source: BtInsightSource,
    window: BtInsightWindow,
): BtInsightSnapshot {
    val ids = source.portfolios.map { it.id }
    val series = ids.mapNotNull { id ->
        source.valueSeries[id]?.filter { window.contains(it.epochDay) }?.takeIf { it.isNotEmpty() }
    }
    if (series.isEmpty()) return empty(BtInsight.PORTFOLIO_DEVELOPMENT, window, BtInsightEmptyReason.NO_HISTORY)

    // One portfolio → its own curve. Several → the curves are kept separate and
    // directly labelled, because no endpoint returns a combined series and
    // adding them up here would be the app calculating a portfolio value.
    val primary = series.first()
    val covered = source.portfolios.mapNotNull { it.totals }
    val totalNow = covered.sumOf { it.totalValueEur }

    val perf = if (window.kind == BtInsightPeriodKind.CUSTOM) {
        emptyList()
    } else {
        ids.firstNotNullOfOrNull { source.performanceSeries[it] }.orEmpty()
    }
    val perfPct = perf.lastOrNull()?.value

    val first = primary.first().value
    val last = primary.last().value
    val minPoint = primary.minByOrNull { it.value }
    val maxPoint = primary.maxByOrNull { it.value }

    val facts = buildList {
        add(BtInsightFact(R.string.bt_insight_fact_start_value, BtInsightValue.Money(first)))
        maxPoint?.let {
            add(BtInsightFact(R.string.bt_insight_fact_max_value, BtInsightValue.Money(it.value)))
        }
        minPoint?.let {
            add(BtInsightFact(R.string.bt_insight_fact_min_value, BtInsightValue.Money(it.value)))
        }
        if (perfPct != null) {
            add(
                BtInsightFact(
                    R.string.bt_insight_fact_performance,
                    BtInsightValue.Percent(perfPct, signed = true),
                ),
            )
        }
    }

    return base(BtInsight.PORTFOLIO_DEVELOPMENT, window).copy(
        series = primary,
        compareSeries = if (config.compare) series.getOrNull(1).orEmpty() else emptyList(),
        headline = BtInsightValue.Money(if (totalNow > 0.0) totalNow else last),
        facts = facts,
        caption = BtInsightCaption(
            templateRes = if (perfPct != null) {
                R.string.bt_insight_caption_development
            } else {
                R.string.bt_insight_caption_development_value_only
            },
            value = perfPct?.let { BtInsightValue.Percent(it, signed = true) }
                ?: BtInsightValue.Money(last),
        ),
        total = totalNow,
    )
}

// ── 2 · Anlageklassen ───────────────────────────────────────────────────────

private fun buildAssetClasses(
    config: BtInsightConfig,
    source: BtInsightSource,
    window: BtInsightWindow,
): BtInsightSnapshot {
    val showCash = config.showCash ?: true
    val cashEur = source.portfolios.sumOf { it.totals?.cashEur ?: 0.0 }
    val byClass = source.holdings
        .groupBy { it.assetType }
        .map { (type, rows) ->
            VizDatum(
                key = "type:$type",
                label = source.assetTypeLabel(type),
                value = rows.sumOf { it.marketValueEur ?: 0.0 },
                hiddenCount = rows.size,
            )
        }
        .filter { it.value > 0.0 }

    val parts = if (showCash && cashEur > 0.0) {
        byClass + VizDatum("cash", source.cashLabel, cashEur, VizRole.Cash, hiddenCount = 1)
    } else {
        byClass
    }
    if (parts.isEmpty()) return empty(BtInsight.ASSET_CLASSES, window, BtInsightEmptyReason.NO_ALLOCATION)

    val items = withStableColorIndices(parts.sortedByDescending { it.value })
    val total = items.sumOf { it.value }
    val largest = items.maxByOrNull { it.value }

    return base(BtInsight.ASSET_CLASSES, window).copy(
        datums = items,
        headline = BtInsightValue.Money(total),
        facts = buildList {
            largest?.let {
                add(BtInsightFact(R.string.bt_insight_fact_largest_class, BtInsightValue.Text(it.label)))
                add(
                    BtInsightFact(
                        R.string.bt_insight_fact_largest_share,
                        BtInsightValue.Percent(share(it.value, total)),
                    ),
                )
            }
            add(
                BtInsightFact(
                    R.string.bt_insight_fact_class_count,
                    BtInsightValue.Count(items.size, R.plurals.bt_insight_classes),
                ),
            )
        },
        caption = largest?.let {
            BtInsightCaption(
                templateRes = R.string.bt_insight_caption_share_of_total,
                name = it.label,
                value = BtInsightValue.Percent(share(it.value, total)),
            )
        },
        total = total,
    )
}

// ── 3 · Bewegungen ──────────────────────────────────────────────────────────

/**
 * Which positions moved, over the span the card is set to.
 *
 * Three shapes, because three different server facts answer the question and
 * they are not interchangeable — see [BtInsightMoveRange] for the full table and
 * the reasoning:
 *
 *  - **Heute** and **Seit Kauf** are euro answers, each summed from a
 *    server-computed per-holding field (`dayChangeEur`, `unrealizedPnlEur`).
 *  - **1 Woche / 1 Monat / 1 Jahr** are PERCENT answers derived from the server's
 *    own close series, because the euro contribution of a position over a span
 *    needs its quantity *through* that span and no endpoint states it.
 *
 * The card never mixes the two: [BtInsightSnapshot.datumUnit] tells every
 * renderer which one it is holding.
 */
private fun buildMovers(
    config: BtInsightConfig,
    source: BtInsightSource,
    window: BtInsightWindow,
): BtInsightSnapshot {
    val range = config.moveRange ?: BT_INSIGHT_MOVE_RANGE_DEFAULT
    val span = insightMoveWindow(range, window.asOfEpochDay)
    val spanned = window.copy(fromEpochDay = span.first, toEpochDay = span.last)
    val snapshot = when (range) {
        BtInsightMoveRange.DAY -> buildSessionMovers(config, source, spanned)
        BtInsightMoveRange.SINCE_BUY -> buildSinceBuyMovers(config, source, spanned)
        BtInsightMoveRange.WEEK,
        BtInsightMoveRange.MONTH,
        BtInsightMoveRange.YEAR,
        -> buildPriceMovers(config, source, spanned, range)
    }
    // Stamped once, here, so the empty and populated paths cannot disagree about
    // which span the card is showing.
    return snapshot.copy(moveRange = range)
}

/**
 * Today's session, unchanged from the card as it shipped.
 *
 * `dayChangeEur` is server-computed. A euro move is never derived from a
 * percentage and a market value here.
 */
private fun buildSessionMovers(
    config: BtInsightConfig,
    source: BtInsightSource,
    window: BtInsightWindow,
): BtInsightSnapshot {
    val rows = source.holdings
        .filter { (it.dayChangeEur ?: 0.0) != 0.0 }
        .groupBy { it.assetSymbol }
        .map { (symbol, group) ->
            VizDatum(
                key = "mv:$symbol",
                label = symbol,
                // One asset held in several scoped portfolios contributed the
                // sum of its rows to the session; listing them apart would
                // double-count the day.
                value = group.sumOf { it.dayChangeEur ?: 0.0 },
                hiddenCount = group.size,
            )
        }
    if (rows.isEmpty()) {
        return empty(BtInsight.DAILY_MOVERS, window, BtInsightEmptyReason.NO_MOVEMENTS_TODAY)
    }

    val sorted = when (config.sort) {
        BtInsightSort.PERCENT -> rows.sortedByDescending { datum ->
            source.holdings.firstOrNull { "mv:${it.assetSymbol}" == datum.key }?.dayChangePct ?: 0.0
        }
        else -> rows.sortedByDescending { it.value }
    }
    val dayTotal = source.portfolios.sumOf { it.totals?.dayChangeEur ?: 0.0 }
    val best = rows.maxByOrNull { it.value }
    val worst = rows.minByOrNull { it.value }

    return base(BtInsight.DAILY_MOVERS, window).copy(
        datums = sorted,
        headline = BtInsightValue.Money(dayTotal, signed = true),
        facts = buildList {
            best?.let {
                add(BtInsightFact(R.string.bt_insight_fact_best, BtInsightValue.Text(it.label)))
                add(
                    BtInsightFact(
                        R.string.bt_insight_fact_best_amount,
                        BtInsightValue.Money(it.value, signed = true),
                    ),
                )
            }
            worst?.let {
                add(BtInsightFact(R.string.bt_insight_fact_worst, BtInsightValue.Text(it.label)))
                add(
                    BtInsightFact(
                        R.string.bt_insight_fact_worst_amount,
                        BtInsightValue.Money(it.value, signed = true),
                    ),
                )
            }
        },
        caption = best?.let {
            BtInsightCaption(
                templateRes = R.string.bt_insight_caption_biggest_move,
                name = it.label,
                value = BtInsightValue.Money(it.value, signed = true),
            )
        },
        total = dayTotal,
    )
}

/**
 * **Seit Kauf** — the honest "all time".
 *
 * The tempting reading of "all time" is the MAX close series, and it is wrong:
 * that series starts when the *instrument's* data starts, typically years before
 * the user bought anything, so its first-to-last move describes a position nobody
 * held. The result the user actually has since they bought is the server's
 * per-holding `unrealizedPnlEur` — the same figure the *Unrealisierte G/V*
 * insight prints, deliberately, rather than a second number that would have to
 * disagree with it.
 *
 * A holding with no recorded cost basis has no such result and is counted in
 * [BtInsightCoverage] instead of being shown at break-even.
 */
private fun buildSinceBuyMovers(
    config: BtInsightConfig,
    source: BtInsightSource,
    window: BtInsightWindow,
): BtInsightSnapshot {
    val all = source.holdings.filter { (it.marketValueEur ?: 0.0) > 0.0 }
    val covered = all.filter { it.unrealizedPnlEur != null }
    val coverage = BtInsightCoverage(covered.size, all.size)
    val rows = covered
        .groupBy { it.assetSymbol }
        .map { (symbol, group) ->
            VizDatum(
                key = "mv:$symbol",
                label = symbol,
                value = group.sumOf { it.unrealizedPnlEur ?: 0.0 },
                hiddenCount = group.size,
            )
        }
        .filter { it.value != 0.0 }
    if (rows.isEmpty()) {
        return empty(BtInsight.DAILY_MOVERS, window, BtInsightEmptyReason.NO_COST_BASIS, coverage)
    }

    val sorted = when (config.sort) {
        BtInsightSort.PERCENT -> rows.sortedByDescending { datum ->
            covered.firstOrNull { "mv:${it.assetSymbol}" == datum.key }?.unrealizedPnlPct ?: 0.0
        }
        else -> rows.sortedByDescending { it.value }
    }
    val total = source.portfolios.sumOf { it.totals?.unrealizedPnlEur ?: 0.0 }
    val best = rows.maxByOrNull { it.value }
    val worst = rows.minByOrNull { it.value }

    return base(BtInsight.DAILY_MOVERS, window).copy(
        datums = sorted,
        headline = BtInsightValue.Money(total, signed = true),
        facts = listOfNotNull(
            best?.let { BtInsightFact(R.string.bt_insight_fact_best, BtInsightValue.Text(it.label)) },
            best?.let {
                BtInsightFact(
                    R.string.bt_insight_fact_best_amount,
                    BtInsightValue.Money(it.value, signed = true),
                )
            },
            worst?.let { BtInsightFact(R.string.bt_insight_fact_worst, BtInsightValue.Text(it.label)) },
            worst?.let {
                BtInsightFact(
                    R.string.bt_insight_fact_worst_amount,
                    BtInsightValue.Money(it.value, signed = true),
                )
            },
        ),
        caption = BtInsightCaption(
            templateRes = R.string.bt_insight_caption_unrealized,
            value = BtInsightValue.Money(total, signed = true),
        ),
        total = total,
        coverage = coverage,
        unavailable = all.filter { it.unrealizedPnlEur == null }
            .map { it.assetSymbol }
            .distinct()
            .sorted(),
    )
}

/**
 * **1 Woche / 1 Monat / 1 Jahr** — percent price movement per position.
 *
 * What this may print and what it may not:
 *
 *  - It prints the first-to-last percent of a series the SERVER returned for a
 *    range the SERVER chose the interval for, which is the same presentation-level
 *    difference the shipped hero's `rangeDeltaEur` already prints.
 *  - It prints **no euro figure and no portfolio total**, because both would need
 *    the position's quantity through the span. The headline is therefore the
 *    strongest single move, which is a value that actually exists.
 *  - It plots nothing for a position whose series is missing. Those are named in
 *    [BtInsightSnapshot.unavailable] and counted in [BtInsightCoverage], because
 *    an unknown move is not a flat one.
 *
 * [BtInsightSource.assetMovesRange] must match the requested range: numbers
 * fetched for last week are not this year's, and showing them under a year's
 * label while the year's fetch is still in flight would be the exact mislabelling
 * this whole file exists to prevent.
 */
private fun buildPriceMovers(
    config: BtInsightConfig,
    source: BtInsightSource,
    window: BtInsightWindow,
    range: BtInsightMoveRange,
): BtInsightSnapshot {
    val held = source.holdings.filter { (it.marketValueEur ?: 0.0) > 0.0 }
    if (held.isEmpty()) {
        return empty(BtInsight.DAILY_MOVERS, window, BtInsightEmptyReason.NO_HOLDINGS)
    }
    val fresh = source.assetMovesRange == range
    val moves = if (fresh) source.assetMoves else emptyMap()

    // Grouped by SYMBOL like every other row on this card, but the move is a
    // property of the ASSET — two portfolios holding the same stock saw one
    // price move, not two, so the percentage is taken once and never summed.
    val bySymbol = held.groupBy { it.assetSymbol }
    val rows = bySymbol.mapNotNull { (symbol, group) ->
        val pct = group.firstNotNullOfOrNull { moves[it.assetId] } ?: return@mapNotNull null
        VizDatum(key = "mv:$symbol", label = symbol, value = pct, hiddenCount = group.size)
    }
    val missing = bySymbol.keys.filter { symbol -> rows.none { it.key == "mv:$symbol" } }.sorted()

    if (rows.isEmpty()) {
        val reason = if (!fresh || source.assetMovesLoading) {
            BtInsightEmptyReason.PRICE_HISTORY_LOADING
        } else {
            BtInsightEmptyReason.NO_PRICE_HISTORY
        }
        return empty(BtInsight.DAILY_MOVERS, window, reason, BtInsightCoverage(0, bySymbol.size))
    }

    // Both offered sorts rank the same quantity here — the rows ARE percentages —
    // so `Betrag` and `Prozent` agree instead of quietly meaning the same thing
    // twice. Order is by the move itself, strongest gain first.
    val sorted = rows.sortedByDescending { it.value }
    val best = rows.maxByOrNull { it.value }
    val worst = rows.minByOrNull { it.value }

    return base(BtInsight.DAILY_MOVERS, window).copy(
        datums = sorted,
        headline = best?.let { BtInsightValue.Percent(it.value, signed = true) },
        facts = listOfNotNull(
            best?.let { BtInsightFact(R.string.bt_insight_fact_best, BtInsightValue.Text(it.label)) },
            best?.let {
                BtInsightFact(
                    R.string.bt_insight_fact_best_move,
                    BtInsightValue.Percent(it.value, signed = true),
                )
            },
            worst?.let { BtInsightFact(R.string.bt_insight_fact_worst, BtInsightValue.Text(it.label)) },
            worst?.let {
                BtInsightFact(
                    R.string.bt_insight_fact_worst_move,
                    BtInsightValue.Percent(it.value, signed = true),
                )
            },
        ),
        caption = best?.let {
            BtInsightCaption(
                templateRes = R.string.bt_insight_caption_biggest_price_move,
                name = it.label,
                value = BtInsightValue.Percent(it.value, signed = true),
            )
        },
        // A signed percent set has no whole to be a share of, and the config's
        // `Beträge` labels have no euro to print — the renderer reads the unit.
        total = 0.0,
        datumUnit = BtInsightUnit.PERCENT,
        coverage = BtInsightCoverage(rows.size, bySymbol.size),
        unavailable = missing,
    )
}

// ── 4 · Monats-Cashflow ─────────────────────────────────────────────────────

/**
 * Monthly net flow from server-recorded cash movements.
 *
 * Transfers are excluded by default and the study is explicit about why:
 * "Transfers alone are not income or spending." Moving money between two of your
 * own sources nets to zero across the account, so counting it would inflate both
 * columns and change neither answer.
 */
private fun buildCashflow(
    config: BtInsightConfig,
    source: BtInsightSource,
    window: BtInsightWindow,
    zone: ZoneId,
): BtInsightSnapshot {
    val transfers = setOf("transfer_in", "transfer_out")
    val rows = source.cashMovements
        .filter { window.contains(epochDay(it.executedAtMs, zone)) }
        .filter { config.includeTransfers || it.kind !in transfers }
    if (rows.isEmpty()) return empty(BtInsight.MONTHLY_CASHFLOW, window, BtInsightEmptyReason.NO_CASHFLOW)

    val quarterly = config.grouping == BtInsightGrouping.QUARTER
    val buckets = rows.groupBy { movement ->
        val date = LocalDate.ofEpochDay(epochDay(movement.executedAtMs, zone))
        if (quarterly) {
            "${date.year}-Q${(date.monthValue - 1) / 3 + 1}"
        } else {
            YearMonth.from(date).toString()
        }
    }

    val datums = buckets.entries
        .sortedBy { it.key }
        .map { (key, movements) ->
            VizDatum(
                key = "flow:$key",
                label = bucketLabel(key, quarterly, source.monthLabel),
                value = movements.sumOf { it.amountEur },
                hiddenCount = movements.size,
            )
        }

    val inflow = rows.filter { it.amountEur > 0.0 }.sumOf { it.amountEur }
    val outflow = rows.filter { it.amountEur < 0.0 }.sumOf { it.amountEur }
    val net = inflow + outflow
    val bestMonth = datums.maxByOrNull { it.value }
    val worstMonth = datums.minByOrNull { it.value }

    return base(BtInsight.MONTHLY_CASHFLOW, window).copy(
        datums = datums,
        headline = BtInsightValue.Money(net, signed = true),
        facts = listOfNotNull(
            BtInsightFact(R.string.bt_insight_fact_inflow, BtInsightValue.Money(inflow)),
            BtInsightFact(R.string.bt_insight_fact_outflow, BtInsightValue.Money(outflow)),
            bestMonth?.let {
                BtInsightFact(R.string.bt_insight_fact_best_month, BtInsightValue.Text(it.label))
            },
            worstMonth?.let {
                BtInsightFact(R.string.bt_insight_fact_worst_month, BtInsightValue.Text(it.label))
            },
        ),
        caption = BtInsightCaption(
            templateRes = R.string.bt_insight_caption_cashflow,
            value = BtInsightValue.Money(net, signed = true),
        ),
        total = net,
    )
}

private fun bucketLabel(
    key: String,
    quarterly: Boolean,
    monthLabel: (String) -> String,
): String = if (quarterly) key.substringAfter('-') else monthLabel(key)

// ── 5 · Budgets & Ausgaben ──────────────────────────────────────────────────

private fun buildSpending(
    config: BtInsightConfig,
    source: BtInsightSource,
    window: BtInsightWindow,
    zone: ZoneId,
): BtInsightSnapshot {
    val transfers = setOf("transfer_in", "transfer_out")
    val spend = source.cashMovements
        .filter { window.contains(epochDay(it.executedAtMs, zone)) }
        .filter { config.includeTransfers || it.kind !in transfers }
        .filter { it.amountEur < 0.0 }

    val tagged = spend.flatMap { movement ->
        source.movementTags[movement.id].orEmpty().map { tagId -> tagId to movement.amountEur }
    }
    if (tagged.isEmpty()) {
        // An unused budget still renders an honest 0,00 € track; only a period
        // with no tagged spending AND no budgets at all is truly empty.
        if (source.budgets.isEmpty()) {
            return empty(BtInsight.BUDGETS_SPENDING, window, BtInsightEmptyReason.NO_SPENDING)
        }
    }

    val byTag = tagged.groupBy({ it.first }, { it.second })
        .map { (tagId, amounts) ->
            VizDatum(
                key = "tag:$tagId",
                label = source.tagNames[tagId] ?: source.unnamedLabel,
                // Spending is drawn as a magnitude; the sign is already stated
                // by the card's subject ("Ausgaben"), and a part-to-whole form
                // cannot render a negative slice.
                value = abs(amounts.sum()),
                hiddenCount = amounts.size,
                colorArgb = source.tagColors[tagId],
            )
        }
        .filter { it.value > 0.0 }
        .sortedByDescending { it.value }

    if (byTag.isEmpty() && source.budgets.isEmpty()) {
        return empty(BtInsight.BUDGETS_SPENDING, window, BtInsightEmptyReason.NO_SPENDING)
    }

    val items = withStableColorIndices(byTag)
    val total = items.sumOf { it.value }
    // "Tightest budget" needs something to BE tight (device QA 2026-09-01, #20).
    //
    // With budgets defined and nothing spent in the window, the guard above lets
    // this card through with an empty datum list — deliberately, because the
    // budgets themselves are still a fact worth carrying. But the summary tiles
    // then printed "Knappstes Budget: Essen · Bereits genutzt 0,00 %" beside a
    // chart that says "Noch keine Daten", which asserts a superlative over an
    // empty set: with nothing spent, no budget is tighter than any other, and
    // naming one invites the reader to act on a ranking that does not exist.
    // So the tiles degrade to silence and the empty chart speaks for the card.
    val tightest = source.budgets
        .filter { it.limitEur > 0.0 && it.spentEur > 0.0 }
        .maxByOrNull { it.spentEur / it.limitEur }

    return base(BtInsight.BUDGETS_SPENDING, window).copy(
        datums = items,
        headline = BtInsightValue.Money(total),
        facts = buildList {
            items.firstOrNull()?.let {
                add(BtInsightFact(R.string.bt_insight_fact_top_tag, BtInsightValue.Text(it.label)))
                add(BtInsightFact(R.string.bt_insight_fact_top_tag_amount, BtInsightValue.Money(it.value)))
            }
            if (config.showBudgets && tightest != null) {
                add(BtInsightFact(R.string.bt_insight_fact_budget_tight, BtInsightValue.Text(tightest.tagName)))
                add(
                    BtInsightFact(
                        R.string.bt_insight_fact_budget_used,
                        BtInsightValue.Percent(share(tightest.spentEur, tightest.limitEur)),
                    ),
                )
            }
        },
        caption = items.firstOrNull()?.let {
            BtInsightCaption(
                templateRes = R.string.bt_insight_caption_share_of_total,
                name = it.label,
                value = BtInsightValue.Percent(share(it.value, total)),
            )
        },
        total = total,
    )
}

// ── 6 · Positionskonzentration ──────────────────────────────────────────────

private fun buildConcentration(
    config: BtInsightConfig,
    source: BtInsightSource,
    window: BtInsightWindow,
): BtInsightSnapshot {
    val showCash = config.showCash ?: true
    val cashEur = source.portfolios.sumOf { it.totals?.cashEur ?: 0.0 }
    val positions = source.holdings
        .groupBy { it.assetSymbol }
        .map { (symbol, rows) ->
            VizDatum(
                key = "sym:$symbol",
                label = symbol,
                value = rows.sumOf { it.marketValueEur ?: 0.0 },
                hiddenCount = rows.size,
            )
        }
        .filter { it.value > 0.0 }

    val parts = if (showCash && cashEur > 0.0) {
        positions + VizDatum("cash", source.cashLabel, cashEur, VizRole.Cash, hiddenCount = 1)
    } else {
        positions
    }
    if (parts.isEmpty()) return empty(BtInsight.HOLDING_CONCENTRATION, window, BtInsightEmptyReason.NO_HOLDINGS)

    val items = withStableColorIndices(parts.sortedByDescending { it.value })
    val total = items.sumOf { it.value }
    val top3 = items.take(3).sumOf { it.value }
    val top5 = items.take(5).sumOf { it.value }
    val largest = items.firstOrNull()

    return base(BtInsight.HOLDING_CONCENTRATION, window).copy(
        datums = items,
        headline = largest?.let { BtInsightValue.Percent(share(it.value, total)) },
        facts = listOfNotNull(
            largest?.let { BtInsightFact(R.string.bt_insight_fact_largest_position, BtInsightValue.Text(it.label)) },
            BtInsightFact(R.string.bt_insight_fact_top3_share, BtInsightValue.Percent(share(top3, total))),
            BtInsightFact(R.string.bt_insight_fact_top5_share, BtInsightValue.Percent(share(top5, total))),
            BtInsightFact(
                R.string.bt_insight_fact_position_count,
                BtInsightValue.Count(positions.size, R.plurals.bt_insight_positions),
            ),
        ),
        caption = largest?.let {
            BtInsightCaption(
                templateRes = R.string.bt_insight_caption_largest_position,
                name = it.label,
                value = BtInsightValue.Percent(share(it.value, total)),
            )
        },
        total = total,
    )
}

// ── 7 · Unrealisierte G/V ───────────────────────────────────────────────────

/**
 * Server-computed unrealized P/L per position.
 *
 * "Missing basis is never shown as zero": a holding whose `unrealizedPnlEur` is
 * absent is excluded from the rows and counted in [BtInsightCoverage] instead,
 * so the card can say "17 von 19 Positionen" rather than plotting a fictional
 * break-even.
 */
private fun buildUnrealized(
    config: BtInsightConfig,
    source: BtInsightSource,
    window: BtInsightWindow,
): BtInsightSnapshot {
    val all = source.holdings.filter { (it.marketValueEur ?: 0.0) > 0.0 }
    val covered = all.filter { it.unrealizedPnlEur != null }
    val coverage = BtInsightCoverage(covered.size, all.size)
    if (covered.isEmpty()) {
        return empty(BtInsight.UNREALIZED_PL, window, BtInsightEmptyReason.NO_COST_BASIS, coverage)
    }

    val rows = covered
        .groupBy { it.assetSymbol }
        .map { (symbol, group) ->
            VizDatum(
                key = "up:$symbol",
                label = symbol,
                value = group.sumOf { it.unrealizedPnlEur ?: 0.0 },
                hiddenCount = group.size,
            )
        }
        .filter { it.value != 0.0 }
    if (rows.isEmpty()) {
        return empty(BtInsight.UNREALIZED_PL, window, BtInsightEmptyReason.NO_COST_BASIS, coverage)
    }

    val sorted = when (config.sort) {
        BtInsightSort.SIGNED -> rows.sortedByDescending { it.value }
        BtInsightSort.NAME -> rows.sortedBy { it.label }
        else -> rows.sortedByDescending { abs(it.value) }
    }
    val total = source.portfolios.sumOf { it.totals?.unrealizedPnlEur ?: 0.0 }
    val best = rows.maxByOrNull { it.value }
    val worst = rows.minByOrNull { it.value }

    return base(BtInsight.UNREALIZED_PL, window).copy(
        datums = sorted,
        headline = BtInsightValue.Money(total, signed = true),
        facts = listOfNotNull(
            best?.let { BtInsightFact(R.string.bt_insight_fact_best, BtInsightValue.Text(it.label)) },
            best?.let {
                BtInsightFact(R.string.bt_insight_fact_best_amount, BtInsightValue.Money(it.value, signed = true))
            },
            worst?.let { BtInsightFact(R.string.bt_insight_fact_worst, BtInsightValue.Text(it.label)) },
            worst?.let {
                BtInsightFact(R.string.bt_insight_fact_worst_amount, BtInsightValue.Money(it.value, signed = true))
            },
        ),
        caption = BtInsightCaption(
            templateRes = R.string.bt_insight_caption_unrealized,
            value = BtInsightValue.Money(total, signed = true),
        ),
        total = total,
        coverage = coverage,
    )
}

// ── 8 · Marktwert vs. Kostenbasis ───────────────────────────────────────────

private fun buildValueVsBasis(
    config: BtInsightConfig,
    source: BtInsightSource,
    window: BtInsightWindow,
): BtInsightSnapshot {
    val all = source.holdings.filter { (it.marketValueEur ?: 0.0) > 0.0 }
    val covered = all.filter { it.costBasisEur != null }
    val coverage = BtInsightCoverage(covered.size, all.size)
    if (covered.isEmpty()) {
        return empty(BtInsight.VALUE_VS_BASIS, window, BtInsightEmptyReason.NO_COST_BASIS, coverage)
    }

    val pairs = covered
        .groupBy { it.assetSymbol }
        .map { (symbol, group) ->
            BtInsightPair(
                key = "vb:$symbol",
                label = symbol,
                valueEur = group.sumOf { it.marketValueEur ?: 0.0 },
                basisEur = group.sumOf { it.costBasisEur ?: 0.0 },
                // The server already reports the difference as unrealized P/L;
                // subtracting basis from value here would recompute it.
                deltaEur = if (group.all { it.unrealizedPnlEur == null }) {
                    null
                } else {
                    group.sumOf { it.unrealizedPnlEur ?: 0.0 }
                },
            )
        }

    val sorted = when (config.sort) {
        BtInsightSort.BASIS -> pairs.sortedByDescending { it.basisEur }
        BtInsightSort.DELTA -> pairs.sortedByDescending { abs(it.deltaEur ?: 0.0) }
        else -> pairs.sortedByDescending { it.valueEur }
    }.mapIndexed { index, pair -> pair.copy(colorIndex = index) }

    val valueTotal = sorted.sumOf { it.valueEur }
    val basisTotal = sorted.sumOf { it.basisEur }
    val deltaTotal = source.portfolios.sumOf { it.totals?.unrealizedPnlEur ?: 0.0 }

    return base(BtInsight.VALUE_VS_BASIS, window).copy(
        paired = sorted,
        headline = BtInsightValue.Money(deltaTotal, signed = true),
        facts = listOf(
            BtInsightFact(R.string.bt_insight_fact_market_value, BtInsightValue.Money(valueTotal)),
            BtInsightFact(R.string.bt_insight_fact_cost_basis, BtInsightValue.Money(basisTotal)),
            BtInsightFact(
                R.string.bt_insight_fact_position_count,
                BtInsightValue.Count(sorted.size, R.plurals.bt_insight_positions),
            ),
        ),
        caption = BtInsightCaption(
            templateRes = R.string.bt_insight_caption_value_vs_basis,
            value = BtInsightValue.Money(deltaTotal, signed = true),
        ),
        total = valueTotal,
        coverage = coverage,
    )
}

// ── 9 · Realisiert & Gebühren ───────────────────────────────────────────────

/**
 * Realized results and recorded fees inside the window.
 *
 * Realized P/L per sell is computed by the server (the tax engine reports it per
 * disposal, with its date). Selecting the sells whose date falls in the window
 * and summing those server figures is aggregation, not calculation — the app
 * never re-derives a disposal's result from prices.
 *
 * Fees come from the cash ledger's `fee` movements, which are recorded amounts.
 * They stay a separate bookkeeping total and are never netted into the realized
 * figure, because a fee is a cost, not a disposal result.
 */
private fun buildRealizedFees(
    config: BtInsightConfig,
    source: BtInsightSource,
    window: BtInsightWindow,
): BtInsightSnapshot {
    val rows = source.taxPositions
        .map { position ->
            val inWindow = position.sells.filter { window.contains(it.epochDay) }
            position.symbol to inWindow
        }
        .filter { it.second.isNotEmpty() }
        .map { (symbol, sells) ->
            VizDatum(
                key = "rz:$symbol",
                label = symbol,
                value = sells.sumOf { it.amountEur },
                hiddenCount = sells.size,
            )
        }

    val fees = source.cashMovements
        .filter { it.kind == "fee" }
        .filter { window.contains(epochDayUtc(it.executedAtMs)) }
        .sumOf { abs(it.amountEur) }

    if (rows.isEmpty() && fees <= 0.0) {
        return empty(BtInsight.REALIZED_FEES, window, BtInsightEmptyReason.NOTHING_REALIZED)
    }

    val net = rows.sumOf { it.value }
    val gains = rows.filter { it.value > 0.0 }
    val losses = rows.filter { it.value < 0.0 }
    val sorted = rows.sortedByDescending { it.value }

    return base(BtInsight.REALIZED_FEES, window).copy(
        datums = sorted,
        headline = BtInsightValue.Money(net, signed = true),
        facts = buildList {
            add(
                BtInsightFact(
                    R.string.bt_insight_fact_realized_gains,
                    BtInsightValue.Money(gains.sumOf { it.value }, signed = true),
                ),
            )
            add(
                BtInsightFact(
                    R.string.bt_insight_fact_realized_losses,
                    BtInsightValue.Money(losses.sumOf { it.value }, signed = true),
                ),
            )
            if (config.showFees) {
                add(BtInsightFact(R.string.bt_insight_fact_fees, BtInsightValue.Money(fees)))
            }
            add(
                BtInsightFact(
                    R.string.bt_insight_fact_disposal_count,
                    BtInsightValue.Count(
                        rows.sumOf { it.hiddenCount },
                        R.plurals.bt_insight_disposals,
                    ),
                ),
            )
        },
        caption = BtInsightCaption(
            templateRes = R.string.bt_insight_caption_realized,
            value = BtInsightValue.Money(net, signed = true),
        ),
        total = net,
    )
}

// ── 10 · Dividenden ─────────────────────────────────────────────────────────

private fun buildDividends(
    config: BtInsightConfig,
    source: BtInsightSource,
    window: BtInsightWindow,
): BtInsightSnapshot {
    val byPayer = source.taxPositions.mapNotNull { position ->
        val inWindow = position.dividends.filter { window.contains(it.epochDay) }
        if (inWindow.isEmpty()) {
            null
        } else {
            VizDatum(
                key = "dv:${position.symbol}",
                label = position.symbol,
                value = inWindow.sumOf { it.amountEur },
                hiddenCount = inWindow.size,
            )
        }
    }.filter { it.value > 0.0 }

    if (byPayer.isEmpty()) return empty(BtInsight.DIVIDENDS, window, BtInsightEmptyReason.NO_DIVIDENDS)

    val grouped = if (config.grouping == BtInsightGrouping.MONTH) {
        source.taxPositions
            .flatMap { it.dividends }
            .filter { window.contains(it.epochDay) }
            .groupBy { YearMonth.from(LocalDate.ofEpochDay(it.epochDay)).toString() }
            .map { (month, events) ->
                VizDatum(
                    key = "dvm:$month",
                    label = bucketLabel(month, quarterly = false, monthLabel = source.monthLabel),
                    value = events.sumOf { it.amountEur },
                    hiddenCount = events.size,
                )
            }
            .sortedBy { it.key }
    } else {
        byPayer.sortedByDescending { it.value }
    }

    val items = withStableColorIndices(grouped)
    val total = byPayer.sumOf { it.value }
    val count = byPayer.sumOf { it.hiddenCount }
    val top = byPayer.maxByOrNull { it.value }

    return base(BtInsight.DIVIDENDS, window).copy(
        datums = items,
        headline = BtInsightValue.Money(total),
        facts = listOfNotNull(
            top?.let { BtInsightFact(R.string.bt_insight_fact_top_payer, BtInsightValue.Text(it.label)) },
            top?.let { BtInsightFact(R.string.bt_insight_fact_top_payer_amount, BtInsightValue.Money(it.value)) },
            BtInsightFact(
                R.string.bt_insight_fact_payment_count,
                BtInsightValue.Count(count, R.plurals.bt_insight_payments),
            ),
        ),
        caption = top?.let {
            BtInsightCaption(
                templateRes = R.string.bt_insight_caption_share_of_total,
                name = it.label,
                value = BtInsightValue.Percent(share(it.value, total)),
            )
        },
        total = total,
    )
}

// ── 11 · Steuerübersicht ────────────────────────────────────────────────────

/**
 * The server's tax totals for one calendar year.
 *
 * Absence is not zero: a portfolio with no tax record for the year is not a
 * portfolio with a nil result, so a missing summary renders the designed empty
 * state rather than four 0,00 € rows.
 */
private fun buildTaxSummary(
    source: BtInsightSource,
    window: BtInsightWindow,
): BtInsightSnapshot {
    val years = source.taxYear.filter { it.year == window.year }
    if (years.isEmpty()) return empty(BtInsight.TAX_SUMMARY, window, BtInsightEmptyReason.NO_TAX_DATA)

    val realized = years.sumOf { it.realizedPnlEur }
    val dividends = years.sumOf { it.dividendsGrossEur }
    val withheld = years.sumOf { it.taxWithheldEur }
    val refunded = years.sumOf { it.taxRefundedEur }
    val net = years.sumOf { it.taxNetEur }

    val datums = listOf(
        VizDatum("tax:realized", "", realized),
        VizDatum("tax:dividends", "", dividends),
        // Withheld tax leaves the account, so it is drawn below the zero axis;
        // the sign is the server's meaning, not a presentation flourish.
        VizDatum("tax:withheld", "", -withheld),
        VizDatum("tax:refunded", "", refunded),
    ).filter { it.value != 0.0 }

    if (datums.isEmpty()) return empty(BtInsight.TAX_SUMMARY, window, BtInsightEmptyReason.NO_TAX_DATA)

    return base(BtInsight.TAX_SUMMARY, window).copy(
        datums = datums,
        headline = BtInsightValue.Money(realized, signed = true),
        facts = listOf(
            BtInsightFact(R.string.bt_insight_fact_realized_result, BtInsightValue.Money(realized, signed = true)),
            BtInsightFact(R.string.bt_insight_fact_dividends_gross, BtInsightValue.Money(dividends)),
            BtInsightFact(R.string.bt_insight_fact_tax_withheld, BtInsightValue.Money(withheld)),
            BtInsightFact(R.string.bt_insight_fact_tax_net, BtInsightValue.Money(net, signed = true)),
        ),
        caption = BtInsightCaption(
            templateRes = R.string.bt_insight_caption_tax,
            value = BtInsightValue.Money(net, signed = true),
        ),
        total = realized,
    )
}

/** The four tax rows' localized labels, resolved by the surface. */
val BT_INSIGHT_TAX_ROW_LABELS: Map<String, Int> = mapOf(
    "tax:realized" to R.string.bt_insight_fact_realized_result,
    "tax:dividends" to R.string.bt_insight_fact_dividends_gross,
    "tax:withheld" to R.string.bt_insight_fact_tax_withheld,
    "tax:refunded" to R.string.bt_insight_fact_tax_refunded,
)

// ── 12 · Liquide Mittel ─────────────────────────────────────────────────────

private fun buildLiquidFunds(
    config: BtInsightConfig,
    source: BtInsightSource,
    window: BtInsightWindow,
): BtInsightSnapshot {
    val includeBrokerCash = config.showCash ?: true
    val rows = source.cashSources
        .filter { it.archivedAt == null }
        .filter { includeBrokerCash || it.kind != "cash" }
        .filter { it.balanceEur > 0.0 }
        .map {
            VizDatum(
                key = "src:${it.id}",
                label = it.name.ifBlank { source.unnamedLabel },
                value = it.balanceEur,
                hiddenCount = 1,
            )
        }
    if (rows.isEmpty()) return empty(BtInsight.LIQUID_FUNDS, window, BtInsightEmptyReason.NO_LIQUID_FUNDS)

    val items = withStableColorIndices(rows.sortedByDescending { it.value })
    val total = items.sumOf { it.value }
    val largest = items.firstOrNull()

    return base(BtInsight.LIQUID_FUNDS, window).copy(
        datums = items,
        headline = BtInsightValue.Money(total),
        facts = listOfNotNull(
            largest?.let { BtInsightFact(R.string.bt_insight_fact_top_source, BtInsightValue.Text(it.label)) },
            largest?.let {
                BtInsightFact(
                    R.string.bt_insight_fact_top_source_share,
                    BtInsightValue.Percent(share(it.value, total)),
                )
            },
            BtInsightFact(
                R.string.bt_insight_fact_source_count,
                BtInsightValue.Count(items.size, R.plurals.bt_insight_sources),
            ),
        ),
        caption = largest?.let {
            BtInsightCaption(
                templateRes = R.string.bt_insight_caption_share_of_total,
                name = it.label,
                value = BtInsightValue.Percent(share(it.value, total)),
            )
        },
        total = total,
    )
}

// ── Shared helpers ──────────────────────────────────────────────────────────

/**
 * A part as a percentage of a whole.
 *
 * Returns 0 rather than NaN for a zero denominator: a share of nothing is not a
 * number, and a chart that printed "NaN %" would be worse than one that printed
 * nothing. Callers that need to distinguish "0 %" from "no denominator" check
 * the total themselves.
 */
private fun share(part: Double, whole: Double): Double =
    if (whole == 0.0 || !whole.isFinite()) 0.0 else part / whole * 100.0

private fun epochDay(epochMs: Long, zone: ZoneId): Long =
    java.time.Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toEpochDay()

private fun epochDayUtc(epochMs: Long): Long = Math.floorDiv(epochMs, 86_400_000L)
