package at.bettertrack.app.ui.market

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.bettertrack.app.R
import at.bettertrack.app.data.api.dto.FundamentalsPeriodDto
import at.bettertrack.app.data.api.dto.FundamentalsResponse
import at.bettertrack.app.ui.charts.rememberBtScrubTicker
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.theme.FONT_FEATURE_TABULAR
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * The fundamentals graphic — **revenue against net income, one group per
 * reporting period** (owner order 2026-08-10: *"yearly quarterly reports and nice
 * graphics"*; platform arc f, board #76 item 1).
 *
 * ## Why these two lines and not the other ten
 *
 * The wire carries twelve statement figures per period. Drawing all of them would
 * produce a chart nobody reads; drawing the two that answer the question people
 * actually open a fundamentals tab with — *is this company growing, and does the
 * growth reach the bottom line?* — produces one they do. Revenue is the top line
 * and net income is the bottom line, and the visible GAP between the two bars is
 * the margin, which is the third fact, drawn for free. The remaining figures are
 * either in the ratio row above or are balance-sheet items that do not belong on
 * a time series of results.
 *
 * ## One shared axis, on purpose
 *
 * Net income is a small fraction of revenue, so its bar is short. That is not a
 * scaling flaw to normalise away — it IS the margin, and a dual axis that made
 * the two bars the same height would be actively lying about the relationship
 * the chart exists to show. Both series therefore share one zero-anchored scale.
 *
 * A negative net income (a loss-making year) crosses the baseline and wears the
 * loss colour, which is the one case where the sign matters more than the
 * magnitude — hence the zero anchor rather than a fitted window.
 */

// ═══════════════════════ Pure display logic (unit-tested) ═══════════════════

/** One group of the chart: a reporting period's top and bottom line. */
data class FundamentalsBar(
    /** `"FY"` for an annual row, `"Q1".."Q4"` for a quarterly one. */
    val fiscalPeriod: String,
    val fiscalYear: Int?,
    val revenue: Double?,
    val netIncome: Double?,
)

/**
 * How many periods the chart draws. The endpoint clamps to 12; eight is what a
 * phone-width canvas can give a legible pair of bars each, and eight years or
 * eight quarters is already a trend rather than a snapshot.
 */
const val FUNDAMENTALS_CHART_CAP = 8

/**
 * The bars, **oldest first** — the wire is most-recent-first, and a time axis
 * that runs right-to-left is a chart nobody can read.
 *
 * A period with neither figure is dropped: an empty group reads as "they earned
 * nothing that year", which is not what a missing provider field means. This is
 * the same rule [earningsChartBars] applies, for the same reason.
 */
fun fundamentalsChartBars(
    response: FundamentalsResponse,
    cap: Int = FUNDAMENTALS_CHART_CAP,
): List<FundamentalsBar> {
    fun bar(period: FundamentalsPeriodDto): FundamentalsBar? {
        val revenue = period.revenue?.takeIf { it.isFinite() }
        val netIncome = period.netIncome?.takeIf { it.isFinite() }
        if (revenue == null && netIncome == null) return null
        return FundamentalsBar(period.fiscalPeriod, period.fiscalYear, revenue, netIncome)
    }
    // `take` before reversing: the wire's head is the MOST RECENT, so the cap has
    // to bite the far end of history, not the near one.
    return response.periods.take(cap).mapNotNull { bar(it) }.reversed()
}

/**
 * The y-window, always including zero — see the file KDoc on why the scale is
 * anchored rather than fitted.
 */
fun fundamentalsChartScale(bars: List<FundamentalsBar>): ClosedFloatingPointRange<Double> {
    var lo = 0.0
    var hi = 0.0
    bars.forEach { bar ->
        listOfNotNull(bar.revenue, bar.netIncome).forEach {
            lo = min(lo, it)
            hi = max(hi, it)
        }
    }
    if (lo == 0.0 && hi == 0.0) return -1.0..1.0
    val pad = (hi - lo) * 0.12
    return (lo - if (lo < 0.0) pad else 0.0)..(hi + if (hi > 0.0) pad else 0.0)
}

/** Does this chart have enough to be worth drawing at all? */
fun fundamentalsChartWorthDrawing(bars: List<FundamentalsBar>): Boolean =
    bars.count { it.revenue != null || it.netIncome != null } >= 2

/**
 * The axis tick under a group: a two-digit year for an annual period, the bare
 * quarter for a quarterly one.
 *
 * Deliberately terse — eight groups share a phone width, and the SELECTED
 * period's full label is spelled out in the readout above the chart, so nothing
 * is actually lost by abbreviating the axis. A year with no `fiscalYear` falls
 * back to the fiscal-period token rather than rendering an empty tick.
 */
fun fundamentalsAxisLabel(bar: FundamentalsBar): String {
    val quarterly = bar.fiscalPeriod.startsWith("Q")
    if (quarterly) return bar.fiscalPeriod
    val year = bar.fiscalYear ?: return bar.fiscalPeriod
    return "'" + (year % 100).toString().padStart(2, '0')
}

/** Clamp a horizontal touch to the group under it. */
internal fun fundamentalsIndexAt(x: Float, width: Float, count: Int): Int {
    if (count <= 0 || width <= 0f) return 0
    val groupW = width / count
    return (x / groupW).toInt().coerceIn(0, count - 1)
}

// ═══════════════════════════════ The drawing ════════════════════════════════

/** Column height for the fundamentals bars. */
val FUNDAMENTALS_CHART_HEIGHT = 148.dp

/**
 * Grouped bars with a scrubbable selection.
 *
 * The chart is scrubbable for one concrete reason: there are NO value labels on
 * the canvas at all, so a period's figures exist only in the readout above it.
 * Dragging moves the selection and the readout spells the period out — the same
 * bargain the price chart makes, and the reason both use [rememberBtScrubTicker]
 * rather than each rolling their own haptic throttle.
 *
 * Tapping selects too. A bar chart of eight groups is a set of targets, and
 * making the user drag to reach one they can already see is a gesture tax.
 */
@Composable
fun FundamentalsChart(
    bars: List<FundamentalsBar>,
    locale: Locale,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    val measurer = rememberTextMeasurer()
    val ticker = rememberBtScrubTicker()
    val scale = remember(bars) { fundamentalsChartScale(bars) }
    val labels = remember(bars) { bars.map { fundamentalsAxisLabel(it) } }
    val axisCd = stringResource(R.string.bt_fundamentals_chart_cd)
    val labelStyle = TextStyle(
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
        color = bt.chartAxis,
        fontFeatureSettings = FONT_FEATURE_TABULAR,
    )

    Canvas(
        modifier
            .semantics { contentDescription = axisCd }
            .pointerInput(bars.size) {
                detectTapGestures { offset ->
                    val i = fundamentalsIndexAt(offset.x, size.width.toFloat(), bars.size)
                    if (i != selectedIndex) onSelect(i)
                }
            }
            .pointerInput(bars.size) {
                detectHorizontalDragGestures(
                    onDragEnd = { ticker.end() },
                    onDragCancel = { ticker.end() },
                    onDragStart = { offset ->
                        val i = fundamentalsIndexAt(offset.x, size.width.toFloat(), bars.size)
                        ticker.crossed(i, offset.x)
                        if (i != selectedIndex) onSelect(i)
                    },
                ) { change, _ ->
                    // Load-bearing: without it the enclosing LazyColumn steals the
                    // drag and the chart scrubs for about four pixels.
                    change.consume()
                    val i = fundamentalsIndexAt(
                        change.position.x,
                        size.width.toFloat(),
                        bars.size,
                    )
                    ticker.crossed(i, change.position.x)
                    if (i != selectedIndex) onSelect(i)
                }
            },
    ) {
        if (bars.isEmpty()) return@Canvas
        val labelStrip = 14.dp.toPx()
        val plotH = size.height - labelStrip
        if (plotH <= 0f) return@Canvas

        val span = (scale.endInclusive - scale.start).takeIf { it > 0.0 } ?: 1.0
        fun y(value: Double): Float =
            (plotH * (1.0 - (value - scale.start) / span)).toFloat().coerceIn(0f, plotH)

        val zeroY = y(0.0)
        val groupW = size.width / bars.size
        val gap = 3.dp.toPx()
        val barW = ((groupW - gap) / 2f - 5.dp.toPx()).coerceAtLeast(4.dp.toPx())
        val radius = CornerRadius(2.dp.toPx(), 2.dp.toPx())

        // The selection band first, so every bar reads on top of it.
        //
        // A BRAND WASH, not `surfaceHigh`: the first cut used the neutral surface
        // token and was invisible on the card it sits inside — the readout above
        // changed on tap while the chart gave no sign of which group had been
        // picked. `wash` is mode-aware, so one alpha reads correctly on both
        // themes without a light/dark branch here.
        if (selectedIndex in bars.indices) {
            drawRoundRect(
                color = bt.wash(bt.gold, 0.16f),
                topLeft = Offset(groupW * selectedIndex, 0f),
                size = Size(groupW, plotH),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            )
        }

        // NO y-axis label here, deliberately. The obvious version of this chart
        // floats the scale's ceiling at the top right — but the ceiling is the
        // data max plus 12 % headroom, so it renders a number the company never
        // reported ("466,1 Mrd." over a best year of 416,2 Mrd.), positioned on a
        // gridline that isn't drawn. Labelling the true max instead would put a
        // real number at the wrong height. The readout directly above the chart
        // already states the selected period's figures exactly, which is the job
        // an axis label would have been doing badly.

        fun bar(centerX: Float, value: Double, color: Color) {
            val top = min(y(value), zeroY)
            val bottom = max(y(value), zeroY)
            // A figure that rounds onto the baseline still gets a sliver, so
            // "broke even" never looks like "did not report".
            val h = (bottom - top).coerceAtLeast(1.5.dp.toPx())
            drawRoundRect(
                color = color,
                topLeft = Offset(centerX - barW / 2f, top),
                size = Size(barW, h),
                cornerRadius = radius,
            )
        }

        bars.forEachIndexed { i, item ->
            val center = groupW * (i + 0.5f)
            val leftX = center - (barW + gap) / 2f
            val rightX = center + (barW + gap) / 2f
            item.revenue?.let { bar(leftX, it, bt.chartRest) }
            item.netIncome?.let {
                bar(rightX, it, if (it < 0.0) bt.loss else bt.gain)
            }

            val text = labels[i]
            val measured = measurer.measure(text, labelStyle)
            drawText(
                textMeasurer = measurer,
                text = text,
                style = labelStyle,
                topLeft = Offset(
                    (center - measured.size.width / 2f).coerceIn(
                        0f,
                        (size.width - measured.size.width).coerceAtLeast(0f),
                    ),
                    size.height - measured.size.height,
                ),
            )
        }

        // The baseline last, so it reads on top of every bar that crosses it.
        drawLine(
            color = bt.border,
            start = Offset(0f, zeroY),
            end = Offset(size.width, zeroY),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

/** The chart's key: which bar is the top line and which is the bottom line. */
@Composable
fun FundamentalsChartLegend(modifier: Modifier = Modifier) {
    val bt = BtTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        FundamentalsSwatch(bt.chartRest)
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.bt_fundamentals_revenue),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
        )
        Spacer(Modifier.width(14.dp))
        FundamentalsSwatch(bt.gain)
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.bt_fundamentals_net_income),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
        )
    }
}

@Composable
private fun FundamentalsSwatch(color: Color) {
    Box(Modifier.size(width = 8.dp, height = 10.dp).background(color, RoundedCornerShape(2.dp)))
}
