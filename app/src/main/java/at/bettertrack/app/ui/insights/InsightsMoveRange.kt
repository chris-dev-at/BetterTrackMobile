package at.bettertrack.app.ui.insights

import at.bettertrack.app.data.repo.AssetRange
import at.bettertrack.app.data.repo.HistoryRange
import at.bettertrack.app.data.repo.PricePoint
import at.bettertrack.app.data.repo.assetTwin
import at.bettertrack.app.ui.charts.viz.BtVizScope
import at.bettertrack.app.ui.charts.viz.VizDatum
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The **Bewegungen** card's range vocabulary, and the honesty rails that decide
 * what each range is allowed to print.
 *
 * ## Why this is not [BtInsightPeriodKind]
 *
 * The catalog's period vocabulary (1 M / 6 M / 1 J / MAX / custom) describes a
 * *frame* every card shares, and [BtInsightTiming.SESSION] deliberately refuses
 * it: "Tagesbewegungen cannot become an arbitrary range" is still true, because
 * the server's `dayChangeEur` exists for exactly one session and for no other
 * span. That rail is intact — [insightPeriodKinds] still returns nothing for
 * this insight and [InsightsCatalogTest] still guards it.
 *
 * What the owner asked for (2026-08-18, *"kann man das mit der tagesbewegung
 * auch für mehr zeitspannen machen"*) is a different question with a different
 * data source, so it gets its own, deliberately short vocabulary and its own
 * rules about what may be shown:
 *
 * | Range        | Source                                            | Unit | Aggregate |
 * |--------------|---------------------------------------------------|------|-----------|
 * | [DAY]        | server `dayChangeEur` / `dayChangePct`            | €    | yes       |
 * | [WEEK]       | `GET /portfolios/{id}/history?range=1W&overlay=true` | %  | no        |
 * | [MONTH]      | `GET /portfolios/{id}/history?range=1M&overlay=true` | %  | no        |
 * | [YEAR]       | `GET /portfolios/{id}/history?range=1Y&overlay=true` | %  | no        |
 * | [SINCE_BUY]  | server `unrealizedPnlEur`                         | €    | yes       |
 *
 * The three price rows are ONE request each — the overlay carries every held
 * asset's own close series — not one request per position as they shipped.
 *
 * ## The two rails that decide that table
 *
 *  1. **A € contribution per position over a period is real accounting and the
 *     server is the only calculator.** It needs the holding's quantity *through*
 *     the period — every buy and sell inside it — and no endpoint states it. So
 *     [WEEK], [MONTH] and [YEAR] print a **price movement in percent** and say
 *     so; they never print a euro figure and never imply one.
 *  2. **"All time" is not a MAX price series.** The MAX close series starts when
 *     the instrument's data starts, which for most positions predates the user
 *     owning it by years — labelling that "your all-time move" would be a claim
 *     about a position the user did not hold. The honest all-time answer is the
 *     server's per-holding `unrealizedPnlEur`, which is a real result since the
 *     recorded cost basis, so [SINCE_BUY] is that figure and is named after it.
 *
 * ## What the percentage is, exactly
 *
 * [insightMovePercent] is the last close over the first close of a series the
 * SERVER returned for a range the SERVER chose the interval for. That is the same
 * class of presentation-level difference the shipped hero already prints
 * (`rangeDeltaEur` = last minus first of a server balance series) and the same
 * class as the Tief/Hoch facts on the development card. Nothing is re-based,
 * interpolated or converted here.
 *
 * The closes arrive in the asset's **trading currency** and are never converted.
 * The overlay states that outright — each row carries its own `currency` beside
 * its closes and no FX rate — exactly as the per-asset endpoint did. So for an
 * asset that does not trade in EUR this is the instrument's own move, not the
 * euro move of the position. Since a first-to-last ratio is taken inside one
 * currency, the percentage itself is unaffected — but it is emphatically not a
 * euro result, and the card says so rather than implying one; see
 * `bt_insight_movers_price_note`. Two currencies are never mixed and two series
 * are never combined: one row, one series, one ratio.
 *
 * The overlay serves every window on the **daily** grid, where the per-asset
 * endpoint served 1W/1M as intraday candles. Both are the server's own closes,
 * and a calendar-window card is if anything better described by close-to-close
 * than by "the price at some intraday minute a month ago" — but they are not the
 * same series, so the same span can print a slightly different percentage than
 * it did before the batch (measured on device when this landed).
 */
enum class BtInsightMoveRange {
    /** Today's session — the only range with a server-computed euro figure. */
    DAY,
    WEEK,
    MONTH,
    YEAR,

    /** Since the recorded cost basis. Server `unrealizedPnlEur`, in euro. */
    SINCE_BUY,
    ;

    /**
     * The **euro** ranges. Both read a server-computed euro field per holding;
     * the rest cannot and must not.
     */
    val isMoney: Boolean get() = this == DAY || this == SINCE_BUY

    /** True when this range needs a price series fetched for it. */
    val needsPriceHistory: Boolean get() = historyRange != null

    /**
     * The window this range asks the server for, in the PORTFOLIO history
     * vocabulary — or `null` when the range is answered from a server-computed
     * holding field instead.
     *
     * Why the portfolio vocabulary and not the asset one: the series arrive
     * through `GET /portfolios/{id}/history?overlay=true`, one call for every
     * held asset, and that endpoint enumerates `1D|1W|1M|6M|1Y|5Y|MAX`
     * (deployed `openapi.json`, re-read 2026-08-20 — the older app note claiming
     * `1M|6M|1Y|MAX` was stale, and 1W in particular IS served). All three
     * windows this card asks for are in that set, so no range has to fall back
     * to a fan-out for lack of vocabulary.
     */
    val historyRange: HistoryRange?
        get() = when (this) {
            DAY, SINCE_BUY -> null
            WEEK -> HistoryRange.W1
            MONTH -> HistoryRange.M1
            YEAR -> HistoryRange.Y1
        }

    /**
     * The same window in the `GET /assets/{id}/history` vocabulary — what a data
     * source with no batch of its own fans out with.
     *
     * Derived from [historyRange] rather than restated, so the two can never
     * drift into naming different spans.
     */
    val assetRange: AssetRange? get() = historyRange?.assetTwin
}

/** The card's default when nothing was ever configured: today, as it shipped. */
val BT_INSIGHT_MOVE_RANGE_DEFAULT: BtInsightMoveRange = BtInsightMoveRange.DAY

/**
 * The marks a PERCENT chart may draw, given the density resolver's [limit].
 *
 * ## Why a percent set may not use the shared Top-N reduction
 *
 * `reduceToTopN` folds everything past the limit into one *Andere* mark whose
 * value is the **sum** of what it hides. For euro contributions that is exactly
 * right — three positions that added 2 €, 3 € and 4 € did add 9 € to the
 * portfolio, and the bucket states a real quantity.
 *
 * For price movements it is not right and not close to right. Two positions that
 * fell 4,69 % and 4,39 % did not fall 9,08 %; that number is a sum over unrelated
 * denominators and no market ever printed it. Caught on the owner's device
 * 2026-08-19, where the compact rendition drew exactly that mark.
 *
 * So a percent set is **truncated at the same rank the reducer would have kept**
 * and simply stops. Nothing is aggregated, nothing is invented, and the full
 * rendition still lists every row — the compact card is a summary by design.
 *
 * A [limit] of zero or less means "no limit" in the resolver's vocabulary and is
 * passed through untouched.
 */
fun insightMoveChartDatums(datums: List<VizDatum>, limit: Int): List<VizDatum> {
    if (limit <= 0 || datums.size <= limit) return datums
    return datums
        .sortedWith(compareByDescending<VizDatum> { it.value }.thenBy { it.key })
        .take(limit)
}

/**
 * The dates the card prints as its subject line for [range], ending at [asOf].
 *
 * [BtInsightMoveRange.DAY] and [BtInsightMoveRange.SINCE_BUY] both collapse to a
 * stichtag: a session is one day, and "since purchase" has no single start date
 * across a portfolio of positions bought on different days. Printing a range for
 * either would be a claim the number does not make.
 */
fun insightMoveWindow(range: BtInsightMoveRange, asOfEpochDay: Long): LongRange {
    val end = LocalDate.ofEpochDay(asOfEpochDay)
    val start = when (range) {
        BtInsightMoveRange.DAY, BtInsightMoveRange.SINCE_BUY -> end
        BtInsightMoveRange.WEEK -> end.minusWeeks(1)
        BtInsightMoveRange.MONTH -> end.minusMonths(1)
        BtInsightMoveRange.YEAR -> end.minusYears(1)
    }
    return start.toEpochDay()..asOfEpochDay
}

/**
 * The percent move across a server price series — `(last − first) / first · 100`.
 *
 * Returns `null` rather than `0` when the series cannot carry a move: fewer than
 * two points, a non-finite close, or a first close of zero or less. A row with a
 * null move is reported as *unavailable*, never drawn at 0 %, because 0 % is an
 * answer and "we could not fetch it" is not.
 */
fun insightMovePercent(points: List<PricePoint>): Double? {
    if (points.size < 2) return null
    val first = points.first().close
    val last = points.last().close
    if (!first.isFinite() || !last.isFinite() || first <= 0.0) return null
    val pct = (last - first) / first * 100.0
    return if (pct.isFinite()) pct else null
}

/**
 * The points of [points] that fall inside [range]'s window ending at [asOfEpochDay].
 *
 * ## Why this clamp is not redundant
 *
 * It looks like it should be: the server was asked for `1W` and the server
 * chooses the lookback. Two things make that trust misplaced, and the second is
 * a real bug this prevents:
 *
 *  1. The server's lookbacks are *approximate* to the label — `1M` is a flat 31
 *     days, `1Y` a flat 366 — so clamping to the calendar month or year the card
 *     actually names makes the number match the words above it.
 *  2. **The Drive-autonomous data source ignores `range` completely.**
 *     `NoLivePricesMarketDataSource.assetHistory` returns the entire local price
 *     cache whatever it was asked for. Without this clamp, a card labelled
 *     `1 Woche` in that mode would print the move since the cache began — which
 *     for a long-held position is the kind of `+4.000 %` that tells you the
 *     window was never mapped at all.
 *
 * Order is preserved; the caller's series is already ascending by time.
 */
fun insightMoveSeriesWindow(
    points: List<PricePoint>,
    range: BtInsightMoveRange,
    asOfEpochDay: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<PricePoint> {
    val span = insightMoveWindow(range, asOfEpochDay)
    return points.filter { point ->
        val day = Instant.ofEpochMilli(point.timeMs).atZone(zone).toLocalDate().toEpochDay()
        day in span
    }
}

/**
 * [insightMovePercent] over exactly the window [range] names.
 *
 * The one entry point the fetch layer should use. Clamping first and reducing
 * second is what keeps "1 Jahr" and the number under it describing the same
 * span, in every data-source mode.
 */
fun insightMovePercentIn(
    points: List<PricePoint>,
    range: BtInsightMoveRange,
    asOfEpochDay: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): Double? = insightMovePercent(insightMoveSeriesWindow(points, range, asOfEpochDay, zone))

/**
 * How long a fetched series may be reused before the card refetches it.
 *
 * Longer ranges get longer lives for the obvious reason: one more close at the
 * end of a 250-point year moves the first-to-last percentage by almost nothing,
 * while the same close is a meaningful share of a five-day week.
 *
 * Still worth having now that a span costs ONE overlay call rather than one call
 * per position: the card re-resolves on every scope change, every pull-to-refresh
 * and every return to the page, and a warm cache turns each of those into zero
 * requests instead of one. It is per asset because that is the unit the cache
 * holds; a span whose assets are all warm issues nothing.
 */
fun insightMoveCacheTtlMs(range: BtInsightMoveRange): Long = when (range) {
    BtInsightMoveRange.WEEK -> 10 * MINUTE_MS
    BtInsightMoveRange.MONTH -> 30 * MINUTE_MS
    BtInsightMoveRange.YEAR -> 6 * 60 * MINUTE_MS
    // Neither fetches anything; a TTL would describe a call that never happens.
    BtInsightMoveRange.DAY, BtInsightMoveRange.SINCE_BUY -> 0L
}

/**
 * The hard cap on assets fetched for one range **when the price source has no
 * batch** and every position therefore costs its own round trip.
 *
 * The cap was never a display decision: it exists because one
 * `GET /assets/{id}/history` per position turns `Umfang: Alle` on a
 * thirty-position account into thirty round trips every time the card resolves.
 * It is applied to the positions sorted by market value descending — the ones
 * that actually moved the portfolio — and everything past it is *listed as
 * unavailable*, so the card never pretends the tail is flat.
 *
 * ## Why it no longer applies on the server source
 *
 * `GET /portfolios/{id}/history?range=…&overlay=true` returns EVERY held asset's
 * daily close series in ONE call (deployed `openapi.json`
 * `PortfolioHistoryResponse.assets[]`; platform contract
 * `packages/contracts/src/portfolio.ts`). When the source answers
 * [at.bettertrack.app.data.storage.MarketDataSource.batchesAssetHistories] with
 * true, a thirty-position account costs the same one request as a
 * three-position one, so capping would buy nothing and cost the user real rows —
 * see [insightMoveFetchCap]'s `batched` argument.
 *
 * The other batch the platform offers, `GET /assets/sparklines?ids=…` (1-month
 * daily series, ≤100 ids), stays unused: it serves exactly one span, and this
 * card needs three.
 */
const val BT_INSIGHT_MOVE_FETCH_CAP: Int = 12

/** Concurrent history calls on the fan-out path. Bounded so a wide account cannot flood the API. */
const val BT_INSIGHT_MOVE_FETCH_PARALLELISM: Int = 4

/**
 * The number of assets this card will fetch history for, given its `Umfang` and
 * whether the price source [batched] them into one call.
 *
 * **A batching source is uncapped.** The cap paid for round trips, and there are
 * no per-asset round trips left to pay for: the overlay hands back the whole
 * portfolio whether the caller wanted twelve rows or forty. Keeping it would mean
 * printing "nicht verfügbar" beside positions whose series is already in the
 * response — an invented gap.
 *
 * On a fan-out source the old rule stands, and it honours the card's own Top-N:
 * a user who set `Top 3` has said what they want to see, so there is no reason to
 * pay for twelve round trips to draw three bars. `Automatisch` and `Alle` take
 * the hard cap, because neither states a count and an unbounded fan-out over a
 * wide account is the failure the cap exists to prevent. It never raises above
 * [BT_INSIGHT_MOVE_FETCH_CAP]: `Alle` is a display wish, not a licence to issue
 * forty requests.
 */
fun insightMoveFetchCap(scope: BtVizScope?, batched: Boolean = false): Int {
    if (batched) return Int.MAX_VALUE
    val requested = scope?.limit ?: -1
    // AUTO is -1 and ALL is 0; neither names a count this can honour cheaply.
    if (requested <= 0) return BT_INSIGHT_MOVE_FETCH_CAP
    return minOf(requested, BT_INSIGHT_MOVE_FETCH_CAP)
}

/**
 * Which assets a [range] should fetch for, given every in-scope position keyed by
 * asset id with its market value.
 *
 * Pure and separately tested because it is the whole cost story: the order is by
 * market value descending (largest positions first — a 40 % move on a €30 stub
 * is not what "moved the portfolio" means), ties break on the id so the choice is
 * stable between renders, and the list is cut at [cap] — which
 * [insightMoveFetchCap] leaves effectively unlimited on a batching source.
 */
fun insightMoveFetchTargets(
    valueByAssetId: Map<String, Double>,
    cap: Int = BT_INSIGHT_MOVE_FETCH_CAP,
): List<String> = valueByAssetId.entries
    .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
    .take(cap.coerceAtLeast(0))
    .map { it.key }

private const val MINUTE_MS = 60_000L
