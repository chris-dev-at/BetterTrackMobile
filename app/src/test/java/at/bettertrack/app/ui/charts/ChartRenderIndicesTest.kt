package at.bettertrack.app.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The vertex reduction behind the owner's 2026-08-17 order (*"weniger random
 * spitzen … vielleicht weniger datenpunkte … der chart wieder bissl dicker"*).
 *
 * Two things have to hold at once and they pull in opposite directions, which is
 * why they are pinned rather than argued:
 *
 *  - **noise goes** — a series of sub-pixel jitter must come out dramatically
 *    smaller, because that jitter is what was reading as random spikes;
 *  - **information stays** — the true high, the true low, the endpoints and any
 *    genuine move must survive, at their real value and their real x.
 *
 * The predecessor's min/max-per-column reduction satisfied the second and
 * actively defeated the first (it is a machine for preserving spikes). LTTB is
 * the swap; these tests are what stop it being swapped back by accident.
 */
class ChartRenderIndicesTest {

    private fun ramp(n: Int) = List(n) { it.toDouble() }

    @Test
    fun `a series coarser than the budget is drawn in full`() {
        // The overwhelmingly common case — a 30-point range on a wide plot must
        // go through completely untouched, never resampled up or down.
        val values = ramp(30)
        assertEquals(values.indices.toList(), chartRenderIndices(values, vertices = 200))
        assertEquals(values.indices.toList(), chartRenderIndices(values, vertices = 30))
    }

    @Test
    fun `the owner's measured case reduces to the count he asked for`() {
        // 313 points, his 1080px plot, 2dp pitch @420dpi = 5.25px → budget ~205.
        // He named 200 explicitly, overriding the pitch-vs-stroke rule.
        val budget = chartVertexBudget(plotWidthPx = 1080f, pitchPx = 2f * 2.625f)
        assertTrue("not the ~200 he asked for: $budget", budget in 190..215)

        val values = List(313) { Random(7).nextDouble() }
        val kept = chartRenderIndices(values, vertices = budget)
        // At most the budget plus the two pinned extremes.
        assertTrue("reduced too little: ${kept.size}", kept.size <= budget + 2)
        // And it is still a real reduction, not a rounding.
        assertTrue("barely reduced: ${kept.size}", kept.size < 313)
    }

    @Test
    fun `output is ascending, unique, and invents nothing`() {
        val values = List(500) { Random(11).nextDouble() * 100 }
        val kept = chartRenderIndices(values, vertices = 60)
        assertEquals("duplicate indices", kept.size, kept.toSet().size)
        assertEquals("not ascending", kept.sorted(), kept)
        assertTrue("index out of range", kept.all { it in values.indices })
    }

    @Test
    fun `the first and last observations always survive`() {
        // Otherwise the curve would start and end somewhere the series does not.
        val values = List(400) { Random(3).nextDouble() }
        val kept = chartRenderIndices(values, vertices = 25)
        assertEquals(0, kept.first())
        assertEquals(values.lastIndex, kept.last())
    }

    @Test
    fun `a real spike survives at its true height`() {
        // The line between "smoothing" and "lying". A single big move is exactly
        // the information a chart reader is looking for; dropping it would be a
        // different asset, not a calmer one.
        val values = MutableList(600) { 50.0 }
        values[137] = 999.0 // the high
        values[421] = -999.0 // the low
        val kept = chartRenderIndices(values, vertices = 40)
        assertTrue("the peak was dropped", 137 in kept)
        assertTrue("the trough was dropped", 421 in kept)
    }

    @Test
    fun `the global extremes survive on noisy data too`() {
        // These two are the numbers the Tief/Hoch footer prints. A curve that
        // never reaches them contradicts its own caption.
        val values = List(2000) { Random(99).nextDouble() * 10 }
        val kept = chartRenderIndices(values, vertices = 85).toSet()
        assertTrue("global max dropped", values.indices.maxBy { values[it] } in kept)
        assertTrue("global min dropped", values.indices.minBy { values[it] } in kept)
    }

    @Test
    fun `sub-pixel jitter on a real trend is dropped, the trend is not`() {
        // The actual complaint, reproduced: a clean rise with ±0.05% jitter on
        // every sample. The jitter is smaller than a pixel on any plot the app
        // draws; before this change every one of those wiggles got its own
        // round-capped vertex.
        val rng = Random(4)
        val values = List(313) { i -> 9000.0 + i * 1.5 + (rng.nextDouble() - 0.5) * 9.0 }
        val kept = chartRenderIndices(values, vertices = 85)
        assertTrue("not reduced enough to calm the curve: ${kept.size}", kept.size <= 87)

        // The trend is intact: the drawn curve rises across its span by
        // essentially the full real rise.
        val realRise = values.last() - values.first()
        val drawnRise = values[kept.last()] - values[kept.first()]
        assertEquals(realRise, drawnRise, 0.001)

        // And the drawn vertices are still real observations, in order.
        kept.zipWithNext().forEach { (a, b) -> assertTrue(a < b) }
    }

    @Test
    fun `a reduced volatile series keeps the same silhouette`() {
        // "Fewer points" must not turn into "a different asset". Compare the
        // drawn curve against the full one at 24 evenly spaced probes: the
        // nearest drawn vertex is never far from the real value there.
        val rng = Random(21)
        var v = 100.0
        val values = List(500) { v += (rng.nextDouble() - 0.48) * 4.0; v }
        val kept = chartRenderIndices(values, vertices = 85)
        val range = values.max() - values.min()
        (0 until 24).forEach { probe ->
            val at = probe * (values.size - 1) / 23
            val nearest = kept.minBy { abs(it - at) }
            assertTrue(
                "silhouette drifted at $at: ${values[nearest]} vs ${values[at]}",
                abs(values[nearest] - values[at]) <= range * 0.12,
            )
        }
    }

    @Test
    fun `degenerate inputs are survivable`() {
        assertEquals(emptyList<Int>(), chartRenderIndices(emptyList(), vertices = 10))
        assertEquals(listOf(0), chartRenderIndices(listOf(1.0), vertices = 10))
        assertEquals(listOf(0, 1), chartRenderIndices(listOf(1.0, 2.0), vertices = 1))
        // A zero or negative budget must not divide by zero.
        assertTrue(chartRenderIndices(ramp(50), vertices = 0).isNotEmpty())
        assertTrue(chartRenderIndices(ramp(50), vertices = -5).isNotEmpty())
        // A flat series has no extremes to pin and must still reduce cleanly.
        val flat = List(400) { 7.0 }
        val kept = chartRenderIndices(flat, vertices = 30)
        assertTrue(kept.size <= 32)
        assertEquals(0, kept.first())
        assertEquals(399, kept.last())
    }

    @Test
    fun `a monotonic ramp keeps its shape and reduces evenly`() {
        // Reduction must not introduce a wobble into a straight line.
        val values = ramp(1000)
        val kept = chartRenderIndices(values, vertices = 50)
        val heights = kept.map { values[it] }
        assertEquals(heights.sorted(), heights)
        val gaps = kept.zipWithNext { a, b -> b - a }.filter { it > 0 }
        val mean = gaps.average()
        assertTrue("uneven reduction: $gaps", gaps.all { abs(it - mean) <= mean + 2 })
    }
}
