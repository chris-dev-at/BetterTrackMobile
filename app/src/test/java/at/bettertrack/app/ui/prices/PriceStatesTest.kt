package at.bettertrack.app.ui.prices

import at.bettertrack.app.data.db.HoldingEntity
import at.bettertrack.app.data.storage.StorageMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The no-live-prices state machine (S3/S4 plan §5 W6, item 2).
 *
 * W6's done-when is a single sentence — *"correct cash + custom-asset +
 * manually-priced net worth and never a €0 lie"* — and the second half of it is
 * what this file holds the line on. Every case below is a screen that would
 * otherwise print a confident number nobody can stand behind.
 */
class PriceStatesTest {

    private fun holding(assetId: String, marketValueEur: Double?) = HoldingEntity(
        portfolioId = "p1",
        assetId = assetId,
        assetSymbol = assetId,
        assetName = assetId,
        assetExchange = null,
        assetCurrency = "EUR",
        assetType = "stock",
        assetIsCustom = false,
        quantity = 10.0,
        avgCost = 100.0,
        realizedPnl = 0.0,
        price = marketValueEur?.let { it / 10.0 },
        marketValueEur = marketValueEur,
        costBasisEur = 1_000.0,
        unrealizedPnlEur = null,
        unrealizedPnlPct = null,
        dayChangeEur = null,
        dayChangePct = null,
    )

    // ── Coverage ────────────────────────────────────────────────────────────

    @Test
    fun `no holdings is complete coverage of nothing`() {
        val coverage = priceCoverage(emptyList())
        assertEquals(0, coverage.total)
        assertTrue(coverage.complete)
        assertFalse(coverage.nothingPriced)
    }

    @Test
    fun `a fully priced portfolio is complete`() {
        val coverage = priceCoverage(listOf(holding("AAPL", 2_314.0), holding("MSFT", 900.0)))
        assertEquals(PriceCoverage(priced = 2, unpriced = 0), coverage)
        assertTrue(coverage.complete)
    }

    @Test
    fun `a partly priced portfolio counts both sides`() {
        val coverage = priceCoverage(listOf(holding("AAPL", 2_314.0), holding("MSFT", null)))
        assertEquals(PriceCoverage(priced = 1, unpriced = 1), coverage)
        assertFalse(coverage.complete)
        assertFalse(coverage.nothingPriced)
    }

    @Test
    fun `nothing priced is recognised as such`() {
        val coverage = priceCoverage(listOf(holding("AAPL", null), holding("MSFT", null)))
        assertTrue(coverage.nothingPriced)
        assertFalse(coverage.complete)
    }

    // ── The €0 lie ──────────────────────────────────────────────────────────

    @Test
    fun `nothing priced and no cash renders the designed empty, never zero euro`() {
        // The headline case: a Drive user holding ten shares nobody can price and
        // no cash. `totalValueEur` is 0.0 and every part of that zero is a lie.
        val coverage = priceCoverage(listOf(holding("AAPL", null), holding("MSFT", null)))
        val state = netWorthState(totalValueEur = 0.0, cashEur = 0.0, coverage = coverage)

        assertTrue("nothing priced + no cash must not render a figure", state is NetWorthState.Unpriceable)
        assertEquals(coverage, (state as NetWorthState.Unpriceable).coverage)
    }

    @Test
    fun `an empty portfolio may legitimately show zero`() {
        // Zero is not always a lie. A portfolio with nothing in it really is worth
        // nothing, and hiding that behind an error state would be its own dishonesty.
        val state = netWorthState(totalValueEur = 0.0, cashEur = 0.0, coverage = priceCoverage(emptyList()))

        assertTrue(state is NetWorthState.Value)
        assertEquals(0.0, (state as NetWorthState.Value).eur, 0.0)
        assertTrue(state.complete)
    }

    @Test
    fun `cash alone is shown even when no holding can be priced`() {
        // The cash figure is genuinely correct — it comes from the ported cash
        // ledger, not from any price — so it is shown. The caveat rides along.
        val coverage = priceCoverage(listOf(holding("AAPL", null)))
        val state = netWorthState(totalValueEur = 500.0, cashEur = 500.0, coverage = coverage)

        assertTrue(state is NetWorthState.Value)
        state as NetWorthState.Value
        assertEquals(500.0, state.eur, 0.0)
        assertFalse("the total covers only the cash and must say so", state.complete)
        assertEquals(1, state.coverage.unpriced)
    }

    @Test
    fun `a partly priced portfolio shows its figure with the caveat`() {
        val coverage = priceCoverage(listOf(holding("AAPL", 2_314.0), holding("MSFT", null)))
        val state = netWorthState(totalValueEur = 2_814.0, cashEur = 500.0, coverage = coverage)

        assertTrue(state is NetWorthState.Value)
        state as NetWorthState.Value
        assertEquals(2_814.0, state.eur, 0.0)
        assertFalse(state.complete)
    }

    @Test
    fun `a fully priced portfolio carries no caveat`() {
        val coverage = priceCoverage(listOf(holding("AAPL", 2_314.0)))
        val state = netWorthState(totalValueEur = 2_814.0, cashEur = 500.0, coverage = coverage)

        assertTrue((state as NetWorthState.Value).complete)
    }

    @Test
    fun `a manually priced portfolio is indistinguishable from a quoted one at the total`() {
        // Once a manual price exists the holding is priced like any other, which
        // is the whole design: the honest state is a state, not a permanent label.
        val coverage = priceCoverage(listOf(holding("AAPL", 2_314.0)))
        val state = netWorthState(totalValueEur = 2_314.0, cashEur = 0.0, coverage = coverage)

        assertTrue(state is NetWorthState.Value)
        assertTrue((state as NetWorthState.Value).complete)
    }

    // ── Per-asset ───────────────────────────────────────────────────────────

    @Test
    fun `a live quote wins over a stored manual price`() {
        val state = assetPriceState(
            mode = StorageMode.BOTH,
            livePrice = 244.0, liveCurrency = "EUR", liveAsOfIso = "2026-08-05",
            manualPrice = 231.4, manualCurrency = "EUR", manualAsOfIso = "2026-08-01",
        )
        assertEquals(
            AssetPriceState.Known(244.0, "EUR", "2026-08-05", PriceProvenance.LIVE),
            state,
        )
    }

    @Test
    fun `a manual price is used when there is no quote and is badged as manual`() {
        val state = assetPriceState(
            mode = StorageMode.DRIVE,
            livePrice = null, liveCurrency = null, liveAsOfIso = null,
            manualPrice = 231.4, manualCurrency = "EUR", manualAsOfIso = "2026-08-01",
        )
        assertEquals(
            AssetPriceState.Known(231.4, "EUR", "2026-08-01", PriceProvenance.MANUAL),
            state,
        )
    }

    @Test
    fun `no price at all is absent, and drive mode offers to fix it`() {
        val state = assetPriceState(
            mode = StorageMode.DRIVE,
            livePrice = null, liveCurrency = null, liveAsOfIso = null,
            manualPrice = null, manualCurrency = null, manualAsOfIso = null,
        )
        assertEquals(AssetPriceState.Absent(canAddManually = true), state)
    }

    @Test
    fun `server mode never offers manual entry for a missing quote`() {
        // A missing quote in SERVER mode is the server's problem. Offering a text
        // field would imply the user could fix it, and the typed number would not
        // even be read — the server computes the totals.
        for (mode in listOf(StorageMode.SERVER, StorageMode.UNSET, StorageMode.BOTH)) {
            val state = assetPriceState(
                mode = mode,
                livePrice = null, liveCurrency = null, liveAsOfIso = null,
                manualPrice = null, manualCurrency = null, manualAsOfIso = null,
            )
            assertEquals("mode $mode", AssetPriceState.Absent(canAddManually = false), state)
        }
    }

    @Test
    fun `manual entry is available in drive mode only`() {
        assertTrue(manualEntryAvailable(StorageMode.DRIVE))
        assertFalse(manualEntryAvailable(StorageMode.SERVER))
        assertFalse(manualEntryAvailable(StorageMode.BOTH))
        // UNSET behaves as SERVER everywhere, including here.
        assertFalse(manualEntryAvailable(StorageMode.UNSET))
    }

    @Test
    fun `a price without its currency is not rendered as a bare number`() {
        // Defensive: a value with no currency cannot be formatted honestly, so it
        // falls through to absent rather than rendering in a guessed currency.
        val state = assetPriceState(
            mode = StorageMode.DRIVE,
            livePrice = 244.0, liveCurrency = null, liveAsOfIso = null,
            manualPrice = null, manualCurrency = null, manualAsOfIso = null,
        )
        assertEquals(AssetPriceState.Absent(canAddManually = true), state)
    }
}
