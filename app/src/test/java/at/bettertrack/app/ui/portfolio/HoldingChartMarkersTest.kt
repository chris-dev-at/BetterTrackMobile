package at.bettertrack.app.ui.portfolio

import at.bettertrack.app.data.db.TransactionEntity
import at.bettertrack.app.ui.charts.ChartMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ledger → chart-marker mapping behind the holding page's timing chart
 * (owner order 2026-08-10).
 *
 * Small, but it is the seam where a *sell* could silently become a buy, or a row
 * with no usable timestamp could be drawn at the epoch — both of which would be
 * the chart making a false claim about the user's own history.
 */
class HoldingChartMarkersTest {

    private fun tx(
        id: String,
        side: String,
        atMs: Long,
        price: Double = 100.0,
        quantity: Double = 3.0,
    ) = TransactionEntity(
        id = id,
        portfolioId = "p1",
        assetId = "a1",
        side = side,
        quantity = quantity,
        price = price,
        fee = 0.0,
        executedAt = "2026-06-01T00:00:00Z",
        executedAtMs = atMs,
        note = null,
        assetSymbol = "AAPL",
        assetName = "Apple",
        assetExchange = "NASDAQ",
        assetCurrency = "USD",
        assetType = "stock",
        assetIsCustom = false,
    )

    @Test
    fun `a sell is a sell and everything else is a buy`() {
        val markers = holdingChartMarkers(
            listOf(
                tx("1", "buy", 1_000),
                tx("2", "sell", 2_000),
                tx("3", "SELL", 3_000),
            ),
        )
        assertEquals(
            listOf(ChartMarker.Kind.BUY, ChartMarker.Kind.SELL, ChartMarker.Kind.SELL),
            markers.map { it.kind },
        )
    }

    @Test
    fun `a row whose timestamp never parsed is not plotted`() {
        // executedAtMs is 0 when the ISO string was unusable. A marker's claim is
        // about WHEN, so a row without a when has nothing to say here.
        assertTrue(holdingChartMarkers(listOf(tx("1", "buy", 0L))).isEmpty())
        assertTrue(holdingChartMarkers(listOf(tx("1", "buy", -5L))).isEmpty())
    }

    @Test
    fun `the marker carries the paid price and the quantity for the readout`() {
        val marker = holdingChartMarkers(listOf(tx("7", "buy", 1_000, price = 148.2, quantity = 12.0)))
            .single()
        assertEquals(148.2, marker.price, 0.0001)
        assertEquals(12.0, marker.quantity!!, 0.0001)
        assertEquals(1_000L, marker.timeMs)
        assertEquals("7", marker.id)
    }

    @Test
    fun `a non-finite price cannot be positioned`() {
        assertTrue(holdingChartMarkers(listOf(tx("1", "buy", 1_000, price = Double.NaN))).isEmpty())
    }
}
