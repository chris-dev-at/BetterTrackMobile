package at.bettertrack.app.ui.charts

import at.bettertrack.app.data.repo.PricePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a buy or a sell lands on the price curve (owner order 2026-08-10: *"a
 * nice graph that shows you where you bought at what time and where you sold"*).
 *
 * The mark's position is a factual claim — "you bought here, at this price, on
 * this day" — so the arithmetic behind it is pinned rather than eyeballed.
 */
class ChartMarkersTest {

    private val hour = 3_600_000L

    /** Ten hourly points starting at t=0, price 100, 101, … 109. */
    private fun series(n: Int = 10): List<PricePoint> =
        (0 until n).map { PricePoint(timeMs = it * hour, close = 100.0 + it) }

    private fun buy(t: Long, price: Double = 100.0) =
        ChartMarker(timeMs = t, price = price, kind = ChartMarker.Kind.BUY)

    private fun sell(t: Long, price: Double = 100.0) =
        ChartMarker(timeMs = t, price = price, kind = ChartMarker.Kind.SELL)

    @Test
    fun `a marker snaps to the nearest point in time`() {
        val s = series()
        assertEquals(0, nearestIndex(s, -5 * hour))
        assertEquals(0, nearestIndex(s, hour / 3))
        assertEquals(1, nearestIndex(s, hour * 2 / 3))
        assertEquals(4, nearestIndex(s, 4 * hour))
        assertEquals(9, nearestIndex(s, 99 * hour))
    }

    @Test
    fun `trades outside the plotted window are dropped, never clamped`() {
        val s = series()
        val placed = placeMarkers(
            listOf(buy(-10 * hour), buy(3 * hour), sell(50 * hour)),
            s,
        )
        assertEquals(1, placed.clusters.size)
        assertEquals(3, placed.clusters.single().index)
    }

    @Test
    fun `a marker keeps the price the user paid, not the close beside it`() {
        // Point 3 closed at 103; the fill was at 97.40. The gap is the content.
        val placed = placeMarkers(listOf(buy(3 * hour, price = 97.4)), series())
        assertEquals(97.4, placed.clusters.single().price, 0.0001)
    }

    @Test
    fun `buys and sells in the same slot stay two glyphs`() {
        val placed = placeMarkers(listOf(buy(3 * hour), sell(3 * hour)), series())
        assertEquals(2, placed.clusters.size)
        assertEquals(
            setOf(ChartMarker.Kind.BUY, ChartMarker.Kind.SELL),
            placed.clusters.map { it.kind }.toSet(),
        )
    }

    @Test
    fun `neighbouring trades on a dense series collapse into one glyph`() {
        // 560 points is a 1-minute intraday range; MARKER_SLOTS is 28, so 20
        // indices share a slot and two trades three points apart are one blob.
        val dense = (0 until 560).map { PricePoint(it * 60_000L, 100.0) }
        val placed = placeMarkers(
            listOf(buy(100 * 60_000L), buy(103 * 60_000L)),
            dense,
        )
        assertEquals(1, placed.clusters.size)
        assertEquals(2, placed.clusters.single().members.size)
    }

    @Test
    fun `a sparse series keeps every trade separate`() {
        val placed = placeMarkers(listOf(buy(2 * hour), buy(3 * hour)), series())
        assertEquals(2, placed.clusters.size)
    }

    @Test
    fun `the crosshair finds the trades in the slot it is standing on`() {
        val dense = (0 until 560).map { PricePoint(it * 60_000L, 100.0) }
        val placed = placeMarkers(listOf(buy(100 * 60_000L), sell(400 * 60_000L)), dense)
        assertEquals(1, placed.at(100).size)
        assertEquals(ChartMarker.Kind.BUY, placed.at(100).single().kind)
        assertEquals(ChartMarker.Kind.SELL, placed.at(400).single().kind)
        assertTrue(placed.at(250).isEmpty())
    }

    @Test
    fun `a cluster reports a real trade's index, never an average`() {
        val dense = (0 until 560).map { PricePoint(it * 60_000L, 100.0) }
        val placed = placeMarkers(
            listOf(buy(100 * 60_000L, 90.0), buy(110 * 60_000L, 110.0)),
            dense,
        )
        val cluster = placed.clusters.single()
        assertEquals(90.0, cluster.price, 0.0001)
        assertEquals(100, cluster.index)
    }

    @Test
    fun `nothing is placed on a series too short to draw`() {
        assertEquals(0, placeMarkers(listOf(buy(0)), emptyList()).clusters.size)
        assertEquals(0, placeMarkers(listOf(buy(0)), listOf(PricePoint(0, 1.0))).clusters.size)
        assertEquals(0, placeMarkers(emptyList(), series()).clusters.size)
    }

    @Test
    fun `a non-finite price cannot be plotted`() {
        val placed = placeMarkers(listOf(buy(3 * hour, Double.NaN)), series())
        assertEquals(0, placed.clusters.size)
    }
}
