package at.bettertrack.app.ui.market

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.bettertrack.app.R
import at.bettertrack.app.data.api.dto.EarningsEventDto
import at.bettertrack.app.data.api.dto.EarningsResponse
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.theme.FONT_FEATURE_TABULAR
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The earnings graphic (owner order 2026-08-10: *"more info for earnings. like
 * yearly quarterly reports and nice graphics"*).
 *
 * ## What this can and cannot show, and why
 *
 * The platform serves **EPS only** — `GET /assets/{id}/intel/earnings` returns a
 * report date, an EPS estimate and an EPS actual per period, and nothing else.
 * There is no revenue, no income statement, no fiscal-period label and no annual
 * roll-up anywhere in the API: the Yahoo provider's module whitelist does not
 * even fetch them, so they are not sitting unused behind the route either.
 *
 * So this draws the thing that exists, properly: **estimate against actual, one
 * group per report, in report order** — which is exactly the question an EPS
 * series answers ("does this company beat its guidance, and is it getting better
 * or worse at it?"). It does not aggregate quarters into years, because summing
 * four EPS figures is a calculation the server did not do and §7.1 says the app
 * does not do those. The missing half is a platform ask, not a thing to fake.
 *
 * Periods are labelled by REPORT MONTH, not "Q1/Q2": the wire has a date and no
 * fiscal calendar, and deriving a fiscal quarter from a report date is a guess
 * that is wrong for every company whose year does not start in January.
 */

// ═══════════════════════ Pure display logic (unit-tested) ═══════════════════

/** One group of the chart: what was expected, what came in, and the verdict. */
data class EarningsBar(
    val timeMs: Long,
    val estimate: Double?,
    val actual: Double?,
    /** True for the not-yet-reported period, which has no actual by definition. */
    val upcoming: Boolean,
)

/** How many report periods the chart draws before it stops. */
const val EARNINGS_CHART_CAP = 6

/**
 * The bars, oldest first, with the next scheduled report last.
 *
 * A period with neither number is dropped — an empty group is a gap in a chart
 * that reads as "they reported nothing", which is not what a missing provider
 * field means. A period with no date is dropped too: it has no place on a time
 * axis, and this axis is ordered.
 */
fun earningsChartBars(
    response: EarningsResponse,
    cap: Int = EARNINGS_CHART_CAP,
    time: (String?) -> Long? = { intelTimeMs(it) },
): List<EarningsBar> {
    fun bar(event: EarningsEventDto, upcoming: Boolean): EarningsBar? {
        val t = time(event.date) ?: return null
        val estimate = event.epsEstimate?.takeIf { it.isFinite() }
        val actual = event.epsActual?.takeIf { it.isFinite() }
        if (estimate == null && actual == null) return null
        return EarningsBar(t, estimate, actual, upcoming)
    }

    val past = response.recent.mapNotNull { bar(it, upcoming = false) }
        .sortedBy { it.timeMs }
        .takeLast(cap)
    val next = response.next?.let { bar(it, upcoming = true) }
        // A "next" the provider dates in the past, or one already present in the
        // history, would draw a second bar for a period the chart already has.
        ?.takeIf { n -> past.none { it.timeMs == n.timeMs } }
    return past + listOfNotNull(next)
}

/**
 * The y-window, always including zero.
 *
 * A loss-making quarter is negative EPS and the sign is the whole point, so the
 * axis is anchored at zero rather than fitted to the data — bars have to grow
 * from a common baseline or their lengths mean nothing.
 */
fun earningsChartScale(bars: List<EarningsBar>): ClosedFloatingPointRange<Double> {
    var lo = 0.0
    var hi = 0.0
    bars.forEach { bar ->
        listOfNotNull(bar.estimate, bar.actual).forEach {
            lo = min(lo, it)
            hi = max(hi, it)
        }
    }
    if (lo == 0.0 && hi == 0.0) return -1.0..1.0
    val pad = (hi - lo) * 0.12
    return (lo - if (lo < 0.0) pad else 0.0)..(hi + if (hi > 0.0) pad else 0.0)
}

/** Does this chart have enough to be worth drawing at all? */
fun earningsChartWorthDrawing(bars: List<EarningsBar>): Boolean =
    bars.count { it.estimate != null || it.actual != null } >= 2

// ═══════════════════════════════ The drawing ════════════════════════════════

/**
 * Grouped bars: the muted one is what the street expected, the coloured one is
 * what the company delivered.
 *
 * Two bars side by side rather than one overlaid on the other: an overlay reads
 * as a progress bar ("83 % of target"), and a quarter is not a target it partly
 * reached — the two numbers are peers, and peers stand next to each other. The
 * actual wears the verdict colour so the beat/miss pattern is legible as a shape
 * across the whole row without reading a single number.
 */
@Composable
fun EarningsChart(bars: List<EarningsBar>, locale: Locale, modifier: Modifier = Modifier) {
    val bt = BtTheme.colors
    val measurer = rememberTextMeasurer()
    val scale = remember(bars) { earningsChartScale(bars) }
    val labelStyle = TextStyle(
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
        color = bt.chartAxis,
        fontFeatureSettings = FONT_FEATURE_TABULAR,
    )
    val labels = remember(bars, locale) { bars.map { earningsPeriodLabel(it.timeMs, locale) } }

    Canvas(modifier) {
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
        val barW = ((groupW - gap) / 2f - 6.dp.toPx()).coerceAtLeast(4.dp.toPx())
        val radius = CornerRadius(2.dp.toPx(), 2.dp.toPx())

        fun bar(centerX: Float, value: Double, color: Color) {
            val top = min(y(value), zeroY)
            val bottom = max(y(value), zeroY)
            // A bar for a value that rounds to the baseline still gets a sliver,
            // so "reported, and it was ~0" never looks like "did not report".
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
            item.estimate?.let { bar(leftX, it, bt.chartRest) }
            item.actual?.let {
                val surprise = intelEarningsSurprise(item.estimate, it)
                bar(
                    rightX,
                    it,
                    when {
                        surprise == null || surprise == 0 -> bt.chartSeries.first()
                        surprise > 0 -> bt.gain
                        else -> bt.loss
                    },
                )
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

/** "May 26" — report month and year, in the reader's locale. See the file KDoc. */
internal fun earningsPeriodLabel(timeMs: Long, locale: Locale): String =
    Instant.ofEpochMilli(timeMs)
        .atZone(ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("MMM yy", locale))

/** The chart's key: which bar is the expectation and which is the result. */
@Composable
fun EarningsChartLegend(modifier: Modifier = Modifier) {
    val bt = BtTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        LegendSwatch(bt.chartRest)
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.bt_intel_eps_estimate),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
        )
        Spacer(Modifier.width(14.dp))
        // Two-tone, because the reported bar has no single colour: it wears the
        // verdict. A one-colour swatch here would have to pick either green or
        // red and would then contradict half the chart — which is exactly what a
        // neutral-blue swatch did next to four green beats.
        Box(
            Modifier
                .size(width = 8.dp, height = 10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    Brush.verticalGradient(
                        0f to bt.gain,
                        0.5f to bt.gain,
                        0.5f to bt.loss,
                        1f to bt.loss,
                    ),
                ),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.bt_intel_eps_actual),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
        )
    }
}

@Composable
private fun LegendSwatch(color: Color) {
    Box(Modifier.size(width = 8.dp, height = 10.dp).background(color, RoundedCornerShape(2.dp)))
}

/** Column height for the earnings bars. Tall enough that a small beat is visible. */
val EARNINGS_CHART_HEIGHT = 132.dp

/**
 * An EPS figure as a reader wants it: two decimals.
 *
 * The rows used to render these through `formatQuantity`, which is the ASSET
 * QUANTITY formatter — up to eight decimals, because a holding can be 0.06251
 * BTC. Applied to a provider's EPS estimate it printed *"EPS-Schätzung 4,71331"*,
 * which is five decimals of false precision on a number nobody trades in and the
 * exact kind of clutter the owner meant by wanting this section cleaner. Cents
 * per share is the unit; the provider's extra digits are its regression fit, not
 * a company's result.
 */
fun formatEps(value: Double, locale: Locale): String {
    val nf = java.text.NumberFormat.getNumberInstance(locale)
    nf.minimumFractionDigits = 2
    nf.maximumFractionDigits = 2
    return nf.format(value)
}

/** Guard against a chart of one bar pretending to be a trend. */
internal fun earningsBarSpread(bars: List<EarningsBar>): Double =
    bars.mapNotNull { it.actual ?: it.estimate }.let { values ->
        if (values.size < 2) 0.0 else abs(values.max() - values.min())
    }
