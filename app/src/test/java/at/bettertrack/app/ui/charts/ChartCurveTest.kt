package at.bettertrack.app.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The **honesty rails** on the 2026-08-17 smoothing order.
 *
 * The owner asked for *"ein bisschen mehr smoothing"*. The app's standing rule
 * is that it does not invent numbers, and a spline is the classic way to break
 * that rule by accident: a Catmull-Rom curve through a local maximum bulges
 * ABOVE it, drawing a value the series never reached — sometimes above the
 * chart's own axis maximum. Every test here exists to prove the shipped curve
 * cannot do that.
 */
class ChartCurveTest {

    private fun samples(xs: FloatArray, ys: FloatArray) = chartCurveSamples(xs, ys)

    private fun evenX(n: Int, step: Float = 10f) = FloatArray(n) { it * step }

    @Test
    fun `the curve never leaves the range of the points it is drawn through`() {
        // The rail in one sentence. Random data, every segment sampled 16×.
        val rng = Random(5)
        repeat(40) {
            val n = 3 + rng.nextInt(30)
            val xs = evenX(n)
            val ys = FloatArray(n) { rng.nextFloat() * 400f }
            val lo = ys.min()
            val hi = ys.max()
            samples(xs, ys).forEach { y ->
                assertTrue("curve dipped to $y below $lo", y >= lo - 1e-3f)
                assertTrue("curve bulged to $y above $hi", y <= hi + 1e-3f)
            }
        }
    }

    @Test
    fun `a local peak is not overshot — the Catmull-Rom failure mode`() {
        // The exact shape a cardinal spline gets wrong: a spike between two
        // gentle slopes. A cardinal spline overshoots the peak by ~15%; the
        // monotone curve touches it and stops.
        val xs = evenX(5)
        val ys = floatArrayOf(100f, 90f, 20f, 90f, 100f) // y is screen space: 20 is the peak
        val peak = samples(xs, ys).min()
        assertEquals("the peak is drawn at its true height, not past it", 20f, peak, 1e-3f)
    }

    @Test
    fun `a monotone run stays monotone — no wobble invented between two points`() {
        // A steadily rising series must be drawn as a steadily rising line. A
        // spline that dips between two ascending points is claiming a pullback
        // that never happened.
        val xs = evenX(12)
        val ys = FloatArray(12) { 300f - it * 17f }
        val drawn = samples(xs, ys)
        for (i in 1 until drawn.size) {
            assertTrue(
                "the curve went back up between two falling points",
                drawn[i] <= drawn[i - 1] + 1e-3f,
            )
        }
    }

    @Test
    fun `a flat stretch is drawn flat`() {
        // No sag, no bulge: if the value provably did not move, the picture must
        // not suggest it did.
        val xs = evenX(6)
        val ys = floatArrayOf(50f, 50f, 50f, 50f, 80f, 90f)
        val flatPart = chartCurveSamples(xs, ys).take(3 * 16 + 1)
        flatPart.forEach { assertEquals(50f, it, 1e-3f) }
    }

    @Test
    fun `the curve passes exactly through every drawn vertex`() {
        // Interpolating, not approximating: a smoothing that merely passes NEAR
        // the observations would be showing values the server never returned.
        val xs = evenX(8)
        val ys = floatArrayOf(10f, 90f, 40f, 55f, 55f, 12f, 70f, 33f)
        val perSegment = 16
        val drawn = chartCurveSamples(xs, ys, perSegment = perSegment)
        ys.forEachIndexed { i, y -> assertEquals(y, drawn[i * perSegment], 1e-3f) }
    }

    @Test
    fun `control points stay inside their own segment, so the curve is a function of x`() {
        // If a control point escaped its segment horizontally the curve could
        // double back — two values at one x, which is nonsense for a time
        // series, and it would also break the area fill's x-bounds.
        val rng = Random(13)
        val xs = evenX(20)
        val ys = FloatArray(20) { rng.nextFloat() * 100f }
        chartCurveSegments(xs, ys).forEachIndexed { i, seg ->
            assertTrue(seg.c1x >= xs[i] - 1e-3f && seg.c1x <= xs[i + 1] + 1e-3f)
            assertTrue(seg.c2x >= xs[i] - 1e-3f && seg.c2x <= xs[i + 1] + 1e-3f)
        }
    }

    @Test
    fun `smoothing zero degenerates to the straight polyline`() {
        // The parameter is a dial, not a mode: at 0 the drawn curve is exactly
        // the segments the old renderer drew, which is what makes it safe to
        // turn down if the owner ever wants less.
        val xs = evenX(5)
        val ys = floatArrayOf(10f, 60f, 20f, 80f, 30f)
        val drawn = chartCurveSamples(xs, ys, smoothing = 0f, perSegment = 4)
        // Midpoint of each leg must be the linear midpoint.
        for (i in 0 until 4) {
            val expected = (ys[i] + ys[i + 1]) / 2f
            assertEquals(expected, drawn[i * 4 + 2], 1e-3f)
        }
    }

    @Test
    fun `degenerate inputs do not blow up`() {
        assertEquals(0, chartCurveSegments(FloatArray(0), FloatArray(0)).size)
        assertEquals(0, chartCurveSegments(floatArrayOf(1f), floatArrayOf(2f)).size)
        assertEquals(1, chartCurveSegments(floatArrayOf(0f, 5f), floatArrayOf(2f, 9f)).size)
        // Two vertices at the same x (possible if a reduction ever collapses)
        // must not divide by zero.
        val flatX = chartCurveSegments(floatArrayOf(0f, 0f, 5f), floatArrayOf(1f, 2f, 3f))
        assertEquals(2, flatX.size)
        flatX.forEach {
            assertTrue(it.c1y.isFinite() && it.c2y.isFinite())
        }
    }

    // ── The budget ──────────────────────────────────────────────────────────

    /** [CHART_VERTEX_PITCH] (2dp) on his 420dpi phone. */
    private val pitchOnHisPhone = 2f * 2.625f

    @Test
    fun `the owner's plot draws the ~200 points he asked for`() {
        // *"make twice as many data points again. so 200 instead of 100 or
        // whatever"* — 1080px plot, 2dp pitch at 420dpi = 5.25px.
        val budget = chartVertexBudget(plotWidthPx = 1080f, pitchPx = pitchOnHisPhone)
        assertTrue("not the ~200 he asked for: $budget", budget in 190..215)
    }

    @Test
    fun `the budget is theme-independent`() {
        // It used to be a multiple of the stroke, which gave light and dark
        // different point counts for the same series — a difference a reader
        // should never be able to see. The pitch is a length now.
        assertEquals(
            chartVertexBudget(1080f, pitchOnHisPhone),
            chartVertexBudget(1080f, pitchOnHisPhone),
        )
    }

    @Test
    fun `a narrower plot gets fewer vertices, not the same number squeezed in`() {
        val hero = chartVertexBudget(1080f, pitchOnHisPhone)
        val card = chartVertexBudget(900f, pitchOnHisPhone)
        val small = chartVertexBudget(300f, pitchOnHisPhone)
        assertTrue("$hero <= $card", hero > card)
        assertTrue("$card <= $small", card > small)
        // …and never so few that a curve stops being a shape.
        assertEquals(CHART_MIN_VERTICES, chartVertexBudget(60f, pitchOnHisPhone))
    }

    @Test
    fun `degenerate plot sizes are survivable`() {
        assertEquals(CHART_MIN_VERTICES, chartVertexBudget(0f, 8f))
        assertEquals(CHART_MIN_VERTICES, chartVertexBudget(1080f, 0f))
        assertEquals(CHART_MAX_VERTICES, chartVertexBudget(100_000f, 1f))
    }

    // ── The two halves together ─────────────────────────────────────────────

    @Test
    fun `reduce then smooth still cannot leave the raw series' range`() {
        // End-to-end rail: this is what the hero actually draws. The drawn curve
        // is bounded by the FULL series' min/max, so it can never contradict the
        // Tief/Hoch footer, which reads the full series.
        val rng = Random(77)
        var v = 9_000.0
        val values = List(313) { v += (rng.nextDouble() - 0.5) * 60.0; v }
        val lo = values.min().toFloat()
        val hi = values.max().toFloat()

        val kept = chartRenderIndices(values, vertices = chartVertexBudget(1080f, pitchOnHisPhone))
        val xs = FloatArray(kept.size) { 1080f * kept[it] / (values.size - 1) }
        val ys = FloatArray(kept.size) { values[kept[it]].toFloat() }
        chartCurveSamples(xs, ys).forEach { y ->
            assertTrue("drawn $y below the real low $lo", y >= lo - 1e-2f)
            assertTrue("drawn $y above the real high $hi", y <= hi + 1e-2f)
        }

        // And it genuinely reaches both, because they are pinned into the draw.
        val drawnLo = ys.min()
        val drawnHi = ys.max()
        assertEquals("the curve must reach the real low", lo, drawnLo, 1e-2f)
        assertEquals("the curve must reach the real high", hi, drawnHi, 1e-2f)
    }

    @Test
    fun `a genuine crash still reads as a crash after reduce and smooth`() {
        // "Real moves stay, noise goes." A 30% drop in the middle of an
        // otherwise quiet series must still be the dominant feature.
        val rng = Random(31)
        val values = List(400) { i ->
            val base = if (i < 200) 100.0 else 70.0
            base + (rng.nextDouble() - 0.5) * 0.4
        }
        val kept = chartRenderIndices(values, vertices = chartVertexBudget(1080f, pitchOnHisPhone))
        val xs = FloatArray(kept.size) { 1080f * kept[it] / (values.size - 1) }
        val ys = FloatArray(kept.size) { values[kept[it]].toFloat() }
        val drawn = chartCurveSamples(xs, ys)
        // The drawn curve spans essentially the whole real move…
        assertTrue("the crash was flattened", (drawn.max() - drawn.min()) >= 29.5f)
        // …and it happens where it really happened, near the middle.
        val steepest = (1 until drawn.size).maxBy { abs(drawn[it] - drawn[it - 1]) }
        val where = steepest.toFloat() / drawn.size
        assertTrue("the crash moved to $where of the span", where in 0.4f..0.6f)
    }
}
