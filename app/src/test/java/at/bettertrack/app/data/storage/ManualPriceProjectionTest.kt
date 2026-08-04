package at.bettertrack.app.data.storage

import at.bettertrack.app.data.repo.HistoryRange
import at.bettertrack.app.vault.VaultEntityGraph
import at.bettertrack.app.vault.VaultKinds
import at.bettertrack.app.vault.VaultPayloads
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The W6 pickup gate**: a price the user typed must reach the Room rows the
 * screens read, through the same ported engine everything else goes through.
 *
 * ```
 * ManualPriceStore ──► price_cache
 *   ──NoLivePricesMarketDataSource.cachedPrices()──► assetId → PricePoint[]
 *   ──buildMarketInputs──► AssetMarketData(prices, quote)
 *   ──VaultProjector (ported packages/domain)──► HoldingEntity.marketValueEur
 * ```
 *
 * `VaultProjectionTest` proves the engine→Room arrow against the platform's own
 * published fixture. This file proves the arrow *into* it — that manual entry is
 * wired to the money path at all, and that the plan's "reuse the value-point
 * machinery" is literally true rather than aspirational.
 *
 * The assertion that matters most is the negative one
 * ([an unpriced asset stays null rather than becoming zero]): the entire honest
 * degradation story depends on `marketValueEur` staying `null` all the way to
 * Room, because that null is what every no-live-prices state downstream reads.
 */
class ManualPriceProjectionTest {

    private val portfolioId = "018f0000-0000-7000-8000-0000000000a1"
    private val assetId = "AAPL"
    private val today = "2026-08-05"
    private val projector = VaultProjector(
        kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        },
    )

    /** One portfolio holding 10 AAPL bought at 200 EUR — no custom asset anywhere. */
    private fun graphWithOneHolding(): VaultEntityGraph = VaultEntityGraph().apply {
        create(
            VaultKinds.PORTFOLIO,
            portfolioId,
            VaultPayloads.portfolio(userId = null, name = "Drive"),
            "2026-08-01T00:00:00.000Z",
            "device",
        )
        create(
            VaultKinds.TRANSACTION,
            "018f0000-0000-7000-8000-0000000000a2",
            VaultPayloads.transaction(
                portfolioId = portfolioId,
                assetId = assetId,
                side = "buy",
                quantity = 10.0,
                price = 200.0,
                fee = 0.0,
                executedAt = "2026-08-01T10:00:00.000Z",
            ),
            "2026-08-01T10:00:00.000Z",
            "device",
        )
    }

    private suspend fun project(dao: FakePriceCacheDao): at.bettertrack.app.data.storage.ProjectedPortfolioData {
        val market = NoLivePricesMarketDataSource(dao)
        val graph = graphWithOneHolding()
        return projector.project(
            graph = graph,
            portfolioId = portfolioId,
            inputs = VaultProjectionInputs(
                today = today,
                market = buildMarketInputs(graph, market.cachedPrices()),
                converter = EurOnlyCurrencyConverter(),
                syncedAtMs = 1_000L,
            ),
            ranges = listOf(HistoryRange.DEFAULT),
        )
    }

    @Test
    fun `an unpriced asset stays null rather than becoming zero`() = runBlocking {
        // The precondition for every honest state downstream. If the projector
        // ever wrote 0.0 here instead of null, no UI rule could tell "worth
        // nothing" from "not known" and the €0 lie would be unfixable in the UI.
        val projected = project(FakePriceCacheDao())
        val holding = projected.holdings.single { it.assetId == assetId }

        assertNull(holding.marketValueEur)
        assertNull(holding.price)
    }

    @Test
    fun `a manually entered price values the holding`() = runBlocking {
        val dao = FakePriceCacheDao()
        ManualPriceStore(dao) { 1_000L }.record(ManualPrice(assetId, "2026-08-01", 231.40, "EUR"))

        val holding = project(dao).holdings.single { it.assetId == assetId }

        // 10 × 231.40, exactly — the ported engine, not a shortcut in this file.
        assertEquals(2_314.0, holding.marketValueEur!!, 0.0)
        assertEquals(231.40, holding.price!!, 0.0)
    }

    @Test
    fun `the portfolio total picks the manual price up too`() = runBlocking {
        val dao = FakePriceCacheDao()
        ManualPriceStore(dao) { 1_000L }.record(ManualPrice(assetId, "2026-08-01", 231.40, "EUR"))

        val totals = project(dao).portfolios.single { it.id == portfolioId }.totals

        assertNotNull(totals)
        assertEquals(2_314.0, totals!!.marketValueEur, 0.0)
        assertEquals(2_314.0, totals.totalValueEur, 0.0)
    }

    @Test
    fun `the newest manual price is the one used, and the day before is the previous close`() = runBlocking {
        val dao = FakePriceCacheDao()
        val store = ManualPriceStore(dao) { 1_000L }
        store.record(ManualPrice(assetId, "2026-08-01", 200.0, "EUR"))
        store.record(ManualPrice(assetId, "2026-08-04", 231.40, "EUR"))

        val holding = project(dao).holdings.single { it.assetId == assetId }

        assertEquals(2_314.0, holding.marketValueEur!!, 0.0)
        // buildMarketInputs treats the previous point as prevClose so the
        // day-change column is honest rather than absent.
        assertNotNull(holding.dayChangeEur)
        // Asserted as the engine's own expression rather than the hand-computed
        // 314.0: `10 * (231.40 - 200.0)` is 314.00000000000006 in IEEE double,
        // and the engine is right — the naive literal is what is wrong. Written
        // this way the comparison stays at 0.0 tolerance, which is the project's
        // rule for the money path (plan §3.3).
        assertEquals(10.0 * (231.40 - 200.0), holding.dayChangeEur!!, 0.0)
    }

    @Test
    fun `a manually priced asset joins the value curve`() = runBlocking {
        // The projector excludes assets with NO price history from the curve. A
        // manual price is price history, so entry must move the asset from the
        // excluded set into the drawn one.
        val emptyCurve = project(FakePriceCacheDao()).history.single().pointsJson
        assertTrue("no prices ⇒ nothing to draw", emptyCurve == "[]")

        val dao = FakePriceCacheDao()
        ManualPriceStore(dao) { 1_000L }.record(ManualPrice(assetId, "2026-08-01", 231.40, "EUR"))
        val curve = project(dao).history.single().pointsJson

        assertTrue("a manual price must produce a curve", curve.contains("2314"))
    }

    @Test
    fun `removing the only manual price returns the holding to unpriced`() = runBlocking {
        val dao = FakePriceCacheDao()
        val store = ManualPriceStore(dao) { 1_000L }
        store.record(ManualPrice(assetId, "2026-08-01", 231.40, "EUR"))
        assertNotNull(project(dao).holdings.single().marketValueEur)

        store.delete(assetId, "2026-08-01")

        // Back to the honest absent state, not to zero.
        assertNull(project(dao).holdings.single().marketValueEur)
    }

    @Test
    fun `buildMarketInputs prefers vault value points over the local price cache`() = runBlocking {
        // A custom asset's value points are vault content that syncs to Drive; a
        // price_cache row is a device-local convenience. When an id has both, the
        // durable one is the truth.
        val dao = FakePriceCacheDao()
        ManualPriceStore(dao) { 1_000L }.record(ManualPrice("custom-1", "2026-08-01", 5.0, "EUR"))

        val graph = VaultEntityGraph().apply {
            create(
                VaultKinds.CUSTOM_ASSET_VALUE,
                "vp-1",
                kotlinx.serialization.json.JsonObject(
                    linkedMapOf(
                        "assetId" to kotlinx.serialization.json.JsonPrimitive("custom-1"),
                        "date" to kotlinx.serialization.json.JsonPrimitive("2026-08-01"),
                        "value" to kotlinx.serialization.json.JsonPrimitive("99"),
                    ),
                ),
                "2026-08-01T00:00:00.000Z",
                "device",
            )
        }

        val inputs = buildMarketInputs(graph, NoLivePricesMarketDataSource(dao).cachedPrices())

        assertEquals(99.0, inputs.getValue("custom-1").quote!!.price, 0.0)
    }

    @Test
    fun `a price for an untransacted asset does not invent a holding`() = runBlocking {
        // Entering a price is not a claim of ownership. The holdings come from
        // transactions and nothing else.
        val dao = FakePriceCacheDao()
        ManualPriceStore(dao) { 1_000L }.record(ManualPrice("MSFT", "2026-08-01", 400.0, "EUR"))

        val projected = project(dao)

        assertEquals(listOf(assetId), projected.holdings.map { it.assetId })
    }
}
