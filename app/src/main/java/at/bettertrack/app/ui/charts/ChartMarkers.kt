package at.bettertrack.app.ui.charts

import at.bettertrack.app.data.repo.PricePoint

/**
 * A trade to mark on a price chart (owner order 2026-08-10: *"make a nice graph
 * that shows you where you bought at what time and where you sold so you see how
 * good you hit the sell and buy timings"*).
 *
 * [price] is the price the user actually paid or received, in the same unit the
 * series is drawn in — the mark's whole point is the gap between that and the
 * curve, so it is never the curve's own close at that moment.
 */
data class ChartMarker(
    val timeMs: Long,
    val price: Double,
    val kind: Kind,
    /** Carried for the scrub readout; the drawing ignores it. */
    val quantity: Double? = null,
    /** Row identity for the readout, never drawn. */
    val id: String = "",
) {
    enum class Kind { BUY, SELL }
}

/**
 * How many marker slots the chart's width is divided into.
 *
 * Clustering has to happen in *screen* terms — two buys a day apart are one blob
 * on a 1Y chart and two clearly separate glyphs on a 1W one — but this math runs
 * outside the draw scope and must stay pure and testable, so it works in slots
 * rather than pixels: the canvas is treated as this many marker-width columns.
 * On a 360dp-wide chart a slot is ~13dp, which is one 9dp glyph plus air.
 */
internal const val MARKER_SLOTS = 28

/** Which slot point [index] of an [count]-point series falls in. */
internal fun markerSlot(index: Int, count: Int): Int =
    if (count <= 1) 0 else (index.toLong() * MARKER_SLOTS / count).toInt()

/**
 * One drawn glyph. [price] and [index] are the FIRST member's, never an average:
 * a cluster's position is a real trade's position, because a mark placed at a
 * mean of two prices is a point at which nothing happened.
 */
internal data class MarkerCluster(
    val slot: Int,
    val index: Int,
    val kind: ChartMarker.Kind,
    val price: Double,
    val members: List<ChartMarker>,
)

/** Markers mapped onto a series, ready to draw and to look up under a crosshair. */
internal class PlacedMarkers(val clusters: List<MarkerCluster>, private val count: Int) {
    /** Every marker sharing point [index]'s slot — what the crosshair is over. */
    fun at(index: Int): List<ChartMarker> {
        val slot = markerSlot(index, count)
        return clusters.filter { it.slot == slot }.flatMap { it.members }
    }
}

/**
 * Snap [markers] onto [series].
 *
 * ## Out-of-range markers are DROPPED, not clamped
 *
 * A trade older than the window has no honest x: pinning it to the left edge
 * would draw a buy at a date it did not happen on, and the whole value of these
 * marks is that their position is a claim about *when*. So a 1W chart of a
 * position held for a year simply shows no marks, and the page says so in words
 * rather than the chart lying in pictures.
 *
 * Buys and sells cluster separately even when they share a slot, so a day that
 * saw both still shows both — collapsing them would erase exactly the comparison
 * the owner asked for.
 *
 * Pure, and unit-tested (`ChartMarkersTest`), because "which pixel does this buy
 * sit on" is arithmetic and arithmetic should not need a phone to verify.
 */
internal fun placeMarkers(markers: List<ChartMarker>, series: List<PricePoint>): PlacedMarkers {
    if (markers.isEmpty() || series.size < 2) return PlacedMarkers(emptyList(), series.size)
    val from = series.first().timeMs
    val to = series.last().timeMs
    val clusters = markers
        .filter { it.timeMs in from..to && it.price.isFinite() }
        .sortedBy { it.timeMs }
        .map { it to nearestIndex(series, it.timeMs) }
        .groupBy { (marker, index) -> markerSlot(index, series.size) to marker.kind }
        .map { (key, entries) ->
            val (slot, kind) = key
            val (first, index) = entries.first()
            MarkerCluster(
                slot = slot,
                index = index,
                kind = kind,
                price = first.price,
                members = entries.map { it.first },
            )
        }
        .sortedBy { it.index }
    return PlacedMarkers(clusters, series.size)
}

/**
 * The index of the series point closest in time to [timeMs].
 *
 * Binary search rather than a scan: an asset's 1D range comes back as 1-minute
 * candles (~390 points) and this runs once per marker per series change.
 */
internal fun nearestIndex(series: List<PricePoint>, timeMs: Long): Int {
    var lo = 0
    var hi = series.size - 1
    while (hi - lo > 1) {
        val mid = (lo + hi) / 2
        if (series[mid].timeMs <= timeMs) lo = mid else hi = mid
    }
    val dLo = kotlin.math.abs(series[lo].timeMs - timeMs)
    val dHi = kotlin.math.abs(series[hi].timeMs - timeMs)
    return if (dLo <= dHi) lo else hi
}
