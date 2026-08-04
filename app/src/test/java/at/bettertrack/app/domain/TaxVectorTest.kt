package at.bettertrack.app.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The conformance runner for the `tax` module (plan §3.4 step 3) — the sibling of
 * [DomainVectorTest] and [CashLedgerVectorTest], sharing all of their plumbing in
 * `DomainVectors.kt`.
 *
 * Every vector in `app/src/test/resources/domain-vectors/tax.json` is replayed
 * against `Tax.kt` as its own JUnit case and compared with **exact** `Double`
 * equality — `assertEquals(expected, actual, 0.0)`. The expected values were
 * produced by executing the pinned platform TypeScript (`packages/domain/src/tax.ts`
 * driven by the inputs of `tax.test.ts`, `deTaxEngine.test.ts`, `customTax.test.ts`
 * and the `deTaxFixtures.ts` scenario set), so a divergence here is a real
 * translation defect, not a disagreement about what the answer should be.
 *
 * **There are no tolerances in this module — every vector asserts at `0.0`.** The
 * tax engine is addition, subtraction, multiplication, division, `Math.floor`,
 * `Math.min`/`Math.max` and comparison only: not one transcendental function whose
 * last bit could legitimately differ between V8 and the JVM (contrast
 * [DomainVectorTest]'s single `Math.pow` case). Every one of the five IEEE-754
 * basic operations is correctly rounded and therefore bit-identical on both
 * runtimes, and `Math.floor` is `roundToIntegral(−∞)` in both.
 *
 * The cases that cannot travel as a `{fn, input, output}` vector — non-finite
 * inputs (JSON has no NaN/Infinity), signed-zero identity, and assertions that
 * relate TWO calls to each other — are listed in the generator's skip manifest and
 * hand-ported in [TaxHandPortedTest] and [DeTaxFixturesHandPortedTest].
 */
@RunWith(Parameterized::class)
class TaxVectorTest(
    @Suppress("unused") private val label: String,
    private val vector: JsonObject,
) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun vectors(): Collection<Array<Any>> =
            loadVectorFile("tax").map { v ->
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
            assertTaxThrewLike(expectedThrows, thrown)
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
        "TAX_CONSTANTS" -> buildJsonObject {
            put("TAX_MODES", buildJsonArray { TAX_MODES.forEach { add(JsonPrimitive(it)) } })
            put("TAX_COUNTRY_AT", JsonPrimitive(TAX_COUNTRY_AT))
            put("TAX_COUNTRY_DE", JsonPrimitive(TAX_COUNTRY_DE))
            put("TAX_COUNTRY_FI", JsonPrimitive(TAX_COUNTRY_FI))
            put(
                "SUPPORTED_TAX_COUNTRIES",
                buildJsonArray { SUPPORTED_TAX_COUNTRIES.forEach { add(JsonPrimitive(it)) } },
            )
            put(
                "COST_BASIS_STRATEGIES",
                buildJsonArray { COST_BASIS_STRATEGIES.forEach { add(JsonPrimitive(it)) } },
            )
            put("AT_KEST_RATE", num(AT_KEST_RATE))
            put("DE_KAPEST_RATE", num(DE_KAPEST_RATE))
            put("DE_SOLI_RATE", num(DE_SOLI_RATE))
            put("DE_SPARER_PAUSCHBETRAG_EUR", num(DE_SPARER_PAUSCHBETRAG_EUR))
            put("FI_CAPITAL_INCOME_RATE", num(FI_CAPITAL_INCOME_RATE))
            put("FI_CAPITAL_INCOME_HIGH_RATE", num(FI_CAPITAL_INCOME_HIGH_RATE))
            put("FI_HIGH_RATE_THRESHOLD_EUR", num(FI_HIGH_RATE_THRESHOLD_EUR))
            put("TAX_YEAR_TIME_ZONE", JsonPrimitive(TAX_YEAR_TIME_ZONE))
            put("QTY_EPSILON", num(TAX_QTY_EPSILON))
            put("QTY_STORAGE_QUANTUM", num(QTY_STORAGE_QUANTUM))
            put("AT_AS_CUSTOM_PARAMS", encodeCustomParams(AT_AS_CUSTOM_PARAMS))
        }

        // ---- scalars ----
        "floorCents" -> num(taxFloorCents(input.d("amountEur")))

        "viennaYearOf" -> JsonPrimitive(viennaYearOf(input.s("isoTimestamp")))

        "costBasisStrategyForCountry" ->
            JsonPrimitive(costBasisStrategyForCountry(input.sOrNull("country")))

        "atYearTargetEur" -> num(atYearTargetEur(input.d("poolEur")))

        "fiYearTargetEur" -> num(fiYearTargetEur(input.d("poolEur")))

        "dePotCategoryForAssetType" ->
            JsonPrimitive(dePotCategoryForAssetType(input.s("assetType")))

        "manualTaxEur" -> numOrNull(
            manualTaxEur(
                ManualTaxInput(
                    baseEur = input.d("baseEur"),
                    taxAmountEur = input.dOrNull("taxAmountEur"),
                    taxRatePct = input.dOrNull("taxRatePct"),
                ),
            ),
        )

        "taxMovementForDelta" -> {
            val spec = taxMovementForDelta(input.d("deltaEur"))
            if (spec == null) {
                JsonNull
            } else {
                buildJsonObject {
                    put("kind", JsonPrimitive(spec.kind))
                    put("amountEur", num(spec.amountEur))
                }
            }
        }

        // ---- the EUR cost-basis replay ----
        "realizedSellsEur" -> encodeRealizations(
            realizedSellsEur(
                decodeTaxableTransactions(input.a("transactions")),
                input.sOrNull("strategy") ?: "moving-average",
            ),
        )

        // ---- AT / FI pool settlements ----
        "settleAtYear" -> encodePoolSettlement(settleAtYear(decodePoolInput(input)))

        "settleFiYear" -> encodePoolSettlement(settleFiYear(decodePoolInput(input)))

        // ---- DE ----
        "deYearOutcome" -> encodeDeYearOutcome(deYearOutcome(decodeDeAggregates(input)))

        "deCarryPots" -> {
            val pots = deCarryPots(
                input.a("priorYearEvents").map { decodeDeEvents(it as JsonArray) },
            )
            buildJsonObject {
                put("aktienEur", num(pots.aktienEur))
                put("sonstigeEur", num(pots.sonstigeEur))
            }
        }

        "settleDeYear" -> {
            val result = settleDeYear(
                DeYearSettlementInput(
                    aktienPotInEur = input.d("aktienPotInEur"),
                    sonstigePotInEur = input.d("sonstigePotInEur"),
                    existingEvents = decodeDeEvents(input.a("existingEvents")),
                    heldEur = input.d("heldEur"),
                    newEvents = decodeDeEvents(input.a("newEvents")),
                ),
            )
            buildJsonObject {
                put("correctionDeltaEur", num(result.correctionDeltaEur))
                put("newEventDeltasEur", encodeDoubles(result.newEventDeltasEur))
                put("heldAfterEur", num(result.heldAfterEur))
                put("yearEnd", encodeDeYearOutcome(result.yearEnd))
            }
        }

        // ---- the custom rule-built engine ----
        "initialCustomCarry" -> encodeCustomCarry(initialCustomCarry())

        "customYearOutcome" -> {
            val outcome = customYearOutcome(
                decodeCustomParams(input.o("params")),
                decodeCustomCarry(input.o("carry")),
                decodeCustomEvents(input.a("events")),
            )
            buildJsonObject {
                put("targetEur", num(outcome.targetEur))
                put("carryOut", encodeCustomCarry(outcome.carryOut))
            }
        }

        "customCarryForYears" -> encodeCustomCarry(
            customCarryForYears(
                decodeCustomParams(input.o("params")),
                input.a("priorYearEvents").map { decodeCustomEvents(it as JsonArray) },
            ),
        )

        "settleCustomYear" -> {
            val result = settleCustomYear(
                CustomYearSettlementInput(
                    params = decodeCustomParams(input.o("params")),
                    carry = decodeCustomCarry(input.o("carry")),
                    existingEvents = decodeCustomEvents(input.a("existingEvents")),
                    heldEur = input.d("heldEur"),
                    newEvents = decodeCustomEvents(input.a("newEvents")),
                ),
            )
            buildJsonObject {
                put("correctionDeltaEur", num(result.correctionDeltaEur))
                put("newEventDeltasEur", encodeDoubles(result.newEventDeltasEur))
                put("heldAfterEur", num(result.heldAfterEur))
                put("carryOut", encodeCustomCarry(result.carryOut))
            }
        }

        else -> error("no runner for fn=$fn")
    }
}

// ---------------------------------------------------------------------------
// tax decoders / encoders
// ---------------------------------------------------------------------------
//
// Kept in this file rather than in `DomainVectors.kt` so the tax port adds no
// edit to a file the other two runners share (see the integration note in the
// module KDoc of Tax.kt).

internal fun decodeTaxableTransaction(o: JsonObject) = TaxableTransaction(
    id = o.s("id"),
    assetId = o.s("assetId"),
    side = o.s("side"),
    quantity = o.d("quantity"),
    priceEur = o.d("priceEur"),
    feeEur = o.d("feeEur"),
    executedAt = o.s("executedAt"),
    allowUncovered = o.bOrNull("allowUncovered"),
    uncoveredEntryPriceEur = o.dOrNull("uncoveredEntryPriceEur"),
)

internal fun decodeTaxableTransactions(a: JsonArray): List<TaxableTransaction> =
    a.map { decodeTaxableTransaction(it.jsonObject) }

internal fun encodeRealizations(list: List<SellRealizationEur>): JsonElement = buildJsonArray {
    list.forEach { r ->
        add(
            buildJsonObject {
                put("id", JsonPrimitive(r.id))
                put("assetId", JsonPrimitive(r.assetId))
                put("executedAt", JsonPrimitive(r.executedAt))
                put("quantity", num(r.quantity))
                put("proceedsEur", num(r.proceedsEur))
                put("costBasisEur", num(r.costBasisEur))
                put("realizedPnlEur", num(r.realizedPnlEur))
                put("uncoveredQuantity", num(r.uncoveredQuantity))
            },
        )
    }
}

internal fun encodeDoubles(values: List<Double>): JsonElement = buildJsonArray {
    values.forEach { add(num(it)) }
}

internal fun decodePoolInput(o: JsonObject) = AtYearSettlementInput(
    existingGainsEur = o.a("existingGainsEur").doubles(),
    existingDividendsEur = o.a("existingDividendsEur").doubles(),
    heldEur = o.d("heldEur"),
    newEvents = o.a("newEvents").map {
        NewAtEvent(it.jsonObject.s("kind"), it.jsonObject.d("amountEur"))
    },
)

internal fun encodePoolSettlement(r: AtYearSettlementResult): JsonElement = buildJsonObject {
    put("correctionDeltaEur", num(r.correctionDeltaEur))
    put("newEventDeltasEur", encodeDoubles(r.newEventDeltasEur))
    put("heldAfterEur", num(r.heldAfterEur))
}

internal fun decodeDeAggregates(o: JsonObject) = DeYearAggregates(
    aktienPotInEur = o.d("aktienPotInEur"),
    sonstigePotInEur = o.d("sonstigePotInEur"),
    aktienSalePnlEur = o.d("aktienSalePnlEur"),
    sonstigeSalePnlEur = o.d("sonstigeSalePnlEur"),
    dividendsEur = o.d("dividendsEur"),
)

internal fun decodeDeEvents(a: JsonArray): List<DeTaxableEvent> = a.map {
    val o = it.jsonObject
    DeTaxableEvent(
        kind = o.s("kind"),
        amountEur = o.d("amountEur"),
        category = o.sOrNull("category"),
    )
}

internal fun encodeDeYearOutcome(o: DeYearOutcome): JsonElement = buildJsonObject {
    put("taxableBeforeAllowanceEur", num(o.taxableBeforeAllowanceEur))
    put("allowanceUsedEur", num(o.allowanceUsedEur))
    put("allowanceRemainingEur", num(o.allowanceRemainingEur))
    put("taxableBaseEur", num(o.taxableBaseEur))
    put("kapestEur", num(o.kapestEur))
    put("soliEur", num(o.soliEur))
    put("totalTaxEur", num(o.totalTaxEur))
    put("aktienPotOutEur", num(o.aktienPotOutEur))
    put("sonstigePotOutEur", num(o.sonstigePotOutEur))
}

internal fun decodeCustomParams(o: JsonObject) = CustomTaxParams(
    ratePct = o.d("ratePct"),
    lossOffset = o.bOrNull("lossOffset")!!,
    refund = o.bOrNull("refund")!!,
    yearReset = o.bOrNull("yearReset")!!,
    carryForward = o.bOrNull("carryForward")!!,
    costBasis = o.s("costBasis"),
)

internal fun encodeCustomParams(p: CustomTaxParams): JsonElement = buildJsonObject {
    put("ratePct", num(p.ratePct))
    put("lossOffset", JsonPrimitive(p.lossOffset))
    put("refund", JsonPrimitive(p.refund))
    put("yearReset", JsonPrimitive(p.yearReset))
    put("carryForward", JsonPrimitive(p.carryForward))
    put("costBasis", JsonPrimitive(p.costBasis))
}

internal fun decodeCustomCarry(o: JsonObject) = CustomCarry(
    potEur = o.d("potEur"),
    cumulativePoolEur = o.d("cumulativePoolEur"),
    cumulativeHeldEur = o.d("cumulativeHeldEur"),
)

internal fun encodeCustomCarry(c: CustomCarry): JsonElement = buildJsonObject {
    put("potEur", num(c.potEur))
    put("cumulativePoolEur", num(c.cumulativePoolEur))
    put("cumulativeHeldEur", num(c.cumulativeHeldEur))
}

internal fun decodeCustomEvents(a: JsonArray): List<CustomTaxableEvent> = a.map {
    CustomTaxableEvent(it.jsonObject.s("kind"), it.jsonObject.d("amountEur"))
}

/**
 * Assert the port threw the same error class and the same message as the
 * TypeScript.
 *
 * A tax-local twin of `DomainVectors.assertThrewLike`: the shared one maps the
 * `holdings` / `cashLedger` error classes, and teaching it about
 * [TaxComputationError] would mean editing a file the other two runners own.
 */
internal fun assertTaxThrewLike(expected: JsonObject, thrown: Throwable?) {
    val expectedName = expected.s("name")
    val expectedMessage = expected.s("message")
    if (thrown == null) {
        fail("expected $expectedName(\"$expectedMessage\") but nothing was thrown")
        return
    }
    // Mirrors the TypeScript `Error.name` the class sets in its constructor.
    val actualName = if (thrown is TaxComputationError) "TaxComputationError" else "Error"
    assertEquals("error class", expectedName, actualName)
    assertEquals("error message", expectedMessage, thrown.message)
}
