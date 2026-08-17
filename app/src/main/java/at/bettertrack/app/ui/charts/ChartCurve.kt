package at.bettertrack.app.ui.charts

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/**
 * How a dense server series becomes a *drawn* curve: first fewer vertices
 * ([chartRenderIndices]), then a bounded smooth between them
 * ([chartCurveThrough]).
 *
 * ## The order that produced this file (owner, 2026-08-17)
 *
 * > *"mach die dicke wie vorher aber mach die ausschläge beim chart weniger.
 * > ich weiß nicht wie ich es beschreiben soll aber weniger random spitzen und
 * > ein bisschen mehr smoothing und der chart wieder bissl dicker wie vorher"*
 *
 * and, a minute later, pointing straight at the lever himself:
 *
 * > *"vielleicht weniger datenpunkte"*
 *
 * Two requests that pull against each other only if you try to answer both with
 * the stroke: a thicker line on the same vertex soup is *more* lumpy, and the
 * previous round had answered the lumpiness by thinning the stroke — which he
 * has now rejected. So the thickness goes back UP — 3dp light, and 2.5dp dark,
 * because he sent this from a dark true-black screen where the line had gone
 * thin and nervous — and the calm comes entirely out of the CURVE.
 *
 * ## The measurement this is sized against
 *
 * On his phone (420dpi, 1080px plot), on the 1M range he was looking at:
 *
 * | | vertices | pitch | stroke @3dp | stroke ÷ pitch |
 * |---|---|---|---|---|
 * | while loading (range morph) | 121 | 8.9 px | 7.9 px | 0.9× — he likes this |
 * | settled, before this change | 313 | 3.5 px | 7.9 px | **2.3× — the lumps** |
 * | settled, after this change | 205 | 5.3 px | 7.9 px | 1.5× |
 *
 * A round-capped stroke drawn through vertices closer together than it is wide
 * is drawing on top of itself: each cap swallows its neighbours and a wiggle of
 * one or two pixels — which is *noise*, not information, because no reader can
 * resolve it — comes out as a bump. Widen the gaps past the stroke and the same
 * data reads as a line again. That is the entire mechanism.
 *
 * ## Honesty rails
 *
 * This file changes what is STROKED. It changes nothing that is READ:
 *
 *  - the scrub still indexes the full-resolution series, so the crosshair can
 *    still land on every real observation and report its exact value;
 *  - the Tief/Hoch footer and the range delta still come from the raw series;
 *  - the y-scale is still computed from the raw series;
 *  - the reduction never invents a point — every drawn vertex is a real
 *    observation, at its real value, at its real x;
 *  - the series' true high and true low are pinned into the drawn set, so a
 *    genuine spike can never be smoothed away and the curve always visibly
 *    reaches the numbers the footer names;
 *  - the smoothing is monotone-bounded, so the curve cannot bulge past the
 *    values it is drawn through (see [chartCurveTangents]).
 */

/**
 * The gap the drawn vertices keep, as a **length** rather than as a multiple of
 * the stroke.
 *
 * ## Why it is not a stroke multiple any more (owner, 2026-08-17)
 *
 * It was. The rule was "never draw vertices closer together than the stroke is
 * wide", which is the honest rasteriser argument and which landed the curve at
 * 68–82 vertices. He looked at that and said:
 *
 * > *"make twice as many data points again. so 200 instead of 100 or whatever"*
 *
 * That is a deliberate override of the geometric rule, and it is his call to
 * make — he is the one looking at it. 2dp is 5.25px on his phone, so a 1080px
 * plot draws ~205 vertices: exactly the "200" he asked for.
 *
 * Two consequences worth being honest about:
 *
 *  - **The pitch is now NARROWER than the stroke** (5.25px against a 6.6px dark
 *    / 7.9px light line), which is the condition that produced the original
 *    lumping. What makes 205 vertices read calm where 313 did not is that the
 *    survivors are chosen by [chartRenderIndices] for *shape* and then joined
 *    by a monotone curve instead of by corners — the jitter is gone even though
 *    the density is back. Verified on his device before shipping.
 *  - **It is a dp, so it is density- and theme-independent.** A stroke multiple
 *    would have given light and dark different point counts for the same
 *    series, which is not something a reader should be able to see.
 */
internal val CHART_VERTEX_PITCH: Dp = 2.dp

/** Below this a curve stops being a shape and becomes a zig-zag. */
internal const val CHART_MIN_VERTICES = 24

/**
 * Above this the reduction has stopped buying anything. 320 is a tablet-width
 * plot at [CHART_VERTEX_PITCH]; past it the curve is drawing detail no display
 * the app runs on can space out.
 */
internal const val CHART_MAX_VERTICES = 320

/**
 * How many vertices a plot [plotWidthPx] wide draws, at [pitchPx] apart.
 *
 * Deriving the budget from the plot instead of fixing a number per range is
 * what makes it right everywhere without a table: on his phone the full-bleed
 * hero gets ~205, a 180dp asset card inside the page gutter gets ~170, and a
 * narrow plot bottoms out at [CHART_MIN_VERTICES] — each one as much detail as
 * its own width can space out.
 *
 * That is also the honest answer to "scale it per range": 1M, 1J and MAX all
 * share one plot, so they share one budget, and the only range-dependent
 * behaviour is the one that should exist — a range whose series is *already*
 * coarser than the budget (a sparse MAX, a 12-point custom asset, a 1W) is
 * drawn in full and never touched at all.
 *
 * Callers pass `CHART_VERTEX_PITCH.toPx()`; the parameter is pixels so the
 * function stays pure and unit-testable without a `Density`.
 */
internal fun chartVertexBudget(plotWidthPx: Float, pitchPx: Float): Int {
    if (plotWidthPx <= 0f || pitchPx <= 0f) return CHART_MIN_VERTICES
    return (plotWidthPx / pitchPx).toInt().coerceIn(CHART_MIN_VERTICES, CHART_MAX_VERTICES)
}

/**
 * Which indices of [values] the stroke actually visits, reduced to at most
 * about [vertices] of them by **LTTB** (largest-triangle-three-buckets).
 *
 * ## Why LTTB and not min/max-per-column
 *
 * The previous round reduced by keeping the highest and the lowest observation
 * in every pixel column. That is the correct algorithm for an *audit* view —
 * it preserves every extreme by construction — and it is the wrong one here,
 * because "every extreme" includes every single-sample noise spike. It answers
 * the opposite of *"weniger random spitzen"*: it is a machine for keeping
 * spikes.
 *
 * LTTB keeps, per bucket, the point that forms the largest triangle with the
 * previously kept point and the mean of the next bucket — i.e. the point that
 * carries the most *shape*. A real turning point wins its bucket easily; a
 * one-sample jitter next to a real move does not. The result is a curve with
 * the same silhouette drawn through a third of the vertices.
 *
 * Areas are computed in NORMALIZED space (x over the index range, y over the
 * value range) so the algorithm behaves identically on a €9,440 portfolio and
 * on a 0.42% return — a raw-units triangle would be all y and would degenerate
 * back into "keep the extreme".
 *
 * ## What is pinned regardless
 *
 * The first and last observation (the curve starts and ends where the series
 * does) and the series' **global high and low** (the two numbers the Tief/Hoch
 * footer prints — a curve that does not reach them is a lie about the range,
 * and it is also how a genuine spike is guaranteed to survive).
 *
 * @param values the series values, in x order.
 * @param vertices the drawing budget from [chartVertexBudget].
 * @return ascending, unique indices into [values]; the input's own indices when
 *   it is already coarser than the budget.
 */
internal fun chartRenderIndices(values: List<Double>, vertices: Int): List<Int> {
    val n = values.size
    if (n == 0) return emptyList()
    // Three is the floor LTTB itself needs: first, last, and one bucket.
    val budget = vertices.coerceAtLeast(3)
    if (n <= budget) return values.indices.toList()

    var lo = values[0]
    var hi = values[0]
    var lowAt = 0
    var highAt = 0
    for (i in 1 until n) {
        val v = values[i]
        if (v < lo) { lo = v; lowAt = i }
        if (v > hi) { hi = v; highAt = i }
    }
    val span = hi - lo
    val xStep = 1.0 / (n - 1)
    fun xn(i: Int) = i * xStep
    fun yn(i: Int) = if (span == 0.0) 0.0 else (values[i] - lo) / span

    val kept = LinkedHashSet<Int>(budget + 4)
    kept += 0
    val every = (n - 2).toDouble() / (budget - 2)
    var anchor = 0
    for (bucket in 0 until budget - 2) {
        // Mean of the NEXT bucket — the third corner of the triangle.
        val avgFrom = floor((bucket + 1) * every).toInt() + 1
        val avgTo = min(floor((bucket + 2) * every).toInt() + 1, n)
        var avgX: Double
        var avgY: Double
        if (avgFrom < avgTo) {
            var sx = 0.0
            var sy = 0.0
            for (j in avgFrom until avgTo) {
                sx += xn(j)
                sy += yn(j)
            }
            val len = (avgTo - avgFrom).toDouble()
            avgX = sx / len
            avgY = sy / len
        } else {
            avgX = xn(n - 1)
            avgY = yn(n - 1)
        }

        val from = floor(bucket * every).toInt() + 1
        val to = min(floor((bucket + 1) * every).toInt() + 1, n)
        if (from >= to) continue
        val ax = xn(anchor)
        val ay = yn(anchor)
        var best = from
        var bestArea = -1.0
        for (j in from until to) {
            // Twice the triangle area; the factor is constant so it is dropped.
            val area = abs((ax - avgX) * (yn(j) - ay) - (ax - xn(j)) * (avgY - ay))
            if (area > bestArea) {
                bestArea = area
                best = j
            }
        }
        kept += best
        anchor = best
    }
    kept += n - 1
    // Pinned last so they are never lost to a bucket that preferred a slope.
    kept += lowAt
    kept += highAt
    return kept.sorted()
}

// ── Bounded smoothing ───────────────────────────────────────────────────────

/**
 * How much of the monotone tangent to actually use: 0 draws straight segments,
 * 1 is the full monotone cubic.
 *
 * 1 is safe *because* the tangents are monotone-limited — scaling them down can
 * only make the curve straighter, never make it overshoot — so there is no
 * honesty argument for a lower value, only a taste one, and the owner asked for
 * *more* smoothing, not less.
 */
internal const val CHART_SMOOTHING = 1f

/**
 * Fritsch–Carlson monotone tangents for the polyline ([xs], [ys]).
 *
 * ## Why this variant and not Catmull-Rom
 *
 * A plain Catmull-Rom / cardinal spline through the same points overshoots: at
 * a local maximum it bulges *above* the highest point it passes through, and on
 * a chart that bulge is a value the series never had — drawn above the true
 * high, sometimes above the axis maximum. That is precisely the *"feeling
 * smooth by lying"* this app refuses.
 *
 * Fritsch–Carlson clamps each tangent so the cubic is monotone on every
 * interval where the data is monotone, and zero at every local extremum. A
 * monotone cubic on an interval is bounded by its endpoints, so the whole curve
 * is bounded by the values it is drawn through — which are themselves real
 * observations. No point of the drawn curve is ever outside the series' own
 * min..max. `ChartCurveTest` asserts exactly that.
 *
 * Tangents are dy/dx in whatever space the caller passes — the charts pass
 * pixels, where y grows downward. Monotonicity and boundedness are orientation
 * agnostic, so that costs nothing.
 */
internal fun chartCurveTangents(
    xs: FloatArray,
    ys: FloatArray,
    smoothing: Float = CHART_SMOOTHING,
): FloatArray {
    val n = xs.size
    val m = FloatArray(n)
    if (n < 2) return m

    val d = FloatArray(n - 1)
    for (i in 0 until n - 1) {
        val h = xs[i + 1] - xs[i]
        d[i] = if (h <= 0f) 0f else (ys[i + 1] - ys[i]) / h
    }
    m[0] = d[0]
    m[n - 1] = d[n - 2]
    for (i in 1 until n - 1) m[i] = (d[i - 1] + d[i]) / 2f

    for (i in 0 until n - 1) {
        if (d[i] == 0f) {
            // A flat interval pins both ends flat — otherwise the curve dips
            // through a stretch where the value provably did not move.
            m[i] = 0f
            m[i + 1] = 0f
            continue
        }
        // Overshoot happens when a tangent points the wrong way, or is more
        // than 3× the interval's own slope. Both are clamped here.
        if (m[i] / d[i] < 0f) m[i] = 0f
        if (m[i + 1] / d[i] < 0f) m[i + 1] = 0f
        val a = m[i] / d[i]
        val b = m[i + 1] / d[i]
        val s = a * a + b * b
        if (s > 9f) {
            val t = 3f / sqrt(s)
            m[i] = t * a * d[i]
            m[i + 1] = t * b * d[i]
        }
    }

    val k = smoothing.coerceIn(0f, 1f)
    if (k != 1f) for (i in m.indices) m[i] *= k
    return m
}

/** One cubic Bézier leg of a smoothed curve, in the caller's coordinate space. */
internal class ChartCubic(
    val c1x: Float,
    val c1y: Float,
    val c2x: Float,
    val c2y: Float,
    val x: Float,
    val y: Float,
)

/**
 * The cubic legs of the monotone curve through ([xs], [ys]).
 *
 * Hermite → Bézier is the standard third-of-the-interval rule, which keeps both
 * control points horizontally INSIDE their own segment: the curve is therefore
 * a function of x (it can never double back) and the path's x-bounds are still
 * exactly the first and last vertex — which the area fill relies on when it
 * closes the shape against the baseline.
 */
internal fun chartCurveSegments(
    xs: FloatArray,
    ys: FloatArray,
    smoothing: Float = CHART_SMOOTHING,
): List<ChartCubic> {
    val n = xs.size
    if (n < 2) return emptyList()
    val m = chartCurveTangents(xs, ys, smoothing)
    val out = ArrayList<ChartCubic>(n - 1)
    for (i in 0 until n - 1) {
        val h = xs[i + 1] - xs[i]
        val third = h / 3f
        out += ChartCubic(
            c1x = xs[i] + third,
            c1y = ys[i] + m[i] * third,
            c2x = xs[i + 1] - third,
            c2y = ys[i + 1] - m[i + 1] * third,
            x = xs[i + 1],
            y = ys[i + 1],
        )
    }
    return out
}

/** Lay the monotone curve through ([xs], [ys]) into this path. */
internal fun Path.chartCurveThrough(
    xs: FloatArray,
    ys: FloatArray,
    smoothing: Float = CHART_SMOOTHING,
) {
    if (xs.isEmpty()) return
    moveTo(xs[0], ys[0])
    chartCurveSegments(xs, ys, smoothing).forEach {
        cubicTo(it.c1x, it.c1y, it.c2x, it.c2y, it.x, it.y)
    }
}

/**
 * The drawn curve's y values, sampled [perSegment] times per leg.
 *
 * This is the *rendered* geometry, evaluated from the same Bézier control
 * points [chartCurveThrough] hands the rasteriser — which is what makes the
 * no-overshoot guarantee testable rather than merely argued.
 */
internal fun chartCurveSamples(
    xs: FloatArray,
    ys: FloatArray,
    smoothing: Float = CHART_SMOOTHING,
    perSegment: Int = 16,
): FloatArray {
    if (xs.isEmpty()) return FloatArray(0)
    val segments = chartCurveSegments(xs, ys, smoothing)
    if (segments.isEmpty()) return floatArrayOf(ys[0])
    val steps = perSegment.coerceAtLeast(1)
    val out = FloatArray(segments.size * steps + 1)
    out[0] = ys[0]
    var at = 1
    var y0 = ys[0]
    segments.forEach { seg ->
        for (s in 1..steps) {
            val t = s / steps.toFloat()
            val u = 1f - t
            out[at++] = u * u * u * y0 +
                3f * u * u * t * seg.c1y +
                3f * u * t * t * seg.c2y +
                t * t * t * seg.y
        }
        y0 = seg.y
    }
    return out
}
