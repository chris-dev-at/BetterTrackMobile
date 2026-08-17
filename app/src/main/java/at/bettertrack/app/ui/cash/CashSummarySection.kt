package at.bettertrack.app.ui.cash

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.dto.CashSummaryResponse
import at.bettertrack.app.data.api.dto.CashTagSummaryDto
import at.bettertrack.app.data.api.dto.CashTrendPointDto
import at.bettertrack.app.ui.charts.rememberBtScrubTicker
import at.bettertrack.app.ui.components.BtInlineEmpty
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.formatEur
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * The **month summary** and **inflow/outflow trend** blocks on the cash screen
 * (V5 S2c, the leftover of S2c-1).
 *
 * Two deliberate rules run through this file:
 *
 *  1. **The totals come from the wire, never from the rows.** `GET /cash/summary`
 *     documents that a movement carrying two tags contributes its FULL magnitude
 *     to both tag rows, so `tags.sumOf { outflow } >= totalOutflow`. Summing the
 *     rows to draw a total would quietly invent a number the ledger disagrees
 *     with. The rows are a breakdown; [CashSummaryResponse.totalInflow] /
 *     `totalOutflow` / `net` are the figures. The overlap is called out in copy
 *     the moment it is actually visible (see [summaryRowsOverlap]) rather than
 *     as a permanent disclaimer nobody reads.
 *  2. **Every amount renders through [formatEur]**, so discreet-mode masking is
 *     inherited by construction — this file never formats money itself.
 *
 * The bars are relative to the largest row in view, not to the month total: the
 * question a per-tag breakdown answers is "which of these is the big one", and
 * scaling to a total the rows deliberately over-sum would make every bar wrong.
 */

// ── Pure logic (unit-tested without Compose) ────────────────────────────────

/** The magnitude a summary row is ranked and drawn by: spend first, else income. */
fun summaryRowMagnitude(row: CashTagSummaryDto): Double {
    val out = if (row.outflow.isFinite()) max(0.0, row.outflow) else 0.0
    val inn = if (row.inflow.isFinite()) max(0.0, row.inflow) else 0.0
    return max(out, inn)
}

/**
 * Bar width for one summary row, relative to the biggest row on screen.
 *
 * Returns `0f` when there is nothing to compare against, so an all-zero month
 * draws no bars at all rather than a row of full-width ones.
 */
fun summaryBarFraction(row: CashTagSummaryDto, rows: List<CashTagSummaryDto>): Float {
    val peak = rows.maxOfOrNull { summaryRowMagnitude(it) } ?: 0.0
    if (peak <= 0.0 || !peak.isFinite()) return 0f
    return (summaryRowMagnitude(row) / peak).coerceIn(0.0, 1.0).toFloat()
}

/**
 * Whether the tag rows genuinely over-sum the authoritative totals — i.e. at
 * least one movement carries more than one tag. Only then is the explanatory
 * hint worth the vertical space.
 *
 * The comparison is on the OUT side with a one-cent tolerance: the rows and the
 * totals are computed from the same movements server-side, so any difference
 * beyond rounding is the multi-tag effect.
 */
fun summaryRowsOverlap(summary: CashSummaryResponse): Boolean {
    val rowsOut = summary.tags.sumOf { if (it.outflow.isFinite()) it.outflow else 0.0 }
    val rowsIn = summary.tags.sumOf { if (it.inflow.isFinite()) it.inflow else 0.0 }
    return rowsOut - summary.totalOutflow > 0.005 || rowsIn - summary.totalInflow > 0.005
}

/** True when a month has no movements at all (nothing to draw, not an error). */
fun summaryIsEmpty(summary: CashSummaryResponse): Boolean =
    summary.totalInflow == 0.0 && summary.totalOutflow == 0.0 && summary.tags.isEmpty()

/**
 * The tallest bar in the trend chart. Inflow and outflow share ONE scale so the
 * two series stay visually comparable month to month — the whole point of the
 * chart is "did more come in than went out", which a per-series scale destroys.
 */
fun trendPeak(points: List<CashTrendPointDto>): Double {
    var peak = 0.0
    points.forEach { p ->
        if (p.inflow.isFinite()) peak = max(peak, p.inflow)
        if (p.outflow.isFinite()) peak = max(peak, p.outflow)
    }
    return peak
}

/** One bar's height as a fraction of the chart, against the shared [peak]. */
fun trendBarFraction(value: Double, peak: Double): Float {
    if (peak <= 0.0 || !peak.isFinite() || !value.isFinite() || value <= 0.0) return 0f
    return (value / peak).coerceIn(0.0, 1.0).toFloat()
}

/**
 * Short axis label for a `YYYY-MM` bucket ("Aug", "Aug 26" in January so a
 * year boundary is never silently crossed). Unparseable input falls back to the
 * raw wire value rather than dropping the bar's identity.
 */
fun trendMonthLabel(wire: String, locale: Locale): String = try {
    val ym = YearMonth.parse(wire)
    val short = ym.month.getDisplayName(TextStyle.SHORT, locale)
    if (ym.monthValue == 1) "$short ${ym.format(DateTimeFormatter.ofPattern("yy"))}" else short
} catch (_: Exception) {
    wire
}

// ── Summary block ───────────────────────────────────────────────────────────

/**
 * The summary block's three honest states — same shape as [BudgetsUi].
 *
 * [Failed] carries the message rather than being a payload-less marker: the
 * block used to collapse every refusal into one fixed sentence ("Couldn't load
 * this month's summary"), which threw away the one thing the server actually
 * said — offline, rate-limited, portfolio gone. Every other feature in the app
 * carries its error (`ConglomerateListState.Error`, `ChainRosterState.Failed`,
 * `IntelBlockUi.Failed`), and carrying it here is what lets the block render the
 * shared `BtInlineError` instead of a private copy of it.
 */
sealed interface CashSummaryUi {
    data object Loading : CashSummaryUi
    data class Ready(val summary: CashSummaryResponse) : CashSummaryUi
    data class Failed(val message: BtMessage) : CashSummaryUi
}

/** The trends block's three honest states — see [CashSummaryUi]. */
sealed interface CashTrendsUi {
    data object Loading : CashTrendsUi
    data class Ready(val points: List<CashTrendPointDto>) : CashTrendsUi
    data class Failed(val message: BtMessage) : CashTrendsUi
}

/**
 * In · Out · Net for the month, then one row per tag.
 *
 * The three totals sit on one line as a small stat strip because they are the
 * headline; the tag rows below answer "on what". Net takes gain/loss colour —
 * it is the only figure here with a direction.
 */
@Composable
fun CashSummaryBlock(
    summary: CashSummaryResponse,
    locale: Locale,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    if (summaryIsEmpty(summary)) {
        // In-section empty: this sits under its own heading inside a scrolling
        // page, so it takes the compact idiom rather than a full BtEmptyState —
        // a 64dp badge here would say the PAGE is empty.
        BtInlineEmpty(
            text = stringResource(R.string.bt_cash_summary_empty),
            modifier = modifier,
        )
        return
    }

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            SummaryTotal(
                label = stringResource(R.string.bt_cash_summary_in),
                amount = formatEur(summary.totalInflow, locale),
                tint = bt.gain,
                modifier = Modifier.weight(1f),
            )
            SummaryTotal(
                label = stringResource(R.string.bt_cash_summary_out),
                amount = formatEur(summary.totalOutflow, locale),
                tint = bt.loss,
                modifier = Modifier.weight(1f),
            )
            SummaryTotal(
                label = stringResource(R.string.bt_cash_summary_net),
                amount = formatEur(summary.net, locale, showSign = true),
                tint = when {
                    summary.net > 0.0 -> bt.gain
                    summary.net < 0.0 -> bt.loss
                    else -> bt.textPrimary
                },
                modifier = Modifier.weight(1f),
            )
        }

        if (summary.tags.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            summary.tags.forEachIndexed { index, row ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                SummaryTagRow(row, summary.tags, locale)
            }
            if (summaryRowsOverlap(summary)) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.bt_cash_summary_overlap_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = bt.textMuted,
                )
            }
        }
    }
}

@Composable
private fun SummaryTotal(
    label: String,
    amount: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = bt.textMuted)
        Spacer(Modifier.height(2.dp))
        Text(
            text = amount,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One tag's month: colour dot + name, the amount, and a bar sized against the
 * heaviest row. Outflow-dominant rows read in the loss colour, income rows in
 * gain — the direction is the first thing the eye should get.
 */
@Composable
private fun SummaryTagRow(
    row: CashTagSummaryDto,
    rows: List<CashTagSummaryDto>,
    locale: Locale,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    val spendDominant = row.outflow >= row.inflow
    val tint = if (spendDominant) bt.loss else bt.gain
    val amount = if (spendDominant) row.outflow else row.inflow
    val name = row.name ?: stringResource(R.string.bt_cash_summary_untagged)
    // The untagged bucket has no colour of its own — a muted dot keeps the row
    // aligned with the tagged ones without inventing an identity for it.
    val dot = if (row.tagId == null) bt.borderStrong else parseTagColor(row.color, bt.tagFallback)

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatEur(amount, locale),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = tint,
            )
        }
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(BtShapes.pill)
                .background(bt.border),
        ) {
            val fraction = summaryBarFraction(row, rows)
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .clip(BtShapes.pill)
                        .background(bt.wash(tint, 0.75f)),
                )
            }
        }
        if (row.movements > 0) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = pluralStringResource(
                    R.plurals.bt_cash_summary_movements,
                    row.movements,
                    row.movements,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = bt.textMuted,
            )
        }
    }
}

// ── Trends block ────────────────────────────────────────────────────────────

/**
 * A compact paired-bar chart: one green inflow bar and one red outflow bar per
 * month, oldest → newest, on a single shared scale — **selectable**.
 *
 * Hand-drawn from boxes rather than a chart library because it is six pairs of
 * rectangles and the app already owns its chart language; a Canvas would buy
 * nothing here and cost the automatic discreet-mode masking that the labels get
 * from [formatEur].
 *
 * ## The selection (owner ask 2026-08-16, restated 2026-08-17)
 *
 * *"auch im cash sollte man beim cashflow diagram draufdrücken können und sehen
 * wie viel die jeweiligen balken im diagramm representieren … also so dass man
 * einen monat selektieren kann"*.
 *
 * Four decisions follow from that sentence and none of them is negotiable:
 *
 *  - **The numbers land in a fixed readout above the chart, never in a floating
 *    tooltip.** A tooltip over a 6-column chart on a phone is under the thumb
 *    that summoned it. The readout row is composed unconditionally so selecting
 *    and clearing never changes the block's height.
 *  - **The selection is STICKY.** *Selektieren* is a state, not a hover. Lifting
 *    the finger keeps the month; tapping it again (or the reset control) returns
 *    to the whole-window totals, which is the default.
 *  - **Gold marks the selection, emerald/red keep meaning money direction.** The
 *    bars never change colour — a selected column gets a gold wash behind it, a
 *    gold rule under it and gold ink on its axis label.
 *  - **Dragging is the portfolio chart's gesture, not a second one.** Same
 *    [BtScrubTicker], so the haptic detent per bar crossed has the identical
 *    cadence; the only difference is that this chart keeps what the drag left.
 *
 * @param onOpenMonth given, the readout offers a door into the ledger narrowed
 *   to the selected month. Null hides the affordance rather than showing a dead
 *   one.
 */
@Composable
fun CashTrendsBlock(
    points: List<CashTrendPointDto>,
    locale: Locale,
    modifier: Modifier = Modifier,
    onOpenMonth: ((String) -> Unit)? = null,
) {
    val bt = BtTheme.colors
    if (points.isEmpty()) {
        BtInlineEmpty(
            text = stringResource(R.string.bt_cash_trends_empty),
            modifier = modifier,
        )
        return
    }
    val peak = trendPeak(points)
    val ticker = rememberBtScrubTicker()

    // The month key, not the index — see `CashTrendSelection.kt`. Saveable so a
    // rotation does not silently throw the user's selection away.
    var selectedMonth by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = resolveTrendSelection(points, selectedMonth)
    // A refresh that slid the window past the selected month leaves a stale key
    // behind; drop it so the reset control cannot offer to clear nothing.
    if (selectedMonth != null && selected == null) selectedMonth = null

    val readout = selected ?: trendTotals(points)
    val monthsLabel = pluralStringResource(
        R.plurals.bt_cash_trends_months,
        points.size,
        points.size,
    )

    Column(modifier.fillMaxWidth()) {
        TrendReadout(
            title = selected?.let { trendMonthTitle(it.month, locale) } ?: monthsLabel,
            point = readout,
            locale = locale,
            selectedMonth = selected?.month,
            onClear = { selectedMonth = null },
            onOpenMonth = onOpenMonth,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                // ONE gesture region across the whole chart, so a narrow bar is
                // still selectable: the hit test is nearest-column over the full
                // width and full height (see [trendIndexAt]), which at six months
                // on a 412dp handset is a ~60dp target per month.
                .pointerInput(points) {
                    val report: (Float) -> Unit = { x ->
                        val i = trendIndexAt(x, size.width.toFloat(), points.size)
                        if (i >= 0 && points[i].month != selectedMonth) {
                            selectedMonth = points[i].month
                            ticker.crossed(i, x)
                        }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startX = down.position.x
                        var dragged = false
                        // The DOWN does not select: a tap must be able to toggle
                        // the bar it lands on OFF, and a down-select would have
                        // already turned it on before the up arrived.
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUpIgnoreConsumed()) {
                                change.consume()
                                if (!dragged) {
                                    val i = trendIndexAt(startX, size.width.toFloat(), points.size)
                                    if (i >= 0) {
                                        selectedMonth = toggleTrendMonth(selectedMonth, points[i].month)
                                        ticker.crossed(i, startX)
                                    }
                                }
                                break
                            }
                            if (!change.isConsumed) {
                                val dx = change.position.x - startX
                                if (!dragged && abs(dx) > viewConfiguration.touchSlop) {
                                    dragged = true
                                    ticker.end()
                                }
                                if (dragged) {
                                    // Consuming is load-bearing: it keeps the page's
                                    // LazyColumn and the shell's tab pager from
                                    // stealing a horizontal scrub.
                                    change.consume()
                                    report(change.position.x)
                                }
                            }
                        }
                        ticker.end()
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            points.forEach { p ->
                val isSelected = p.month == selected?.month
                // The whole column is one accessibility node: twelve unlabelled
                // rectangles would otherwise be twelve meaningless stops. It
                // carries the month's actual figures, because "August" alone is
                // exactly the tooltip-less state a screen reader was already in.
                val cd = trendColumnDescription(p, locale, isSelected)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) bt.goldWash else Color.Transparent)
                        .semantics {
                            contentDescription = cd
                            this.selected = isSelected
                        },
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        TrendBar(trendBarFraction(p.inflow, peak), bt.gain, Modifier.weight(1f))
                        TrendBar(trendBarFraction(p.outflow, peak), bt.loss, Modifier.weight(1f))
                    }
                    // The gold rule under the selected column. Always composed,
                    // transparent when unselected, so selecting cannot shift the
                    // bars by 2dp.
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(if (isSelected) bt.goldEmphasis else Color.Transparent),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = trendMonthLabel(p.month, locale),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) bt.goldEmphasis else bt.textMuted,
                        maxLines = 1,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TrendLegend(bt.gain, stringResource(R.string.bt_cash_summary_in))
            Spacer(Modifier.width(14.dp))
            TrendLegend(bt.loss, stringResource(R.string.bt_cash_summary_out))
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.bt_cash_trends_hint),
                style = MaterialTheme.typography.labelSmall,
                color = bt.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The stable figures row above the chart: what the bars under it are worth.
 *
 * Composed in both states with the same geometry, so the readout is a place the
 * eye can return to rather than something that appears and shoves the chart
 * down. When a month is selected the heading names it and gains two gold
 * affordances — clear, and (when the caller offers one) the ledger door.
 */
@Composable
private fun TrendReadout(
    title: String,
    point: CashTrendPointDto,
    locale: Locale,
    selectedMonth: String?,
    onClear: () -> Unit,
    onOpenMonth: ((String) -> Unit)?,
) {
    val bt = BtTheme.colors
    Column(Modifier.fillMaxWidth()) {
        // Fixed height, because the selected state adds two tappable
        // affordances whose padding made this row 12dp taller — which pushed
        // the chart down on the frame the user tapped a bar, i.e. exactly when
        // they were looking at it. The row is sized for the taller state and
        // the idle one centres inside it.
        Row(
            modifier = Modifier.fillMaxWidth().height(34.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (selectedMonth != null) bt.goldEmphasis else bt.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selectedMonth != null) {
                if (onOpenMonth != null) {
                    Text(
                        text = stringResource(R.string.bt_cash_trends_open_month),
                        style = MaterialTheme.typography.labelMedium,
                        color = bt.goldInk,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(BtShapes.pill)
                            .clickable { onOpenMonth(selectedMonth) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = stringResource(R.string.bt_cash_trends_clear),
                    style = MaterialTheme.typography.labelMedium,
                    color = bt.textSecondary,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(BtShapes.pill)
                        .clickable(onClick = onClear)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            val net = trendNet(point)
            SummaryTotal(
                label = stringResource(R.string.bt_cash_summary_in),
                amount = formatEur(point.inflow, locale),
                tint = bt.gain,
                modifier = Modifier.weight(1f),
            )
            SummaryTotal(
                label = stringResource(R.string.bt_cash_summary_out),
                amount = formatEur(point.outflow, locale),
                tint = bt.loss,
                modifier = Modifier.weight(1f),
            )
            SummaryTotal(
                label = stringResource(R.string.bt_cash_summary_net),
                amount = formatEur(net, locale, showSign = true),
                tint = when {
                    net > 0.0 -> bt.gain
                    net < 0.0 -> bt.loss
                    else -> bt.textPrimary
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The spoken form of one month column: name, both magnitudes, and its state. */
@Composable
private fun trendColumnDescription(
    point: CashTrendPointDto,
    locale: Locale,
    isSelected: Boolean,
): String {
    val body = stringResource(
        R.string.bt_cash_trends_bar_cd,
        trendMonthTitle(point.month, locale),
        formatEur(point.inflow, locale),
        formatEur(point.outflow, locale),
        formatEur(trendNet(point), locale, showSign = true),
    )
    return if (isSelected) {
        body + ", " + stringResource(R.string.bt_cash_trends_selected_cd)
    } else {
        body
    }
}

@Composable
private fun TrendBar(fraction: Float, tint: Color, modifier: Modifier = Modifier) {
    val bt = BtTheme.colors
    Box(modifier.fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
        // A hairline base keeps a zero month visible as "nothing happened"
        // rather than as a gap in the chart.
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(bt.border),
        )
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(fraction.coerceAtLeast(0.02f))
                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                    .background(bt.wash(tint, 0.85f)),
            )
        }
    }
}

@Composable
private fun TrendLegend(tint: Color, label: String) {
    val bt = BtTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(7.dp).clip(CircleShape).background(tint))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = bt.textMuted)
    }
}

// ── Shared placeholders ─────────────────────────────────────────────────────

/** Loading placeholder matching the summary's geometry (no layout jump). */
@Composable
fun CashSummarySkeleton(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) { BtSkeleton(Modifier.weight(1f).height(30.dp)) }
        }
        BtSkeleton(Modifier.fillMaxWidth().height(14.dp))
        BtSkeleton(Modifier.fillMaxWidth().height(14.dp))
    }
}

/** Loading placeholder matching the trend chart's geometry. */
@Composable
fun CashTrendsSkeleton(modifier: Modifier = Modifier) {
    BtSkeleton(modifier.fillMaxWidth().height(84.dp))
}

// `CashAnalyticsError` used to live here: a byte-for-byte copy of
// `BtInlineError` (same glyph, tint, spacing, weights and gold retry) whose only
// real difference was taking a raw `String` instead of a typed `BtMessage`. It
// predated the P0-4 typed-message contract, and keeping it meant the two blocks
// on this screen were the last place in the app where a section failure could
// not say what the server said. Both call sites now use the shared component
// directly, with the message the VM stopped discarding.
