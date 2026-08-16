package at.bettertrack.app.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The stroke-density reduction behind the owner's 2026-08-17 chart report
 * (*"once it loads fully its looking really weird… less spikey?"*).
 *
 * The rule this pins is the one that makes the reduction HONEST rather than
 * cosmetic: it may drop points the rasteriser could not resolve, and it may not
 * invent, move, or flatten anything. Every assertion below is a way of saying
 * that — most importantly that the highest and lowest real observations always
 * survive, because a "smoothing" that clips a peak is a lie about the data.
 */
class ChartRenderIndicesTest {

    private fun ramp(n: Int) = List(n) { it.toDouble() }

    @Test
    fun `a series coarser than the pixel grid is drawn in full`() {
        // The overwhelmingly common case — a 30-point 1M range on a 1000px plot
        // must go through completely untouched.
        val values = ramp(30)
        assertEquals(values.indices.toList(), chartRenderIndices(values, columns = 200))
        assertEquals(values.indices.toList(), chartRenderIndices(values, columns = 30))
    }

    @Test
    fun `the owner's measured case reduces to about the pixel grid`() {
        // 313 points, 1080px plot, 2.5dp stroke ≈ 6.9px → ~156 columns.
        val values = List(313) { Random(7).nextDouble() }
        val kept = chartRenderIndices(values, columns = 156)
        assertTrue("nothing was reduced: ${kept.size}", kept.size < 313)
        // min+max per column is at most two per column, plus the pinned ends.
        assertTrue("reduced too little: ${kept.size}", kept.size <= 2 * 156 + 2)
    }

    @Test
    fun `output is ascending, unique, and invents nothing`() {
        val values = List(500) { Random(11).nextDouble() * 100 }
        val kept = chartRenderIndices(values, columns = 60)
        assertEquals("duplicate indices", kept.size, kept.toSet().size)
        assertEquals("not ascending", kept.sorted(), kept)
        assertTrue("index out of range", kept.all { it in values.indices })
    }

    @Test
    fun `the first and last observations always survive`() {
        // Otherwise the curve would start and end somewhere the series does not.
        val values = List(400) { Random(3).nextDouble() }
        val kept = chartRenderIndices(values, columns = 25)
        assertEquals(0, kept.first())
        assertEquals(values.lastIndex, kept.last())
    }

    @Test
    fun `every peak and trough survives at its true height`() {
        // The whole justification for min-max over "take every Nth point":
        // decimation would walk straight past a spike, which is exactly the
        // information a chart reader is looking for.
        val values = MutableList(600) { 50.0 }
        values[137] = 999.0 // the high
        values[421] = -999.0 // the low
        val kept = chartRenderIndices(values, columns = 40)
        assertTrue("the peak was dropped", 137 in kept)
        assertTrue("the trough was dropped", 421 in kept)
    }

    @Test
    fun `the global extremes survive on noisy data too`() {
        val values = List(2000) { Random(99).nextDouble() * 10 }
        val kept = chartRenderIndices(values, columns = 100).toSet()
        val highest = values.indices.maxBy { values[it] }
        val lowest = values.indices.minBy { values[it] }
        assertTrue("global max dropped", highest in kept)
        assertTrue("global min dropped", lowest in kept)
    }

    @Test
    fun `within a column the low and the high keep their real order`() {
        // If they were emitted in a fixed order the stroke would double back on
        // itself and draw a spike that never happened.
        val values = MutableList(20) { 5.0 }
        values[2] = 9.0 // high first…
        values[5] = 1.0 // …then low, inside the same bucket
        val kept = chartRenderIndices(values, columns = 2)
        assertTrue(kept.indexOf(2) < kept.indexOf(5))
    }

    @Test
    fun `degenerate inputs are survivable`() {
        assertEquals(emptyList<Int>(), chartRenderIndices(emptyList(), columns = 10))
        assertEquals(listOf(0), chartRenderIndices(listOf(1.0), columns = 10))
        assertEquals(listOf(0, 1), chartRenderIndices(listOf(1.0, 2.0), columns = 1))
        // A zero or negative column budget must not divide by zero.
        assertTrue(chartRenderIndices(ramp(50), columns = 0).isNotEmpty())
        assertTrue(chartRenderIndices(ramp(50), columns = -5).isNotEmpty())
    }

    @Test
    fun `a monotonic ramp keeps its shape`() {
        // Reduction must not introduce a wobble into a straight line: on a ramp
        // the kept values are still ascending.
        val values = ramp(1000)
        val kept = chartRenderIndices(values, columns = 50)
        val heights = kept.map { values[it] }
        assertEquals(heights.sorted(), heights)
        // And the reduction is roughly even, not bunched at one end.
        val gaps = kept.zipWithNext { a, b -> b - a }.filter { it > 0 }
        val mean = gaps.average()
        assertTrue("uneven reduction: $gaps", gaps.all { abs(it - mean) <= mean + 2 })
    }
}
