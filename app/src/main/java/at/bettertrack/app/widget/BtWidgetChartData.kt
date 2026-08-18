package at.bettertrack.app.widget

import at.bettertrack.app.data.db.HoldingEntity
import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.ui.home.homeMovers
import kotlin.math.abs

/**
 * The chart widgets' DATA half: grouping, folding, fractions and scaling, as
 * pure functions — no Android, no Canvas, fully unit-tested.
 *
 * The raster half lives in `BtWidgetChartBitmaps.kt` and draws exactly what
 * these functions hand it. The split is the point: a pie slice being 32 % is a
 * claim about the user's money and belongs on the JVM under test; a pie slice
 * being anti-aliased is not.
 *
 * The same money rule as [BtWidgetModels]: everything here is a SUM of
 * already-server-computed EUR aggregates or a RATIO of two such sums — the
 * display mapping the app's own screens already perform. No price, no
 * conversion, no derived performance.
 */

// ── Slices (spending donut) ──────────────────────────────────────────────────

/** [BtWidgetSlice.colorIndex] for the folded tail — `chartRest`, not an identity hue. */
const val BT_SLICE_REST: Int = -1

/** [BtWidgetSlice.colorIndex] for a cash slice — `chartCash`. Kept for the palette contract. */
const val BT_SLICE_CASH: Int = -2

/**
 * One donut slice. [label] is a RAW key for the identity slices (a tag name) —
 * localisation happens at render, keeping this file pure. [colorIndex] is a
 * palette slot (0-based, by descending weight) or one of the two semantic slots
 * above.
 */
data class BtWidgetSlice(
    val label: String,
    val value: Double,
    val colorIndex: Int,
)

/** Slice weight as a fraction of the whole list; 0 when the list sums to nothing. */
fun btWidgetSliceFraction(slice: BtWidgetSlice, slices: List<BtWidgetSlice>): Double {
    val total = slices.sumOf { it.value }
    return if (total <= 0.0) 0.0 else slice.value / total
}

/**
 * How many identity slots a WIDGET donut uses before folding into "Other".
 *
 * Deliberately fewer than the theme's ten: the app's donut has a full-width
 * legend to name ten slices, a 2x2 widget does not, and an unnamed slice is a
 * coloured guess. Five identity slices + Other is six hues, which is the most a
 * glanceable ring stays readable at.
 */
const val BT_WIDGET_SLICE_SLOTS: Int = 5

/**
 * The spending donut's slices: per-tag OUTFLOW magnitudes for the month, ranked,
 * tail folded. Inflows are deliberately absent — "where did the money go" is the
 * widget's one question, and mixing directions into one ring would answer none.
 *
 * The caveat the summary DTO shouts is inherited here and must be inherited by
 * every renderer: a movement carrying two tags counts fully in both rows, so
 * these slices are a BREAKDOWN whose fractions can legitimately exceed the
 * month's true outflow total. The donut shows relative weight, and the absolute
 * total shown next to it must come from `totalOutflow`, never from this sum.
 */
fun btWidgetSpendingSlices(
    tags: List<BtWidgetTagSpend>,
    maxSlots: Int = BT_WIDGET_SLICE_SLOTS,
): List<BtWidgetSlice> {
    val parts = tags
        .filter { it.outflow > 0.0 }
        .sortedByDescending { it.outflow }
    val top = parts.take(maxSlots)
    val rest = parts.drop(maxSlots).sumOf { it.outflow }
    return buildList {
        top.forEachIndexed { i, tag -> add(BtWidgetSlice(tag.name, tag.outflow, i)) }
        if (rest > 0.0) add(BtWidgetSlice("", rest, BT_SLICE_REST))
    }
}

// ── Allocation slices (reinstated for round 2, owner ruling) ─────────────────

/** How the allocation donut groups the book. */
enum class BtWidgetAllocationGroup {
    /** By asset TYPE — the Overview donut's own `byCategory` view. */
    CLASS,

    /** By PORTFOLIO — "which depot holds how much". */
    PORTFOLIO,

    /** By the asset's native CURRENCY (values stay the server's EUR sums). */
    CURRENCY,
}

fun btWidgetAllocationGroup(raw: String?): BtWidgetAllocationGroup =
    BtWidgetAllocationGroup.entries.firstOrNull { it.name == raw }
        ?: BtWidgetAllocationGroup.CLASS

/**
 * The allocation donut's slices: holdings grouped by [group], ranked by summed
 * server-computed `marketValueEur`, tail folded into [BT_SLICE_REST], cash
 * appended as [BT_SLICE_CASH] when included and positive.
 *
 * Labels are RAW keys ([BtWidgetAllocationGroup.CLASS] → the asset-type string,
 * localized at render; PORTFOLIO → the portfolio's name; CURRENCY → the code).
 * A holding whose value has not synced contributes nothing rather than a zero.
 * Same money rule as everything here: sums of already-EUR aggregates only.
 */
fun btWidgetAllocationSlices(
    holdings: List<HoldingEntity>,
    portfolios: List<PortfolioEntity>,
    group: BtWidgetAllocationGroup = BtWidgetAllocationGroup.CLASS,
    includeCash: Boolean = true,
    maxSlots: Int = BT_WIDGET_SLICE_SLOTS,
): List<BtWidgetSlice> {
    val names = portfolios.associate { it.id to it.name }
    val parts = holdings
        .groupBy { h ->
            when (group) {
                BtWidgetAllocationGroup.CLASS -> h.assetType
                BtWidgetAllocationGroup.PORTFOLIO -> names[h.portfolioId] ?: h.portfolioId
                BtWidgetAllocationGroup.CURRENCY -> h.assetCurrency
            }
        }
        .map { (key, rows) -> key to rows.sumOf { it.marketValueEur ?: 0.0 } }
        .filter { (_, value) -> value > 0.0 }
        .sortedByDescending { (_, value) -> value }

    val top = parts.take(maxSlots)
    val rest = parts.drop(maxSlots).sumOf { (_, value) -> value }
    val cash = if (includeCash) btWidgetAllocationCash(portfolios) else 0.0

    return buildList {
        top.forEachIndexed { i, (key, value) -> add(BtWidgetSlice(key, value, i)) }
        if (rest > 0.0) add(BtWidgetSlice("", rest, BT_SLICE_REST))
        if (cash > 0.0) add(BtWidgetSlice("", cash, BT_SLICE_CASH))
    }
}

/** The cash the allocation donut shows: the covered ACTIVE portfolios' server cash, summed. */
fun btWidgetAllocationCash(portfolios: List<PortfolioEntity>): Double =
    portfolios.filter { it.archivedAt == null }.mapNotNull { it.totals }.sumOf { it.cashEur }

// ── Series-derived figures (range delta, low/high) ───────────────────────────

/** A range delta read off a server series' two endpoints. */
data class BtWidgetSeriesDelta(val eur: Double, val pct: Double?)

/**
 * First→last movement of a cached server series — the performance card's
 * period pill ("+1.924,60 € · +5,25 %"). Two points of one server-computed
 * series; the percent is their ratio, guarded against a zero base. This is the
 * sanctioned round-2 class of derivation (same as the hi/lo below) — it invents
 * no price and no conversion.
 */
fun btWidgetSeriesDelta(values: List<Double>): BtWidgetSeriesDelta? {
    if (values.size < 2) return null
    val first = values.first()
    val last = values.last()
    val eur = last - first
    return BtWidgetSeriesDelta(
        eur = eur,
        pct = if (first != 0.0) eur / first * 100.0 else null,
    )
}

/** The range's Tief/Hoch footer: the series' own extremes. */
fun btWidgetSeriesLowHigh(values: List<Double>): Pair<Double, Double>? =
    if (values.isEmpty()) null else values.min() to values.max()

// ── The row family (holdings & watchlist, merged round 2) ────────────────────

/** Where the row family's rows come from. */
enum class BtWidgetRowSource { WATCHLIST, HOLDINGS }

fun btWidgetRowSource(raw: String?, default: BtWidgetRowSource): BtWidgetRowSource =
    BtWidgetRowSource.entries.firstOrNull { it.name == raw } ?: default

/** How the rows order themselves. */
enum class BtWidgetRowSort {
    /** Biggest absolute day move first — the movers reading. */
    MOVEMENT,

    /** Largest merged position value first (holdings only; watchlist falls back to MANUAL). */
    VALUE,

    /** The user's own order: board order for a watchlist, symbol order for holdings. */
    MANUAL,
}

fun btWidgetRowSort(raw: String?, default: BtWidgetRowSort): BtWidgetRowSort =
    BtWidgetRowSort.entries.firstOrNull { it.name == raw } ?: default

/** Which directions the card shows. */
enum class BtWidgetRowDirection {
    /** Everything, one ranked list. */
    ALL,

    /** Gainers only. */
    WINNERS,

    /** Losers only. */
    LOSERS,

    /** Winners left, losers right — the split reading (wide sizes). */
    SPLIT,
}

fun btWidgetRowDirection(raw: String?, default: BtWidgetRowDirection): BtWidgetRowDirection =
    BtWidgetRowDirection.entries.firstOrNull { it.name == raw } ?: default

/**
 * The HOLDINGS source's rows: one row per ASSET (the same merge rule
 * [homeMovers] applies — AAPL in two depots is one position), price and day
 * move from the server row, merged value as the VALUE sort's key. The merged
 * value is a sum of server-computed EUR aggregates — the one sanctioned sum.
 */
fun btWidgetHoldingRows(
    holdings: List<HoldingEntity>,
    limit: Int = BT_WIDGET_ROW_LIMIT,
): List<BtWidgetRow> =
    holdings.groupBy { it.assetId }.map { (assetId, rows) ->
        val first = rows.first()
        val values = rows.mapNotNull { it.marketValueEur }
        BtWidgetRow(
            assetId = assetId,
            symbol = first.assetSymbol,
            name = first.assetName,
            price = first.price,
            currency = first.assetCurrency,
            dayChangePct = rows.firstNotNullOfOrNull { it.dayChangePct },
            valueEur = values.takeIf { it.isNotEmpty() }?.sum(),
        )
    }.take(limit)

/**
 * Order rows for display. Unknowns always sink: a row with no move cannot rank
 * among the movers, a row with no value cannot rank among the largest.
 */
fun btWidgetSortRows(rows: List<BtWidgetRow>, sort: BtWidgetRowSort): List<BtWidgetRow> =
    when (sort) {
        BtWidgetRowSort.MOVEMENT ->
            rows.sortedWith(
                compareByDescending<BtWidgetRow> { it.dayChangePct != null }
                    .thenByDescending { abs(it.dayChangePct ?: 0.0) },
            )

        BtWidgetRowSort.VALUE ->
            rows.sortedWith(
                compareByDescending<BtWidgetRow> { it.valueEur != null }
                    .thenByDescending { it.valueEur ?: 0.0 },
            )

        BtWidgetRowSort.MANUAL -> rows
    }

/** Keep only one direction of rows; exact zero is neither a winner nor a loser. */
fun btWidgetFilterRows(rows: List<BtWidgetRow>, direction: BtWidgetRowDirection): List<BtWidgetRow> =
    when (direction) {
        BtWidgetRowDirection.WINNERS -> rows.filter { (it.dayChangePct ?: 0.0) > 0.0 }
        BtWidgetRowDirection.LOSERS -> rows.filter { (it.dayChangePct ?: 0.0) < 0.0 }
        else -> rows
    }

/** The split header's "4↑ · 4↓" and the movers footer's moved-count. Counts only. */
data class BtWidgetRowCounts(val up: Int, val down: Int, val moved: Int, val total: Int)

fun btWidgetRowCounts(rows: List<BtWidgetRow>): BtWidgetRowCounts {
    val up = rows.count { (it.dayChangePct ?: 0.0) > 0.0 }
    val down = rows.count { (it.dayChangePct ?: 0.0) < 0.0 }
    return BtWidgetRowCounts(up = up, down = down, moved = up + down, total = rows.size)
}

// ── Monthly flow bars (reinstated + zero-line restyle) ───────────────────────

/** One month's bar pair, heights normalised to the window's tallest bar. */
data class BtWidgetBarPair(
    /** `YYYY-MM`, as the wire sent it; the renderer formats a short label. */
    val month: String,
    val inflowFrac: Float,
    val outflowFrac: Float,
)

/**
 * Normalise the trend window for drawing: every bar as a fraction of the
 * window's single largest magnitude, so inflow and outflow share one scale and
 * the eye can compare across both directions. A window that is all zeros
 * renders all-zero bars (the widget shows its empty state instead).
 */
fun btWidgetCashflowBars(points: List<BtWidgetCashflowPoint>): List<BtWidgetBarPair> {
    val peak = points.maxOfOrNull { maxOf(it.inflow, it.outflow) } ?: 0.0
    return points.map { p ->
        BtWidgetBarPair(
            month = p.month,
            inflowFrac = if (peak <= 0.0) 0f else (p.inflow / peak).toFloat().coerceIn(0f, 1f),
            outflowFrac = if (peak <= 0.0) 0f else (p.outflow / peak).toFloat().coerceIn(0f, 1f),
        )
    }
}

/**
 * The flow window's headline net: Σ inflow − Σ outflow over the cached months —
 * a sum/difference of server-computed monthly EUR magnitudes, the same class as
 * every other sanctioned sum here. Null for an empty window.
 */
fun btWidgetFlowNet(points: List<BtWidgetCashflowPoint>): Double? =
    points.takeIf { it.isNotEmpty() }?.sumOf { it.inflow - it.outflow }

// ── Budget progress ring ─────────────────────────────────────────────────────

/**
 * The two arcs of a single budget's progress ring: (filled, remaining), both in
 * `0f..1f`, summing to 1 — exactly the fraction list [btWidgetDonutBitmap]
 * paints. An over-spent budget fills the WHOLE ring (1, 0): the ring cannot
 * draw past full, so like the bar ([btWidgetBudgetFraction]) it saturates and
 * the loss colour plus the unclamped percent label carry the truth. A
 * non-positive limit renders an empty ring rather than dividing by zero.
 */
fun btWidgetRingFractions(spent: Double, amount: Double): Pair<Float, Float> {
    val fill = btWidgetBudgetFraction(spent, amount)
    return fill to (1f - fill)
}

// ── Winners & losers (the Movers widget's wide layout) ───────────────────────

/** How many rows each side of the movers widget's wide layout can ever show. */
const val BT_WIDGET_WINLOSE_PER_SIDE: Int = 3

/** The day's two ends, each ranked from its extreme inward. */
data class BtWidgetWinnersLosers(
    val winners: List<BtWidgetMover>,
    val losers: List<BtWidgetMover>,
) {
    val isEmpty: Boolean get() = winners.isEmpty() && losers.isEmpty()
}

/**
 * Split the day's moves into gainers and losers — the SAME rows [homeMovers]
 * produces (merged per asset, no-move holdings dropped), partitioned by sign.
 *
 * An exact 0.0 % move lands on neither side: it is not a winner, it is not a
 * loser, and padding a side with it would invent a mover. A side can therefore
 * be shorter than [perSide] or empty, and the widget says so instead.
 */
fun btWidgetWinnersLosers(
    holdings: List<HoldingEntity>,
    perSide: Int = BT_WIDGET_WINLOSE_PER_SIDE,
): BtWidgetWinnersLosers {
    // homeMovers ranks by |%| — both extremes surface first, so taking from an
    // unlimited ranking and partitioning keeps one shared definition of "moved".
    val movers = homeMovers(holdings, limit = holdings.size).mapNotNull { h ->
        val pct = h.dayChangePct ?: return@mapNotNull null
        BtWidgetMover(h.assetId, h.assetSymbol, pct, h.dayChangeEur)
    }
    return BtWidgetWinnersLosers(
        winners = movers.filter { it.dayChangePct > 0.0 }
            .sortedByDescending { it.dayChangePct }
            .take(perSide),
        losers = movers.filter { it.dayChangePct < 0.0 }
            .sortedBy { it.dayChangePct }
            .take(perSide),
    )
}

// ── Sparkline / line-chart scaling ───────────────────────────────────────────

/**
 * Normalise a value series to `0f..1f` for drawing (0 = series min, 1 = max).
 *
 * Min/max scaling, not zero-based: a portfolio chart's job on a widget is the
 * SHAPE of the month, and a series that moved 2 % around a large base would be
 * a flat line at the top of a zero-based plot. This matches the app's own
 * portfolio chart, which also plots the range's min..max window.
 *
 * A flat or single-point series maps to 0.5 — a visible midline rather than a
 * degenerate divide-by-zero at the floor.
 */
fun btWidgetSparkNormalize(values: List<Double>): List<Float> {
    if (values.isEmpty()) return emptyList()
    val min = values.min()
    val max = values.max()
    val span = max - min
    if (span <= 0.0) return values.map { 0.5f }
    return values.map { ((it - min) / span).toFloat() }
}

/**
 * Thin a series to at most [maxPoints], keeping the first and last point.
 *
 * A MAX-range history can be thousands of points; a widget bitmap a few hundred
 * pixels wide cannot show them and the launcher should not be handed the work.
 * Uniform stride, endpoints pinned, so the chart still starts and ends on the
 * true values.
 */
fun btWidgetSparkThin(values: List<Double>, maxPoints: Int): List<Double> {
    if (maxPoints < 2 || values.size <= maxPoints) return values
    val last = values.size - 1
    return (0 until maxPoints).map { i ->
        values[(i.toLong() * last / (maxPoints - 1)).toInt()]
    }
}

/** How many points a widget line chart keeps — ~2px per point at 4-cell width. */
const val BT_WIDGET_SPARK_MAX_POINTS: Int = 120

// ── Size classes ─────────────────────────────────────────────────────────────

/**
 * The launcher-grid size classes every widget composes against (device QA
 * 2026-08-16, measured on the owner's One UI launcher via `dumpsys appwidget`).
 *
 * ## Why the old per-widget DpSize thresholds were wrong
 *
 * The Codex study's reference canvas assumes ~92dp launcher rows (2x1 =
 * 160x92dp). REAL grids are nothing like that: the owner's One UI reports one
 * row as **120dp** and two rows as **250dp** (Samsung adds the inter-cell
 * gutter to the widget), while Pixel-style grids sit near 92/190. Thresholds
 * tuned to the study's canvas (40dp strips, 72dp blocks, 90dp squares) put a
 * Samsung 2x2 (250dp!) into a layout composed for 90dp — the owner's
 * "squished" verdict was this bucketing error, not the padding.
 *
 * The splits below sit BETWEEN the buckets of both measured grids, so each
 * cell count lands in the same class on either launcher:
 *
 *  * columns — 2-cell ≈ 160–181dp, 4-cell ≈ 322–366dp ⇒ split at 250dp.
 *  * rows — 1 row ≈ 92–120dp, 2 rows ≈ 190–250dp, 3 rows ≈ 290–380dp,
 *    4 rows ≈ 390–510dp ⇒ splits at 165 / 300 / 430dp. Anything under 100dp
 *    is a STRIP — a cell denser than either measured grid produces, kept as
 *    the safety rendition for compact hosts.
 */
enum class BtWidgetSizeClass { STRIP, ROW1, ROW2, ROW3, ROW4 }

/** True at 4 launcher cells and up on every measured grid. */
fun btWidgetIsWide(widthDp: Float): Boolean = widthDp >= 250f

/** The row class for a reported height — see [BtWidgetSizeClass]. */
fun btWidgetRowClass(heightDp: Float): BtWidgetSizeClass = when {
    heightDp < 90f -> BtWidgetSizeClass.STRIP
    heightDp < 165f -> BtWidgetSizeClass.ROW1
    heightDp < 300f -> BtWidgetSizeClass.ROW2
    heightDp < 430f -> BtWidgetSizeClass.ROW3
    else -> BtWidgetSizeClass.ROW4
}

/**
 * The per-row height that makes [count] rows and their `count − 1` hairline
 * dividers exactly fill [availableDp] — the mockup's edge-to-edge list, with
 * no clipped half-row and no dead band under the last row. Clamped: a sparse
 * list must not inflate its rows into banners, and a miscounted budget must
 * not shrink them below the two-line minimum ([BT_ROW_HEIGHT_DP]).
 */
fun btWidgetRowFillHeight(availableDp: Float, count: Int): Float {
    if (count <= 0) return BT_ROW_HEIGHT_DP
    return ((availableDp - (count - 1)) / count)
        .coerceIn(BT_ROW_HEIGHT_DP, BT_ROW_HEIGHT_DP + 10f)
}

// ── Bitmap sizing ────────────────────────────────────────────────────────────

/**
 * The longest edge a widget chart bitmap may have, in px.
 *
 * Round-3 device review: the old 480px cap forced a 4x2 chart (≈330dp × 3x
 * density ≈ 990px on the owner's panel) to render at half resolution and be
 * upscaled by the host — the "blocky staircase" verdict. Charts now render at
 * the widget's REAL pixel size; this cap is only the safety rail against a
 * pathological size. Large bitmaps are safe to send: `Bitmap.writeToParcel`
 * moves pixel data to ashmem, so the parcel carries a handle, not the pixels.
 */
const val BT_WIDGET_BITMAP_MAX_EDGE_PX: Int = 1400

/**
 * Convert a dp box to a capped px box, preserving aspect. Never returns a
 * dimension below 1 — a degenerate size draws a blank pixel, not a crash.
 */
fun btWidgetBitmapSize(
    widthDp: Float,
    heightDp: Float,
    density: Float,
    maxEdgePx: Int = BT_WIDGET_BITMAP_MAX_EDGE_PX,
): Pair<Int, Int> {
    var w = (widthDp * density).toInt().coerceAtLeast(1)
    var h = (heightDp * density).toInt().coerceAtLeast(1)
    val longest = maxOf(w, h)
    if (longest > maxEdgePx) {
        val scale = maxEdgePx.toFloat() / longest
        w = (w * scale).toInt().coerceAtLeast(1)
        h = (h * scale).toInt().coerceAtLeast(1)
    }
    return w to h
}

// ── Heatmap tiles (owner ask 2026-08-18) ────────────────────────────────────

/**
 * One heatmap cell: a holding sized by what it is worth and coloured by what it
 * did today.
 *
 * @param weight tile AREA — `marketValueEur`. Never a fabricated magnitude.
 * @param changePct today's move, server-computed. Null means "no quote today",
 *   which is drawn neutral rather than green.
 */
data class BtWidgetHeatTile(
    val symbol: String,
    val weight: Double,
    val changePct: Double?,
    val hiddenCount: Int = 0,
)

/**
 * Holdings as heatmap tiles: descending by value, one row per ASSET, capped at
 * [maxTiles] with the remainder folded into one `Andere` cell.
 *
 * ## Why this is holdings and not "the market"
 *
 * There is no market-universe endpoint on the platform — no screener, no index
 * constituents, no gainers list. The honest universe this app can name is the
 * one the account already defines, and of the two candidates only holdings
 * carry BOTH a size and a change: `marketValueEur` and `dayChangePct`, both
 * server-computed, both already in Room. A watchlist row carries neither, so a
 * watchlist heatmap could only ever be equal-weight.
 *
 * Sizing by market cap was considered and rejected: it exists only on the
 * per-asset fundamentals response (one call per asset) and is denominated in
 * each company's reporting currency with no FX on the response — so tiles would
 * be sized by mixed-currency numbers. That is not a magnitude, it is a
 * coincidence.
 *
 * The same asset held in two portfolios is ONE tile: its value adds and its
 * percentage change is value-weighted, because two rows of the same ticker are
 * one position as far as today's move is concerned.
 */
fun btWidgetHeatTiles(
    holdings: List<HoldingEntity>,
    maxTiles: Int,
): List<BtWidgetHeatTile> {
    val merged = holdings
        .filter { (it.marketValueEur ?: 0.0) > 0.0 }
        .groupBy { it.assetSymbol }
        .map { (symbol, rows) ->
            val value = rows.sumOf { it.marketValueEur ?: 0.0 }
            val quoted = rows.filter { it.dayChangePct != null && (it.marketValueEur ?: 0.0) > 0.0 }
            val quotedValue = quoted.sumOf { it.marketValueEur ?: 0.0 }
            BtWidgetHeatTile(
                symbol = symbol,
                weight = value,
                changePct = if (quoted.isEmpty() || quotedValue <= 0.0) {
                    null
                } else {
                    quoted.sumOf { (it.dayChangePct ?: 0.0) * (it.marketValueEur ?: 0.0) } / quotedValue
                },
            )
        }
        .sortedByDescending { it.weight }

    if (maxTiles <= 0 || merged.size <= maxTiles) return merged
    val kept = merged.take(maxTiles - 1)
    val rest = merged.drop(maxTiles - 1)
    // The fold keeps the total honest but drops the change: an aggregate of
    // unlike movements has no single direction worth colouring.
    return kept + BtWidgetHeatTile(
        symbol = "",
        weight = rest.sumOf { it.weight },
        changePct = null,
        hiddenCount = rest.size,
    )
}

/**
 * How saturated a tile should be for a move of [changePct].
 *
 * ## Why this is not simply "relative to the biggest move today"
 *
 * That was the first implementation and the device showed it lying. On a flat
 * day the largest mover might be −0,18 %, and scaling to it painted that tile
 * full-strength red — a portfolio that did nothing looked like a portfolio that
 * crashed. Saturation is a magnitude channel, so it has to be anchored to a
 * magnitude that means something.
 *
 * The anchor is therefore [BT_HEAT_REFERENCE] — a genuinely strong single-day
 * move — and the day's own maximum only takes over when it EXCEEDS that. So a
 * calm day reads calm, a violent day still uses the full range, and the two are
 * distinguishable from each other rather than both looking maximal.
 *
 * The floor keeps the smallest mover on the right side of neutral; direction is
 * additionally printed on the tile, so it never rests on hue alone.
 */
fun btWidgetHeatIntensity(changePct: Double?, maxAbs: Double): Float {
    if (changePct == null || changePct == 0.0) return 0f
    val reference = kotlin.math.max(maxAbs, BT_HEAT_REFERENCE)
    if (reference <= 0.0) return 0f
    val ratio = (kotlin.math.abs(changePct) / reference).coerceIn(0.0, 1.0)
    return (BT_HEAT_FLOOR + (1f - BT_HEAT_FLOOR) * ratio.toFloat()).coerceIn(0f, 1f)
}

/** The palest a directional tile may get. Below this, green stops reading as green. */
private const val BT_HEAT_FLOOR = 0.42f

/** The day-move that counts as "full strength" when nothing bigger happened. */
private const val BT_HEAT_REFERENCE = 3.0
