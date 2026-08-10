package at.bettertrack.app.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test

/**
 * The conformance runner for the `tax` module (plan §3.4 step 3) — the sibling of
 * [DomainVectorTest] and [CashLedgerVectorTest], sharing all of their plumbing in
 * `VectorHarness.kt`.
 *
 * Every vector in the `tax` fixture is replayed against `Tax.kt` and compared
 * with **exact** `Double` equality. The expected values were produced by
 * executing the pinned platform TypeScript (`packages/domain/src/tax.ts` driven
 * by the inputs of `tax.test.ts`, `deTaxEngine.test.ts`, `customTax.test.ts` and
 * the `deTaxFixtures.ts` scenario set), so a divergence here is a real
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
 * hand-ported in `TaxHandPortedTest` / `DeTaxFixturesHandPortedTest`, which remain
 * JVM-side in :app.
 */
class TaxVectorTest {

    @Test
    fun taxVectorsReplayExactly() =
        replayModule("tax", 273, errorName = TAX_ERROR_NAME, run = ::run)

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
