package at.bettertrack.app.widget

import at.bettertrack.app.data.db.HoldingEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The heatmap widget's pure half (owner ask 2026-08-18).
 *
 * Two claims are worth pinning hardest, because both are about telling the
 * truth rather than about geometry: the same ticker held twice is ONE position
 * whose percentage is value-weighted, and a holding with no quote today is
 * **not** a holding that moved 0 %.
 */
class BtWidgetHeatmapTest {

    private fun holding(
        symbol: String,
        value: Double?,
        dayPct: Double?,
        portfolioId: String = "p1",
    ) = HoldingEntity(
        portfolioId = portfolioId,
        assetId = "$symbol-$portfolioId",
        assetSymbol = symbol,
        assetName = "$symbol Inc.",
        assetExchange = null,
        assetCurrency = "USD",
        assetType = "stock",
        assetIsCustom = false,
        quantity = 1.0,
        avgCost = 1.0,
        realizedPnl = 0.0,
        price = 1.0,
        marketValueEur = value,
        costBasisEur = null,
        unrealizedPnlEur = null,
        unrealizedPnlPct = null,
        dayChangeEur = null,
        dayChangePct = dayPct,
    )

    // ── Tiles ───────────────────────────────────────────────────────────────

    @Test
    fun `tiles are ordered by value, largest first`() {
        val tiles = btWidgetHeatTiles(
            listOf(
                holding("SMALL", 100.0, 1.0),
                holding("BIG", 900.0, 1.0),
                holding("MID", 400.0, 1.0),
            ),
            maxTiles = 10,
        )
        assertEquals(listOf("BIG", "MID", "SMALL"), tiles.map { it.symbol })
    }

    @Test
    fun `one ticker in two portfolios is one tile with a value-weighted change`() {
        // 900 € at +10 % and 100 € at −10 % is a position that rose 8 %, not one
        // that did nothing. A plain average would print 0 % and be wrong.
        val tiles = btWidgetHeatTiles(
            listOf(
                holding("MSFT", 900.0, 10.0, portfolioId = "p1"),
                holding("MSFT", 100.0, -10.0, portfolioId = "p2"),
            ),
            maxTiles = 10,
        )
        assertEquals(1, tiles.size)
        assertEquals(1000.0, tiles.single().weight, 0.0001)
        assertEquals(8.0, tiles.single().changePct!!, 0.0001)
    }

    @Test
    fun `a holding with no quote stays uncoloured rather than flat`() {
        val tiles = btWidgetHeatTiles(listOf(holding("NEW", 500.0, null)), maxTiles = 10)
        assertNull("no quote must not become 0 %", tiles.single().changePct)
    }

    @Test
    fun `an unquoted row does not drag its quoted sibling toward zero`() {
        val tiles = btWidgetHeatTiles(
            listOf(
                holding("ETH", 500.0, 6.0, portfolioId = "p1"),
                holding("ETH", 500.0, null, portfolioId = "p2"),
            ),
            maxTiles = 10,
        )
        assertEquals(1000.0, tiles.single().weight, 0.0001)
        assertEquals("the weighting must use only the quoted value", 6.0, tiles.single().changePct!!, 0.0001)
    }

    @Test
    fun `holdings with no value are dropped, not drawn as slivers`() {
        val tiles = btWidgetHeatTiles(
            listOf(holding("OK", 100.0, 1.0), holding("ZERO", 0.0, 5.0), holding("NULL", null, 5.0)),
            maxTiles = 10,
        )
        assertEquals(listOf("OK"), tiles.map { it.symbol })
    }

    @Test
    fun `the tail folds into one unnamed cell that preserves the total`() {
        val holdings = (1..12).map { holding("T$it", it * 100.0, 1.0) }
        val tiles = btWidgetHeatTiles(holdings, maxTiles = 4)
        assertEquals(4, tiles.size)
        assertEquals(holdings.sumOf { it.marketValueEur!! }, tiles.sumOf { it.weight }, 0.0001)

        val fold = tiles.last()
        assertEquals("", fold.symbol)
        assertEquals(9, fold.hiddenCount)
        assertNull("an aggregate of unlike moves has no direction to colour", fold.changePct)
    }

    @Test
    fun `a list that already fits is returned untouched`() {
        val holdings = listOf(holding("A", 200.0, 1.0), holding("B", 100.0, 1.0))
        assertEquals(2, btWidgetHeatTiles(holdings, maxTiles = 4).size)
        assertTrue(btWidgetHeatTiles(emptyList(), maxTiles = 4).isEmpty())
    }

    // ── Intensity ───────────────────────────────────────────────────────────

    @Test
    fun `a tile carries the asset it stands for so the in-app map can open it`() {
        // The widget never needs this — a widget is one tap target — but the
        // in-app heatmap navigates from it, and deriving the id a second time
        // there is the duplication this shared function exists to prevent.
        val tiles = btWidgetHeatTiles(
            listOf(holding("AAPL", 100.0, 1.0), holding("MSFT", 50.0, -1.0)),
            maxTiles = 0,
        )
        assertEquals("AAPL-p1", tiles.first { it.symbol == "AAPL" }.assetId)
        assertEquals("MSFT-p1", tiles.first { it.symbol == "MSFT" }.assetId)
    }

    @Test
    fun `one ticker in two portfolios keeps a single asset id`() {
        val tiles = btWidgetHeatTiles(
            listOf(
                holding("AAPL", 100.0, 2.0, portfolioId = "p1"),
                holding("AAPL", 100.0, 4.0, portfolioId = "p2"),
            ),
            maxTiles = 0,
        )
        assertEquals(1, tiles.size)
        // Whichever row won, it must be a real id and not an empty string — an
        // empty id is the folded-bucket signal and would make the tile inert.
        assertTrue("was '${tiles[0].assetId}'", tiles[0].assetId.startsWith("AAPL-p"))
    }

    @Test
    fun `the folded bucket names no asset, because it is several`() {
        val tiles = btWidgetHeatTiles(
            (1..6).map { holding("S$it", (10 - it).toDouble(), 1.0) },
            maxTiles = 3,
        )
        val bucket = tiles.last()
        assertEquals("", bucket.symbol)
        assertEquals("a multi-asset cell must not navigate anywhere", "", bucket.assetId)
    }

    @Test
    fun `the strongest move is fully saturated and the rest scale under it`() {
        assertEquals(1f, btWidgetHeatIntensity(5.0, 5.0), 0.0001f)
        assertEquals(1f, btWidgetHeatIntensity(-5.0, 5.0), 0.0001f)
        assertTrue(btWidgetHeatIntensity(1.0, 5.0) < btWidgetHeatIntensity(4.0, 5.0))
    }

    @Test
    fun `a flat day does not paint itself as a crash`() {
        // The regression this exists for: with scaling relative to the day's own
        // maximum, a board whose biggest move was -0,18 % rendered that tile
        // FULL-strength red on the device. Saturation is a magnitude channel, so
        // a nothing-day has to look like nothing.
        val flatDay = btWidgetHeatIntensity(-0.18, maxAbs = 0.18)
        assertTrue("a 0,18 % move must stay pale, was $flatDay", flatDay < 0.55f)

        val realCrash = btWidgetHeatIntensity(-6.0, maxAbs = 6.0)
        assertEquals(1f, realCrash, 0.0001f)
        assertTrue("a real crash must out-saturate a flat day", realCrash > flatDay)
    }

    @Test
    fun `the day's own maximum still takes over once it is big enough`() {
        // Above the reference the scale stretches, so a violent day uses the
        // full range instead of clipping everything to maximum.
        assertEquals(1f, btWidgetHeatIntensity(10.0, maxAbs = 10.0), 0.0001f)
        assertTrue(btWidgetHeatIntensity(3.0, maxAbs = 10.0) < 1f)
    }

    @Test
    fun `even the smallest mover stays above the legibility floor`() {
        // Below this the directional hue stops reading as a direction at all,
        // which would leave the printed sign doing the work alone.
        assertTrue(btWidgetHeatIntensity(0.01, 100.0) >= 0.42f)
    }

    @Test
    fun `no move and no quote are both fully neutral`() {
        assertEquals(0f, btWidgetHeatIntensity(null, 5.0), 0.0001f)
        assertEquals(0f, btWidgetHeatIntensity(0.0, 5.0), 0.0001f)
    }

    @Test
    fun `a lone mover on an otherwise still board is scaled, not maximised`() {
        // maxAbs of 0 used to mean "no signal"; it now simply falls back to the
        // reference, so one quoted tile on a still board still shows its size.
        assertTrue(btWidgetHeatIntensity(1.0, 0.0) > 0f)
        assertTrue(btWidgetHeatIntensity(1.0, 0.0) < 1f)
    }

    // ── The size ladder ─────────────────────────────────────────────────────

    @Test
    fun `tile forms are refused on a cell too small to label them`() {
        // A one-row strip on this launcher is 120dp tall and much wider; a
        // treemap there is four coloured slivers, so the bar takes over.
        listOf(
            BtWidgetAllocationForm.TREEMAP,
            BtWidgetAllocationForm.MOSAIC,
            BtWidgetAllocationForm.HEATMAP,
        ).forEach { form ->
            assertEquals(
                "$form must fall back on a short cell",
                BtWidgetAllocationForm.BAR,
                btWidgetAllocationFormFor(form, widthDp = 330f, heightDp = 60f),
            )
            assertEquals(
                "$form must fall back on a narrow cell",
                BtWidgetAllocationForm.BAR,
                btWidgetAllocationFormFor(form, widthDp = 70f, heightDp = 250f),
            )
            assertEquals(
                "$form must survive a 2x2 cell",
                form,
                btWidgetAllocationFormFor(form, widthDp = 160f, heightDp = 190f),
            )
        }
    }

    @Test
    fun `the donut and the bar survive every cell`() {
        listOf(BtWidgetAllocationForm.DONUT, BtWidgetAllocationForm.BAR).forEach { form ->
            assertEquals(form, btWidgetAllocationFormFor(form, 330f, 60f))
            assertEquals(form, btWidgetAllocationFormFor(form, 160f, 190f))
        }
    }

    @Test
    fun `a bigger canvas names more tiles`() {
        val small = btWidgetTileCount(160f, 190f)
        val wide = btWidgetTileCount(330f, 120f)
        val large = btWidgetTileCount(330f, 250f)
        // The study's 2x2 capacity is 3-4. Six was tried on device and the last
        // two rendered as unlabelled slivers.
        assertEquals("a 2x2 names four tiles", 4, small)
        assertTrue(wide > small)
        assertTrue(large > wide)
    }

    @Test
    fun `a tall narrow cell is still a narrow cell`() {
        // Height cannot buy horizontal room for a ticker, so growing only the
        // height must not unlock more tiles.
        assertEquals(4, btWidgetTileCount(160f, 400f))
    }

    // ── Blending ────────────────────────────────────────────────────────────

    @Test
    fun `blending walks from the ground colour to the full hue`() {
        val hue = 0xFF34D399.toInt()
        val ground = 0xFF161B22.toInt()
        assertEquals(ground, btWidgetBlendToward(hue, ground, 0f))
        assertEquals(hue, btWidgetBlendToward(hue, ground, 1f))
        // Out-of-range intensities clamp rather than producing garbage pixels.
        assertEquals(ground, btWidgetBlendToward(hue, ground, -3f))
        assertEquals(hue, btWidgetBlendToward(hue, ground, 9f))
    }

    @Test
    fun `a blended colour is always fully opaque`() {
        val blended = btWidgetBlendToward(0x0034D399, 0x00161B22, 0.5f)
        assertEquals(0xFF, (blended ushr 24) and 0xFF)
    }
}
