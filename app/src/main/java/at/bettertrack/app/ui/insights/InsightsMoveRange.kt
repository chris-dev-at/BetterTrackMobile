package at.bettertrack.app.ui.insights

import at.bettertrack.app.data.repo.AssetRange
import at.bettertrack.app.data.repo.PricePoint
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
 * | Range        | Source                                   | Unit | Aggregate |
 * |--------------|------------------------------------------|------|-----------|
 * | [DAY]        | server `dayChangeEur` / `dayChangePct`   | €    | yes       |
 * | [WEEK]       | `GET /assets/{id}/history?range=1W`      | %    | no        |
 * | [MONTH]      | `GET /assets/{id}/history?range=1M`      | %    | no        |
 * | [YEAR]       | `GET /assets/{id}/history?range=1Y`      | %    | no        |
 * | [SINCE_BUY]  | server `unrealizedPnlEur`                | €    | yes       |
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
 * The closes arrive in the asset's **trading currency** and are never converted:
 * the wire response carries a `currency` alongside `points[{time, close}]` and no
 * FX rate, and `AssetHistoryResponse` does not even model that field today. So
 * for an asset that does not trade in EUR this is the instrument's own move, not
 * the euro move of the position. Since a first-to-last ratio is taken inside one
 * currency, the percentage itself is unaffected — but it is emphatically not a
 * euro result, and the card says so rather than implying one; see
 * `bt_insight_movers_price_note`.
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

    /** True when this range needs a per-asset price series fetched for it. */
    val needsPriceHistory: Boolean get() = assetRange != null

    /**
     * The `GET /assets/{id}/history` range this maps onto, or `null` when the
     * range is answered from a server-computed holding field instead.
     *
     * Only wire values the asset-history endpoint actually accepts appear here
     * (`1D|1W|1M|3M|6M|1Y|5Y|MAX`), and only ones this card has a question for.
     */
    val assetRange: AssetRange?
        get() = when (this) {
            DAY, SINCE_BUY -> null
            WEEK -> AssetRange.W1
            MONTH -> AssetRange.M1
            YEAR -> AssetRange.Y1
        }
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
 * while the same close is a meaningful share of a five-day week. Together with
 * [BT_INSIGHT_MOVE_FETCH_CAP] this is the cost-control story for the per-asset
 * fan-out this card uses today — one warm cache turns N calls into zero for the
 * rest of the session.
 */
fun insightMoveCacheTtlMs(range: BtInsightMoveRange): Long = when (range) {
    BtInsightMoveRange.WEEK -> 10 * MINUTE_MS
    BtInsightMoveRange.MONTH -> 30 * MINUTE_MS
    BtInsightMoveRange.YEAR -> 6 * 60 * MINUTE_MS
    // Neither fetches anything; a TTL would describe a call that never happens.
    BtInsightMoveRange.DAY, BtInsightMoveRange.SINCE_BUY -> 0L
}

/**
 * The hard cap on assets fetched for one range.
 *
 * This card fetches one `GET /assets/{id}/history` per position, so `Umfang: Alle`
 * on a thirty-position account would otherwise mean thirty round trips every time
 * it resolves. The cap is applied to the positions sorted by market value
 * descending — the ones that actually moved the portfolio — and everything past
 * it is *listed as unavailable*, so the card never pretends the tail is flat.
 *
 * ## The batch endpoint this does not yet use
 *
 * A per-asset fan-out is not the only option the platform offers, and the cap
 * exists because of the shape this card fetches with rather than because the
 * server forces it. Verified in the platform contracts:
 *
 *  - `GET /portfolios/{id}/history?range=…&overlay=true` returns EVERY held
 *    asset's own daily close series in ONE call
 *    (`packages/contracts/src/portfolio.ts` `overlay`/`assets[]`). The app's
 *    Retrofit signature sends only `range`, so the capability is unused.
 *  - `GET /assets/sparklines?ids=…` returns 1-month daily series for up to 100
 *    ids in one call (`packages/contracts/src/assets.ts`). Also unused.
 *
 * Adopting either would delete this cap and the whole *unavailable tail* it
 * produces, but it means new DTOs and an implementation in each of the four
 * `MarketDataSource`s — a deliberate change, not a detail of this card.
 */
const val BT_INSIGHT_MOVE_FETCH_CAP: Int = 12

/** Concurrent history calls. Bounded so a wide account cannot flood the API. */
const val BT_INSIGHT_MOVE_FETCH_PARALLELISM: Int = 4

/**
 * The number of assets this card will fetch history for, given its `Umfang`.
 *
 * The card already has a Top-N control, so a user who set `Top 3` has said what
 * they want to see and there is no reason to pay for twelve round trips to draw
 * three bars. An explicit small scope therefore *lowers* the fetch count;
 * `Automatisch` and `Alle` take the hard cap, because neither states a number
 * and an unbounded fan-out over a wide account is the failure this cap exists
 * to prevent.
 *
 * Never raises above [BT_INSIGHT_MOVE_FETCH_CAP]: `Alle` is a display wish, not
 * a licence to issue forty requests.
 */
fun insightMoveFetchCap(scope: BtVizScope?): Int {
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
 * stable between renders, and the list is cut at [BT_INSIGHT_MOVE_FETCH_CAP].
 */
fun insightMoveFetchTargets(
    valueByAssetId: Map<String, Double>,
    cap: Int = BT_INSIGHT_MOVE_FETCH_CAP,
): List<String> = valueByAssetId.entries
    .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
    .take(cap.coerceAtLeast(0))
    .map { it.key }

private const val MINUTE_MS = 60_000L
