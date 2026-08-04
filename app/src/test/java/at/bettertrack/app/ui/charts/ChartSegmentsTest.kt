package at.bettertrack.app.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6 P0-3: market-closed stretches must render as GAPS, not as a straight
 * diagonal ramp joining the last tick before the close to the first one after
 * it. These tests pin the segmentation rule the chart draws from.
 */
class ChartSegmentsTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    /** Ascending timestamps from [start], one per step in [steps]. */
    private fun series(start: Long, vararg steps: Long): List<Long> {
        val out = ArrayList<Long>(steps.size + 1)
        var t = start
        out.add(t)
        steps.forEach { t += it; out.add(t) }
        return out
    }

    // ── Median spacing ──────────────────────────────────────────────────────

    @Test
    fun `median spacing ignores a single huge outlier`() {
        // Nine 5-minute steps and one overnight hole: the median stays 5 min,
        // which is the whole point — a mean would be dragged to ~2 h and the
        // gap would hide itself.
        val times = series(0L, 5 * minute, 5 * minute, 5 * minute, 5 * minute, 17 * hour, 5 * minute, 5 * minute)
        assertEquals(5 * minute, medianSpacingMs(times))
    }

    @Test
    fun `median spacing tolerates duplicate and out-of-order stamps`() {
        assertEquals(10 * minute, medianSpacingMs(listOf(0L, 10 * minute, 10 * minute, 20 * minute)))
        assertEquals(0L, medianSpacingMs(listOf(5L, 5L, 5L)))
        assertEquals(0L, medianSpacingMs(listOf(42L)))
        assertEquals(0L, medianSpacingMs(emptyList()))
    }

    @Test
    fun `median of an even number of steps averages the two central ones`() {
        // Steps: 1, 2, 4, 5 min → (2+4)/2 = 3 min.
        val times = series(0L, minute, 2 * minute, 4 * minute, 5 * minute)
        assertEquals(3 * minute, medianSpacingMs(times))
    }

    // ── Threshold ───────────────────────────────────────────────────────────

    @Test
    fun `threshold is three times the median, floored at ninety minutes`() {
        // Dense intraday series: 3 x 5 min = 15 min is below the floor, so the
        // floor wins and a five-minute hole in the feed does NOT break the line.
        val dense = series(0L, 5 * minute, 5 * minute, 5 * minute)
        assertEquals(CHART_GAP_FLOOR_MS, chartGapThresholdMs(dense))

        // Daily series: 3 x 24 h = 72 h, well above the floor.
        val daily = series(0L, day, day, day)
        assertEquals(3 * day, chartGapThresholdMs(daily))
    }

    @Test
    fun `a series with no usable spacing never breaks`() {
        assertEquals(Long.MAX_VALUE, chartGapThresholdMs(listOf(7L, 7L)))
    }

    // ── Segmentation ────────────────────────────────────────────────────────

    @Test
    fun `an evenly sampled series is one segment`() {
        val times = series(0L, 5 * minute, 5 * minute, 5 * minute, 5 * minute)
        assertEquals(listOf(0..4), chartSegments(times))
    }

    @Test
    fun `an overnight market close breaks the series in two`() {
        // The P0-3 symptom: four intraday ticks, then the next session.
        val times = series(0L, 5 * minute, 5 * minute, 5 * minute, 17 * hour, 5 * minute, 5 * minute)
        assertEquals(listOf(0..3, 4..6), chartSegments(times))
    }

    @Test
    fun `a normal weekend does not break a daily series`() {
        // Fri → Mon is exactly 3 x the 1-day median: 3x is the threshold and the
        // break is strictly greater-than, so the weekend stays connected.
        val times = series(0L, day, day, 3 * day, day, day)
        assertEquals(listOf(0..5), chartSegments(times))
    }

    @Test
    fun `a long market holiday does break a daily series`() {
        val times = series(0L, day, day, 5 * day, day, day)
        assertEquals(listOf(0..2, 3..5), chartSegments(times))
    }

    @Test
    fun `every index lands in exactly one ascending segment`() {
        val times = series(0L, 5 * minute, 20 * hour, 5 * minute, 20 * hour, 5 * minute)
        val segments = chartSegments(times)
        assertEquals(times.indices.toList(), segments.flatMap { it.toList() })
        segments.zipWithNext().forEach { (a, b) -> assertTrue(a.last < b.first) }
    }

    @Test
    fun `an isolated point is its own single-index segment`() {
        // A lone observation between two gaps — the chart marks it with a dot
        // rather than dropping it or connecting across the gap. The dense runs
        // on either side keep the median (and therefore the threshold) small.
        val times = series(
            0L,
            5 * minute, 5 * minute, 5 * minute,
            30 * hour,
            30 * hour,
            5 * minute, 5 * minute, 5 * minute,
        )
        assertEquals(5 * minute, medianSpacingMs(times))
        assertEquals(listOf(0..3, 4..4, 5..8), chartSegments(times))
    }

    @Test
    fun `a mixed-density series is not shattered into dots`() {
        // A MAX-shaped series: a weekly-sampled early half, a daily-sampled late
        // half. The median comes from the dense half, so every weekly step looks
        // like a gap — but they are the sampling rate, not market closures. The
        // anti-shatter guard keeps the series connected.
        val steps = LongArray(10) { 7 * day } + LongArray(14) { day }
        val times = series(0L, *steps)
        assertEquals(listOf(times.indices), chartSegments(times))
    }

    @Test
    fun `a single trailing gap survives the anti-shatter guard`() {
        // The P0-3 1D symptom: a dense session and one lone point hours later.
        // Two segments, one of them a single point — that is not "most", so the
        // gap is kept.
        val steps = LongArray(20) { 5 * minute } + longArrayOf(6 * hour)
        val times = series(0L, *steps)
        assertEquals(listOf(0..20, 21..21), chartSegments(times))
    }

    @Test
    fun `degenerate inputs are handled`() {
        assertEquals(emptyList<IntRange>(), chartSegments(emptyList()))
        assertEquals(listOf(0..0), chartSegments(listOf(1L)))
        assertEquals(listOf(0..1), chartSegments(listOf(1L, 1L)))
    }
}
