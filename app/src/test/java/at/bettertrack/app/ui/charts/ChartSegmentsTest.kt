package at.bettertrack.app.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * OWNER OVERRIDE 2026-08-06 — **the line is never broken.**
 *
 * S6 P0-3 split the series wherever the gap between two observations exceeded
 * `max(3 × median(Δt), 90 min)`, so that a market close rendered as a gap rather
 * than as a diagonal ramp. The owner reviewed that on the device and rejected it:
 * *"I don't want gaps in the graphs — a continuous line, not gapped."*
 *
 * These tests are the guard on that decision. Every case below is an input that
 * the old rule DID break — an overnight close, a long market holiday, a lone
 * observation between two holes, a mixed-density MAX range — and each one must
 * now come back as a single run covering every index. If someone reintroduces
 * gap-breaking, these fail by name and say whose call it was.
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

    // ── The always-connect contract ─────────────────────────────────────────

    @Test
    fun `an evenly sampled series is one segment`() {
        val times = series(0L, 5 * minute, 5 * minute, 5 * minute, 5 * minute)
        assertEquals(listOf(0..4), chartSegments(times))
    }

    @Test
    fun `an overnight market close does NOT break the series`() {
        // The exact P0-3 symptom the old rule was built for: four intraday ticks,
        // a 17-hour hole, then the next session. Owner override — one line.
        val times = series(0L, 5 * minute, 5 * minute, 5 * minute, 17 * hour, 5 * minute, 5 * minute)
        assertEquals(listOf(0..6), chartSegments(times))
    }

    @Test
    fun `a long market holiday does NOT break a daily series`() {
        val times = series(0L, day, day, 5 * day, day, day)
        assertEquals(listOf(0..5), chartSegments(times))
    }

    @Test
    fun `a normal weekend stays connected in a daily series`() {
        val times = series(0L, day, day, 3 * day, day, day)
        assertEquals(listOf(0..5), chartSegments(times))
    }

    @Test
    fun `a point that used to be isolated between two gaps is joined on both sides`() {
        // The old rule returned [0..3, 4..4, 5..8] here and the chart drew index
        // 4 as a lone dot. It is now a vertex of the one continuous line.
        val times = series(
            0L,
            5 * minute, 5 * minute, 5 * minute,
            30 * hour,
            30 * hour,
            5 * minute, 5 * minute, 5 * minute,
        )
        assertEquals(listOf(0..8), chartSegments(times))
    }

    @Test
    fun `a mixed-density MAX range is one segment`() {
        // A weekly-sampled early half and a daily-sampled late half.
        val steps = LongArray(10) { 7 * day } + LongArray(14) { day }
        val times = series(0L, *steps)
        assertEquals(listOf(times.indices), chartSegments(times))
    }

    @Test
    fun `a dense session followed by one far-later point stays connected`() {
        // The 1D symptom: 20 five-minute ticks, then a point six hours on. The
        // old rule kept that break; the override joins it.
        val steps = LongArray(20) { 5 * minute } + longArrayOf(6 * hour)
        val times = series(0L, *steps)
        assertEquals(listOf(0..21), chartSegments(times))
    }

    // ── Structural guarantees the chart's draw loop relies on ───────────────

    @Test
    fun `there is never more than one segment and it covers every index`() {
        val times = series(0L, 5 * minute, 20 * hour, 5 * minute, 20 * hour, 5 * minute)
        val segments = chartSegments(times)
        assertEquals(1, segments.size)
        assertEquals(times.indices.toList(), segments.single().toList())
    }

    @Test
    fun `degenerate inputs are handled`() {
        assertEquals(emptyList<IntRange>(), chartSegments(emptyList()))
        assertEquals(listOf(0..0), chartSegments(listOf(1L)))
        // Duplicate stamps carry no spacing information and never mattered to the
        // connect decision; they are still two joined vertices.
        assertEquals(listOf(0..1), chartSegments(listOf(1L, 1L)))
    }
}
