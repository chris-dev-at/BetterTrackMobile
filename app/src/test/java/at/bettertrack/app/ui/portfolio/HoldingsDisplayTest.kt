package at.bettertrack.app.ui.portfolio

import at.bettertrack.app.data.db.HoldingEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Holdings list's display rules (owner UI batch 2026-08-16): sold-out
 * positions vanish ([visibleHoldings]), and the sort toggle's two orders
 * ([sortedHoldings]) are pinned — allocation size stays the default.
 */
class HoldingsDisplayTest {

    private fun holding(
        assetId: String,
        quantity: Double,
        marketValueEur: Double? = null,
        unrealizedPnlEur: Double? = null,
    ) = HoldingEntity(
        portfolioId = "p1",
        assetId = assetId,
        assetSymbol = assetId,
        assetName = assetId,
        assetExchange = null,
        assetCurrency = "EUR",
        assetType = "stock",
        assetIsCustom = false,
        quantity = quantity,
        avgCost = 100.0,
        realizedPnl = 0.0,
        price = null,
        marketValueEur = marketValueEur,
        costBasisEur = null,
        unrealizedPnlEur = unrealizedPnlEur,
        unrealizedPnlPct = null,
        dayChangeEur = null,
        dayChangePct = null,
    )

    // ── visibleHoldings ─────────────────────────────────────────────────────

    @Test
    fun `a sold-out position leaves the list`() {
        val rows = listOf(
            holding("AAPL", quantity = 4.0),
            holding("PEBIX", quantity = 0.0),
        )
        assertEquals(listOf("AAPL"), visibleHoldings(rows).map { it.assetId })
    }

    @Test
    fun `dust and short positions stay`() {
        val rows = listOf(
            holding("BTC", quantity = 0.00000001),
            holding("TSLA", quantity = -2.0),
        )
        assertEquals(rows, visibleHoldings(rows))
    }

    @Test
    fun `an all-sold portfolio shows an empty list`() {
        assertEquals(
            emptyList<HoldingEntity>(),
            visibleHoldings(listOf(holding("PEBIX", quantity = 0.0))),
        )
    }

    // ── sortedHoldings ──────────────────────────────────────────────────────

    @Test
    fun `allocation order restates the DAO order - biggest value first`() {
        val rows = listOf(
            holding("SMALL", 1.0, marketValueEur = 50.0),
            holding("BIG", 1.0, marketValueEur = 900.0),
            holding("MID", 1.0, marketValueEur = 200.0),
        )
        assertEquals(
            listOf("BIG", "MID", "SMALL"),
            sortedHoldings(rows, HoldingsSort.ALLOCATION).map { it.assetId },
        )
    }

    @Test
    fun `profit order ranks by unrealized P&L - best first, losses last`() {
        val rows = listOf(
            holding("FLAT", 1.0, unrealizedPnlEur = 0.0),
            holding("WINNER", 1.0, unrealizedPnlEur = 320.0),
            holding("LOSER", 1.0, unrealizedPnlEur = -75.0),
        )
        assertEquals(
            listOf("WINNER", "FLAT", "LOSER"),
            sortedHoldings(rows, HoldingsSort.PROFIT).map { it.assetId },
        )
    }

    @Test
    fun `unpriced rows sink to the bottom in both orders`() {
        val rows = listOf(
            holding("NOPRICE", 1.0),
            holding("PRICED", 1.0, marketValueEur = 10.0, unrealizedPnlEur = -5.0),
        )
        assertEquals(
            listOf("PRICED", "NOPRICE"),
            sortedHoldings(rows, HoldingsSort.ALLOCATION).map { it.assetId },
        )
        assertEquals(
            listOf("PRICED", "NOPRICE"),
            sortedHoldings(rows, HoldingsSort.PROFIT).map { it.assetId },
        )
    }

    @Test
    fun `the sort toggle offers allocation first - the default order`() {
        assertEquals(listOf(HoldingsSort.ALLOCATION, HoldingsSort.PROFIT), HOLDINGS_SORTS)
        assertEquals(HoldingsSort.ALLOCATION, HOLDINGS_SORTS.first())
    }

    @Test
    fun `every sort has its own label`() {
        assertEquals(
            HOLDINGS_SORTS.size,
            HOLDINGS_SORTS.map { holdingsSortLabel(it) }.toSet().size,
        )
    }
}
