package at.bettertrack.app.ui.portfolio

import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.data.db.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure display-logic tests for the M4 screens: ledger filters (§6.2), the
 * switcher's selection rule (§6.1), weight proportions and the sync-marker
 * note scrub.
 */
class PortfolioDisplayLogicTest {

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private fun tx(
        id: String,
        side: String,
        assetId: String,
        symbol: String = assetId.uppercase(),
        note: String? = null,
    ) = TransactionEntity(
        id = id,
        portfolioId = "p1",
        assetId = assetId,
        side = side,
        quantity = 2.0,
        price = 10.0,
        fee = 0.0,
        executedAt = "2026-06-01T00:00:00Z",
        executedAtMs = 0L,
        note = note,
        assetSymbol = symbol,
        assetName = "$symbol Inc",
        assetExchange = null,
        assetCurrency = "EUR",
        assetType = "stock",
        assetIsCustom = false,
    )

    private fun portfolio(
        id: String,
        isDefault: Boolean = false,
        archivedAt: String? = null,
        sortOrder: Int = 0,
    ) = PortfolioEntity(
        id = id,
        name = "Portfolio $id",
        visibility = "private",
        sortOrder = sortOrder,
        isDefault = isDefault,
        defaultPayFromCash = false,
        archivedAt = archivedAt,
        baseCurrency = null,
        totals = null,
        detailSyncedAtMs = null,
    )

    private val ledger = listOf(
        tx("t1", "buy", "aapl"),
        tx("t2", "sell", "aapl"),
        tx("t3", "buy", "btc"),
        tx("t4", "buy", "aapl"),
    )

    // ── Transaction filters (§6.2) ───────────────────────────────────────────

    @Test
    fun `side filter narrows to buys or sells`() {
        assertEquals(listOf("t1", "t3", "t4"), filterTransactions(ledger, TxSideFilter.BUY, null).map { it.id })
        assertEquals(listOf("t2"), filterTransactions(ledger, TxSideFilter.SELL, null).map { it.id })
        assertEquals(4, filterTransactions(ledger, TxSideFilter.ALL, null).size)
    }

    @Test
    fun `asset filter narrows to one asset and stacks with side`() {
        assertEquals(
            listOf("t1", "t2", "t4"),
            filterTransactions(ledger, TxSideFilter.ALL, "aapl").map { it.id },
        )
        assertEquals(
            listOf("t1", "t4"),
            filterTransactions(ledger, TxSideFilter.BUY, "aapl").map { it.id },
        )
        assertEquals(0, filterTransactions(ledger, TxSideFilter.SELL, "btc").size)
    }

    @Test
    fun `distinct assets are deduped and sorted by symbol`() {
        val options = distinctTxAssets(ledger)
        assertEquals(listOf("AAPL", "BTC"), options.map { it.symbol })
        assertEquals(listOf("aapl", "btc"), options.map { it.assetId })
    }

    @Test
    fun `notional is quantity times unit price`() {
        assertEquals(20.0, transactionNotional(2.0, 10.0), 0.0)
        assertEquals(0.0, transactionNotional(0.0, 10.0), 0.0)
    }

    // ── Selection rule (§6.1) ────────────────────────────────────────────────

    @Test
    fun `stored active portfolio wins`() {
        val all = listOf(portfolio("a", isDefault = true), portfolio("b"))
        assertEquals("b", PortfolioOverviewViewModel.resolveSelection(all, "b")?.id)
    }

    @Test
    fun `stored archived portfolio falls back to default`() {
        val all = listOf(
            portfolio("a", isDefault = true),
            portfolio("b", archivedAt = "2026-01-01T00:00:00Z"),
        )
        assertEquals("a", PortfolioOverviewViewModel.resolveSelection(all, "b")?.id)
    }

    @Test
    fun `no stored choice uses default then first active`() {
        val withDefault = listOf(portfolio("a"), portfolio("b", isDefault = true))
        assertEquals("b", PortfolioOverviewViewModel.resolveSelection(withDefault, null)?.id)

        val noDefault = listOf(portfolio("a", sortOrder = 0), portfolio("b", sortOrder = 1))
        assertEquals("a", PortfolioOverviewViewModel.resolveSelection(noDefault, null)?.id)
    }

    @Test
    fun `all archived resolves to null`() {
        val all = listOf(
            portfolio("a", isDefault = true, archivedAt = "2026-01-01T00:00:00Z"),
            portfolio("b", archivedAt = "2026-01-01T00:00:00Z"),
        )
        assertNull(PortfolioOverviewViewModel.resolveSelection(all, "a"))
    }

    // ── Weight proportion + note scrub ──────────────────────────────────────

    @Test
    fun `weight is a percent proportion with zero and null guards`() {
        assertEquals(25.0, weightPct(25.0, 100.0)!!, 1e-9)
        assertNull(weightPct(25.0, 0.0))
        assertNull(weightPct(null, 100.0))
        assertNull(weightPct(25.0, null))
    }

    @Test
    fun `sync reconcile marker is stripped from display notes`() {
        assertEquals(
            "Monthly savings plan",
            displayNote("Monthly savings plan [bt:c963cc59-1b7a-4a34-9e1c-aaaaaaaaaaaa]"),
        )
        assertNull(displayNote(" [bt:c963cc59-1b7a-4a34-9e1c-aaaaaaaaaaaa]"))
        assertNull(displayNote(null))
        assertEquals("Plain note", displayNote("Plain note"))
    }
}
