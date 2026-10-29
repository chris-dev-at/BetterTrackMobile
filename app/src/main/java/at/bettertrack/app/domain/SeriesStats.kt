package at.bettertrack.app.domain

import kotlin.math.abs
import kotlin.math.pow

/**
 * Series statistics for the Analytics deep-dive — a **literal** Kotlin port of
 * `packages/domain/src/seriesStats.ts` at the commit pinned in
 * `tools/domain-vectors/PINNED_AT`.
 *
 * Side-by-side compare stats (total %, CAGR, max drawdown, best/worst day), the
 * performance-% display mode, real-terms (inflation) deflation, the
 * time-weighted window statistics a forecast samples as its return factor
 * (§13.5 V5-P6b), the money-weighted (Modified Dietz) headline (#1669), the
 * per-asset contribution table, and the N-series comparison.
 *
 * Like the rest of the domain this is money-critical code and a **pure** module:
 * it depends on nothing but the value/flow point types of `Holdings.kt` (so the
 * money-weighted window reads the very series the TWR curve is built from),
 * reads no clock (`dateToMs` is a deterministic parse of a *passed-in* ISO
 * string), performs no I/O, and never mutates its inputs. No rounding happens
 * here — every figure is returned at full `Double` precision.
 *
 * `computeSeriesStats` mirrors the backtest engine's `computeStats` — same
 * total-return, ACT/365.25 CAGR, running-peak drawdown, and consecutive-day
 * best/worst rules — but adds the guards a *generic* value series needs which a
 * base-100 backtest index never does: every division is guarded against a
 * non-positive base.
 */

// ---------------------------------------------------------------------------
// Constants & date helpers
// ---------------------------------------------------------------------------

private const val MS_PER_DAY: Double = 86_400_000.0

/**
 * Calendar days per year for CAGR/deflation exponents (ACT/365.25 — averages in
 * the leap day so multi-year annualisation does not drift).
 */
private const val DAYS_PER_YEAR: Double = 365.25

/** Tolerance below which a total is treated as zero (guards 0/0 divisions). */
private const val EPSILON: Double = 1e-9

/** UTC midnight epoch-ms of an ISO `YYYY-MM-DD` date (no clock read; deterministic). */
private fun dateToMs(date: String): Double = jsDateOnlyToMs(date)

/** The `/^\d{4}-\d{2}-\d{2}$/` of `isoDayToMs` (same literal as `Holdings.kt`'s `ISO_DATE`). */
private val ISO_DAY = Regex("^\\d{4}-\\d{2}-\\d{2}$")

/**
 * UTC midnight epoch-ms of an ISO `YYYY-MM-DD` date that MUST parse — the
 * money-weighted window throws on a malformed date rather than weighting a
 * flow by `NaN` (the TWR's `assertIsoDate` discipline, `Holdings.kt`).
 */
private fun isoDayToMs(date: String, what: String): Double {
    val ms = if (ISO_DAY.matches(date)) dateToMs(date) else Double.NaN
    if (!ms.isFinite()) {
        throw DomainException(
            "Invalid $what: expected ISO YYYY-MM-DD, got ${jsJsonStringify(date)}",
        )
    }
    return ms
}

/** Elapsed calendar years from ISO date `a` to ISO date `b` (signed, ACT/365.25). */
private fun yearsBetween(a: String, b: String): Double =
    (dateToMs(b) - dateToMs(a)) / (MS_PER_DAY * DAYS_PER_YEAR)

// ---------------------------------------------------------------------------
// Series statistics
// ---------------------------------------------------------------------------

/** One point of a dated value series (portfolio value, benchmark index, …). */
data class StatSeriesPoint(val date: String, val value: Double)

/** A single day's percentage return, tagged with the *later* day's date. */
data class DayReturn(val date: String, val returnPct: Double)

/** The side-by-side stats block (total %, CAGR, max drawdown, best/worst day). */
data class SeriesStats(
    val totalReturnPct: Double,
    /** Annualised return (ACT/365.25); `null` when no calendar time elapsed. */
    val cagrPct: Double?,
    /** Deepest peak-to-trough loss, always ≤ 0 (0 when the series only rises). */
    val maxDrawdownPct: Double,
    val bestDay: DayReturn?,
    val worstDay: DayReturn?,
)

/** What every degenerate series produces (the guarded defaults). */
private fun emptyStats() = SeriesStats(
    totalReturnPct = 0.0,
    cagrPct = null,
    maxDrawdownPct = 0.0,
    bestDay = null,
    worstDay = null,
)

/**
 * Performance statistics for an arbitrary value series.
 *
 *  - Empty series, or a series whose first value is ≤ 0 (no meaningful base to
 *    divide by), returns the zeroed defaults with `null` CAGR and days.
 *  - `totalReturnPct` is last/first − 1; `cagrPct` annualises it over elapsed
 *    calendar time and is `null` for a single-day window (`years == 0`).
 *  - Max drawdown is a single running-peak sweep: `value/peak − 1`, minimum
 *    tracked, so it is 0 for a series that never dips below a prior high.
 *  - Daily returns are ratios of *consecutive* points, tagged with the later
 *    point's date; strict `>`/`<` comparisons make the FIRST occurrence win
 *    ties. A day whose previous value is ≤ 0 has no meaningful ratio return and
 *    is skipped. Fewer than 2 points ⇒ `bestDay`/`worstDay` are `null`.
 */
fun computeSeriesStats(series: List<StatSeriesPoint>): SeriesStats {
    val first = series.firstOrNull()
    val last = series.lastOrNull()
    if (first == null || last == null || first.value <= 0) {
        return emptyStats()
    }

    val totalReturnPct = (last.value / first.value - 1) * 100

    val years = (dateToMs(last.date) - dateToMs(first.date)) / (MS_PER_DAY * DAYS_PER_YEAR)
    val cagrPct =
        if (years > 0) ((last.value / first.value).pow(1 / years) - 1) * 100 else null

    // Single sweep: running peak for drawdown, consecutive ratios for daily
    // returns. `peak` starts at the first value, which the guard above proves
    // positive, and only ever rises — so the drawdown division is safe even if
    // the series later touches ≤ 0.
    var peak = first.value
    var maxDd = 0.0
    var bestDay: DayReturn? = null
    var worstDay: DayReturn? = null
    for (i in series.indices) {
        val pt = series[i]
        if (pt.value > peak) peak = pt.value
        val dd = pt.value / peak - 1
        if (dd < maxDd) maxDd = dd
        if (i > 0) {
            val prev = series[i - 1]
            if (prev.value > 0) {
                val r = DayReturn(pt.date, (pt.value / prev.value - 1) * 100)
                if (bestDay == null || r.returnPct > bestDay.returnPct) bestDay = r
                if (worstDay == null || r.returnPct < worstDay.returnPct) worstDay = r
            }
        }
    }

    return SeriesStats(
        totalReturnPct = totalReturnPct,
        cagrPct = cagrPct,
        maxDrawdownPct = maxDd * 100,
        bestDay = bestDay,
        worstDay = worstDay,
    )
}

// ---------------------------------------------------------------------------
// Performance-% display mode
// ---------------------------------------------------------------------------

/** One point of a cumulative-percent (performance mode) series. */
data class PerfPoint(val date: String, val pct: Double)

/**
 * Rebase a value series to cumulative percent from its first point
 * (`pct = value/first − 1`, so the first point is exactly 0). Dates are
 * preserved. Empty input ⇒ `[]`; a non-positive first value has no meaningful
 * base, so every point is emitted as 0 % (guarded division).
 */
fun toPerformanceSeries(series: List<StatSeriesPoint>): List<PerfPoint> {
    val first = series.firstOrNull() ?: return emptyList()
    if (first.value <= 0) {
        return series.map { pt -> PerfPoint(pt.date, 0.0) }
    }
    val base = first.value
    return series.map { pt -> PerfPoint(pt.date, (pt.value / base - 1) * 100) }
}

// ---------------------------------------------------------------------------
// Inflation mode (real-terms deflation)
// ---------------------------------------------------------------------------

/** One monthly price-index anchor: ISO `YYYY-MM` and the index level. */
data class MonthlyIndexPoint(val month: String, val value: Double)

/**
 * How to deflate nominal values into real terms: either a flat annual rate
 * ("custom flat %/yr") or a monthly price-index series (AT/EU HICP, US CPI).
 */
sealed interface Deflator {
    data class Flat(val pctPerYear: Double) : Deflator
    data class Index(val monthly: List<MonthlyIndexPoint>) : Deflator
}

/**
 * Convert a nominal series to real (inflation-adjusted) terms, expressed in
 * **start-date money**: the first point is the base, so
 * `real[0].value == series[0].value` and later points are discounted by the
 * price growth since then.
 *
 *  - [Deflator.Flat]: `value · (1 + r/100)^(−yearsElapsed)` with ACT/365.25 years.
 *  - [Deflator.Index]: `value · index(startMonth)/index(pointMonth)`. The index
 *    level for a `YYYY-MM` month is **linearly interpolated** between the
 *    anchors that bracket it. Months before the earliest anchor floor to that
 *    anchor's value; months **after the latest anchor extrapolate** linearly
 *    along the slope of the last two anchors. A single-anchor set carries that
 *    value everywhere. An empty index leaves the series unchanged.
 *
 * Dates are preserved; the result is always a fresh list of fresh points.
 */
fun deflateSeries(series: List<StatSeriesPoint>, deflator: Deflator): List<StatSeriesPoint> {
    val first = series.firstOrNull() ?: return emptyList()

    if (deflator is Deflator.Flat) {
        val growth = 1 + deflator.pctPerYear / 100
        return series.map { pt ->
            StatSeriesPoint(pt.date, pt.value * growth.pow(-yearsBetween(first.date, pt.date)))
        }
    }

    val monthly = (deflator as Deflator.Index).monthly
    val indexAt = buildIndexResolver(monthly)
        ?: return series.map { pt -> StatSeriesPoint(pt.date, pt.value) }
    val baseLevel = indexAt(first.date.take(7))
    return series.map { pt ->
        StatSeriesPoint(pt.date, pt.value * (baseLevel / indexAt(pt.date.take(7))))
    }
}

/**
 * ISO `YYYY-MM` → a comparable month-of-anchor number (year * 12 + month).
 *
 * Returns `Double`, not `Int`, deliberately: the value feeds the interpolation
 * weight `(… − …) / dx`, and JavaScript's `/` is always floating-point. Integer
 * division here would silently truncate every interior interpolation to 0 or 1.
 */
private fun monthKey(month: String): Double {
    val y = month.take(4).toDoubleOrNull() ?: Double.NaN
    val m = month.substring(5, 7).toDoubleOrNull() ?: Double.NaN
    return y * 12 + (m - 1)
}

/**
 * Build the `indexAt(month)` resolver used by both [deflateSeries] and
 * [indexAveragePctPerYear] — one code path so the two agree on how a given
 * month reads. `null` when the anchor set is empty (caller degrades to the
 * identity).
 */
private fun buildIndexResolver(monthly: List<MonthlyIndexPoint>): ((String) -> Double)? {
    if (monthly.isEmpty()) return null
    val sorted = monthly.sortedWith { a, b ->
        if (a.month < b.month) -1 else if (a.month > b.month) 1 else 0
    }
    val earliest = sorted[0]
    val latest = sorted[sorted.size - 1]
    return { month: String ->
        if (month <= earliest.month) {
            earliest.value
        } else if (month >= latest.month) {
            // Linear extrapolation along the slope of the last two anchors, so a
            // window whose points all sit past the last observation still
            // deflates. With a single anchor no slope exists → carry the level.
            if (sorted.size == 1) {
                latest.value
            } else {
                val prev = sorted[sorted.size - 2]
                val dx = monthKey(latest.month) - monthKey(prev.month)
                if (dx == 0.0) {
                    latest.value
                } else {
                    val slope = (latest.value - prev.value) / dx
                    latest.value + slope * (monthKey(month) - monthKey(latest.month))
                }
            }
        } else {
            // Interior: find the bracket (a, b) with a.month <= month < b.month
            // and interpolate linearly. `sorted` is already ascending.
            var result: Double? = null
            var i = 1
            while (i < sorted.size) {
                val b = sorted[i]
                val a = sorted[i - 1]
                if (month < b.month) {
                    val dx = monthKey(b.month) - monthKey(a.month)
                    result = if (dx == 0.0) {
                        a.value
                    } else {
                        val t = (monthKey(month) - monthKey(a.month)) / dx
                        a.value + (b.value - a.value) * t
                    }
                    break
                }
                i += 1
            }
            // Unreachable fallback: the `>= latest.month` guard above catches this.
            result ?: latest.value
        }
    }
}

/**
 * Effective annualised %/yr an inflation-index preset averaged over its
 * checked-in observations: the CAGR from the first to the last anchor
 * `(last/first)^(1/years) − 1`, so a UI can show "≈ 2.6 %/yr" next to the preset
 * label. Empty / single-anchor / non-positive base all resolve to `null`.
 */
fun indexAveragePctPerYear(monthly: List<MonthlyIndexPoint>): Double? {
    if (monthly.size < 2) return null
    val sorted = monthly.sortedWith { a, b ->
        if (a.month < b.month) -1 else if (a.month > b.month) 1 else 0
    }
    val first = sorted[0]
    val last = sorted[sorted.size - 1]
    if (first.value <= 0) return null
    val months = monthKey(last.month) - monthKey(first.month)
    if (months <= 0) return null
    val years = months / 12
    return ((last.value / first.value).pow(1 / years) - 1) * 100
}

// ---------------------------------------------------------------------------
// Time-weighted return statistics
// ---------------------------------------------------------------------------

/**
 * The window statistics of a **time-weighted** return series (#1759): the
 * flow-neutral counterpart of [SeriesStats]'s `totalReturnPct`/`cagrPct`.
 */
data class TwrStats(
    /** TWR over the window, percent, rebased so the first point is 0 %. */
    val totalReturnPct: Double,
    /** Annualised TWR (ACT/365.25); `null` when no calendar time elapsed. */
    val cagrPct: Double?,
)

/**
 * Window statistics of a cumulative **time-weighted** return series — the
 * annualised figure a forecast may sample as "the return this portfolio has
 * historically earned" (§13.5 V5-P6b, #1759).
 *
 * The CAGR of a portfolio's *value* series is not a rate of return: every
 * deposit raises the value, so a saver who contributes monthly reads their own
 * contributions back as performance. A TWR series carries no such inflation —
 * it links each period's return across the flows — so annualising IT answers
 * the question the value CAGR only appeared to.
 *
 * The input is the cumulative-percent curve the portfolio already computes
 * (`timeWeightedReturn`, `Holdings.kt`): `pct` is the compounded return since
 * the curve's own anchor. It is rebased here onto the FIRST point of whatever
 * window is passed in (compounding, never subtraction — percentages don't add
 * across time), so slicing a since-inception curve to a 1Y/3Y/5Y window and
 * handing it over states that window's return. An optional [Deflator]
 * expresses the result in real terms, exactly as [deflateSeries] does for a
 * value series.
 *
 * The rebased index is then measured by [computeSeriesStats] itself, so the
 * total-return and ACT/365.25 CAGR formulas have ONE definition in this
 * module: for a portfolio with no external flows the TWR index is `V/V₀` point
 * for point, and this function therefore returns exactly the value series'
 * `totalReturnPct`/`cagrPct` — the no-flows case cannot diverge between the two
 * statistics.
 *
 * `null` when the window cannot state a return at all: an empty series, or a
 * base point at or below −100 % (a non-positive index, which a well-formed TWR
 * curve never reaches).
 *
 * The TypeScript's `ReadonlyArray<PerfPoint>` is this module's own point type;
 * a `timeWeightedReturn` curve (`PerformancePoint`, structurally identical) is
 * mapped onto it by the caller.
 */
fun computeTwrStats(
    performance: List<PerfPoint>,
    deflator: Deflator? = null,
): TwrStats? {
    val first = performance.firstOrNull() ?: return null
    val base = 1 + first.pct / 100
    if (!base.isFinite() || base <= 0) return null
    val index: List<StatSeriesPoint> = performance.map { pt ->
        StatSeriesPoint(pt.date, (1 + pt.pct / 100) / base)
    }
    val stats = computeSeriesStats(if (deflator != null) deflateSeries(index, deflator) else index)
    return TwrStats(totalReturnPct = stats.totalReturnPct, cagrPct = stats.cagrPct)
}

// ---------------------------------------------------------------------------
// Money-weighted return — Modified Dietz (#1669)
// ---------------------------------------------------------------------------

/** One external cash flow inside a Modified Dietz window, at an exact instant. */
data class DietzFlow(
    /** Epoch-ms instant the money was put to work (positive) or taken out (negative). */
    val atMs: Double,
    /** Signed amount in the window's currency: into the portfolio positive, out negative. */
    val amount: Double,
)

/** The ingredients of one Modified Dietz window — see [modifiedDietz]. */
data class DietzWindow(
    /** Epoch-ms instant the window opens; `startValue` is the capital at work then. */
    val startMs: Double,
    /** Epoch-ms instant the window closes (never before `startMs`); `endValue` is the value then. */
    val endMs: Double,
    val startValue: Double,
    val endValue: Double,
    /** External flows; those outside `[startMs, endMs]` are ignored (see [modifiedDietz]). */
    val flows: List<DietzFlow>,
)

/**
 * Modified Dietz return of one window, percent (#1669, §16 2026-09-14):
 *
 *     MD = (V_end − V_start − Σ F_i) / (V_start + Σ w_i · F_i)
 *     w_i = (t_end − t_i) / (t_end − t_start)   — the fraction of the window
 *                                                 remaining after flow i
 *
 * — the gain the window produced, over the capital that was at work in it,
 * each flow counting for the share of the window it was invested for. This is
 * the **money-weighted** counterpart of the time-weighted curve
 * ([computeTwrStats], `timeWeightedReturn`): a deposit made just before a rally
 * raises it and the same deposit made just after a crash barely moves it,
 * whereas the TWR — by design — ignores both. That is exactly why the two are
 * served side by side: a tiny early stake that lost two thirds before the real
 * money arrived drags the TWR deep into the red while the money-weighted figure
 * reports what the money actually earned. Exact XIRR is deliberately not
 * attempted (the headline is a summary; Dietz is what the reference tools print
 * for "your money's return").
 *
 *  - Flows before `startMs` are already inside `startValue` and flows after
 *    `endMs` have not happened yet: both are ignored. A flow AT `startMs`
 *    weighs 1, a flow AT `endMs` weighs 0. A zero-length window
 *    (`startMs == endMs`) has no time to weight by: a flow at that instant
 *    weighs 1 — it IS the capital.
 *  - `null` when the window has no capital to measure against: a denominator
 *    within [EPSILON] of zero or below it (nothing invested, or withdrawals
 *    that outweigh the start value — the known Dietz blind spot, which the
 *    ruling maps to "no figure", never to a sign flip).
 *  - Throws on a non-finite input or a window that runs backwards: this is
 *    the money path, and a silent `NaN` would surface as a wrong headline.
 *  - Full precision (§5.4); the display layer rounds.
 *
 * Epoch instants are `Double`s, as every `Date.parse` result in this port is.
 */
fun modifiedDietz(window: DietzWindow): Double? {
    val startMs = window.startMs
    val endMs = window.endMs
    val startValue = window.startValue
    val endValue = window.endValue
    val flows = window.flows
    for ((label, n) in listOf(
        "startMs" to startMs,
        "endMs" to endMs,
        "startValue" to startValue,
        "endValue" to endValue,
    )) {
        if (!n.isFinite()) {
            throw DomainException("modifiedDietz: $label must be finite, got ${jsNum(n)}")
        }
    }
    if (endMs < startMs) {
        throw DomainException(
            "modifiedDietz: window runs backwards (${jsNum(startMs)} → ${jsNum(endMs)})",
        )
    }
    val span = endMs - startMs

    var netFlow = 0.0
    var weightedFlow = 0.0
    for (flow in flows) {
        if (!flow.atMs.isFinite() || !flow.amount.isFinite()) {
            throw DomainException(
                "modifiedDietz: flow must be finite, got ${jsNum(flow.atMs)} / ${jsNum(flow.amount)}",
            )
        }
        if (flow.atMs < startMs || flow.atMs > endMs) continue
        val weight = if (span > 0) (endMs - flow.atMs) / span else 1.0
        netFlow += flow.amount
        weightedFlow += weight * flow.amount
    }

    val denominator = startValue + weightedFlow
    if (denominator <= EPSILON) return null
    return ((endValue - startValue - netFlow) / denominator) * 100
}

/**
 * Where a daily-series Dietz window opens relative to its first point — the
 * two anchors the served TWR curve already uses (§6.8, #125), so the two
 * headline figures of a range always measure the same window:
 *
 *  - [INCEPTION] — the MAX / since-inception anchor. The window opens just
 *    BEFORE the first day's own flows with no capital at all, so the money
 *    that opened the position is the first flow (weight 1) and day one's
 *    execution→close move counts as return — exactly as `timeWeightedReturn`
 *    anchors its index at 1 before day one (never re-based to the first
 *    plotted point).
 *  - [FIRST_POINT] — the re-based range slice (1W … 5Y). The window opens
 *    at the first point's close, that value is the starting capital and the
 *    first day's flows are already inside it (they are not counted) — exactly
 *    as `rebasePerformance` divides the curve by its first point.
 *
 * The TypeScript is the string union `'inception' | 'first-point'`; [wire] is
 * that literal (the `ComparisonMetric` precedent).
 */
enum class DietzAnchor(val wire: String) {
    INCEPTION("inception"),
    FIRST_POINT("first-point"),
}

/**
 * Money-weighted (Modified Dietz) return of a daily value series over the
 * window its points span, percent, or `null` when the window has no capital
 * (#1669). Consumes the SAME `values`/`flows` the time-weighted curve is
 * built from (`timeWeightedReturn` — `flows` are the external
 * deposits/withdrawals `externalCashFlowsForTwr` classifies, never cash-funded
 * buys, sell proceeds or internal transfers), so the two figures can never
 * disagree about what counts as money crossing the portfolio boundary.
 *
 * Daily flows are placed on the clock by the TWR's own hybrid convention: the
 * day's NET flow counts as an **inflow at the start of its day** (the previous
 * close — the new money is at work for that whole day's move) or as an
 * **outflow at its close** (the money left with the day's move already
 * earned). Over a single day this makes the Dietz figure equal the TWR's
 * daily link `r_d − 1` exactly, for either sign — the two headlines start from
 * one definition of "when did the money arrive" and diverge only in how they
 * chain across days.
 *
 *  - [anchor] picks the start, see [DietzAnchor]. Under [DietzAnchor.INCEPTION]
 *    a first day that brings no measurable money in (net flow not positive — a
 *    series whose history starts before its FX pair's, say, V3-P10d) has
 *    nothing to anchor at; the window then opens at the first point like a
 *    slice does, which is what `timeWeightedReturn` effectively does when its
 *    day-one link is flat.
 *  - A flow dated inside the window counts whether or not that day carries a
 *    value point; flows before the window are inside its start value, flows
 *    after it have not happened yet (a future-dated transaction), and both are
 *    ignored — the TWR ignores the latter the same way.
 *  - Empty series ⇒ `null`. A single-point [DietzAnchor.FIRST_POINT] window has
 *    no elapsed time and no counted flows, so it reads 0 % on any positive
 *    value (and `null` on none), exactly like the re-based one-point TWR.
 *  - Unsorted input is tolerated (sorted on a copy); nothing is mutated.
 *    Throws on a non-finite value/flow or a malformed date, as the TWR does.
 *
 * The TypeScript takes `window: { readonly anchor: DietzAnchor }`; the
 * one-field bag is flattened to [anchor] (the `CurrencyConverter` precedent).
 */
fun modifiedDietzReturn(
    values: List<ValuePoint>,
    flows: List<FlowPoint>,
    anchor: DietzAnchor,
): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sortedWith { a, b ->
        if (a.date < b.date) -1 else if (a.date > b.date) 1 else 0
    }
    val first = sorted[0]
    val last = sorted[sorted.size - 1]
    for (point in sorted) {
        isoDayToMs(point.date, "value point date")
        if (!point.valueEur.isFinite()) {
            throw DomainException(
                "Value on ${point.date} must be a finite number, got ${jsNum(point.valueEur)}",
            )
        }
    }

    // The day's NET external flow, as the TWR links it (one bucket per day).
    val netByDate = LinkedHashMap<String, Double>()
    for (flow in flows) {
        isoDayToMs(flow.date, "flow point date")
        if (!flow.flowEur.isFinite()) {
            throw DomainException(
                "Flow on ${flow.date} must be a finite number, got ${jsNum(flow.flowEur)}",
            )
        }
        netByDate[flow.date] = (netByDate[flow.date] ?: 0.0) + flow.flowEur
    }

    // A point's instant stands in for its close; only differences matter, so
    // UTC midnight is as good a close as any. An inflow on day d is at work from
    // the previous close (d − 1 day), an outflow leaves at d's own close.
    val firstMs = isoDayToMs(first.date, "value point date")
    val endMs = isoDayToMs(last.date, "value point date")
    val resolvedAnchor: DietzAnchor =
        if (anchor == DietzAnchor.INCEPTION && (netByDate[first.date] ?: 0.0) > EPSILON) {
            DietzAnchor.INCEPTION
        } else {
            DietzAnchor.FIRST_POINT
        }
    val startMs = if (resolvedAnchor == DietzAnchor.INCEPTION) firstMs - MS_PER_DAY else firstMs
    val startValue = if (resolvedAnchor == DietzAnchor.INCEPTION) 0.0 else first.valueEur

    val dietzFlows = ArrayList<DietzFlow>()
    for ((date, net) in netByDate) {
        if (net == 0.0) continue
        val inWindow =
            if (resolvedAnchor == DietzAnchor.INCEPTION) date >= first.date else date > first.date
        if (!inWindow || date > last.date) continue
        val dayMs = isoDayToMs(date, "flow point date")
        dietzFlows.add(DietzFlow(atMs = if (net > 0) dayMs - MS_PER_DAY else dayMs, amount = net))
    }

    return modifiedDietz(
        DietzWindow(
            startMs = startMs,
            endMs = endMs,
            startValue = startValue,
            endValue = last.valueEur,
            flows = dietzFlows,
        ),
    )
}

// ---------------------------------------------------------------------------
// Per-asset contribution table
// ---------------------------------------------------------------------------

/** Per-asset inputs for the contribution table over a chosen period. */
data class ContributionInput(
    val assetId: String,
    /** Asset value at the period start. */
    val startValue: Double,
    /** Asset value at the period end. */
    val endValue: Double,
    /** Asset value now (drives the portfolio weight column). */
    val currentValue: Double,
)

/** One row of the contribution table. */
data class ContributionShare(
    val assetId: String,
    /** `currentValue / Σ currentValue`; 0 when the total is ~0. */
    val weight: Double,
    /** `(endValue − startValue) / Σ startValue · 100`; 0 when the start total is ~0. */
    val contributionPct: Double,
)

/**
 * Per-asset weight and contribution to the period's change. Contributions are
 * additive against the *common* start total, so
 * `Σ contributionPct == (Σ end / Σ start − 1) · 100` — the rows sum exactly to
 * the filtered total return. Input order is preserved; degenerate totals
 * (|Σ start| or Σ current within [EPSILON] of 0) yield 0 instead of a division
 * by ~0.
 */
fun computeContributions(inputs: List<ContributionInput>): List<ContributionShare> {
    var totalStart = 0.0
    var totalCurrent = 0.0
    for (input in inputs) {
        totalStart += input.startValue
        totalCurrent += input.currentValue
    }
    return inputs.map { input ->
        ContributionShare(
            assetId = input.assetId,
            weight = if (totalCurrent > EPSILON) input.currentValue / totalCurrent else 0.0,
            contributionPct =
                if (abs(totalStart) > EPSILON) {
                    ((input.endValue - input.startValue) / totalStart) * 100
                } else {
                    0.0
                },
        )
    }
}

// ---------------------------------------------------------------------------
// N-series comparison (deltas vs a chosen baseline)
// ---------------------------------------------------------------------------

/**
 * The stat metrics an N-series comparison ranks side by side: a flat numeric
 * projection of the backtest engine's `BacktestStats`.
 *
 * Declaration order IS the TypeScript's `COMPARISON_METRICS` tuple order, which
 * is the order the delta record is populated in (plan §3.3 rule 4).
 */
enum class ComparisonMetric(val wire: String) {
    TOTAL_RETURN_PCT("totalReturnPct"),
    CAGR_PCT("cagrPct"),
    MAX_DRAWDOWN_PCT("maxDrawdownPct"),
    VOLATILITY_PCT("volatilityPct"),
    BEST_DAY_PCT("bestDayPct"),
    WORST_DAY_PCT("worstDayPct"),
}

/** Port of the `COMPARISON_METRICS` tuple; iteration order is contractual. */
val COMPARISON_METRICS: List<ComparisonMetric> = ComparisonMetric.entries.toList()

/**
 * One series' comparable stat vector. A metric is `null` exactly where the
 * underlying `BacktestStats` figure is undefined (CAGR / volatility on a
 * single-day window, best/worst day with no returns) — the delta against it is
 * then `null`, never a spurious `0`.
 */
typealias ComparisonMetricVector = Map<ComparisonMetric, Double?>

/** One input series to [compareSeriesStats]: an id + its stat vector. */
data class ComparisonSeriesInput(val id: String, val metrics: ComparisonMetricVector)

/** One series in the comparison result: its vector echoed back + deltas vs the baseline. */
data class ComparisonSeriesResult(
    val id: String,
    val metrics: ComparisonMetricVector,
    val deltas: Map<ComparisonMetric, Double?>,
)

/** The comparison outcome: the chosen baseline id + one result per input. */
data class SeriesComparison(
    val baselineId: String,
    val series: List<ComparisonSeriesResult>,
)

/**
 * Compare N aligned series' stat vectors against one baseline series.
 *
 * For every input series and every [COMPARISON_METRICS] metric the result
 * carries `metric − baselineMetric`, at full precision, with a `null` wherever
 * either operand is `null`. The baseline series compares against itself, so its
 * own deltas are `0` (or `null` where its metric is `null`). Input order is
 * preserved. The function is pure.
 *
 * Rejects (throws) on structurally invalid input: no series, a `baselineId`
 * absent from the set, or a duplicate id (which would make "the baseline"
 * ambiguous).
 */
fun compareSeriesStats(
    inputs: List<ComparisonSeriesInput>,
    baselineId: String,
): SeriesComparison {
    if (inputs.isEmpty()) {
        throw DomainException("compareSeriesStats requires at least one series")
    }
    val ids = LinkedHashSet<String>()
    for (input in inputs) {
        if (input.id in ids) {
            throw DomainException("compareSeriesStats: duplicate series id ${input.id}")
        }
        ids.add(input.id)
    }
    val baseline = inputs.find { it.id == baselineId }
        ?: throw DomainException(
            "compareSeriesStats: baselineId $baselineId is not among the series",
        )

    val series = inputs.map { input ->
        val deltas = LinkedHashMap<ComparisonMetric, Double?>()
        for (metric in COMPARISON_METRICS) {
            val value = input.metrics[metric]
            val base = baseline.metrics[metric]
            deltas[metric] = if (value == null || base == null) null else value - base
        }
        ComparisonSeriesResult(id = input.id, metrics = input.metrics, deltas = deltas)
    }

    return SeriesComparison(baselineId = baselineId, series = series)
}
