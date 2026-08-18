package at.bettertrack.app.ui.charts.viz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pins the promises the round-5 chart study makes about each new form. These
 * are not smoke tests: every assertion here is a claim the UI makes to the user
 * ("area is value", "one dot is one percent", "the axis is symmetric"), and a
 * silent regression in any of them would turn a chart into a lie.
 */
class VizGeometryTest {

    // Set A from the study: Anlageklassen, 38.579,23 € total.
    private val setA = listOf(
        VizDatum("aktien", "Aktien", 16_203.28),
        VizDatum("etfs", "ETFs", 10_802.18),
        VizDatum("krypto", "Krypto", 6_172.68),
        VizDatum("cash", "Cash", 3_086.34, role = VizRole.Cash),
        VizDatum("anleihen", "Anleihen", 1_543.17),
        VizDatum("andere", "Andere", 771.58, role = VizRole.Other),
    )

    // Set B: the 19-position long tail, as shares.
    private val setB = listOf(
        "MSFT" to 12.8, "VWCE" to 11.8, "ETH" to 10.4, "BAYN.DE" to 8.4, "RKLB" to 7.5,
        "NVDA" to 6.8, "AAPL" to 6.2, "BTC" to 5.6, "ASML" to 5.0, "SAP" to 4.6,
        "VUSA" to 4.1, "SOL" to 3.6, "AMZN" to 3.2, "GOOGL" to 2.7, "NESN" to 2.3,
        "LVMH" to 1.9, "ADA" to 1.5, "DOT" to 0.9, "LINK" to 0.7,
    ).map { (ticker, share) -> VizDatum(ticker, ticker, share) }

    // Set D: today's movers around a visible zero.
    private val setD = listOf(
        VizDatum("RKLB", "RKLB", 184.20),
        VizDatum("ETH", "ETH", 121.44),
        VizDatum("MSFT", "MSFT", 82.91),
        VizDatum("ASML", "ASML", 31.50),
        VizDatum("SAP", "SAP", -12.18),
        VizDatum("VUSA", "VUSA", -18.90),
        VizDatum("SOL", "SOL", -74.32),
        VizDatum("BAYN.DE", "BAYN.DE", -143.75),
    )

    private val canvas = VizRect.of(760f, 480f)

    // -----------------------------------------------------------------------
    // Treemap
    // -----------------------------------------------------------------------

    @Test
    fun `treemap tiling covers the whole rect exactly`() {
        val tiles = squarifiedTreemap(setA, canvas)
        assertEquals(setA.size, tiles.size)
        val covered = tiles.sumOf { it.rect.area.toDouble() }
        assertEquals(
            "tiles must partition the canvas",
            canvas.area.toDouble(),
            covered,
            canvas.area * 0.001,
        )
    }

    @Test
    fun `treemap tiles stay inside the bounds and never overlap`() {
        val tiles = squarifiedTreemap(setB, canvas)
        assertEquals(setB.size, tiles.size)
        tiles.forEach { tile ->
            assertTrue("${tile.key} escapes left", tile.rect.left >= canvas.left - 0.01f)
            assertTrue("${tile.key} escapes top", tile.rect.top >= canvas.top - 0.01f)
            assertTrue("${tile.key} escapes right", tile.rect.right <= canvas.right + 0.01f)
            assertTrue("${tile.key} escapes bottom", tile.rect.bottom <= canvas.bottom + 0.01f)
            assertTrue("${tile.key} is degenerate", tile.rect.width > 0f && tile.rect.height > 0f)
        }
        for (i in tiles.indices) {
            for (j in i + 1 until tiles.size) {
                assertFalse(
                    "${tiles[i].key} overlaps ${tiles[j].key}",
                    overlaps(tiles[i].rect, tiles[j].rect),
                )
            }
        }
    }

    @Test
    fun `treemap area is proportional to value`() {
        val tiles = squarifiedTreemap(setA, canvas).associateBy { it.key }
        val total = setA.sumOf { it.value }
        setA.forEach { datum ->
            val expected = datum.value / total * canvas.area
            val actual = tiles.getValue(datum.key).rect.area.toDouble()
            assertEquals(datum.label, expected, actual, expected * 0.02)
        }
    }

    @Test
    fun `treemap drops non-positive values instead of drawing nothing`() {
        val withZero = setA + VizDatum("zero", "Zero", 0.0)
        val tiles = squarifiedTreemap(withZero, canvas)
        assertEquals(setA.size, tiles.size)
        assertFalse(tiles.any { it.key == "zero" })
    }

    @Test
    fun `treemap of an empty or degenerate canvas is empty, not a crash`() {
        assertTrue(squarifiedTreemap(emptyList(), canvas).isEmpty())
        assertTrue(squarifiedTreemap(setA, VizRect.of(0f, 480f)).isEmpty())
        assertTrue(squarifiedTreemap(setA.map { it.copy(value = 0.0) }, canvas).isEmpty())
    }

    // -----------------------------------------------------------------------
    // Ordered mosaic
    // -----------------------------------------------------------------------

    @Test
    fun `mosaic tiling covers the whole rect exactly`() {
        val tiles = orderedMosaic(setA, canvas)
        assertEquals(setA.size, tiles.size)
        assertEquals(
            canvas.area.toDouble(),
            tiles.sumOf { it.rect.area.toDouble() },
            canvas.area * 0.001,
        )
    }

    @Test
    fun `mosaic preserves the given reading order`() {
        // The mosaic must not sort: its whole point is a predictable
        // left-to-right, top-to-bottom scan that survives value changes.
        val tiles = orderedMosaic(setA, canvas)
        assertEquals(setA.map { it.key }, tiles.map { it.key })
    }

    @Test
    fun `mosaic tiles never overlap`() {
        val tiles = orderedMosaic(setA, canvas)
        for (i in tiles.indices) {
            for (j in i + 1 until tiles.size) {
                assertFalse(
                    "${tiles[i].key} overlaps ${tiles[j].key}",
                    overlaps(tiles[i].rect, tiles[j].rect),
                )
            }
        }
    }

    @Test
    fun `mosaic is stable under a small value change`() {
        // A 1 % nudge must not reorder or teleport a cell — that stability is
        // the reason the study picks the mosaic over the treemap for widgets.
        val before = orderedMosaic(setA, canvas)
        val nudged = setA.map { if (it.key == "krypto") it.copy(value = it.value * 1.01) else it }
        val after = orderedMosaic(nudged, canvas)
        assertEquals(before.map { it.key }, after.map { it.key })
        before.zip(after).forEach { (b, a) ->
            assertTrue(
                "${b.key} moved too far for a 1 % change",
                abs(b.rect.left - a.rect.left) < canvas.width * 0.1f &&
                    abs(b.rect.top - a.rect.top) < canvas.height * 0.1f,
            )
        }
    }

    // -----------------------------------------------------------------------
    // Waffle
    // -----------------------------------------------------------------------

    @Test
    fun `waffle always totals exactly one hundred cells`() {
        assertEquals(100, waffleCounts(setA).sum())
        assertEquals(100, waffleCounts(setB).sum())
        assertEquals(100, waffleCells(setA).size)
    }

    @Test
    fun `waffle matches the study's published counts for set A`() {
        val counts = setA.map { it.key }.zip(waffleCounts(setA)).toMap()
        assertEquals(42, counts.getValue("aktien"))
        assertEquals(28, counts.getValue("etfs"))
        assertEquals(16, counts.getValue("krypto"))
        assertEquals(8, counts.getValue("cash"))
        assertEquals(4, counts.getValue("anleihen"))
        assertEquals(2, counts.getValue("andere"))
    }

    @Test
    fun `waffle cells are contiguous and in the given order`() {
        val cells = waffleCells(setA)
        val runs = cells.fold(mutableListOf<String>()) { acc, key ->
            if (acc.lastOrNull() != key) acc += key
            acc
        }
        // One run per category means no category is split across the grid.
        assertEquals(setA.map { it.key }, runs)
    }

    @Test
    fun `waffle of an empty set fills no cells`() {
        assertTrue(waffleCells(emptyList()).isEmpty())
        assertEquals(listOf(0, 0), waffleCounts(setA.take(2).map { it.copy(value = 0.0) }))
    }

    @Test
    fun `waffle allocation uses largest remainder, not truncation`() {
        // Three equal thirds: truncation would give 33+33+33 = 99.
        val thirds = listOf(
            VizDatum("a", "A", 1.0),
            VizDatum("b", "B", 1.0),
            VizDatum("c", "C", 1.0),
        )
        val counts = waffleCounts(thirds)
        assertEquals(100, counts.sum())
        assertTrue(counts.all { it == 33 || it == 34 })
    }

    // -----------------------------------------------------------------------
    // Ranked bars
    // -----------------------------------------------------------------------

    @Test
    fun `ranked bars are ordered descending with the longest bar full width`() {
        val bars = rankedBars(setA)
        assertEquals(
            listOf("aktien", "etfs", "krypto", "cash", "anleihen", "andere"),
            bars.map { it.datum.key },
        )
        assertEquals(1f, bars.first().fraction, 0.0001f)
        val fractions = bars.map { it.fraction }
        assertEquals(fractions.sortedDescending(), fractions)
    }

    @Test
    fun `ranked bar fractions are on the common max scale`() {
        val bars = rankedBars(setA).associateBy { it.datum.key }
        val max = setA.maxOf { it.value }
        setA.forEach { datum ->
            assertEquals(
                datum.label,
                (datum.value / max).toFloat(),
                bars.getValue(datum.key).fraction,
                0.0001f,
            )
        }
    }

    @Test
    fun `ranked bars keep the aggregate bucket last whatever its size`() {
        // The bucket outweighs every named row here; it must still sort last,
        // because it is a summary of the rows below, not a competitor to them.
        val reduced = reduceToTopN(setB, 4) { count, _ -> "Weitere · $count" }
        val bars = rankedBars(reduced)
        assertEquals(VIZ_BUCKET_KEY, bars.last().datum.key)
        assertTrue(bars.last().datum.value > bars.first().datum.value)
    }

    @Test
    fun `ranked bars of an all-zero set draw no fill instead of dividing by zero`() {
        val bars = rankedBars(setA.map { it.copy(value = 0.0) })
        assertEquals(setA.size, bars.size)
        assertTrue(bars.all { it.fraction == 0f })
    }

    // -----------------------------------------------------------------------
    // Signed dot plot
    // -----------------------------------------------------------------------

    @Test
    fun `dot plot puts zero exactly on the axis`() {
        val withZero = setD + VizDatum("FLAT", "FLAT", 0.0)
        val rows = signedDotPlot(withZero).associateBy { it.datum.key }
        assertEquals(0.5f, rows.getValue("FLAT").axisFraction, 0.0001f)
    }

    @Test
    fun `dot plot axis is symmetric around zero`() {
        val symmetric = listOf(
            VizDatum("up", "UP", 100.0),
            VizDatum("down", "DOWN", -100.0),
        )
        val rows = signedDotPlot(symmetric).associateBy { it.datum.key }
        assertEquals(1f, rows.getValue("up").axisFraction, 0.0001f)
        assertEquals(0f, rows.getValue("down").axisFraction, 0.0001f)

        // An asymmetric set still gets a symmetric axis: the smaller side must
        // not be stretched to the edge, or losses would look like gains.
        val skewed = signedDotPlot(setD).associateBy { it.datum.key }
        assertEquals(1f, skewed.getValue("RKLB").axisFraction, 0.0001f)
        val bayn = skewed.getValue("BAYN.DE").axisFraction
        assertTrue("largest loss must not reach the axis end", bayn > 0f)
        assertEquals(0.5 - 143.75 / 184.20 / 2.0, bayn.toDouble(), 0.0001)
    }

    @Test
    fun `dot plot orders gains above losses`() {
        val rows = signedDotPlot(setD)
        assertEquals("RKLB", rows.first().datum.key)
        assertEquals("BAYN.DE", rows.last().datum.key)
        val values = rows.map { it.datum.value }
        assertEquals(values.sortedDescending(), values)
    }

    @Test
    fun `dot plot with no movement parks every row on the axis`() {
        val rows = signedDotPlot(setD.map { it.copy(value = 0.0) })
        assertTrue(rows.all { it.axisFraction == 0.5f })
    }

    @Test
    fun `extrema reduce the movers to one winner and one loser`() {
        val extrema = signedExtrema(setD)
        assertEquals(listOf("RKLB", "BAYN.DE"), extrema.map { it.key })
    }

    @Test
    fun `extrema of a one-sided day return only the side that exists`() {
        val gainsOnly = setD.filter { it.value > 0 }
        assertEquals(listOf("RKLB"), signedExtrema(gainsOnly).map { it.key })
        assertTrue(signedExtrema(emptyList()).isEmpty())
    }

    // -----------------------------------------------------------------------
    // Bucketing
    // -----------------------------------------------------------------------

    @Test
    fun `reduce preserves the total exactly`() {
        val reduced = reduceToTopN(setB, 5) { count, _ -> "Weitere · $count" }
        assertEquals(setB.sumOf { it.value }, reduced.sumOf { it.value }, 0.000001)
    }

    @Test
    fun `reduce never sweeps cash into the bucket`() {
        val reduced = reduceToTopN(setA, 3) { count, _ -> "Weitere · $count" }
        assertTrue(reduced.any { it.key == "cash" })
        assertEquals(setA.sumOf { it.value }, reduced.sumOf { it.value }, 0.000001)
    }

    @Test
    fun `reduce tells the label how many rows are hidden and whether Andere exists`() {
        var seenCount = -1
        var seenRealOther = false
        reduceToTopN(setA, 4) { count, realOther ->
            seenCount = count
            seenRealOther = realOther
            "Weitere · $count"
        }
        // Cash and the supplied Andere are pinned, so of the four ordinary
        // classes only the top two survive a cap of four.
        assertEquals(2, seenCount)
        assertTrue("set A ships a real Andere, so the bucket must be 'Weitere'", seenRealOther)
    }

    @Test
    fun `reduce puts the bucket last and leaves short lists untouched`() {
        val reduced = reduceToTopN(setB, 5) { count, _ -> "Weitere · $count" }
        assertEquals(6, reduced.size)
        assertEquals(VIZ_BUCKET_KEY, reduced.last().key)
        assertEquals(14, reduced.last().hiddenCount)

        val untouched = reduceToTopN(setA, 10) { count, _ -> "Weitere · $count" }
        assertEquals(setA, untouched)
        assertEquals(setA, reduceToTopN(setA, 0) { count, _ -> "Weitere · $count" })
    }

    @Test
    fun `stable colour indices survive a rank change among the leaders`() {
        val indexed = withStableColorIndices(setA).associate { it.key to it.colorIndex }
        assertEquals(0, indexed.getValue("aktien"))
        assertEquals(1, indexed.getValue("etfs"))
        assertEquals(2, indexed.getValue("krypto"))
        // Role-fixed colours opt out of the ramp entirely.
        assertEquals(-1, indexed.getValue("cash"))
        assertEquals(-1, indexed.getValue("andere"))
        // Anleihen is the third ordinary category, so it takes ramp slot 3 even
        // though Cash outranks it by value.
        assertEquals(3, indexed.getValue("anleihen"))
    }

    // -----------------------------------------------------------------------
    // Displayed shares
    // -----------------------------------------------------------------------

    @Test
    fun `printed whole percentages sum to one hundred`() {
        assertEquals(100, wholePercentShares(setA.map { it.value }).sum())
        assertEquals(100, wholePercentShares(setB.map { it.value }).sum())
        assertEquals(100, wholePercentShares(listOf(1.0, 1.0, 1.0)).sum())
    }

    @Test
    fun `printed whole percentages match the study's set A column`() {
        assertEquals(
            listOf(42, 28, 16, 8, 4, 2),
            wholePercentShares(setA.map { it.value }),
        )
    }

    @Test
    fun `printed whole percentages of nothing are all zero`() {
        assertTrue(wholePercentShares(emptyList()).isEmpty())
        assertEquals(listOf(0, 0), wholePercentShares(listOf(0.0, 0.0)))
    }

    // -----------------------------------------------------------------------
    // Packed bubbles
    // -----------------------------------------------------------------------

    @Test
    fun `bubbles never overlap`() {
        listOf(setA, setB).forEach { data ->
            val circles = packedBubbles(data, canvas)
            assertEquals(data.size, circles.size)
            for (i in circles.indices) {
                for (j in i + 1 until circles.size) {
                    val a = circles[i]
                    val b = circles[j]
                    val gap = kotlin.math.hypot((a.cx - b.cx).toDouble(), (a.cy - b.cy).toDouble())
                    assertTrue(
                        "${a.key} overlaps ${b.key}",
                        gap >= (a.r + b.r) - 0.5,
                    )
                }
            }
        }
    }

    @Test
    fun `bubbles stay inside the canvas`() {
        packedBubbles(setA, canvas).forEach { c ->
            assertTrue("${c.key} escapes left", c.cx - c.r >= canvas.left - 0.5f)
            assertTrue("${c.key} escapes top", c.cy - c.r >= canvas.top - 0.5f)
            assertTrue("${c.key} escapes right", c.cx + c.r <= canvas.right + 0.5f)
            assertTrue("${c.key} escapes bottom", c.cy + c.r <= canvas.bottom + 0.5f)
            assertTrue("${c.key} is degenerate", c.r > 0f)
        }
    }

    @Test
    fun `bubble packing is deterministic`() {
        // The study gates this form on determinism: a packing that reshuffles
        // between refreshes makes the same portfolio look like a different one.
        val first = packedBubbles(setB, canvas)
        val second = packedBubbles(setB, canvas)
        assertEquals(first, second)
        // Input ORDER must not matter either — the algorithm sorts internally,
        // so a reordered list is the same picture.
        assertEquals(first, packedBubbles(setB.reversed(), canvas))
    }

    @Test
    fun `bubble AREA is proportional to value`() {
        // Radius must scale with the square root, or a 2x position looks 4x.
        val circles = packedBubbles(setA, canvas).associateBy { it.key }
        val aktien = circles.getValue("aktien")
        val etfs = circles.getValue("etfs")
        val expected = 16_203.28 / 10_802.18
        val actual = (aktien.r * aktien.r).toDouble() / (etfs.r * etfs.r)
        assertEquals(expected, actual, expected * 0.02)
    }

    @Test
    fun `the largest bubble is the largest value`() {
        val circles = packedBubbles(setA, canvas)
        assertEquals("aktien", circles.maxByOrNull { it.r }!!.key)
    }

    @Test
    fun `bubbles handle degenerate input without crashing`() {
        assertTrue(packedBubbles(emptyList(), canvas).isEmpty())
        assertTrue(packedBubbles(setA, VizRect.of(0f, 100f)).isEmpty())
        assertTrue(packedBubbles(setA.map { it.copy(value = 0.0) }, canvas).isEmpty())
        assertEquals(1, packedBubbles(setA.take(1), canvas).size)
    }

    @Test
    fun `a single bubble fills the canvas rather than sitting in a corner`() {
        val only = packedBubbles(listOf(VizDatum("solo", "Solo", 10.0)), canvas).single()
        assertEquals(canvas.width / 2f, only.cx, 1f)
        assertEquals(canvas.height / 2f, only.cy, 1f)
    }

    private fun overlaps(a: VizRect, b: VizRect): Boolean {
        val e = 0.01f
        return a.left < b.right - e && b.left < a.right - e &&
            a.top < b.bottom - e && b.top < a.bottom - e
    }
}
