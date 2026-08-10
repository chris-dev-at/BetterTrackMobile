package at.bettertrack.app.domain

import at.bettertrack.app.domain.vectors.GeneratedVectorFixtures
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The golden gate** (plan §3.4 step 5) — the highest-value conformance asset in
 * this work package.
 *
 * `serverTwrParity.fixture.json` is not a hand-written expectation and not a
 * recording of the TypeScript domain either: the platform's
 * `apps/api/src/__tests__/vaultClientTwrParity.test.ts` drives these exact inputs
 * through the **real server pipeline** (price history + snapshot layer +
 * `GET /portfolios/:id/history?range=MAX`) and pins the published `twrPct` to
 * what the server produced. The web client parity tests consume the same
 * vectors. Asserting the Kotlin port against it proves the app computes the same
 * money as the production backend — not merely the same money as a translation.
 *
 * This test reads the **raw fixture** (copied byte-identically out of the platform
 * monorepo by `tools/domain-vectors/generate.ts`) rather than the generator's
 * reshaped vector file, so it is an independent check: if the generator's
 * reshaping were wrong, this test would still fail.
 *
 * Every assertion is exact — **byte-for-byte** double equality across all 34
 * published points — and, since the move to commonTest, on Kotlin/Native as well
 * as the JVM.
 *
 * ## How the fixture's scenario inputs become `timeWeightedReturn` inputs
 *
 * `value(d) = holdings(d) + cash(d)`, where `holdings(d) = quantity · close(d)`
 * and the final day is marked to `quoteToday`. `flows` carries only **external**
 * movements:
 *
 *  - `sinceInceptionMax` — no cash at all. The buy is not cash-settled, so its
 *    gross cost (`qty·price + fee`) is the single external inflow, on the buy day.
 *  - `splitDateCashBuy` — the buy IS cash-settled but its cash leg is dated three
 *    days later. The engine emits split-date **compensators** (`+gross` on the buy
 *    day, `−gross` on the settlement day) which keep the double-counted stretch
 *    neutral; cash steps 2000 → 995 on the settlement day.
 *  - `internalCashFeeDrag` — the buy is NOT cash-settled (the fee movement carries
 *    no `transactionId`), so the gross cost is an external inflow. The `fee` cash
 *    movement is **internal** for TWR: it never appears in `flows`, and drags the
 *    curve only through cash 2000 → 1900. That classification is the whole point
 *    of the vector — a `fee` treated as an external flow would be divided back
 *    out and the curves would diverge from the fee day on.
 *
 * The compensator/classification *derivation* itself lives in the client money
 * engine and lands in a later work package; what is pinned here is that the
 * ported [timeWeightedReturn] turns those inputs into the server's exact numbers.
 */
class ServerTwrParityTest {

    private val fixture: JsonObject by lazy {
        VECTOR_JSON.parseToJsonElement(
            GeneratedVectorFixtures.text("serverTwrParity.fixture"),
        ).jsonObject
    }

    /**
     * Synthetic ascending ISO dates; only their order matters to the TWR chain.
     * (`String.format` is JVM-only, hence `padStart`.)
     */
    private fun dates(n: Int): List<String> =
        (0 until n).map { "2026-07-" + (19 + it).toString().padStart(2, '0') }

    private fun expected(scenario: String, key: String = "twrPct"): List<Double> =
        fixture.o(scenario).a(key).doubles()

    /** Assert every point byte-for-byte, and say which point diverged if not. */
    private fun assertExact(
        scenario: String,
        expectedPct: List<Double>,
        actual: List<PerformancePoint>,
    ) {
        assertEquals(expectedPct.size, actual.size, "$scenario: point count")
        expectedPct.indices.forEach { i ->
            assertDoubleEquals(
                "$scenario point $i (${actual[i].date}): expected ${expectedPct[i]} " +
                    "but was ${actual[i].pct}",
                expectedPct[i],
                actual[i].pct,
                0.0,
            )
        }
    }

    @Test
    fun sinceInceptionMaxReproducesTheServerTwrVectorExactly() {
        val scenario = fixture.o("sinceInceptionMax")
        val closes = scenario.a("closes").doubles()
        val quoteToday = scenario.d("quoteToday")
        val buy = scenario.o("buy")
        val qty = buy.d("quantity")

        // Guard the fixture shape the way clientMoney.test.ts does, so a platform
        // change to the inputs fails here instead of silently re-baselining.
        assertEquals(listOf(100.0, 105.0, 110.0, 115.0, 120.0, 125.0, 128.0), closes)
        assertDoubleEquals("quoteToday", 130.0, quoteToday, 0.0)
        assertDoubleEquals("quantity", 10.0, qty, 0.0)
        assertDoubleEquals("price", 100.0, buy.d("price"), 0.0)
        assertDoubleEquals("fee", 5.0, buy.d("fee"), 0.0)

        val d = dates(8)
        val values = closes.mapIndexed { i, c -> ValuePoint(d[i], qty * c) } +
            ValuePoint(d[7], qty * quoteToday)
        val flows = listOf(FlowPoint(d[0], qty * buy.d("price") + buy.d("fee")))

        assertExact(
            "sinceInceptionMax",
            expected("sinceInceptionMax"),
            timeWeightedReturn(values, flows),
        )
    }

    @Test
    fun splitDateCashBuyReproducesTheServerTwrVectorExactly() {
        val scenario = fixture.o("splitDateCashBuy")
        val closes = scenario.a("closes").doubles()
        val quoteToday = scenario.d("quoteToday")
        val buy = scenario.o("buy")
        val qty = buy.d("quantity")
        val deposit = scenario.d("depositEur")
        val linked = scenario.o("linkedBuyMovement").d("amountEur")

        assertDoubleEquals("depositEur", 2000.0, deposit, 0.0)
        assertDoubleEquals("linkedBuyMovement.amountEur", -1005.0, linked, 0.0)
        assertDoubleEquals("depositDayOffset", -8.0, scenario.d("depositDayOffset"), 0.0)
        assertDoubleEquals(
            "linkedBuyMovement.dayOffset",
            -5.0,
            scenario.o("linkedBuyMovement").d("dayOffset"),
            0.0,
        )

        val d = dates(9)
        // Cash steps down when the linked buy movement lands (dayOffset −5 ⇒ index 3).
        val cashAt = { i: Int -> if (i >= 3) deposit + linked else deposit }
        val values = buildList {
            add(ValuePoint(d[0], deposit))
            closes.forEachIndexed { i, c -> add(ValuePoint(d[i + 1], qty * c + cashAt(i + 1))) }
            add(ValuePoint(d[8], qty * quoteToday + cashAt(8)))
        }
        val gross = qty * buy.d("price") + buy.d("fee")
        val flows = listOf(
            FlowPoint(d[0], deposit),
            FlowPoint(d[1], gross), // compensator on the buy day
            FlowPoint(d[3], -gross), // compensator on the settlement day
        )

        assertExact(
            "splitDateCashBuy",
            expected("splitDateCashBuy"),
            timeWeightedReturn(values, flows),
        )
    }

    @Test
    fun internalCashFeeDragProvesAFeeDragsTheCurveInsteadOfDividingOut() {
        val scenario = fixture.o("internalCashFeeDrag")
        val closes = scenario.a("closes").doubles()
        val quoteToday = scenario.d("quoteToday")
        val buy = scenario.o("buy")
        val qty = buy.d("quantity")
        val deposit = scenario.d("depositEur")
        val fee = scenario.o("cashFee").d("amountEur")

        assertDoubleEquals("depositEur", 2000.0, deposit, 0.0)
        assertDoubleEquals("cashFee.amountEur", 100.0, fee, 0.0)
        assertDoubleEquals("cashFee.dayOffset", -5.0, scenario.o("cashFee").d("dayOffset"), 0.0)

        val d = dates(9)
        val gross = qty * buy.d("price") + buy.d("fee")
        // The fee is INTERNAL: it is absent from `flows` and only moves cash.
        val flows = listOf(FlowPoint(d[0], deposit), FlowPoint(d[1], gross))

        fun seriesWithCashFromDayThree(cashLater: Double): List<ValuePoint> = buildList {
            add(ValuePoint(d[0], deposit))
            closes.forEachIndexed { i, c ->
                add(ValuePoint(d[i + 1], qty * c + if (i + 1 >= 3) cashLater else deposit))
            }
            add(ValuePoint(d[8], qty * quoteToday + cashLater))
        }

        val withFee = timeWeightedReturn(seriesWithCashFromDayThree(deposit - fee), flows)
        val withoutFee = timeWeightedReturn(seriesWithCashFromDayThree(deposit), flows)

        assertExact("internalCashFeeDrag", expected("internalCashFeeDrag"), withFee)
        assertExact(
            "internalCashFeeDrag/withoutTheFee",
            expected("internalCashFeeDrag", "twrPctWithoutTheFee"),
            withoutFee,
        )

        // The drag, stated directly (mirrors clientMoney.test.ts): identical until
        // the fee lands on index 3, strictly lower from there on.
        for (i in 0 until 3) {
            assertDoubleEquals("point $i: curves must match before the fee", withoutFee[i].pct, withFee[i].pct, 0.0)
        }
        for (i in 3 until withFee.size) {
            assertTrue(
                withFee[i].pct < withoutFee[i].pct,
                "point $i: fee curve ${withFee[i].pct} should be below ${withoutFee[i].pct}",
            )
        }
    }

    @Test
    fun rebasePerformanceRestartsABoundedWindowAtExactlyZeroAndCompounds() {
        // The other half of the golden path: the web engine rebases every BOUNDED
        // range while leaving MAX on the since-inception vector
        // (`expect(bounded.value.series[0]?.twrPct).toBe(0)`).
        val scenario = fixture.o("sinceInceptionMax")
        val closes = scenario.a("closes").doubles()
        val qty = scenario.o("buy").d("quantity")
        val d = dates(8)
        val values = closes.mapIndexed { i, c -> ValuePoint(d[i], qty * c) } +
            ValuePoint(d[7], qty * scenario.d("quoteToday"))
        val flows = listOf(
            FlowPoint(d[0], qty * scenario.o("buy").d("price") + scenario.o("buy").d("fee")),
        )
        val sinceInception = timeWeightedReturn(values, flows)

        // A window slice starting three days in.
        val slice = sinceInception.drop(3)
        val rebased = rebasePerformance(slice)

        assertDoubleEquals("first point of a bounded window", 0.0, rebased[0].pct, 0.0)
        // Compounding, not subtraction: re-basing must reproduce the ratio of the
        // chained indices exactly.
        val base = 1 + slice[0].pct / 100
        slice.indices.forEach { i ->
            assertDoubleEquals(
                "rebased point $i",
                ((1 + slice[i].pct / 100) / base - 1) * 100,
                rebased[i].pct,
                0.0,
            )
        }
        // MAX is NOT rebased — it keeps the audited since-inception vector.
        assertDoubleEquals(
            "MAX keeps the since-inception vector",
            expected("sinceInceptionMax")[0],
            sinceInception[0].pct,
            0.0,
        )
    }
}
