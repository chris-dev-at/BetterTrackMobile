package at.bettertrack.app.domain

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The conformance runner for the `cashLedger` module (plan §3.4 step 3) — the
 * sibling of [DomainVectorTest], sharing all of its plumbing in `DomainVectors.kt`.
 *
 * Every vector in `app/src/test/resources/domain-vectors/cashLedger.json` is
 * replayed against `CashLedger.kt` as its own JUnit case and compared with
 * **exact** `Double` equality — `assertEquals(expected, actual, 0.0)`. The
 * expected values were produced by executing the pinned platform TypeScript
 * (`packages/domain/src/cashLedger.ts` plus the `cashBySourceOverTime` half of
 * `dailySnapshotSeries.test.ts`), so a divergence here is a real translation
 * defect, not a disagreement about what the answer should be.
 *
 * **There are no tolerances in this module.** Every one of its vectors asserts at
 * `0.0`: the ledger is addition, subtraction, `Math.floor` and comparison only —
 * no transcendental function whose last bit could legitimately differ between V8
 * and the JVM (contrast [DomainVectorTest]'s single `Math.pow` case). The
 * composed `timeWeightedReturn` vectors are exact too.
 */
@RunWith(Parameterized::class)
class CashLedgerVectorTest(
    @Suppress("unused") private val label: String,
    private val vector: JsonObject,
) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun vectors(): Collection<Array<Any>> =
            loadVectorFile("cashLedger").map { v ->
                arrayOf<Any>("${v.s("fn")} — ${v.s("case")}", v)
            }
    }

    @Test
    fun replaysExactly() {
        val fn = vector.s("fn")
        val case = vector.s("case")
        val input = vector.o("input")
        val expectedThrows = vector.oOrNull("throws")

        var actual: JsonElement? = null
        var thrown: Throwable? = null
        try {
            actual = run(fn, input)
        } catch (e: DomainException) {
            thrown = e
        }

        if (expectedThrows != null) {
            assertThrewLike(expectedThrows, thrown)
            return
        }

        if (thrown != null) throw AssertionError("$fn/$case threw unexpectedly", thrown)
        assertNull("$fn/$case: expected no error", vector["throws"]?.takeIf { it !is JsonNull })
        // tolerance = null → assertEquals(expected, actual, 0.0) at every leaf.
        assertJsonEquals("$fn/$case", vector["output"]!!, actual!!, null)
    }

    /** Decode the vector's input, drive the Kotlin port, encode the result. */
    private fun run(fn: String, input: JsonObject): JsonElement = when (fn) {
        // ---- constants (declaration / insertion order is contract, §3.3 rule 4)
        "CASH_MOVEMENT_KINDS" ->
            buildJsonArray { CASH_MOVEMENT_KINDS.forEach { add(JsonPrimitive(it)) } }

        "CASH_MOVEMENT_SIGN" -> buildJsonArray {
            CASH_MOVEMENT_SIGN.forEach { (kind, sign) ->
                add(
                    buildJsonObject {
                        put("kind", JsonPrimitive(kind))
                        put("sign", JsonPrimitive(sign))
                    },
                )
            }
        }

        "EXTERNAL_CASH_MOVEMENT_KINDS" ->
            buildJsonArray { EXTERNAL_CASH_MOVEMENT_KINDS.forEach { add(JsonPrimitive(it)) } }

        // ---- scalars ----
        "floorCents" -> num(floorCents(input.d("amountEur")))

        "cashBalance" -> num(cashBalance(decodeCashMovements(input.a("movements"))))

        "applyCashMovement" -> num(
            applyCashMovement(
                input.d("balanceEur"),
                decodeCashMovement(input.o("movement")),
            ),
        )

        "spendableAsOf" -> num(
            spendableAsOf(
                decodeCashMovements(input.a("movements")),
                input.s("occurredAt"),
            ),
        )

        "setBalanceDelta" -> num(
            setBalanceDelta(input.d("currentBalanceEur"), input.d("targetBalanceEur")),
        )

        "isExternalCashMovement" -> JsonPrimitive(isExternalCashMovement(input.s("kind")))

        // ---- series & projections ----
        "projectCashLedger" ->
            encodeCashLedgerEntries(projectCashLedger(decodeCashMovements(input.a("movements"))))

        "cashBalanceOverTime" -> {
            val points = cashBalanceOverTime(decodeCashMovements(input.a("movements")))
            encodeDatedSeries(points.map { it.date }, points.map { it.balanceEur }, "balanceEur")
        }

        "externalCashFlowsForTwr" -> {
            val flows = externalCashFlowsForTwr(decodeCashMovements(input.a("movements")))
            encodeDatedSeries(flows.map { it.date }, flows.map { it.flowEur }, "flowEur")
        }

        "netWorthSeries" -> {
            val series = netWorthSeries(
                NetWorthSeriesInput(
                    holdingsValues = decodeValuePoints(input.a("holdingsValues")),
                    movements = decodeCashMovements(input.a("movements")),
                    today = input.s("today"),
                ),
            )
            encodeDatedSeries(series.map { it.date }, series.map { it.valueEur }, "valueEur")
        }

        // The compositions record the ledger's output flowing into the holdings
        // engine, so the ported TWR is exercised on real ledger-derived inputs.
        "timeWeightedReturn" -> {
            val perf = timeWeightedReturn(
                decodeValuePoints(input.a("values")),
                decodeFlowPoints(input.a("flows")),
            )
            encodeDatedSeries(perf.map { it.date }, perf.map { it.pct }, "pct")
        }

        // ---- cash sources ----
        "cashBalancesBySource" -> encodeBalancesBySource(
            cashBalancesBySource(decodeSourcedCashMovements(input.a("movements"))),
        )

        "projectCashLedgerBySource" -> buildJsonArray {
            projectCashLedgerBySource(decodeSourcedCashMovements(input.a("movements")))
                .forEach { (sourceId, entries) ->
                    add(
                        buildJsonObject {
                            put("sourceId", JsonPrimitive(sourceId))
                            put("entries", encodeCashLedgerEntries(entries))
                        },
                    )
                }
        }

        "cashBySourceOverTime" -> buildJsonArray {
            cashBySourceOverTime(
                decodeSourcedCashMovements(input.a("movements")),
                input.s("endDay"),
            ).forEach { point ->
                add(
                    buildJsonObject {
                        put("date", JsonPrimitive(point.date))
                        put("balances", encodeBalancesBySource(point.balances))
                    },
                )
            }
        }

        // ---- transfers & set-balance ----
        "pairedTransferMovements" -> {
            val legs = pairedTransferMovements(
                CashTransferInput(
                    fromSourceId = input.s("fromSourceId"),
                    toSourceId = input.s("toSourceId"),
                    amountEur = input.d("amountEur"),
                    occurredAt = input.s("occurredAt"),
                ),
            )
            buildJsonObject {
                put("outgoing", encodeCashMovement(legs.outgoing))
                put("incoming", encodeCashMovement(legs.incoming))
            }
        }

        "setBalanceMovement" -> {
            val movement = setBalanceMovement(
                SetBalanceInput(
                    sourceId = input.s("sourceId"),
                    currentBalanceEur = input.d("currentBalanceEur"),
                    targetBalanceEur = input.d("targetBalanceEur"),
                    occurredAt = input.s("occurredAt"),
                ),
            )
            if (movement == null) JsonNull else encodeCashMovement(movement)
        }

        else -> error("no runner for fn=$fn")
    }
}
