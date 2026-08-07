package at.bettertrack.app.ui.charts

import at.bettertrack.app.data.repo.HistoryPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chart's pixel↔series mapping (owner: *"if I select stuff it jumps big
 * time… on the webapp it feels great, here it feels choppy"*, 2026-08-07).
 *
 * The diagnosis was that x was laid out proportionally to TIME while the series
 * is not evenly sampled in time, so the distance between neighbouring points —
 * and therefore the size of every crosshair jump — varied with the data instead
 * of with the finger. The web app's chart (TradingView lightweight-charts) uses
 * an ordinal time scale, one equal slice per point, which is why its scrub feels
 * even. These tests pin that the app now does the same.
 */
class ChartScrubTest {

    // ── Layout: one equal slice per point ─────────────────────────────────────

    @Test
    fun `points are spaced evenly by index, edge to edge`() {
        val w = 300f
        assertEquals(0f, seriesX(0, w, 4), 0.001f)
        assertEquals(100f, seriesX(1, w, 4), 0.001f)
        assertEquals(200f, seriesX(2, w, 4), 0.001f)
        assertEquals(300f, seriesX(3, w, 4), 0.001f)
    }

    @Test
    fun `spacing does not depend on how the series is sampled in time`() {
        // The regression this whole change exists to prevent: a series that is
        // dense recently and sparse earlier must still scrub at a constant rate.
        val w = 300f
        val count = 4
        val gaps = (1 until count).map { seriesX(it, w, count) - seriesX(it - 1, w, count) }
        gaps.forEach { assertEquals(100f, it, 0.001f) }
    }

    @Test
    fun `a degenerate series does not divide by zero`() {
        assertEquals(0f, seriesX(0, 300f, 1), 0.001f)
        assertEquals(0f, seriesX(0, 300f, 0), 0.001f)
    }

    // ── Snapping: nearest index, clamped ──────────────────────────────────────

    @Test
    fun `the scrub snaps to the nearest point`() {
        val w = 300f // 4 points at 0, 100, 200, 300
        assertEquals(0, scrubIndexAt(0f, w, 4))
        assertEquals(0, scrubIndexAt(40f, w, 4))
        assertEquals(1, scrubIndexAt(60f, w, 4))
        assertEquals(1, scrubIndexAt(100f, w, 4))
        assertEquals(2, scrubIndexAt(210f, w, 4))
        assertEquals(3, scrubIndexAt(300f, w, 4))
    }

    @Test
    fun `a finger past either edge clamps instead of falling off the series`() {
        val w = 300f
        assertEquals(0, scrubIndexAt(-500f, w, 4))
        assertEquals(3, scrubIndexAt(5000f, w, 4))
    }

    @Test
    fun `every point is reachable, and each owns an equal slice of the canvas`() {
        // A point that no x maps to is a point the user cannot read.
        val w = 360f
        val count = 12
        val hits = (0..w.toInt()).map { scrubIndexAt(it.toFloat(), w, count) }
        assertEquals((0 until count).toList(), hits.distinct().sorted())
        // Interior cells are the same width to within the pixel grid (a spacing
        // of 360/11 cannot land on integers), and the two end cells are
        // half-cells because the first and last points sit ON the edges. The
        // point of the assertion is that no cell is a MULTIPLE of another — that
        // is what "jumps big time" looked like.
        val widths = (1 until count - 1).map { i -> hits.count { it == i } }
        assertTrue(
            "interior scrub cells are not uniform: $widths",
            widths.max() - widths.min() <= 1,
        )
    }

    @Test
    fun `a zero-width canvas is survivable`() {
        assertEquals(0, scrubIndexAt(10f, 0f, 5))
        assertEquals(0, scrubIndexAt(10f, 300f, 1))
    }

    // ── The y scale ───────────────────────────────────────────────────────────

    @Test
    fun `a money scale never pads below zero`() {
        val scale = yScale(points(100.0, 200.0))
        assertTrue("an all-positive money series must not show a negative axis", scale.min >= 0.0)
    }

    @Test
    fun `a performance scale keeps zero inside the window`() {
        // Without this the zero line — the thing the up/down colouring is
        // coloured AGAINST — would leave the canvas on an all-positive run.
        val up = yScale(points(4.0, 9.0), zeroAnchored = true)
        assertTrue(up.min <= 0.0 && up.max >= 9.0)
        val down = yScale(points(-9.0, -4.0), zeroAnchored = true)
        assertTrue(down.min <= -9.0 && down.max >= 0.0)
    }

    @Test
    fun `a flat series still renders mid-plot instead of dividing by zero`() {
        val scale = yScale(points(50.0, 50.0))
        assertEquals(0.5f, scale.normalize(50.0), 0.02f)
    }

    // ── The morph resampler ───────────────────────────────────────────────────

    @Test
    fun `the morph samples the series across the whole index axis`() {
        val pts = points(0.0, 10.0, 20.0, 30.0)
        val scale = yScale(pts)
        assertEquals(scale.normalize(0.0), normalizedAtFraction(pts, 0f, scale), 0.001f)
        assertEquals(scale.normalize(30.0), normalizedAtFraction(pts, 1f, scale), 0.001f)
        // Halfway along four evenly-indexed points is the midpoint value.
        assertEquals(scale.normalize(15.0), normalizedAtFraction(pts, 0.5f, scale), 0.001f)
    }

    @Test
    fun `the morph resampler survives short and empty series`() {
        val scale = yScale(points(1.0, 2.0))
        assertEquals(0.5f, normalizedAtFraction(emptyList(), 0.5f, scale), 0.001f)
        val one = points(7.0)
        assertEquals(scale.normalize(7.0), normalizedAtFraction(one, 0.5f, scale), 0.001f)
    }

    @Test
    fun `the morph resampler clamps a fraction outside 0 to 1`() {
        val pts = points(0.0, 10.0)
        val scale = yScale(pts)
        assertEquals(normalizedAtFraction(pts, 0f, scale), normalizedAtFraction(pts, -3f, scale), 0.001f)
        assertEquals(normalizedAtFraction(pts, 1f, scale), normalizedAtFraction(pts, 3f, scale), 0.001f)
    }

    private fun points(vararg values: Double): List<HistoryPoint> =
        values.mapIndexed { i, v -> HistoryPoint(epochMillis = i * 86_400_000L, valueEur = v) }
}
