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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.bettertrack.app.data.repo.HistoryPoint
import at.bettertrack.app.data.repo.MILLIS_PER_DAY
import at.bettertrack.app.ui.components.rememberReducedMotion
import at.bettertrack.app.ui.format.BT_MASKED_PLAIN
import at.bettertrack.app.ui.format.BtDiscreetMode
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.theme.FONT_FEATURE_TABULAR
import at.bettertrack.app.ui.util.rememberBtLocale
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The BetterTrack area chart (spec §3.6) — hand-rolled Compose Canvas in the
 * TradingView lightweight-charts look: thin 2dp line, soft vertical gradient
 * fading to transparent, three recessive gridlines, muted axis labels.
 *
 * The values plotted are ALWAYS server output (§7.1) — this file only maps
 * them to pixels.
 *
 * ## x is laid out by INDEX, not by time (2026-08-07, owner "it feels choppy")
 *
 * Points used to be placed proportionally to `epochMillis`. That is the honest
 * mapping for a time axis, and it is why scrubbing felt broken: the server's
 * series is not evenly sampled — recent ranges come back dense and sub-daily,
 * older stretches daily, and a market close is a hole — so adjacent points could
 * be 2px apart in one stretch and 300px apart in the next. Dragging a finger at
 * constant speed therefore moved the crosshair in wildly uneven lurches, which
 * is exactly the owner's *"if I select stuff it jumps big time"*.
 *
 * The web app does not have this problem, and reading why settled the fix: its
 * chart is TradingView `lightweight-charts`, whose time scale is **ordinal** —
 * `barSpacing = width / n` and point *i* sits at index *i*, so every point owns
 * an equal-width slice of the canvas and the crosshair advances in uniform
 * steps. That is the "properly displays and feels great" the owner compared us
 * against, so the app now lays out the same way. The visible consequence is the
 * one TradingView users already expect: a weekend or an overnight close is one
 * step wide like any other, not a wide diagonal ramp.
 *
 * ## The readout SNAPS to a real point, and that is deliberate
 *
 * The crosshair, the dot and the reported value are all a genuine
 * [HistoryPoint] — never a value interpolated between two of them. Web does the
 * same (lightweight-charts' default `CrosshairMode.Magnet` locks the readout onto
 * the series value at the snapped index), and for this app it is also a §7.1
 * requirement: an interpolated balance is a number the server never computed, and
 * this app does not invent money. Uniform spacing is what makes snapping feel
 * smooth; interpolation would have made it feel smooth by lying.
 *
 * ## Scrubbing does not recompose per frame any more
 *
 * The scrub callback used to fire from inside the `DrawScope`, on every frame of
 * every drag. It now fires from the gesture handler, and only when the snapped
 * INDEX actually changes — so a drag across one point's cell costs zero
 * recompositions instead of one per frame.
 *
 * Touch: a horizontal drag scrubs the series (horizontal-only detection keeps the
 * page's vertical scroll alive, and consuming the drag is what makes scrubbing
 * beat the shell's tab swipe on the chart area — see `btTabSwipe`). Web enters
 * scrub on a 240ms long-press instead, because a browser cannot let a child claim
 * a drag from the page; Compose can, so the app keeps the cheaper gesture.
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
    /**
     * Zero-baseline **geometry** for a performance-% series: the y-scale is
     * anchored to zero, a zero rule line is drawn, and the area fill is mirrored
     * about it — strongest at the top for the part above zero, strongest at the
     * bottom for the part below, faint where they meet.
     *
     * This says nothing about colour. See [colorBySign], which used to be the
     * same flag and no longer is.
     */
    baseline: Boolean = false,
    /**
     * Paint the curve emerald above zero and red below it, rather than in
     * [lineColor].
     *
     * Split out of [baseline] by owner order 2026-08-07. The two were one flag,
     * which forced any chart wanting a zero baseline to also accept a gain/loss
     * verdict — and that is wrong for the hero's hybrid mode, whose headline is
     * the € balance while the curve is the % return: coloring it by sign states
     * a verdict about a quantity the user is not reading.
     *
     * Defaults to [baseline] so every existing call site keeps its behaviour.
     */
    colorBySign: Boolean = baseline,
    onScrub: ((HistoryPoint?) -> Unit)? = null,
) {
    val bt = BtTheme.colors
    val reducedMotion = rememberReducedMotion()
    val textMeasurer = rememberTextMeasurer()
    val locale = rememberBtLocale()

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
    // The INDEX, not the point: it is what the gesture computes, what the draw
    // needs, and comparing it is how a drag inside one point's cell avoids
    // recomposing at all.
    var scrubIndex by remember { mutableStateOf<Int?>(null) }
    val onScrubState = rememberUpdatedState(onScrub)
    LaunchedEffect(currentPoints) { scrubIndex = null }

    val labelStyle = TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        color = bt.chartAxis,
        fontFeatureSettings = FONT_FEATURE_TABULAR,
    )

    // The y-scale is an O(n) pass over the series, so it is computed ONCE per
    // series rather than per frame — the crosshair layer needs it too.
    val scale = remember(currentPoints, baseline) { yScale(currentPoints, zeroAnchored = baseline) }

    Box(
        modifier = modifier.pointerInput(currentPoints) {
            if (onScrub == null || currentPoints.size < 2) return@pointerInput
            val report: (Float) -> Unit = { x ->
                val i = scrubIndexAt(x, size.width.toFloat(), currentPoints.size)
                if (i != scrubIndex) {
                    scrubIndex = i
                    onScrubState.value?.invoke(currentPoints[i])
                }
            }
            val clear: () -> Unit = {
                scrubIndex = null
                onScrubState.value?.invoke(null)
            }
            detectHorizontalDragGestures(
                onDragStart = { offset -> report(offset.x) },
                onDragEnd = clear,
                onDragCancel = clear,
                onHorizontalDrag = { change, _ ->
                    // Consuming is load-bearing twice over: it keeps the parent
                    // LazyColumn from stealing the drag, and it makes the shell's
                    // tab swipe stand down over the chart.
                    change.consume()
                    report(change.position.x)
                },
            )
        },
    ) {
    // ── Layer 1: the series. Reads NO scrub state, so moving the crosshair
    // never re-rasterises the path, the gradients or the axis text. This is the
    // single biggest reason scrubbing used to feel heavy, and it is what the web
    // gets for free — lightweight-charts paints its crosshair on a separate top
    // canvas for exactly this reason.
    Canvas(modifier = Modifier.matchParentSize()) {
        val series = currentPoints
        if (series.size < 2) return@Canvas

        // Reserve a quiet strip for x labels; y labels overlay the plot right.
        // In minimal/hero mode there is no scaffolding, so the plot uses the full
        // height and the gradient fades all the way into the page background.
        val xLabelStrip = if (minimal) 0f else 18.dp.toPx()
        val plotH = size.height - xLabelStrip
        val plotW = size.width

        val morphing = progress.value < 1f && previousPoints.size >= 2

        // ── Gridlines + y labels (min / mid / max of the padded scale) ──────
        if (!minimal) {
            val gridColor = bt.chartGrid
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
                val text = if (baseline) axisPercent(value, locale) else axisMoney(value, locale, compactAxis)
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
        // Colour forks on `colorBySign`; geometry below still forks on `baseline`.
        // When they disagree (the hero's hybrid mode) both halves are `lineColor`,
        // so the mirrored-about-zero fill renders in one hue — a single-colour
        // baseline area, which is exactly the intended look.
        val upColor = if (colorBySign) bt.gain else lineColor
        val downColor = if (colorBySign) bt.loss else lineColor
        val zeroY = if (baseline) plotH * (1f - scale.normalize(0.0)) else plotH

        val lineStroke = Stroke(
            width = bt.chartLineWidth.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )

        // Baseline mode splits its colours at the zero line using a gradient with
        // a DOUBLED colour stop at the zero ratio, which is exactly how the web's
        // BaselineSeries does it:
        //
        //   gradient.addColorStop(baselineRatio, topColor2);
        //   gradient.addColorStop(baselineRatio, bottomColor1);
        //
        // Two stops at the same offset make the transition a hard edge rather
        // than a blend. Doing it this way instead of drawing the geometry twice
        // under complementary clips is also what makes the mode affordable to
        // scrub: it halves the path rasterisations per frame, and on the device
        // the clipped version measured 19ms/88% janky against 14ms/27% for the
        // single-pass € chart.
        val zeroRatio = (zeroY / plotH).coerceIn(0f, 1f)
        val lineBrush: Brush = if (!colorBySign) {
            SolidColor(lineColor)
        } else {
            Brush.verticalGradient(
                0f to upColor,
                zeroRatio to upColor,
                zeroRatio to downColor,
                1f to downColor,
                startY = 0f,
                endY = plotH,
            )
        }
        val fillBrush: Brush = if (!baseline) {
            Brush.verticalGradient(
                colors = listOf(bt.wash(lineColor, bt.chartAreaTopAlpha), Color.Transparent),
                startY = 0f,
                endY = plotH,
            )
        } else {
            // Mirrored about zero: strongest at the top for gains, strongest at
            // the bottom for losses, faint where they meet.
            Brush.verticalGradient(
                0f to bt.wash(upColor, bt.chartAreaTopAlpha),
                zeroRatio to bt.wash(upColor, bt.chartAreaZeroAlpha),
                zeroRatio to bt.wash(downColor, bt.chartAreaZeroAlpha),
                1f to bt.wash(downColor, bt.chartAreaTopAlpha),
                startY = 0f,
                endY = plotH,
            )
        }

        /** Paint one connected run: its fill, then its stroke. */
        fun drawSegment(linePath: Path) {
            val bounds = linePath.getBounds()
            val fillPath = Path()
            fillPath.addPath(linePath)
            // Close each segment against the baseline UNDER ITSELF — a shared
            // full-width close would paint the gradient straight across a gap.
            fillPath.lineTo(bounds.right, zeroY)
            fillPath.lineTo(bounds.left, zeroY)
            fillPath.close()
            drawPath(path = fillPath, brush = fillBrush)
            drawPath(path = linePath, brush = lineBrush, style = lineStroke)
        }

        if (morphing) {
            // Range transition (≤320 ms): both series are resampled onto one
            // index grid and lerped, so the morph is deliberately drawn as a
            // single continuous stroke — it is an animation BETWEEN two truths,
            // not a claim about the data. The settled frame below is segmented.
            val linePath = Path()
            val oldScale = yScale(previousPoints, zeroAnchored = baseline)
            val samples = 120
            for (i in 0..samples) {
                val frac = i / samples.toFloat()
                val oldY = normalizedAtFraction(previousPoints, frac, oldScale)
                val newY = normalizedAtFraction(series, frac, scale)
                val yNorm = oldY + (newY - oldY) * progress.value
                val x = plotW * frac
                val y = plotH * (1f - yNorm)
                if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
            }
            drawSegment(linePath)
        } else {
            fun px(i: Int) = seriesX(i, plotW, series.size)
            fun py(p: HistoryPoint) = plotH * (1f - scale.normalize(p.valueEur))

            // The chart draws every connected run [chartSegments] hands it.
            //
            // OWNER OVERRIDE 2026-08-06: that is now always exactly ONE run over
            // the whole series. The loop simply draws what it is given, and would
            // still draw several runs correctly if the call were ever reversed.
            chartSegments(series.map { it.epochMillis }).forEach { range ->
                val linePath = Path()
                for (i in range) {
                    val p = series[i]
                    if (i == range.first) linePath.moveTo(px(i), py(p)) else linePath.lineTo(px(i), py(p))
                }
                drawSegment(linePath)
            }

            // The zero line itself, so "am I up or down" has a mark to read
            // against rather than only a colour change.
            if (baseline && zeroY in 0f..plotH) {
                drawLine(
                    color = bt.border,
                    start = Offset(0f, zeroY),
                    end = Offset(plotW, zeroY),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }

        // ── x labels: first + last date, muted, in the reserved strip ──────
        if (!minimal) {
            val spanMillis = series.last().epochMillis - series.first().epochMillis
            val startText = formatChartAxisTime(series.first().epochMillis, spanMillis, locale)
            val endText = formatChartAxisTime(series.last().epochMillis, spanMillis, locale)
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

    // ── Layer 2: the crosshair. The ONLY reader of `scrubIndex`, so a scrub
    // invalidates a canvas that draws one line and two circles.
    Canvas(modifier = Modifier.matchParentSize()) {
        val series = currentPoints
        val i = scrubIndex ?: return@Canvas
        if (series.size < 2 || i !in series.indices) return@Canvas
        if (progress.value < 1f && previousPoints.size >= 2) return@Canvas

        val xLabelStrip = if (minimal) 0f else 18.dp.toPx()
        val plotH = size.height - xLabelStrip
        val p = series[i]
        val x = seriesX(i, size.width, series.size)
        val y = plotH * (1f - scale.normalize(p.valueEur))
        val dotColor = when {
            !colorBySign -> lineColor
            p.valueEur >= 0.0 -> bt.gain
            else -> bt.loss
        }
        drawLine(
            color = bt.borderStrong,
            start = Offset(x, 0f),
            end = Offset(x, plotH),
            strokeWidth = 1.dp.toPx(),
        )
        // Dot with a surface ring so it reads on top of the line. Both radii are
        // multiples of the line weight, so dark keeps its exact 6dp/4dp and light
        // scales with the fatter light curve instead of being swallowed by it.
        drawCircle(color = bt.surface, radius = bt.chartLineWidth.toPx() * 3f, center = Offset(x, y))
        drawCircle(color = dotColor, radius = bt.chartLineWidth.toPx() * 2f, center = Offset(x, y))
    }
    }
}

// ── Series math (pixel mapping only — never value computation) ──────────────

/**
 * Where point [index] of an [count]-point series sits horizontally.
 *
 * Edge-to-edge rather than the half-bar inset a TradingView `fitContent()`
 * leaves, because this chart's hero mode is full-bleed and a margin would read
 * as the data stopping short of the screen.
 */
internal fun seriesX(index: Int, plotWidth: Float, count: Int): Float =
    if (count <= 1) 0f else plotWidth * index / (count - 1).toFloat()

/**
 * The index whose cell contains [x] — plain nearest-neighbour over a uniform
 * grid, which is what makes the crosshair advance at a constant rate no matter
 * how unevenly the series is sampled in time.
 */
internal fun scrubIndexAt(x: Float, plotWidth: Float, count: Int): Int {
    if (count <= 1 || plotWidth <= 0f) return 0
    val frac = (x / plotWidth).coerceIn(0f, 1f)
    return (frac * (count - 1)).roundToInt().coerceIn(0, count - 1)
}

internal class YScale(val min: Double, val max: Double) {
    fun normalize(v: Double): Float =
        if (max == min) 0.5f else ((v - min) / (max - min)).toFloat()
}

/**
 * Padded y-scale: 8% headroom above/below so the line never kisses the edge.
 *
 * [zeroAnchored] keeps 0 inside the window even for an all-positive or
 * all-negative performance series — without it a curve that never went negative
 * would have its zero line pushed off-canvas and the up/down colouring would
 * lose the thing it is coloured against.
 */
internal fun yScale(points: List<HistoryPoint>, zeroAnchored: Boolean = false): YScale {
    var lo = Double.MAX_VALUE
    var hi = -Double.MAX_VALUE
    points.forEach {
        lo = min(lo, it.valueEur)
        hi = max(hi, it.valueEur)
    }
    if (zeroAnchored) {
        lo = min(lo, 0.0)
        hi = max(hi, 0.0)
    }
    if (lo == hi) {
        // Flat series: pad around the value so it renders mid-plot.
        val pad = max(1.0, abs(lo) * 0.05)
        return YScale(lo - pad, hi + pad)
    }
    val pad = (hi - lo) * 0.08
    // An all-positive MONEY series never shows a negative axis label — the
    // padding clamps at zero instead of inventing values the data doesn't have.
    // A zero-anchored (performance) scale is allowed to pad past zero, because
    // there the sign is the point.
    val paddedLo = if (!zeroAnchored && lo >= 0.0) max(0.0, lo - pad) else lo - pad
    return YScale(paddedLo, hi + pad)
}

/**
 * Normalized (0..1) series value at x-fraction [frac] of the INDEX axis, linearly
 * interpolated between the two neighbouring points.
 *
 * Only the range-morph animation uses this. Interpolation is legitimate there
 * precisely because those frames are not a readout: nothing reports a number off
 * this curve, it is 320ms of motion between two server truths.
 */
internal fun normalizedAtFraction(points: List<HistoryPoint>, frac: Float, scale: YScale): Float {
    if (points.isEmpty()) return 0.5f
    if (points.size == 1) return scale.normalize(points[0].valueEur)
    val pos = frac.coerceIn(0f, 1f) * (points.size - 1)
    val lo = pos.toInt().coerceIn(0, points.size - 1)
    val hi = (lo + 1).coerceAtMost(points.size - 1)
    val t = pos - lo
    val v = points[lo].valueEur + (points[hi].valueEur - points[lo].valueEur) * t
    return scale.normalize(v)
}

// ── Label formatting (display-only) ─────────────────────────────────────────

/**
 * Axis money label. [compact] is decided ONCE per axis from the scale's
 * magnitude: 1,2M · 12,4k (locale separators) — or plain integers otherwise.
 */
internal fun axisMoney(value: Double, locale: Locale, compact: Boolean): String {
    // A value axis is absolute money, so discreet mode has to blank it too —
    // otherwise the chart gridlines simply spell out the portfolio's size.
    if (BtDiscreetMode.masking) return BT_MASKED_PLAIN
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
 * Axis percent label. Unlike [axisMoney] this is NOT masked in discreet mode: a
 * return says how well the money did, not how much of it there is, which is the
 * whole distinction discreet mode draws.
 */
internal fun axisPercent(value: Double, locale: Locale): String {
    val nf = NumberFormat.getNumberInstance(locale)
    nf.maximumFractionDigits = if (abs(value) < 10) 1 else 0
    return nf.format(value) + " %"
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

/**
 * Axis label for an epoch-millis x-key. The DATA decides the granularity, not
 * the selected range: a span inside a single day reads as time-of-day ("14:35"),
 * a span of a few days carries both day and time ("2 Aug 14:35"), and anything
 * longer falls back to the day-granular [formatChartDate] wording — so 1M+ keeps
 * exactly the labels it had before intraday points existed.
 */
internal fun formatChartAxisTime(epochMillis: Long, spanMillis: Long, locale: Locale): String {
    val zoned = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    val pattern = when {
        spanMillis <= MILLIS_PER_DAY -> "HH:mm"
        spanMillis <= 8L * MILLIS_PER_DAY -> "d MMM HH:mm"
        else -> return formatChartDate(
            Math.floorDiv(epochMillis, MILLIS_PER_DAY),
            spanMillis / MILLIS_PER_DAY,
            locale,
        )
    }
    return zoned.format(DateTimeFormatter.ofPattern(pattern, locale))
}
