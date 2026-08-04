package at.bettertrack.app.data.storage

import at.bettertrack.app.data.api.dto.HistoryPointDto
import at.bettertrack.app.data.api.dto.PerformancePointDto
import at.bettertrack.app.data.repo.HistoryRange
import at.bettertrack.app.domain.CurrencyConverter
import at.bettertrack.app.domain.PricePoint
import at.bettertrack.app.vault.VaultEntityGraph
import at.bettertrack.app.vault.VaultKinds
import at.bettertrack.app.vault.decryptVaultDocument
import at.bettertrack.app.vault.text
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The W4 composition gate.**
 *
 * `ClientMoneyEndToEndTest` (W3) proved that the platform's published encrypted
 * fixture decrypts and that the ported engine reproduces its published numbers.
 * This test carries that one layer further — all the way to **the Room rows the
 * Compose screens actually read**:
 *
 * ```
 * published BTVAULT1 envelope
 *   ──decrypt (W3)──► vault document ──parse (W3)──► entities
 *   ──ported packages/domain engine (W2 + W3)──► money
 *   ──VaultProjector (W4)──► PortfolioTotals / HoldingEntity / CashSourceEntity /
 *                            PortfolioHistoryEntity.pointsJson
 * ```
 *
 * That last arrow is the one this file exists for. The §7.1 doctrine says
 * screens read ONLY from Room, so "the engine computes 2285" is not yet a
 * feature — `totals.totalValueEur == 2285.0` in a row the portfolio screen reads
 * *is*. A projector that derived the right numbers and then wrote them into the
 * wrong column, or dropped the cash into `investedEur`, would pass every W2/W3
 * test and ship a lying screen.
 *
 * The expectations are the platform's own, from `clientMoney.test.ts:48-101`:
 * `cashBalanceEur` 1020, `holdingsValueEur` 1265, `totalValueEur` 2285, and a
 * final series point of 2285 on 2026-07-27. Every assertion is exact `Double`
 * equality (`0.0` tolerance).
 *
 * Market data is fixed exactly as the platform's own test fixes it
 * (`clientMoney.testSupport.ts:281-349`): 130 EUR for the EUR asset, 50 USD for
 * the USD one, USD→EUR = 0.9.
 */
class VaultProjectionTest {

    private val portfolioId = "018f0000-0000-7000-8000-000000000102"
    private val cashSourceId = "018f0000-0000-7000-8000-000000000103"
    private val eurAssetId = "018f0000-0000-7000-8000-000000000104"
    private val usdAssetId = "018f0000-0000-7000-8000-000000000105"

    /** `NOW` in clientMoney.test.ts:39 is 2026-07-27T12:00Z. */
    private val today = "2026-07-27"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private val fixture: JsonObject by lazy {
        val stream = javaClass.getResourceAsStream("/vault-vectors/clientMoney.fixture.json")
            ?: error("vault-vectors/clientMoney.fixture.json missing from test resources")
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    /** The real published envelope, decrypted by the real W3 codec. */
    private val graph: VaultEntityGraph by lazy {
        val decrypted = decryptVaultDocument(
            Base64.getDecoder().decode(fixture["envelopeBase64"]!!.jsonPrimitive.content),
            Base64.getDecoder().decode(fixture["vaultKeyBase64"]!!.jsonPrimitive.content),
        )
        VaultEntityGraph(decrypted.document.entities)
    }

    /** The fixture's FX fake, verbatim: EUR identity, USD→EUR 0.9, nothing else. */
    private val converter = object : CurrencyConverter {
        override suspend fun toBase(amount: Double, currency: String, date: String?, base: String?): Double =
            when (currency) {
                "EUR" -> amount
                "USD" -> amount * 0.9
                else -> error("unsupported FX: $currency")
            }
    }

    /** The fixture's price fake: seven daily closes, then the live quote day. */
    private fun priceHistory(assetId: String): List<PricePoint> {
        val closes = if (assetId == usdAssetId) {
            listOf(40.0, 41.0, 42.0, 43.0, 44.0, 45.0, 46.0)
        } else {
            listOf(100.0, 105.0, 110.0, 115.0, 120.0, 125.0, 128.0)
        }
        val history = closes.mapIndexed { index, close -> PricePoint("2026-07-%02d".format(20 + index), close) }
        return history + PricePoint(today, if (assetId == usdAssetId) 50.0 else 130.0)
    }

    private val inputs = VaultProjectionInputs(
        today = today,
        market = mapOf(
            eurAssetId to AssetMarketData(
                prices = priceHistory(eurAssetId),
                quote = at.bettertrack.app.domain.HoldingQuote(price = 130.0, prevClose = 128.0),
            ),
            usdAssetId to AssetMarketData(
                prices = priceHistory(usdAssetId),
                quote = at.bettertrack.app.domain.HoldingQuote(price = 50.0, prevClose = 46.0),
            ),
        ),
        converter = converter,
        syncedAtMs = 1_754_300_000_000L,
    )

    private fun project(ranges: List<HistoryRange> = listOf(HistoryRange.MAX)) = runBlocking {
        VaultProjector(json).project(graph, portfolioId, inputs, ranges)
    }

    // ── The published headline numbers, in Room columns ─────────────────────

    @Test
    fun derivesThePublishedTotalsIntoThePortfolioRow() {
        val totals = project().portfolios.single { it.id == portfolioId }.totals
            ?: throw AssertionError("the projected portfolio must carry totals")

        assertEquals("holdingsValueEur", 1265.0, totals.marketValueEur, 0.0)
        assertEquals("cashBalanceEur", 1020.0, totals.cashEur, 0.0)
        assertEquals("totalValueEur", 2285.0, totals.totalValueEur, 0.0)

        // 8 × 100.5 (EUR) + 5 × 40.2 × 0.9 (USD) = 804 + 180.9
        assertEquals("investedEur", 984.9, totals.investedEur, 0.0)
        assertEquals(
            "unrealized P/L is holdings value − cost basis; cash is not a gain",
            280.1,
            totals.unrealizedPnlEur,
            0.0,
        )
    }

    @Test
    fun derivesTheHoldingRowsTheServerAndWebClientAgreeOn() {
        val holdings = project().holdings.associateBy { it.assetId }

        val eur = holdings.getValue(eurAssetId)
        assertEquals("10 bought, 2 sold", 8.0, eur.quantity, 0.0)
        assertEquals("fee capitalised into the average", 100.5, eur.avgCost, 0.0)
        assertEquals(1040.0, eur.marketValueEur!!, 0.0)
        assertEquals(804.0, eur.costBasisEur!!, 0.0)
        assertEquals("identity for the base currency", "EUR", eur.assetCurrency)
        assertEquals("EURA", eur.assetSymbol)
        assertEquals("the asset identity travels from the vault, not a placeholder", "Euro Asset", eur.assetName)

        val usd = holdings.getValue(usdAssetId)
        assertEquals(5.0, usd.quantity, 0.0)
        assertEquals(40.2, usd.avgCost, 0.0)
        assertEquals("50 USD × 0.9 × 5", 225.0, usd.marketValueEur!!, 0.0)
        assertEquals("native currency is preserved alongside the EUR figures", "USD", usd.assetCurrency)

        assertEquals(
            "the row is scoped to the portfolio it was derived for",
            portfolioId,
            eur.portfolioId,
        )
    }

    @Test
    fun derivesTheCashSourceBalanceFromTheEncryptedLedger() {
        val sources = project().cashSources
        val main = sources.single()

        assertEquals(cashSourceId, main.id)
        assertEquals("Main", main.name)
        assertTrue("the fixture's source is the main one", main.isMain)
        // deposit 1000 + dividend 30 − tax_withholding 10
        assertEquals(1020.0, main.balanceEur, 0.0)
        assertNull("an active source carries no archive stamp", main.archivedAt)
    }

    // ── The history series, as the chart parses it ──────────────────────────

    /**
     * `pointsJson`/`performanceJson` are opaque server JSON on the server path;
     * the vault path must produce the SAME shape, or the chart silently draws
     * nothing. So this asserts through the real DTO deserializer the chart uses,
     * not against a hand-built string.
     */
    @Test
    fun derivesAHistorySeriesTheExistingChartCanParse() {
        val history = project().history.single()
        assertEquals(HistoryRange.MAX.wire, history.range)
        assertEquals("EUR", history.baseCurrency)
        assertEquals(portfolioId, history.portfolioId)

        val points = json.decodeFromString(ListSerializer(HistoryPointDto.serializer()), history.pointsJson)
        assertTrue("the curve is not empty", points.isNotEmpty())
        assertEquals("it ends on the reporting day", today, points.last().date)
        assertEquals("and on the published net worth", 2285.0, points.last().valueEur, 0.0)

        val performance = json.decodeFromString(
            ListSerializer(PerformancePointDto.serializer()),
            history.performanceJson,
        )
        assertEquals("performance covers the same days", points.size, performance.size)
        assertEquals(
            "a rebased series starts at exactly 0 %",
            0.0,
            performance.first().pct,
            0.0,
        )
    }

    /**
     * Each range is sliced AND rebased separately — a 1M performance curve is not
     * a suffix of the MAX one, because percentages compound rather than add.
     */
    @Test
    fun rebasesEachRangeIndependently() {
        val projected = project(listOf(HistoryRange.MAX, HistoryRange.M1, HistoryRange.W1))
        val byRange = projected.history.associateBy { it.range }
        assertEquals(3, byRange.size)

        for (range in listOf("MAX", "1M", "1W")) {
            val performance = json.decodeFromString(
                ListSerializer(PerformancePointDto.serializer()),
                byRange.getValue(range).performanceJson,
            )
            assertEquals("$range starts at 0 %", 0.0, performance.first().pct, 0.0)
        }

        val week = json.decodeFromString(
            ListSerializer(HistoryPointDto.serializer()),
            byRange.getValue("1W").pointsJson,
        )
        assertTrue("1W is a window, not the whole curve", week.size <= 8)
        assertEquals("every window still ends today", today, week.last().date)
    }

    // ── The rest of the read model ──────────────────────────────────────────

    @Test
    fun projectsTheLedgerRowsWithTheirIdentityAndOrderingKeys() {
        val projected = project()

        assertEquals("every transaction in the fixture", 3, projected.transactions.size)
        val buy = projected.transactions.first { it.assetId == eurAssetId && it.side == "buy" }
        assertEquals(10.0, buy.quantity, 0.0)
        assertEquals(100.0, buy.price, 0.0)
        assertEquals(5.0, buy.fee, 0.0)
        assertEquals("EURA", buy.assetSymbol)
        assertTrue("executedAtMs is parsed so the ledger can sort", buy.executedAtMs > 0)
        assertEquals(
            "the ms key agrees with the ISO string it came from",
            java.time.OffsetDateTime.parse(buy.executedAt).toInstant().toEpochMilli(),
            buy.executedAtMs,
        )

        assertEquals("every cash movement in the fixture", 3, projected.cashMovements.size)
        val dividend = projected.cashMovements.single { it.kind == "dividend" }
        assertEquals(30.0, dividend.amountEur, 0.0)
        assertNotNull("a dividend row points at its parent", dividend.dividendId)
        val withholding = projected.cashMovements.single { it.kind == "tax_withholding" }
        assertEquals("an outflow stays negative through the projection", -10.0, withholding.amountEur, 0.0)
    }

    /**
     * `customAsset` in a vault is the identity record for EVERY asset, not only
     * user-invented ones — `ownerId` is what separates them. The fixture's two
     * assets are platform assets, so the custom-asset catalogue is empty; putting
     * them there would fill the "My assets" screen with things the user never
     * created.
     */
    @Test
    fun keepsPlatformAssetIdentityOutOfTheCustomAssetCatalogue() {
        val projected = project()
        assertEquals(0, projected.customAssets.size)
        assertTrue(
            "the fixture's assets really are platform assets",
            graph.live(VaultKinds.CUSTOM_ASSET).all { it.text("ownerId") == null },
        )
        assertTrue("and neither renders as custom", projected.holdings.none { it.assetIsCustom })
    }

    @Test
    fun carriesThePortfolioIdentityIntoTheRow() {
        val row = project().portfolios.single { it.id == portfolioId }
        assertEquals("Encrypted parity", row.name)
        assertEquals("private", row.visibility)
        assertEquals("EUR", row.baseCurrency)
        assertNotNull("a derived detail is stamped like a synced one", row.detailSyncedAtMs)
    }

    // ── Honest degradation ──────────────────────────────────────────────────

    /**
     * Plan §6/W6's "€0 lie": an asset with no price must be OMITTED from the
     * value curve, never valued at zero. A portfolio that cannot be priced shows
     * its cash, which is true, instead of a crash to zero, which is not.
     */
    @Test
    fun neverValuesAnUnpricedAssetAtZero() {
        val unpriced = VaultProjectionInputs(
            today = today,
            market = emptyMap(),
            converter = converter,
            syncedAtMs = 1_754_300_000_000L,
        )
        val projected = runBlocking {
            VaultProjector(json).project(graph, portfolioId, unpriced, listOf(HistoryRange.MAX))
        }

        val totals = projected.portfolios.single { it.id == portfolioId }.totals!!
        assertEquals("no quote ⇒ no market value, not a zero one", 0.0, totals.marketValueEur, 0.0)
        assertEquals("cash is still known and still true", 1020.0, totals.cashEur, 0.0)
        assertEquals("so net worth is the cash, not €0", 1020.0, totals.totalValueEur, 0.0)

        val holdings = projected.holdings.associateBy { it.assetId }
        assertNull("an unpriced holding reports no value at all", holdings.getValue(eurAssetId).marketValueEur)
        assertEquals("the held quantity is still a fact", 8.0, holdings.getValue(eurAssetId).quantity, 0.0)

        val points = json.decodeFromString(
            ListSerializer(HistoryPointDto.serializer()),
            projected.history.single().pointsJson,
        )
        assertEquals("the curve reflects cash only", 1020.0, points.last().valueEur, 0.0)
    }

    /** A tombstoned entity is invisible to the projection but still in the vault. */
    @Test
    fun ignoresTombstonedEntities() {
        val withDeletion = graph.copy()
        val movement = withDeletion.live(VaultKinds.CASH_MOVEMENT)
            .single { it.text("kind") == "dividend" }
        withDeletion.tombstone(
            VaultKinds.CASH_MOVEMENT,
            movement.id,
            "2026-07-28T08:00:00.000Z",
            "018f0000-0000-7000-8000-000000000106",
        )

        val projected = runBlocking {
            VaultProjector(json).project(withDeletion, portfolioId, inputs, listOf(HistoryRange.MAX))
        }
        assertEquals("the deleted movement is gone from the read model", 2, projected.cashMovements.size)
        assertEquals("and out of the balance: 1020 − 30", 990.0, projected.cashSources.single().balanceEur, 0.0)
        assertEquals(
            "the tombstone itself survives so the delete can propagate",
            3,
            withDeletion.all(VaultKinds.CASH_MOVEMENT).size,
        )
    }
}
