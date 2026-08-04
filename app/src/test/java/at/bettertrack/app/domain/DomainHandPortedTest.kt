package at.bettertrack.app.domain

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vitest cases the vector generator deliberately skipped, hand-ported
 * (plan §3.4 step 4). Each is listed in
 * `app/src/test/resources/domain-vectors/MANIFEST.json` with its reason.
 *
 * Three kinds live here:
 *
 *  1. **Interaction** — `vi.fn()` call-count / call-argument assertions on the
 *     injected [CurrencyConverter]. These prove FX *coalescing*, which no
 *     input→output vector can express. Replayed against a counting fake instead
 *     of a mocking framework.
 *  2. **Non-finite inputs** — `Number.NaN` / `Number.POSITIVE_INFINITY`, which
 *     JSON cannot carry, so they cannot travel in a vector file.
 *  3. **Error detail** — assertions on an error's *fields*, not merely that it
 *     threw.
 */
class DomainHandPortedTest {

    // -----------------------------------------------------------------------
    // Helpers mirroring the vitest suites
    // -----------------------------------------------------------------------

    private fun tx(
        assetId: String = "A",
        side: TransactionSide,
        quantity: Double,
        price: Double,
        fee: Double = 0.0,
        executedAt: String = "2026-01-01T00:00:00Z",
        allowUncovered: Boolean? = null,
        uncoveredEntryPrice: Double? = null,
    ) = Transaction(assetId, side, quantity, price, fee, executedAt, allowUncovered, uncoveredEntryPrice)

    /** `stubConverter()` — flat rates, no date required. */
    private fun flatFx(vararg rates: Pair<String, Double>) = VectorConverter(
        buildJsonObject {
            put("kind", "flat")
            put("rates", buildJsonObject { rates.forEach { (k, v) -> put(k, v) } })
        },
    )

    /** `datedStubConverter()` — EUR identity; everything else needs its day's rate. */
    private fun datedFx(currency: String, vararg byDate: Pair<String, Double>) = VectorConverter(
        buildJsonObject {
            put("kind", "dated")
            put(
                "ratesByDate",
                buildJsonObject {
                    put(currency, buildJsonObject { byDate.forEach { (k, v) -> put(k, v) } })
                },
            )
        },
    )

    private fun prices(vararg points: Pair<String, Double>) =
        points.map { PricePoint(it.first, it.second) }

    // =======================================================================
    // 1. FX coalescing — the vi.fn() interaction assertions
    // =======================================================================

    @Test
    fun `valueOverTime coalesces FX to one conversion per currency and day`() = runBlocking {
        // holdings.test.ts: "reconstructs a multi-asset, multi-currency series…"
        // EUR is needed every day (5) — shared by A and X — and USD on the three
        // days C is held. Exactly 8 conversions, never 13.
        val fx = flatFx("EUR" to 1.0, "USD" to 0.9)
        valueOverTime(
            ValueOverTimeInput(
                transactions = listOf(
                    tx(assetId = "A", side = TransactionSide.BUY, quantity = 10.0, price = 100.0),
                    tx(
                        assetId = "C",
                        side = TransactionSide.BUY,
                        quantity = 2.0,
                        price = 50.0,
                        executedAt = "2026-01-03T00:00:00Z",
                    ),
                    tx(assetId = "X", side = TransactionSide.BUY, quantity = 1.0, price = 1000.0),
                ),
                assets = listOf(
                    ValueOverTimeAsset(
                        "A",
                        "EUR",
                        prices(
                            "2026-01-01" to 100.0,
                            "2026-01-02" to 102.0,
                            "2026-01-03" to 101.0,
                            "2026-01-04" to 105.0,
                            "2026-01-05" to 110.0,
                        ),
                    ),
                    ValueOverTimeAsset(
                        "C",
                        "USD",
                        prices("2026-01-03" to 50.0, "2026-01-04" to 52.0, "2026-01-05" to 51.0),
                    ),
                    ValueOverTimeAsset(
                        "X",
                        "EUR",
                        prices("2026-01-01" to 1000.0, "2026-01-04" to 1200.0),
                    ),
                ),
                today = "2026-01-05",
                converter = fx,
            ),
        )
        assertEquals(8, fx.calls.size)
        // Every distinct (currency, day) pair appears exactly once.
        assertEquals(fx.calls.size, fx.calls.map { it.currency to it.date }.toSet().size)
    }

    @Test
    fun `valueOverTime asks for historical rates by day, never a spot rate`() = runBlocking {
        val days = listOf("2026-01-01", "2026-01-02", "2026-01-03", "2026-01-04", "2026-01-05")
        val fx = datedFx(
            "USD",
            "2026-01-01" to 0.9,
            "2026-01-02" to 0.85,
            "2026-01-03" to 0.8,
            "2026-01-04" to 0.75,
            "2026-01-05" to 0.7,
        )
        valueOverTime(
            ValueOverTimeInput(
                transactions = listOf(
                    tx(assetId = "C", side = TransactionSide.BUY, quantity = 2.0, price = 50.0),
                    tx(
                        assetId = "C",
                        side = TransactionSide.SELL,
                        quantity = 1.0,
                        price = 50.0,
                        executedAt = "2026-01-03T00:00:00Z",
                    ),
                ),
                assets = listOf(
                    ValueOverTimeAsset(
                        "C",
                        "USD",
                        prices("2026-01-01" to 50.0, "2026-01-04" to 52.0),
                    ),
                ),
                today = "2026-01-05",
                converter = fx,
            ),
        )
        assertEquals(5, fx.calls.size)
        // `toHaveBeenCalledWith(1, 'USD', { date: day })` for each day.
        assertEquals(days, fx.calls.map { it.date })
        assertTrue(fx.calls.all { it.amount == 1.0 && it.currency == "USD" })
    }

    @Test
    fun `valueOverTime shares one rate lookup between same-currency assets`() = runBlocking {
        val fx = datedFx("USD", "2026-01-01" to 0.9, "2026-01-02" to 0.8)
        valueOverTime(
            ValueOverTimeInput(
                transactions = listOf(
                    tx(assetId = "C", side = TransactionSide.BUY, quantity = 1.0, price = 100.0),
                    tx(assetId = "D", side = TransactionSide.BUY, quantity = 2.0, price = 50.0),
                ),
                assets = listOf(
                    ValueOverTimeAsset(
                        "C",
                        "USD",
                        prices("2026-01-01" to 100.0, "2026-01-02" to 110.0),
                    ),
                    ValueOverTimeAsset(
                        "D",
                        "USD",
                        prices("2026-01-01" to 50.0, "2026-01-02" to 60.0),
                    ),
                ),
                today = "2026-01-02",
                converter = fx,
            ),
        )
        // Two USD assets, two days: one lookup per day, not one per asset per day.
        assertEquals(2, fx.calls.size)
    }

    @Test
    fun `netFlowsOverTime sums same-day native amounts before converting once`() = runBlocking {
        val fx = datedFx("USD", "2026-01-01" to 0.9, "2026-01-02" to 0.8)
        val flows = netFlowsOverTime(
            NetFlowsInput(
                transactions = listOf(
                    tx(
                        assetId = "U",
                        side = TransactionSide.BUY,
                        quantity = 10.0,
                        price = 100.0,
                        executedAt = "2026-01-01T10:00:00Z",
                    ),
                    tx(
                        assetId = "U",
                        side = TransactionSide.BUY,
                        quantity = 5.0,
                        price = 100.0,
                        executedAt = "2026-01-01T15:00:00Z",
                    ),
                    tx(
                        assetId = "U",
                        side = TransactionSide.BUY,
                        quantity = 10.0,
                        price = 100.0,
                        executedAt = "2026-01-02T10:00:00Z",
                    ),
                ),
                currencyByAsset = linkedMapOf("A" to "EUR", "U" to "USD"),
                converter = fx,
            ),
        )
        assertEquals(2, fx.calls.size)
        // Conversion is linear, so summing natively first is exact, not merely close.
        assertEquals(1500.0 * 0.9, flows[0].flowEur, 0.0)
        assertEquals(1000.0 * 0.8, flows[1].flowEur, 0.0)
    }

    @Test
    fun `costBasisOverTime coalesces FX per currency and day`() = runBlocking {
        // dailySnapshotSeries.test.ts (holdings half): "converts each day at that
        // day's historical FX rate and sums across assets".
        val fx = datedFx("USD", "2026-01-01" to 0.5, "2026-01-02" to 0.8)
        val series = costBasisOverTime(
            CostBasisOverTimeInput(
                transactions = listOf(
                    tx(
                        assetId = "eur",
                        side = TransactionSide.BUY,
                        quantity = 1.0,
                        price = 100.0,
                        executedAt = "2026-01-01T10:00:00.000Z",
                    ),
                    tx(
                        assetId = "usd",
                        side = TransactionSide.BUY,
                        quantity = 2.0,
                        price = 100.0,
                        executedAt = "2026-01-01T10:00:00.000Z",
                    ),
                ),
                assets = listOf(
                    ValueOverTimeAsset("eur", "EUR", prices("2026-01-01" to 1.0)),
                    ValueOverTimeAsset("usd", "USD", prices("2026-01-01" to 1.0)),
                ),
                today = "2026-01-02",
                converter = fx,
            ),
        )
        // Two currencies × two days = four lookups, each exactly once.
        assertEquals(4, fx.calls.size)
        assertEquals(fx.calls.size, fx.calls.map { it.currency to it.date }.toSet().size)
        assertEquals(100.0 + 200.0 * 0.5, series[0].costBasisEur, 0.0)
        assertEquals(100.0 + 200.0 * 0.8, series[1].costBasisEur, 0.0)
    }

    // =======================================================================
    // 2. Non-finite inputs (JSON cannot carry NaN / Infinity)
    // =======================================================================

    @Test
    fun `dailyCloseSeries rejects a non-finite close`() {
        val e = assertThrows(DomainException::class.java) {
            dailyCloseSeries(
                listOf(PricePoint("2026-01-01", Double.NaN)),
                "2026-01-01",
                "2026-01-02",
            )
        }
        assertEquals("Price point on 2026-01-01 must be a finite number, got NaN", e.message)
    }

    @Test
    fun `valueOverTime rejects a non-finite price point close`() = runBlocking {
        val e = assertThrows(DomainException::class.java) {
            runBlocking {
                valueOverTime(
                    ValueOverTimeInput(
                        transactions = listOf(
                            tx(side = TransactionSide.BUY, quantity = 1.0, price = 10.0),
                        ),
                        assets = listOf(
                            ValueOverTimeAsset(
                                "A",
                                "EUR",
                                listOf(PricePoint("2026-01-01", Double.NaN)),
                            ),
                        ),
                        today = "2026-01-02",
                        converter = flatFx("EUR" to 1.0),
                    ),
                )
            }
        }
        assertEquals(
            "Price point for A on 2026-01-01 must be a finite number, got NaN",
            e.message,
        )
    }

    @Test
    fun `timeWeightedReturn rejects a non-finite value and a non-finite flow`() {
        val badValue = assertThrows(DomainException::class.java) {
            timeWeightedReturn(listOf(ValuePoint("2026-01-01", Double.NaN)), emptyList())
        }
        assertEquals("Value on 2026-01-01 must be a finite number, got NaN", badValue.message)

        val badFlow = assertThrows(DomainException::class.java) {
            timeWeightedReturn(
                listOf(ValuePoint("2026-01-01", 1.0)),
                listOf(FlowPoint("2026-01-01", Double.POSITIVE_INFINITY)),
            )
        }
        assertEquals("Flow on 2026-01-01 must be a finite number, got Infinity", badFlow.message)
    }

    @Test
    fun `netFlowsOverTime rejects a non-finite quantity price or fee`() {
        val e = assertThrows(DomainException::class.java) {
            runBlocking {
                netFlowsOverTime(
                    NetFlowsInput(
                        transactions = listOf(
                            tx(side = TransactionSide.BUY, quantity = 1.0, price = Double.NaN),
                        ),
                        currencyByAsset = linkedMapOf("A" to "EUR"),
                        converter = flatFx("EUR" to 1.0),
                    ),
                )
            }
        }
        assertEquals(
            "netFlowsOverTime: non-finite quantity/price/fee on 2026-01-01T00:00:00Z",
            e.message,
        )
    }

    @Test
    fun `reducePosition rejects non-finite quantity and fee`() {
        val badQty = assertThrows(DomainException::class.java) {
            reducePosition(listOf(tx(side = TransactionSide.BUY, quantity = Double.NaN, price = 10.0)))
        }
        assertEquals(
            "Transaction quantity must be a finite positive number, got NaN",
            badQty.message,
        )

        val badFee = assertThrows(DomainException::class.java) {
            reducePosition(
                listOf(
                    tx(
                        side = TransactionSide.BUY,
                        quantity = 1.0,
                        price = 10.0,
                        fee = Double.POSITIVE_INFINITY,
                    ),
                ),
            )
        }
        assertEquals(
            "Transaction fee must be a finite non-negative number, got Infinity",
            badFee.message,
        )
    }

    // =======================================================================
    // 3. Error detail — the fields, not just the fact that it threw
    // =======================================================================

    @Test
    fun `OversellError carries the requested and held quantities`() {
        val e = assertThrows(OversellError::class.java) {
            reducePosition(
                listOf(
                    tx(side = TransactionSide.BUY, quantity = 3.5, price = 10.0),
                    tx(
                        side = TransactionSide.SELL,
                        quantity = 4.0,
                        price = 10.0,
                        executedAt = "2026-01-02T00:00:00Z",
                    ),
                ),
            )
        }
        assertEquals(4.0, e.requested, 0.0)
        assertEquals(3.5, e.held, 1e-10)
        assertEquals("A", e.assetId)
        assertTrue(e.message!!.contains("only 3.5 held"))
        // The full audited message, including JS number rendering ("4", not "4.0").
        assertEquals("Cannot sell 4 units of A: only 3.5 held.", e.message)
    }

    @Test
    fun `OversellError on an empty position reports zero held`() {
        val e = assertThrows(OversellError::class.java) {
            reducePosition(listOf(tx(side = TransactionSide.SELL, quantity = 1.0, price = 100.0)))
        }
        assertEquals(1.0, e.requested, 0.0)
        assertEquals(0.0, e.held, 0.0)
        assertEquals("Cannot sell 1 units of A: only 0 held.", e.message)
    }
}
