package at.bettertrack.app.ui.cash

import at.bettertrack.app.data.api.dto.CashTrendPointDto
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The cash-flow chart's **selection** model (owner ask 2026-08-16, repeated
 * 2026-08-17: *"auch im cash sollte man beim cashflow diagram draufdrücken
 * können und sehen wie viel die jeweiligen balken im diagramm representieren"*
 * … *"also so dass man einen monat selektieren kann"*).
 *
 * Everything here is pure: hit-testing a month column, naming it, and rolling
 * up the visible window. The Compose side owns only the gesture and the paint.
 *
 * ## Why the selection is a MONTH KEY and not an index
 *
 * The chart's data is refetched (pull-to-refresh, portfolio switch, a month
 * rolling over at midnight). An index survives none of those honestly — index 3
 * silently becomes a different month — while `"2026-06"` either still exists in
 * the new series or does not, and [resolveTrendSelection] turns the second case
 * into "nothing selected" rather than into a wrong readout.
 */

/**
 * Which month column the finger at [x] is over, as an index into a series of
 * [count] evenly spaced columns spanning [width] pixels.
 *
 * The chart's columns are laid out with `weight(1f)` and a fixed gap, so the
 * cell pitch is uniform and the gap belongs to whichever column is nearer — the
 * gesture must never fall *between* bars. That is the whole reason this is a
 * nearest-column function over the full width rather than a hit test against
 * the drawn rectangles: the drawn inflow bar of a quiet month is a few pixels
 * wide, and a chart you can only select by hitting a 3px rectangle is a chart
 * that cannot be selected.
 *
 * Returns `-1` when there is nothing to select, so callers never index an empty
 * series.
 */
fun trendIndexAt(x: Float, width: Float, count: Int): Int {
    if (count <= 0) return -1
    if (count == 1) return 0
    if (!x.isFinite() || !width.isFinite() || width <= 0f) return 0
    val cell = width / count
    val raw = (x / cell).toInt()
    return raw.coerceIn(0, count - 1)
}

/**
 * The point a stored month key still refers to, or null.
 *
 * Called on every recomposition rather than only when the series changes, so a
 * refresh that drops the selected month (the window slid past it) leaves the
 * block in its aggregate state instead of showing figures for a bar that is no
 * longer on screen.
 */
fun resolveTrendSelection(points: List<CashTrendPointDto>, month: String?): CashTrendPointDto? {
    if (month == null) return null
    return points.firstOrNull { it.month == month }
}

/**
 * Tapping a bar selects it; tapping the SELECTED bar clears the selection.
 *
 * The second half is the reset affordance the owner gets for free — the same
 * gesture that made the state undoes it, which is how every other toggle in
 * this app behaves. The visible "reset" control is the redundant path for the
 * user who has scrolled the bar out from under their thumb, not the only one.
 */
fun toggleTrendMonth(current: String?, month: String): String? =
    if (current == month) null else month

/** One month's signed direction: what came in minus what went out. */
fun trendNet(point: CashTrendPointDto): Double {
    val inn = if (point.inflow.isFinite()) point.inflow else 0.0
    val out = if (point.outflow.isFinite()) point.outflow else 0.0
    return inn - out
}

/**
 * The whole visible window rolled into one point, keyed `""`.
 *
 * ## Why this is allowed to add
 *
 * §7.1 says the server is the only calculator, and it is: every addend here is
 * a **server-computed monthly aggregate** straight off `GET /cash/trends`, and
 * the result describes exactly the bars drawn underneath it — the same standing
 * this app already gives [cashLedgerStats]. What would violate the rule is
 * deriving a month's figures from the movement rows, or presenting this sum as
 * a portfolio figure. It is neither: it is labelled with the month count it
 * covers, and it is what the block shows when NO month is selected, which is
 * precisely the question "what do all these bars add up to".
 */
fun trendTotals(points: List<CashTrendPointDto>): CashTrendPointDto {
    var inn = 0.0
    var out = 0.0
    points.forEach { p ->
        if (p.inflow.isFinite()) inn += p.inflow
        if (p.outflow.isFinite()) out += p.outflow
    }
    return CashTrendPointDto(month = "", inflow = inn, outflow = out)
}

/**
 * The readout's heading for a selected month — the FULL month name plus the
 * year ("August 2026"), not the axis's three-letter tick.
 *
 * The axis label is a tick mark and is allowed to be terse because its column
 * position carries the rest. A readout that says only "Aug" while the axis also
 * says "Aug" has stated nothing the user did not already have, and in January
 * it is genuinely ambiguous about which year it means.
 */
fun trendMonthTitle(wire: String, locale: Locale): String = try {
    YearMonth.parse(wire).format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
} catch (_: Exception) {
    wire
}

/**
 * The inclusive `[first day, last day]` of a `YYYY-MM` bucket, or null when the
 * key is not a month. Feeds the ledger CTA: "show me this bar's movements" is a
 * date range, and the ledger already speaks date ranges.
 */
fun trendMonthRange(wire: String): Pair<java.time.LocalDate, java.time.LocalDate>? = try {
    val ym = YearMonth.parse(wire)
    ym.atDay(1) to ym.atEndOfMonth()
} catch (_: Exception) {
    null
}
