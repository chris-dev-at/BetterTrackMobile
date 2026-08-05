package at.bettertrack.app.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * `packages/domain/src/__tests__/storageDriftVectors.ts`, hand-ported (plan §3.4
 * step 4) — listed in `app/src/test/resources/domain-vectors/MANIFEST.json` with
 * the same reason.
 *
 * **Why these could not be vectors.** #1094 shipped the F1 storage-drift fixture
 * as a *shared conformance vector*: a row set plus an `expected` block that
 * DECLARES what every replay of it must produce (`throws`, `quantity`,
 * `realizedPnl`, `realizedPnlTolerance`). `holdings.test.ts` and `tax.test.ts`
 * both assert their engine against that declaration, and the module's own header
 * names the mobile Kotlin port as an intended consumer: *"both vectors must
 * replay identically there"*.
 *
 * The generated `{fn, input, output}` vectors in `holdings.json` / `tax.json`
 * already replay both row sets through [reducePosition], [deriveHoldings] and
 * [realizedSellsEur] — but the generator records what the TypeScript engine
 * **returned**. If the platform's engine and its own declared `expected` block
 * ever drifted apart, a recorded vector would faithfully copy the drift and stay
 * green. Only asserting against the DECLARATION closes that loop, and a
 * declaration is fixture data, not an engine call.
 *
 * So the generator emits the two vectors verbatim as `storageDriftVectors.json`
 * and this suite drives the Kotlin engine against the platform's own stated
 * expectations — the same assertions `holdings.test.ts` and `tax.test.ts` make,
 * on the same data, one language over.
 *
 * The envelope itself: quantities persist as `numeric(20,8)`. The write path
 * epsilon-validates the RAW client values, then PostgreSQL rounds each row
 * independently to scale 8, so a stored row can sit one quantum away from the
 * value that was validated. F1 is four raw buys of `0.1000000049` against a sell
 * of their exact raw sum `0.4000000196`; stored, that is four `0.10000000` buys
 * against a `0.40000002` sell — a `2e-8` shortfall inside the five contributing
 * rows' `5e-8` envelope. [reducePosition] waives it (#1094, extending #917's tax
 * behavior); the beyond-envelope twin at `0.40000006` still fails closed.
 */
class StorageDriftVectorsHandPortedTest {

    // =======================================================================
    // Fixture model (mirrors storageDriftVectors.ts, decoded from the JSON)
    // =======================================================================

    data class DriftRow(
        val id: String,
        val side: String,
        val quantity: Double,
        val price: Double,
        val fee: Double,
        val executedAt: String,
    )

    data class DriftOutcome(
        val throws: Boolean,
        val quantity: Double?,
        val realizedPnl: Double?,
        val realizedPnlTolerance: Double?,
    )

    data class DriftVector(
        val name: String,
        val description: String,
        val rows: List<DriftRow>,
        val expected: DriftOutcome,
    )

    companion object {
        private fun JsonObject.boolAt(key: String): Boolean =
            this[key]!!.jsonPrimitive.content.toBooleanStrict()

        internal val RAW_BUY_QUANTITY: Double
        internal val RAW_SELL_QUANTITY: Double
        internal val VECTORS: List<DriftVector>

        init {
            val stream = StorageDriftVectorsHandPortedTest::class.java
                .getResourceAsStream("/domain-vectors/storageDriftVectors.json")
                ?: error(
                    "Missing /domain-vectors/storageDriftVectors.json — regenerate with " +
                        "`node --experimental-strip-types tools/domain-vectors/generate.ts`",
                )
            val root = VECTOR_JSON
                .parseToJsonElement(stream.bufferedReader().use { it.readText() })
                .jsonObject
            RAW_BUY_QUANTITY = root.d("rawBuyQuantity")
            RAW_SELL_QUANTITY = root.d("rawSellQuantity")
            VECTORS = root.a("vectors").map { element ->
                val v = element.jsonObject
                val expected = v.o("expected")
                DriftVector(
                    name = v.s("name"),
                    description = v.s("description"),
                    rows = v.a("rows").map { rowElement ->
                        val r = rowElement.jsonObject
                        DriftRow(
                            id = r.s("id"),
                            side = r.s("side"),
                            quantity = r.d("quantity"),
                            price = r.d("price"),
                            fee = r.d("fee"),
                            executedAt = r.s("executedAt"),
                        )
                    },
                    expected = DriftOutcome(
                        throws = expected.boolAt("throws"),
                        quantity = expected.dOrNull("quantity"),
                        realizedPnl = expected.dOrNull("realizedPnl"),
                        realizedPnlTolerance = expected.dOrNull("realizedPnlTolerance"),
                    ),
                )
            }
        }

        private fun vector(name: String): DriftVector =
            VECTORS.find { it.name == name } ?: error("No storage-drift vector named $name")

        internal val STORED_DRIFT: DriftVector get() = vector("f1-stored-drift")
        internal val BEYOND_ENVELOPE: DriftVector get() = vector("f1-beyond-envelope")
    }

    /** `holdings.test.ts`'s `vectorTxns()`. */
    private fun holdingsRows(vector: DriftVector): List<Transaction> = vector.rows.map { row ->
        Transaction(
            assetId = "A",
            side = if (row.side == "buy") TransactionSide.BUY else TransactionSide.SELL,
            quantity = row.quantity,
            price = row.price,
            fee = row.fee,
            executedAt = row.executedAt,
        )
    }

    /** `tax.test.ts`'s row mapping — the vector's ids travel here. */
    private fun taxRows(vector: DriftVector): List<TaxableTransaction> = vector.rows.map { row ->
        TaxableTransaction(
            id = row.id,
            assetId = "asset-1",
            side = row.side,
            quantity = row.quantity,
            priceEur = row.price,
            feeEur = row.fee,
            executedAt = row.executedAt,
        )
    }

    // =======================================================================
    // The declaration itself — the data the two engines are asserted against
    // =======================================================================

    @Test
    fun `carries both mandated vectors with coherent declarations`() {
        assertEquals("both #1094 vectors present", 2, VECTORS.size)
        assertNotNull(STORED_DRIFT)
        assertNotNull(BEYOND_ENVELOPE)

        // The clean vector declares a value; the throwing one declares no value.
        assertEquals("f1-stored-drift must not declare a throw", false, STORED_DRIFT.expected.throws)
        assertEquals("f1-beyond-envelope must declare a throw", true, BEYOND_ENVELOPE.expected.throws)
        assertEquals("declared flat position", 0.0, STORED_DRIFT.expected.quantity!!, 0.0)
        assertEquals("declared realized P/L", 4.0, STORED_DRIFT.expected.realizedPnl!!, 0.0)
        assertEquals("declared tolerance", 1e-6, STORED_DRIFT.expected.realizedPnlTolerance!!, 0.0)
        assertEquals(null, BEYOND_ENVELOPE.expected.quantity)
        assertEquals(null, BEYOND_ENVELOPE.expected.realizedPnl)
    }

    @Test
    fun `the stored rows really are the raw rows rounded to scale 8`() {
        // The vector's premise: the raw quantities pass the write path's 1e-9
        // epsilon check, and numeric(20,8) rounding is what pulls them apart.
        // Round-half-up at scale 8, the storage rule the fixture header states.
        fun scale8(value: Double): Double = Math.round(value * 1e8) / 1e8

        val buys = STORED_DRIFT.rows.filter { it.side == "buy" }
        val sell = STORED_DRIFT.rows.single { it.side == "sell" }
        assertEquals("F1 has four stored buys", 4, buys.size)
        for (buy in buys) {
            assertEquals("stored buy = round8(raw buy)", scale8(RAW_BUY_QUANTITY), buy.quantity, 0.0)
        }
        assertEquals("stored sell = round8(raw sell)", scale8(RAW_SELL_QUANTITY), sell.quantity, 0.0)

        // Raw, the sell is exactly the sum of the raw buys — no oversell at write time.
        val rawSum = buys.fold(0.0) { acc, _ -> acc + RAW_BUY_QUANTITY }
        assertTrue(
            "the raw sell is within the write path's epsilon of the raw buys " +
                "(${jsNum(rawSum)} vs ${jsNum(RAW_SELL_QUANTITY)})",
            abs(RAW_SELL_QUANTITY - rawSum) <= QTY_EPSILON,
        )

        // Stored, it is a 2e-8 shortfall — inside the five contributing rows'
        // 5e-8 envelope, which is exactly why the replay must waive it.
        val storedHeld = buys.fold(0.0) { acc, buy -> acc + buy.quantity }
        val shortfall = sell.quantity - storedHeld
        assertTrue("stored shortfall is a genuine one", shortfall > QTY_EPSILON)
        assertTrue(
            "stored shortfall ${jsNum(shortfall)} sits inside the 5-row envelope",
            shortfall <= 5 * HOLDINGS_QTY_STORAGE_QUANTUM + QTY_EPSILON,
        )
    }

    @Test
    fun `the two quantum constants agree, as both module headers claim`() {
        // holdings.ts re-declares tax.ts's constant rather than importing it; the
        // port re-declares it too (§3.3 rules 3 + 7). Pin them equal.
        assertEquals(QTY_STORAGE_QUANTUM, HOLDINGS_QTY_STORAGE_QUANTUM, 0.0)
        assertEquals(1e-8, HOLDINGS_QTY_STORAGE_QUANTUM, 0.0)
    }

    // =======================================================================
    // holdings.reducePosition — the replay #1094 fixed
    // =======================================================================

    @Test
    fun `reducePosition derives the F1 vector to its declared outcome`() {
        val vector = STORED_DRIFT
        val position = reducePosition(holdingsRows(vector))

        assertEquals("declared quantity", vector.expected.quantity!!, position.quantity, 0.0)
        assertEquals("a waived close resets the average", 0.0, position.avgCost, 0.0)
        assertEquals(
            "declared realized P/L within the vector's own tolerance",
            vector.expected.realizedPnl!!,
            position.realizedPnl,
            vector.expected.realizedPnlTolerance!!,
        )
        assertEquals("one sell, one realization", 1, position.realizations.size)
    }

    @Test
    fun `reducePosition fails closed on the beyond-envelope vector`() {
        val vector = BEYOND_ENVELOPE
        assertTrue("this vector declares a throw", vector.expected.throws)
        assertThrows(OversellError::class.java) { reducePosition(holdingsRows(vector)) }
    }

    // =======================================================================
    // tax.realizedSellsEur — the replay that already had the envelope (#917)
    // =======================================================================

    @Test
    fun `realizedSellsEur replays the F1 vector identically under both strategies`() {
        val vector = STORED_DRIFT
        for (strategy in COST_BASIS_STRATEGIES) {
            val realizations = realizedSellsEur(taxRows(vector), strategy)
            assertEquals("one realization ($strategy)", 1, realizations.size)
            val realization = realizations.single()
            assertEquals(
                "declared realized P/L ($strategy)",
                vector.expected.realizedPnl!!,
                realization.realizedPnlEur,
                vector.expected.realizedPnlTolerance!!,
            )
            // The waived dust had real recorded acquisitions — it is drift, not a
            // phantom short, so nothing is reported as uncovered.
            assertEquals("no uncovered units ($strategy)", 0.0, realization.uncoveredQuantity, 0.0)
        }
    }

    @Test
    fun `realizedSellsEur fails closed on the beyond-envelope vector under both strategies`() {
        val vector = BEYOND_ENVELOPE
        for (strategy in COST_BASIS_STRATEGIES) {
            assertThrows(
                "beyond-envelope must throw under $strategy",
                TaxComputationError::class.java,
            ) { realizedSellsEur(taxRows(vector), strategy) }
        }
    }

    // =======================================================================
    // The cross-engine property #1094 was filed to establish
    // =======================================================================

    @Test
    fun `both replays now agree on the shared vector, which was the whole finding`() {
        // The audit's finding: tax accepted F1 while holdings threw, so a stored
        // row set that passed create-time validation and priced fine for tax
        // killed the portfolio overview. Pin agreement in BOTH directions.
        val clean = STORED_DRIFT
        val holdingsPnl = reducePosition(holdingsRows(clean)).realizedPnl
        val taxPnl = realizedSellsEur(taxRows(clean)).single().realizedPnlEur
        assertEquals(
            "holdings and tax realize the same P/L on the shared vector",
            taxPnl,
            holdingsPnl,
            clean.expected.realizedPnlTolerance!!,
        )

        val beyond = BEYOND_ENVELOPE
        assertThrows(OversellError::class.java) { reducePosition(holdingsRows(beyond)) }
        assertThrows(TaxComputationError::class.java) { realizedSellsEur(taxRows(beyond)) }
    }
}
