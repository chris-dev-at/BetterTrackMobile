package at.bettertrack.app.ui.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
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
import at.bettertrack.app.ui.util.rememberBtLocale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The BetterTrack asset price chart (§3.6).
 *
 * ## 2026-08-10: rebuilt on [BtAreaChart]'s architecture
 *
 * The owner's verdict on the asset page was *"they look very goofy and too
 * squished and too thick of a line and too small"*, and three of those four were
 * this file rather than its caller:
 *
 *  - **x was laid out by TIME.** The portfolio hero stopped doing that on
 *    2026-08-07 (owner: *"if I select stuff it jumps big time"*) because a
 *    server series is not evenly sampled — an asset's 1D comes back as 1-minute
 *    candles with an overnight hole in the middle, so a constant-speed drag
 *    lurched. x is now laid out by INDEX, exactly like the hero and exactly like
 *    the web's TradingView time scale, which is ordinal.
 *  - **one canvas.** The crosshair shared a canvas with the series, so every
 *    frame of every drag re-rasterised the path, the gradient and the axis text.
 *    Series and crosshair are now two layers; only the second one reads scrub
 *    state.
 *  - **`onScrub` fired from inside the `DrawScope`** — once per frame, forever,
 *    including frames where nothing had changed. It now fires from the gesture
 *    handler and only when the snapped index moves.
 *
 * The fourth — "too thick" — was *ratio*, not stroke: [BtTheme]'s
 * `chartLineWidth` is the same 3dp light / 2dp dark the hero draws, and it read
 * heavy because it was drawing into ~138dp of plot inside a padded card. The
 * caller fixes that by giving the chart the room the hero gets; this file
 * deliberately does NOT introduce a second stroke weight, because two chart
 * geometries in one app is how a design system starts to rot.
 *
 * Values are ALWAYS server closes (§7.1); this file only maps them to pixels.
 * Range switches morph in normalized space; reduced motion snaps.
 */
@Composable
fun BtPriceChart(
    points: List<PricePoint>,
    modifier: Modifier = Modifier,
    lineColor: Color = BtTheme.colors.gold,
    /**
     * Hero mode: no gridlines, no axis labels, plot uses the full height. Same
     * meaning as [BtAreaChart]'s flag of the same name — the surrounding page
     * carries the numbers, and the canvas reads as a shape rather than a figure.
     */
    minimal: Boolean = false,
    /** Transactions to mark on the curve — see [ChartMarker]. */
    markers: List<ChartMarker> = emptyList(),
    /** See [BtAreaChart]'s parameter of the same name. */
    scrimColor: Color = BtTheme.colors.surface,
    onScrub: ((PricePoint?) -> Unit)? = null,
    /**
     * The marker the crosshair is standing on, or null. Lets the page put a
     * transaction's detail on screen while the finger is over its glyph, which is
     * how a marker becomes readable without printing a label per glyph on top of
     * the curve.
     */
    onMarkerFocus: ((List<ChartMarker>) -> Unit)? = null,
) {
    val bt = BtTheme.colors
    val reducedMotion = rememberReducedMotion()
    val textMeasurer = rememberTextMeasurer()
    val locale = rememberBtLocale()
    val ticker = rememberBtScrubTicker()

    // ── Range-transition morph state ────────────────────────────────────────
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

    var scrubIndex by remember { mutableStateOf<Int?>(null) }
    val onScrubState = rememberUpdatedState(onScrub)
    val onMarkerFocusState = rememberUpdatedState(onMarkerFocus)
    LaunchedEffect(currentPoints) {
        scrubIndex = null
        ticker.end()
    }

    val labelStyle = TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        color = bt.chartAxis,
        fontFeatureSettings = FONT_FEATURE_TABULAR,
    )

    // Both are O(n) passes, so they happen once per series rather than per frame.
    val scale = remember(currentPoints) { priceScale(currentPoints) }
    val placed = remember(currentPoints, markers) { placeMarkers(markers, currentPoints) }

    Box(
        modifier = modifier.pointerInput(currentPoints) {
            if (onScrub == null || currentPoints.size < 2) return@pointerInput
            val report: (Float) -> Unit = { x ->
                val i = scrubIndexAt(x, size.width.toFloat(), currentPoints.size)
                if (i != scrubIndex) {
                    scrubIndex = i
                    ticker.crossed(i, x)
                    onScrubState.value?.invoke(currentPoints[i])
                    onMarkerFocusState.value?.invoke(placed.at(i))
                }
            }
            val clear: () -> Unit = {
                scrubIndex = null
                ticker.end()
                onScrubState.value?.invoke(null)
                onMarkerFocusState.value?.invoke(emptyList())
            }
            detectHorizontalDragGestures(
                onDragStart = { offset -> report(offset.x) },
                onDragEnd = clear,
                onDragCancel = clear,
                onHorizontalDrag = { change, _ ->
                    change.consume()
                    report(change.position.x)
                },
            )
        },
    ) {
        // ── Layer 1: series + axis. Reads no scrub state. ───────────────────
        Canvas(modifier = Modifier.matchParentSize()) {
            val series = currentPoints
            if (series.size < 2) return@Canvas

            val xLabelStrip = if (minimal) 0f else 18.dp.toPx()
            val plotH = size.height - xLabelStrip
            val plotW = size.width
            val morphing = progress.value < 1f && previousPoints.size >= 2

            if (!minimal) {
                val compactAxis = scale.max >= 10_000
                listOf(0f, 0.5f, 1f).forEach { f ->
                    val y = plotH * (1f - f)
                    drawLine(bt.chartGrid, Offset(0f, y), Offset(plotW, y), strokeWidth = 1.dp.toPx())
                    val text = priceAxisLabel(
                        scale.min + (scale.max - scale.min) * f,
                        locale,
                        compactAxis,
                    )
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
            }

            val linePath = Path()
            if (morphing) {
                val oldScale = priceScale(previousPoints)
                val samples = 120
                for (i in 0..samples) {
                    val frac = i / samples.toFloat()
                    val oldY = normalizedAtIndex(previousPoints, frac, oldScale)
                    val newY = normalizedAtIndex(series, frac, scale)
                    val yNorm = oldY + (newY - oldY) * progress.value
                    val x = plotW * frac
                    val y = plotH * (1f - yNorm)
                    if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
                }
            } else {
                // One chart language: the asset chart reduces to its own stroke
                // width exactly as the portfolio chart does (owner 2026-08-17,
                // see [chartRenderIndices]). An intraday price series is the
                // densest thing the app draws, so this is the surface that
                // needed it most.
                val visitable = chartRenderIndices(
                    series.map { it.close },
                    columns = (plotW / bt.chartLineWidth.toPx()).toInt(),
                )
                visitable.forEachIndexed { drawn, i ->
                    val p = series[i]
                    val x = seriesX(i, plotW, series.size)
                    val y = plotH * (1f - scale.normalize(p.close))
                    if (drawn == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
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
                    colors = listOf(bt.wash(lineColor, bt.chartAreaTopAlpha), Color.Transparent),
                    startY = 0f,
                    endY = plotH,
                ),
            )
            drawPath(
                path = linePath,
                color = lineColor,
                style = Stroke(
                    width = bt.chartLineWidth.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )

            // Markers sit above the curve and below the crosshair: they are part
            // of the picture, not part of the pointer.
            if (!morphing) {
                placed.clusters.forEach { cluster ->
                    drawMarker(
                        cluster = cluster,
                        x = seriesX(cluster.index, plotW, series.size),
                        y = (plotH * (1f - scale.normalize(cluster.price))).coerceIn(0f, plotH),
                        gain = bt.gain,
                        loss = bt.loss,
                        ring = bt.surface,
                        focused = false,
                    )
                }
            }

            if (!minimal) {
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
            }
        }

        // ── Layer 2: crosshair + future dim. The only reader of scrub state. ─
        Canvas(modifier = Modifier.matchParentSize()) {
            val series = currentPoints
            val i = scrubIndex ?: return@Canvas
            if (series.size < 2 || i !in series.indices) return@Canvas
            if (progress.value < 1f && previousPoints.size >= 2) return@Canvas

            val xLabelStrip = if (minimal) 0f else 18.dp.toPx()
            val plotH = size.height - xLabelStrip
            val p = series[i]
            val x = seriesX(i, size.width, series.size)
            val y = plotH * (1f - scale.normalize(p.close))

            drawScrubFuture(x, plotH, scrimColor.copy(alpha = bt.chartFutureScrimAlpha))
            drawLine(
                color = bt.borderStrong,
                start = Offset(x, 0f),
                end = Offset(x, plotH),
                strokeWidth = 1.dp.toPx(),
            )
            // A marker under the crosshair is redrawn at full strength and one
            // size up: the scrim has just dimmed everything to its right, and a
            // buy the user has deliberately scrubbed onto should not be one of
            // the casualties.
            val slot = markerSlot(i, series.size)
            placed.clusters.filter { it.slot == slot }.forEach { cluster ->
                drawMarker(
                    cluster = cluster,
                    x = seriesX(cluster.index, size.width, series.size),
                    y = (plotH * (1f - scale.normalize(cluster.price))).coerceIn(0f, plotH),
                    gain = bt.gain,
                    loss = bt.loss,
                    ring = bt.surface,
                    focused = true,
                )
            }
            drawCircle(bt.surface, radius = bt.chartLineWidth.toPx() * 3f, center = Offset(x, y))
            drawCircle(lineColor, radius = bt.chartLineWidth.toPx() * 2f, center = Offset(x, y))
        }
    }
}

// ── Series math (pixel mapping only) ────────────────────────────────────────

internal class PriceScale(val min: Double, val max: Double) {
    fun normalize(v: Double): Float =
        if (max == min) 0.5f else ((v - min) / (max - min)).toFloat()
}

/**
 * Padded scale with 8% headroom. The line fills the plot (a stock at €140 sits
 * mid-plot, not pinned to the top of a 0..140 axis) BUT the padded floor never
 * crosses zero — prices are non-negative, so a long range that started near
 * zero must not print a negative axis label.
 */
internal fun priceScale(points: List<PricePoint>): PriceScale {
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

/**
 * Normalized (0..1) close at x-fraction [frac] of the INDEX axis.
 *
 * Only the range-morph animation uses it, and interpolation is legitimate there
 * for the same reason it is in [BtAreaChart]: those 320ms of frames are motion
 * between two server truths, not a readout anybody takes a number off.
 */
private fun normalizedAtIndex(points: List<PricePoint>, frac: Float, scale: PriceScale): Float {
    if (points.isEmpty()) return 0.5f
    if (points.size == 1) return scale.normalize(points[0].close)
    val pos = frac.coerceIn(0f, 1f) * (points.size - 1)
    val lo = pos.toInt().coerceIn(0, points.size - 1)
    val hi = (lo + 1).coerceAtMost(points.size - 1)
    val t = pos - lo
    return scale.normalize(points[lo].close + (points[hi].close - points[lo].close) * t)
}

// ── Label formatting (display-only) ─────────────────────────────────────────

/** Price axis label: compact k/M for big numbers, else 2-decimal locale money. */
private fun priceAxisLabel(value: Double, locale: Locale, compact: Boolean): String {
    if (at.bettertrack.app.ui.format.BtDiscreetMode.masking) {
        return at.bettertrack.app.ui.format.BT_MASKED_PLAIN
    }
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

// ── Markers (drawing only — the math is in ChartMarkers.kt) ─────────────────

/**
 * One cluster's glyph: a triangle pointing the way the trade went, ringed in the
 * page colour so it stays readable where it sits on top of the curve.
 *
 * A triangle rather than a dot because the crosshair is already a dot, and
 * because direction is the whole content of a buy/sell mark — at 9dp a filled
 * arrow says which way it went with no legend, where two coloured dots need one.
 */
private fun DrawScope.drawMarker(
    cluster: MarkerCluster,
    x: Float,
    y: Float,
    gain: Color,
    loss: Color,
    ring: Color,
    focused: Boolean,
) {
    val half = (if (focused) MARKER_HALF_WIDTH_DP * 1.35f else MARKER_HALF_WIDTH_DP).dp.toPx()
    val height = half * 1.7f
    val up = cluster.kind == ChartMarker.Kind.BUY
    // A buy points up and sits BELOW the curve; a sell points down and sits
    // above it. Keeping them off the line is what stops a dense ledger from
    // smothering the very shape the marks are there to comment on.
    val tipY = if (up) y - half * 0.35f else y + half * 0.35f
    val baseY = if (up) tipY + height else tipY - height
    fun glyph(dx: Float, dy: Float) = Path().apply {
        moveTo(x + dx, tipY + dy)
        lineTo(x + dx - half, baseY + dy)
        lineTo(x + dx + half, baseY + dy)
        close()
    }
    // Several trades in one slot draw as a SHINGLE — a second glyph peeking out
    // behind the first. No count badge: at 9dp a numeral is unreadable, and the
    // exact list is one scrub away, which is where a number belongs.
    if (cluster.members.size > 1) {
        val offset = half * 0.55f
        val behind = glyph(-offset, if (up) offset else -offset)
        drawPath(behind, color = ring, style = Stroke(width = MARKER_RING_DP.dp.toPx() * 2f))
        drawPath(behind, color = (if (up) gain else loss).copy(alpha = 0.55f))
    }
    val front = glyph(0f, 0f)
    drawPath(front, color = ring, style = Stroke(width = MARKER_RING_DP.dp.toPx() * 2f))
    drawPath(front, color = if (up) gain else loss)
}

/** Half the base width of a marker glyph. Small enough not to smother the line. */
private const val MARKER_HALF_WIDTH_DP = 4.5f

/** The surface-coloured outline that lifts a glyph off whatever it overlaps. */
private const val MARKER_RING_DP = 1.25f
