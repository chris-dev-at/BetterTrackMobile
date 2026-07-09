package at.bettertrack.app.ui.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.bettertrack.app.data.repo.HistoryPoint
import at.bettertrack.app.ui.components.rememberReducedMotion
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.theme.FONT_FEATURE_TABULAR
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The BetterTrack area chart (spec §3.6) — hand-rolled Compose Canvas in the
 * TradingView lightweight-charts look: thin 2dp line, soft vertical gradient
 * fading to transparent, three recessive gridlines, muted axis labels.
 *
 * The values plotted are ALWAYS server output (§7.1) — this file only maps
 * them to pixels. Range switches morph smoothly between the old and the new
 * series (both resampled onto a common x-grid and lerped in normalized-y
 * space); under reduced motion the new series just appears (§3.7).
 *
 * Touch: a horizontal drag scrubs the series — a thin guide + dot mark the
 * nearest point and [onScrub] reports it so the parent can show value + date
 * (horizontal-only gesture detection keeps the page's vertical scroll alive).
 */
@Composable
fun BtAreaChart(
    points: List<HistoryPoint>,
    modifier: Modifier = Modifier,
    lineColor: Color = BtTheme.colors.gold,
    /**
     * Hero/blend mode: drop ALL axis scaffolding (gridlines, y-labels, x-labels)
     * so the chart reads as a clean full-bleed area that fades into the page.
     * The scrub readout + the surrounding UI carry the exact numbers instead.
     */
    minimal: Boolean = false,
    onScrub: ((HistoryPoint?) -> Unit)? = null,
) {
    val bt = BtTheme.colors
    val reducedMotion = rememberReducedMotion()
    val textMeasurer = rememberTextMeasurer()
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

    // ── Range-transition morph state ────────────────────────────────────────
    val progress = remember { Animatable(1f) }
    var currentPoints by remember { mutableStateOf(points) }
    var previousPoints by remember { mutableStateOf<List<HistoryPoint>>(emptyList()) }
    LaunchedEffect(points) {
        if (points == currentPoints) return@LaunchedEffect
        previousPoints = currentPoints
        currentPoints = points
        if (reducedMotion || previousPoints.size < 2 || points.size < 2) {
            progress.snapTo(1f)
        } else {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(durationMillis = 320, easing = FastOutSlowInEasing))
        }
    }

    // ── Scrub state ─────────────────────────────────────────────────────────
    var scrubX by remember { mutableStateOf<Float?>(null) }
    val onScrubState = rememberUpdatedState(onScrub)

    val labelStyle = TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        color = bt.textMuted,
        fontFeatureSettings = FONT_FEATURE_TABULAR,
    )

    Canvas(
        modifier = modifier.pointerInput(currentPoints) {
            if (onScrub == null || currentPoints.size < 2) return@pointerInput
            detectHorizontalDragGestures(
                onDragStart = { offset -> scrubX = offset.x },
                onDragEnd = {
                    scrubX = null
                    onScrubState.value?.invoke(null)
                },
                onDragCancel = {
                    scrubX = null
                    onScrubState.value?.invoke(null)
                },
                onHorizontalDrag = { change, _ ->
                    change.consume()
                    scrubX = change.position.x
                },
            )
        },
    ) {
        val series = currentPoints
        if (series.size < 2) return@Canvas

        // Reserve a quiet strip for x labels; y labels overlay the plot right.
        // In minimal/hero mode there is no scaffolding, so the plot uses the full
        // height and the gradient fades all the way into the page background.
        val xLabelStrip = if (minimal) 0f else 18.dp.toPx()
        val plotH = size.height - xLabelStrip
        val plotW = size.width

        val scale = yScale(series)
        val morphing = progress.value < 1f && previousPoints.size >= 2

        // ── Gridlines + y labels (min / mid / max of the padded scale) ──────
        if (!minimal) {
            val gridColor = bt.border.copy(alpha = 0.55f)
            // One label format for the whole axis, driven by the scale's magnitude
            // (mixing "15,0k" with "9 440" on one axis reads as two scales).
            val compactAxis = scale.max >= 10_000
            val fractions = listOf(0.0f, 0.5f, 1.0f)
            fractions.forEach { f ->
                val y = plotH * (1f - f)
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(plotW, y),
                    strokeWidth = 1.dp.toPx(),
                )
                val value = scale.min + (scale.max - scale.min) * f
                val text = axisMoney(value, locale, compactAxis)
                val measured = textMeasurer.measure(text, labelStyle)
                // Right-aligned, floated just above its gridline, inset 4dp.
                drawText(
                    textMeasurer = textMeasurer,
                    text = text,
                    style = labelStyle,
                    topLeft = Offset(
                        plotW - measured.size.width - 4.dp.toPx(),
                        (y - measured.size.height - 2.dp.toPx()).coerceAtLeast(0f),
                    ),
                )
            }
        }

        // ── The series: line + gradient fill (morphed while transitioning) ──
        val linePath = Path()
        val fillPath = Path()
        if (morphing) {
            val oldScale = yScale(previousPoints)
            val samples = 120
            for (i in 0..samples) {
                val frac = i / samples.toFloat()
                val oldY = normalizedValueAt(previousPoints, frac, oldScale)
                val newY = normalizedValueAt(series, frac, scale)
                val yNorm = oldY + (newY - oldY) * progress.value
                val x = plotW * frac
                val y = plotH * (1f - yNorm)
                if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
            }
        } else {
            val tMin = series.first().epochDay
            val tMax = series.last().epochDay
            val tSpan = max(1L, tMax - tMin).toFloat()
            series.forEachIndexed { i, p ->
                val x = plotW * ((p.epochDay - tMin) / tSpan)
                val y = plotH * (1f - scale.normalize(p.valueEur))
                if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
            }
        }
        fillPath.addPath(linePath)
        fillPath.lineTo(plotW, plotH)
        fillPath.lineTo(0f, plotH)
        fillPath.close()

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.24f), lineColor.copy(alpha = 0f)),
                startY = 0f,
                endY = plotH,
            ),
        )
        drawPath(
            path = linePath,
            color = lineColor,
            style = Stroke(
                width = 2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )

        // ── x labels: first + last date, muted, in the reserved strip ──────
        if (!minimal) {
            val spanDays = series.last().epochDay - series.first().epochDay
            val startText = formatChartDate(series.first().epochDay, spanDays, locale)
            val endText = formatChartDate(series.last().epochDay, spanDays, locale)
            val startMeasured = textMeasurer.measure(startText, labelStyle)
            val endMeasured = textMeasurer.measure(endText, labelStyle)
            val labelY = size.height - startMeasured.size.height
            drawText(textMeasurer, startText, style = labelStyle, topLeft = Offset(0f, labelY))
            drawText(
                textMeasurer,
                endText,
                style = labelStyle,
                topLeft = Offset(plotW - endMeasured.size.width, labelY),
            )
        }

        // ── Scrub guide + dot ───────────────────────────────────────────────
        val sx = scrubX
        if (sx != null && !morphing) {
            val nearest = nearestPoint(series, sx / plotW)
            onScrubState.value?.invoke(nearest)
            val tMin = series.first().epochDay
            val tSpan = max(1L, series.last().epochDay - tMin).toFloat()
            val px = plotW * ((nearest.epochDay - tMin) / tSpan)
            val py = plotH * (1f - scale.normalize(nearest.valueEur))
            drawLine(
                color = bt.borderStrong,
                start = Offset(px, 0f),
                end = Offset(px, plotH),
                strokeWidth = 1.dp.toPx(),
            )
            // Dot with a surface ring so it reads on top of the line.
            drawCircle(color = bt.surface, radius = 6.dp.toPx(), center = Offset(px, py))
            drawCircle(color = lineColor, radius = 4.dp.toPx(), center = Offset(px, py))
        }
    }
}

// ── Series math (pixel mapping only — never value computation) ──────────────

private class YScale(val min: Double, val max: Double) {
    fun normalize(v: Double): Float =
        if (max == min) 0.5f else ((v - min) / (max - min)).toFloat()
}

/** Padded y-scale: 8% headroom above/below so the line never kisses the edge. */
private fun yScale(points: List<HistoryPoint>): YScale {
    var lo = Double.MAX_VALUE
    var hi = -Double.MAX_VALUE
    points.forEach {
        lo = min(lo, it.valueEur)
        hi = max(hi, it.valueEur)
    }
    if (lo == hi) {
        // Flat series: pad around the value so it renders mid-plot.
        val pad = max(1.0, abs(lo) * 0.05)
        return YScale(lo - pad, hi + pad)
    }
    val pad = (hi - lo) * 0.08
    // An all-positive series never shows a negative axis label — the padding
    // clamps at zero instead of inventing values the data doesn't have.
    val paddedLo = if (lo >= 0.0) max(0.0, lo - pad) else lo - pad
    return YScale(paddedLo, hi + pad)
}

/** Normalized (0..1) series value at x-fraction [frac], time-interpolated. */
private fun normalizedValueAt(points: List<HistoryPoint>, frac: Float, scale: YScale): Float {
    val tMin = points.first().epochDay
    val tMax = points.last().epochDay
    if (tMax == tMin) return scale.normalize(points.last().valueEur)
    val t = tMin + (tMax - tMin) * frac.toDouble()
    var loIdx = 0
    var hiIdx = points.size - 1
    while (hiIdx - loIdx > 1) {
        val mid = (loIdx + hiIdx) / 2
        if (points[mid].epochDay <= t) loIdx = mid else hiIdx = mid
    }
    val a = points[loIdx]
    val b = points[hiIdx]
    val segSpan = (b.epochDay - a.epochDay).toDouble()
    val v = if (segSpan <= 0.0) {
        b.valueEur
    } else {
        a.valueEur + (b.valueEur - a.valueEur) * ((t - a.epochDay) / segSpan)
    }
    return scale.normalize(v)
}

/** The series point whose time is nearest to x-fraction [frac]. */
private fun nearestPoint(points: List<HistoryPoint>, frac: Float): HistoryPoint {
    val tMin = points.first().epochDay
    val tMax = points.last().epochDay
    val t = tMin + (tMax - tMin) * frac.coerceIn(0f, 1f).toDouble()
    return points.minByOrNull { abs(it.epochDay - t) } ?: points.last()
}

// ── Label formatting (display-only) ─────────────────────────────────────────

/**
 * Axis money label. [compact] is decided ONCE per axis from the scale's
 * magnitude: 1,2M · 12,4k (locale separators) — or plain integers otherwise.
 */
internal fun axisMoney(value: Double, locale: Locale, compact: Boolean): String {
    val nf = NumberFormat.getNumberInstance(locale)
    return when {
        abs(value) < 0.5 -> "0"

        compact && abs(value) >= 1_000_000 -> {
            nf.minimumFractionDigits = 1
            nf.maximumFractionDigits = 1
            nf.format(value / 1_000_000) + "M"
        }

        compact -> {
            nf.minimumFractionDigits = 1
            nf.maximumFractionDigits = 1
            nf.format(value / 1_000) + "k"
        }

        else -> {
            nf.maximumFractionDigits = 0
            nf.format(value)
        }
    }
}

/**
 * Axis date: day precision only while the span stays readable at day level
 * ("7 Juni"); beyond ~a quarter the month+full year ("Juli 2026") keeps two
 * same-day endpoints (1Y: 7 Juli → 7 Juli) unambiguous.
 */
internal fun formatChartDate(epochDay: Long, spanDays: Long, locale: Locale): String {
    val date = LocalDate.ofEpochDay(epochDay)
    val pattern = if (spanDays > 95) "MMM yyyy" else "d MMM"
    return date.format(DateTimeFormatter.ofPattern(pattern, locale))
}
