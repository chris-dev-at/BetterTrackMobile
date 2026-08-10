package at.bettertrack.app.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

// ---------------------------------------------------------------------------
// tax decoders / encoders
// ---------------------------------------------------------------------------
//
// Kept out of `VectorHarness.kt` so the tax port adds no edit to the file the
// other two runners share (see the integration note in the module KDoc of
// Tax.kt).

/**
 * Mirrors the TypeScript `Error.name` the class sets in its constructor. A
 * tax-local twin of `VectorHarness.DOMAIN_ERROR_NAME`: the shared one maps the
 * `holdings` / `cashLedger` error classes, and teaching it about
 * [TaxComputationError] would mean editing a file the other two runners own.
 */
internal val TAX_ERROR_NAME: (Throwable) -> String = { thrown ->
    if (thrown is TaxComputationError) "TaxComputationError" else "Error"
}

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
