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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.bettertrack.app.data.repo.PricePoint
import at.bettertrack.app.ui.components.rememberReducedMotion
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.theme.FONT_FEATURE_TABULAR
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The BetterTrack asset price chart (§3.6) — same visual family as
 * [BtAreaChart] (thin gold line, soft gradient, recessive gridlines, muted
 * labels) but the x-axis is epoch-MILLIS so intraday ranges (1D/1W) render
 * correctly. Values are ALWAYS server closes (§7.1); this file only maps them
 * to pixels. Range switches morph in normalized space; reduced motion snaps.
 *
 * A horizontal drag scrubs the series and reports the nearest point via
 * [onScrub] (horizontal-only so the page keeps scrolling vertically).
 */
@Composable
fun BtPriceChart(
    points: List<PricePoint>,
    modifier: Modifier = Modifier,
    lineColor: Color = BtTheme.colors.gold,
    onScrub: ((PricePoint?) -> Unit)? = null,
) {
    val bt = BtTheme.colors
    val reducedMotion = rememberReducedMotion()
    val textMeasurer = rememberTextMeasurer()
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

    val progress = remember { Animatable(1f) }
    var currentPoints by remember { mutableStateOf(points) }
    var previousPoints by remember { mutableStateOf<List<PricePoint>>(emptyList()) }
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

        val xLabelStrip = 18.dp.toPx()
        val plotH = size.height - xLabelStrip
        val plotW = size.width

        val scale = priceScale(series)
        val morphing = progress.value < 1f && previousPoints.size >= 2

        // Gridlines + y (price) labels.
        val gridColor = bt.border.copy(alpha = 0.55f)
        val compactAxis = scale.max >= 10_000
        listOf(0f, 0.5f, 1f).forEach { f ->
            val y = plotH * (1f - f)
            drawLine(gridColor, Offset(0f, y), Offset(plotW, y), strokeWidth = 1.dp.toPx())
            val value = scale.min + (scale.max - scale.min) * f
            val text = priceAxisLabel(value, locale, compactAxis)
            val measured = textMeasurer.measure(text, labelStyle)
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

        // Series line + gradient fill (morphed while transitioning).
        val linePath = Path()
        if (morphing) {
            val oldScale = priceScale(previousPoints)
            val samples = 120
            for (i in 0..samples) {
                val frac = i / samples.toFloat()
                val oldY = normalizedAt(previousPoints, frac, oldScale)
                val newY = normalizedAt(series, frac, scale)
                val yNorm = oldY + (newY - oldY) * progress.value
                val x = plotW * frac
                val y = plotH * (1f - yNorm)
                if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
            }
        } else {
            val tMin = series.first().timeMs
            val tMax = series.last().timeMs
            val tSpan = max(1L, tMax - tMin).toFloat()
            series.forEachIndexed { i, p ->
                val x = plotW * ((p.timeMs - tMin) / tSpan)
                val y = plotH * (1f - scale.normalize(p.close))
                if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
            }
        }
        val fillPath = Path().apply {
            addPath(linePath)
            lineTo(plotW, plotH)
            lineTo(0f, plotH)
            close()
        }
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
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // x labels: first + last timestamp, muted.
        val spanMs = series.last().timeMs - series.first().timeMs
        val startText = formatPriceTime(series.first().timeMs, spanMs, locale)
        val endText = formatPriceTime(series.last().timeMs, spanMs, locale)
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

        // Scrub guide + dot.
        val sx = scrubX
        if (sx != null && !morphing) {
            val nearest = nearestPoint(series, sx / plotW)
            onScrubState.value?.invoke(nearest)
            val tMin = series.first().timeMs
            val tSpan = max(1L, series.last().timeMs - tMin).toFloat()
            val px = plotW * ((nearest.timeMs - tMin) / tSpan)
            val py = plotH * (1f - scale.normalize(nearest.close))
            drawLine(bt.borderStrong, Offset(px, 0f), Offset(px, plotH), strokeWidth = 1.dp.toPx())
            drawCircle(bt.surface, radius = 6.dp.toPx(), center = Offset(px, py))
            drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(px, py))
        }
    }
}

// ── Series math (pixel mapping only) ────────────────────────────────────────

private class PriceScale(val min: Double, val max: Double) {
    fun normalize(v: Double): Float =
        if (max == min) 0.5f else ((v - min) / (max - min)).toFloat()
}

/**
 * Padded scale with 8% headroom. The line fills the plot (a stock at €140 sits
 * mid-plot, not pinned to the top of a 0..140 axis) BUT the padded floor never
 * crosses zero — prices are non-negative, so a long range that started near
 * zero must not print a negative axis label.
 */
private fun priceScale(points: List<PricePoint>): PriceScale {
    var lo = Double.MAX_VALUE
    var hi = -Double.MAX_VALUE
    points.forEach {
        lo = min(lo, it.close)
        hi = max(hi, it.close)
    }
    if (lo == hi) {
        val pad = max(0.01, abs(lo) * 0.05)
        return PriceScale(if (lo >= 0.0) max(0.0, lo - pad) else lo - pad, hi + pad)
    }
    val pad = (hi - lo) * 0.08
    val paddedLo = if (lo >= 0.0) max(0.0, lo - pad) else lo - pad
    return PriceScale(paddedLo, hi + pad)
}

private fun normalizedAt(points: List<PricePoint>, frac: Float, scale: PriceScale): Float {
    val tMin = points.first().timeMs
    val tMax = points.last().timeMs
    if (tMax == tMin) return scale.normalize(points.last().close)
    val t = tMin + (tMax - tMin) * frac.toDouble()
    var loIdx = 0
    var hiIdx = points.size - 1
    while (hiIdx - loIdx > 1) {
        val mid = (loIdx + hiIdx) / 2
        if (points[mid].timeMs <= t) loIdx = mid else hiIdx = mid
    }
    val a = points[loIdx]
    val b = points[hiIdx]
    val segSpan = (b.timeMs - a.timeMs).toDouble()
    val v = if (segSpan <= 0.0) b.close else a.close + (b.close - a.close) * ((t - a.timeMs) / segSpan)
    return scale.normalize(v)
}

private fun nearestPoint(points: List<PricePoint>, frac: Float): PricePoint {
    val tMin = points.first().timeMs
    val tMax = points.last().timeMs
    val t = tMin + (tMax - tMin) * frac.coerceIn(0f, 1f).toDouble()
    return points.minByOrNull { abs(it.timeMs - t) } ?: points.last()
}

// ── Label formatting (display-only) ─────────────────────────────────────────

/** Price axis label: compact k/M for big numbers, else 2-decimal locale money. */
private fun priceAxisLabel(value: Double, locale: Locale, compact: Boolean): String {
    val nf = java.text.NumberFormat.getNumberInstance(locale)
    return when {
        compact && abs(value) >= 1_000_000 -> {
            nf.minimumFractionDigits = 1; nf.maximumFractionDigits = 1
            nf.format(value / 1_000_000) + "M"
        }

        compact -> {
            nf.minimumFractionDigits = 1; nf.maximumFractionDigits = 1
            nf.format(value / 1_000) + "k"
        }

        else -> {
            nf.minimumFractionDigits = 2; nf.maximumFractionDigits = 2
            nf.format(value)
        }
    }
}

/** x label: intraday shows time (HH:mm), short spans "d MMM", long "MMM yyyy". */
private fun formatPriceTime(timeMs: Long, spanMs: Long, locale: Locale): String {
    val zone = ZoneId.systemDefault()
    val dt = Instant.ofEpochMilli(timeMs).atZone(zone)
    val oneDay = 36L * 60 * 60 * 1000
    val ninetyFiveDays = 95L * 24 * 60 * 60 * 1000
    val pattern = when {
        spanMs <= oneDay -> "HH:mm"
        spanMs <= ninetyFiveDays -> "d MMM"
        else -> "MMM yyyy"
    }
    return dt.format(DateTimeFormatter.ofPattern(pattern, locale))
}
