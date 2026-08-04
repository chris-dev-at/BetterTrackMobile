package at.bettertrack.app.vault

import at.bettertrack.app.domain.CashMovement
import at.bettertrack.app.domain.CostBasisOverTimeInput
import at.bettertrack.app.domain.CurrencyConverter
import at.bettertrack.app.domain.HoldingAssetInput
import at.bettertrack.app.domain.HoldingQuote
import at.bettertrack.app.domain.NetWorthSeriesInput
import at.bettertrack.app.domain.PricePoint
import at.bettertrack.app.domain.SourcedCashMovement
import at.bettertrack.app.domain.Transaction
import at.bettertrack.app.domain.TransactionSide
import at.bettertrack.app.domain.ValueOverTimeAsset
import at.bettertrack.app.domain.ValueOverTimeInput
import at.bettertrack.app.domain.cashBalancesBySource
import at.bettertrack.app.domain.costBasisOverTime
import at.bettertrack.app.domain.deriveHoldings
import at.bettertrack.app.domain.netWorthSeries
import at.bettertrack.app.domain.valueOverTime
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The W3 end-to-end gate** (plan §3.4 step 5 / §3.4.5).
 *
 * This is the test that proves the two halves of the storage work actually
 * compose. `clientMoney.fixture.json` is a **real encrypted `BTVAULT1`
 * envelope** published by the platform, containing a fixed multi-currency
 * portfolio. The chain exercised here is:
 *
 * ```
 * encrypted envelope ──decrypt (W3)──► vault document ──parse (W3)──►
 *     entities ──ported packages/domain engine (W2 + W3)──► money
 * ```
 *
 * Nothing is mocked except market data, which the platform's own test fixes to
 * the same constants (`clientMoney.testSupport.ts:281-349`): a live quote of
 * 130 EUR for the EUR asset and 50 USD for the USD one, and USD→EUR = 0.9.
 *
 * The published expectations come from the platform's `clientMoney.test.ts:48-101`
 * ("decrypts the fixed multi-currency fixture and matches shared-domain money
 * math exactly"): `cashBalanceEur` 1020, `holdingsValueEur` 1265,
 * `totalValueEur` 2285, the two allocation rows, and a final series point of
 * `valueEur` 2285 / `costBasisEur` 984.9 / `pnlEur` 280.1 on 2026-07-27.
 *
 * Every assertion is exact `Double` equality (`0.0` tolerance).
 */
class ClientMoneyEndToEndTest {

    // `CLIENT_MONEY_IDS` (clientMoney.testSupport.ts:15-23)
    private val portfolioId = "018f0000-0000-7000-8000-000000000102"
    private val cashSourceId = "018f0000-0000-7000-8000-000000000103"
    private val eurAssetId = "018f0000-0000-7000-8000-000000000104"
    private val usdAssetId = "018f0000-0000-7000-8000-000000000105"

    /** The reporting day: `NOW` in clientMoney.test.ts:39 is 2026-07-27T12:00Z. */
    private val today = "2026-07-27"

    private val fixture: JsonObject by lazy {
        val stream = javaClass.getResourceAsStream("/vault-vectors/clientMoney.fixture.json")
            ?: error("vault-vectors/clientMoney.fixture.json missing from test resources")
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private val decrypted: DecryptedVault by lazy {
        decryptVaultDocument(
            Base64.getDecoder().decode(fixture["envelopeBase64"]!!.jsonPrimitive.content),
            Base64.getDecoder().decode(fixture["vaultKeyBase64"]!!.jsonPrimitive.content),
        )
    }

    private fun rows(kind: String): List<VaultEntity> =
        decrypted.document.entities[kind].orEmpty().filter { it.deletedAt == null }

    /** Entity payload fields are opaque JSON; money arrives as decimal STRINGS. */
    private fun VaultEntity.str(field: String): String = data[field]!!.jsonPrimitive.content
    private fun VaultEntity.num(field: String): Double = str(field).toDouble()

    /**
     * The fixture's market fake, verbatim (`clientMoney.testSupport.ts:335-348`):
     * EUR is identity, USD→EUR is 0.9, anything else is unsupported. Dated and
     * spot lookups return the same rate, exactly as the fake does.
     */
    private val converter = object : CurrencyConverter {
        override suspend fun toBase(amount: Double, currency: String, date: String?, base: String?): Double =
            when (currency) {
                "EUR" -> amount
                "USD" -> amount * 0.9
                else -> error("unsupported FX: $currency")
            }
    }

    private fun transactions(): List<Transaction> = rows("transaction")
        .filter { it.str("portfolioId") == portfolioId }
        .map {
            Transaction(
                assetId = it.str("assetId"),
                side = if (it.str("side") == "buy") TransactionSide.BUY else TransactionSide.SELL,
                quantity = it.num("quantity"),
                price = it.num("price"),
                fee = it.num("fee"),
                executedAt = it.str("executedAt"),
            )
        }

    private fun cashMovements(): List<SourcedCashMovement> = rows("cashMovement")
        .filter { it.str("portfolioId") == portfolioId }
        .map {
            SourcedCashMovement(
                kind = it.str("kind"),
                amountEur = it.num("amountEur"),
                occurredAt = it.str("executedAt"),
                sourceId = it.str("sourceId"),
            )
        }

    private fun currencyOf(assetId: String): String =
        rows("customAsset").first { it.id == assetId }.str("currency")

    /** History from the fixture's market fake, plus the live quote day. */
    private fun priceHistory(assetId: String): List<PricePoint> {
        val closes = if (assetId == usdAssetId) {
            listOf(40.0, 41.0, 42.0, 43.0, 44.0, 45.0, 46.0)
        } else {
            listOf(100.0, 105.0, 110.0, 115.0, 120.0, 125.0, 128.0)
        }
        val history = closes.mapIndexed { index, close ->
            PricePoint("2026-07-%02d".format(20 + index), close)
        }
        // The live quote marks the reporting day (`isLiveToday` in the reference).
        return history + PricePoint(today, if (assetId == usdAssetId) 50.0 else 130.0)
    }

    // =======================================================================

    @Test
    fun decryptsThePublishedClientMoneyVault() {
        assertEquals("vaultVersion", 11, decrypted.header.vaultVersion)
        assertEquals("schemaVersion", VaultContract.DOCUMENT_V1_VERSION, decrypted.document.schemaVersion)
        assertEquals(
            "writeId",
            "018f0000-0000-7000-8000-000000000121",
            decrypted.header.writeId,
        )
        // The document must survive a decrypt → re-serialize round trip unchanged.
        assertTrue(
            "an absent mirrorProvenance must stay absent",
            decrypted.document.mirrorProvenance == null,
        )
        assertEquals(1, rows("portfolio").size)
        assertEquals(3, rows("transaction").size)
        assertEquals(3, rows("cashMovement").size)
        assertEquals(2, rows("customAsset").size)
        assertEquals("Main", rows("cashSource").first().str("name"))
    }

    @Test
    fun derivesTheHoldingsTheServerAndWebClientAgreeOn() = runBlocking {
        val holdings = deriveHoldings(
            transactions(),
            listOf(
                HoldingAssetInput(eurAssetId, currencyOf(eurAssetId), HoldingQuote(price = 130.0, prevClose = 128.0)),
                HoldingAssetInput(usdAssetId, currencyOf(usdAssetId), HoldingQuote(price = 50.0, prevClose = 46.0)),
            ),
            converter,
        )

        val eur = holdings.first { it.assetId == eurAssetId }
        val usd = holdings.first { it.assetId == usdAssetId }

        // 10 bought @100 (+5 fee) → avg 100.5; 2 sold → 8 held.
        assertEquals("EUR asset held quantity", 8.0, eur.quantity, 0.0)
        assertEquals("EUR asset avg cost", 100.5, eur.avgCost, 0.0)
        assertEquals("EUR asset market value", 1040.0, eur.marketValueEur!!, 0.0)
        assertEquals("EUR asset cost basis", 804.0, eur.costBasisEur!!, 0.0)

        // 5 bought @40 (+1 fee) → avg 40.2, in USD; 50 USD × 0.9 = 45 EUR/unit.
        assertEquals("USD asset held quantity", 5.0, usd.quantity, 0.0)
        assertEquals("USD asset avg cost", 40.2, usd.avgCost, 0.0)
        assertEquals("USD asset market value", 225.0, usd.marketValueEur!!, 0.0)

        val holdingsValueEur = eur.marketValueEur!! + usd.marketValueEur!!
        assertEquals("holdingsValueEur", 1265.0, holdingsValueEur, 0.0)

        // Allocation, computed exactly as clientMoney.test.ts:70-81 does.
        assertEquals("EUR allocation pct", (1040.0 / 1265.0) * 100, eur.marketValueEur!! / holdingsValueEur * 100, 0.0)
        assertEquals("USD allocation pct", (225.0 / 1265.0) * 100, usd.marketValueEur!! / holdingsValueEur * 100, 0.0)
    }

    @Test
    fun derivesTheCashBalanceFromTheEncryptedLedger() {
        // deposit 1000 + dividend 30 − tax_withholding 10 = 1020.
        val balances = cashBalancesBySource(cashMovements())
        assertEquals("one cash source", setOf(cashSourceId), balances.keys)
        assertEquals("cashBalanceEur", 1020.0, balances.getValue(cashSourceId), 0.0)
    }

    /**
     * The composed figure the whole feature exists to render: holdings valued
     * through the ported engine plus the vault's own cash ledger.
     */
    @Test
    fun derivesTheFinalNetWorthCostBasisAndPnl() = runBlocking {
        val assets = listOf(
            ValueOverTimeAsset(eurAssetId, currencyOf(eurAssetId), priceHistory(eurAssetId)),
            ValueOverTimeAsset(usdAssetId, currencyOf(usdAssetId), priceHistory(usdAssetId)),
        )
        val transactions = transactions()

        val holdingsCurve = valueOverTime(ValueOverTimeInput(transactions, assets, today, converter))
        assertEquals("the curve ends on the reporting day", today, holdingsCurve.last().date)
        assertEquals("holdings value on the final day", 1265.0, holdingsCurve.last().valueEur, 0.0)

        val netWorth = netWorthSeries(
            NetWorthSeriesInput(
                holdingsValues = holdingsCurve,
                movements = cashMovements().map { it as CashMovement },
                today = today,
            )
        )
        assertEquals("net worth ends on the reporting day", today, netWorth.last().date)
        assertEquals("totalValueEur", 2285.0, netWorth.last().valueEur, 0.0)

        val costBasis = costBasisOverTime(CostBasisOverTimeInput(transactions, assets, today, converter))
        assertEquals("costBasisEur", 984.9, costBasis.last().costBasisEur, 0.0)

        // pnlEur is HOLDINGS value − cost basis, not net worth − cost basis:
        // cash is not a gain. 1265 − 984.9 = 280.1, whereas 2285 − 984.9 would be
        // 1300.1 — the fixture's published 280.1 pins which of the two the
        // reference means, and this assertion is where that gets settled.
        assertEquals(
            "pnlEur",
            280.1,
            holdingsCurve.last().valueEur - costBasis.last().costBasisEur,
            0.0,
        )
    }
}
