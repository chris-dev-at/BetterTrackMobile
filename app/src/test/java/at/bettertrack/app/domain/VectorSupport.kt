package at.bettertrack.app.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The remainder of the old `DomainVectors.kt`.
 *
 * The conformance HARNESS — the vector loader, the encoders, the exact-double
 * comparator and the three parameterized runners — moved to
 * `shared/src/commonTest/kotlin/at/bettertrack/app/domain/VectorHarness.kt` so
 * that the 622 platform vectors replay on Kotlin/Native as well as the JVM.
 *
 * What stays here is only what the tests that did NOT move still need:
 *
 *  - `DomainHandPortedTest` builds [VectorConverter]s,
 *  - `DeTaxFixturesHandPortedTest` and `StorageDriftVectorsHandPortedTest` parse
 *    their own JVM-resource fixtures (`deTaxFixtures.json`,
 *    `storageDriftVectors.json`) with [VECTOR_JSON] and the accessors below.
 *
 * Those three suites cover the cases that deliberately cannot travel as
 * `{fn, input, output}` vectors, so they stay JVM-side for now. The duplication
 * is ~70 lines of accessor plumbing against 450 lines of harness, and Kotlin's
 * `internal` visibility is per-module, so these declarations and the ones in
 * :shared's commonTest never see each other.
 */

internal val VECTOR_JSON = Json { ignoreUnknownKeys = true }

// ---------------------------------------------------------------------------
// JSON accessors — always via the raw literal, never a reformatted number
// ---------------------------------------------------------------------------

internal fun JsonObject.o(key: String): JsonObject = this[key]!!.jsonObject
internal fun JsonObject.a(key: String): JsonArray = this[key]!!.jsonArray
internal fun JsonObject.s(key: String): String = this[key]!!.jsonPrimitive.content
internal fun JsonObject.d(key: String): Double = this[key]!!.jsonPrimitive.content.toDouble()
internal fun JsonObject.i(key: String): Int = this[key]!!.jsonPrimitive.content.toInt()

/** Every element of a JSON number array, parsed from its raw literal. */
internal fun JsonArray.doubles(): List<Double> = map { it.jsonPrimitive.content.toDouble() }

internal fun JsonObject.dOrNull(key: String): Double? {
    val e = this[key] ?: return null
    if (e is JsonNull) return null
    return e.jsonPrimitive.content.toDouble()
}

internal fun JsonObject.sOrNull(key: String): String? {
    val e = this[key] ?: return null
    if (e is JsonNull) return null
    return e.jsonPrimitive.content
}

internal fun JsonObject.bOrNull(key: String): Boolean? {
    val e = this[key] ?: return null
    if (e is JsonNull) return null
    return e.jsonPrimitive.booleanOrNull
}

internal fun JsonObject.oOrNull(key: String): JsonObject? {
    val e = this[key] ?: return null
    if (e is JsonNull) return null
    return e.jsonObject
}

// ---------------------------------------------------------------------------
// The FX fake — reproduces the exact converters the vitest suites used
// ---------------------------------------------------------------------------

/**
 * Deterministic [CurrencyConverter] rebuilt from an `fx` table, so the Kotlin
 * replay sees byte-identical rates.
 *
 * It also **counts** its calls, which is what lets the hand-ported coalescing
 * tests assert "exactly one conversion per (currency, day)" without any mocking
 * framework.
 */
internal class VectorConverter(private val spec: JsonObject) : CurrencyConverter {
    val calls = mutableListOf<Call>()

    data class Call(val amount: Double, val currency: String, val date: String?)

    override suspend fun toBase(
        amount: Double,
        currency: String,
        date: String?,
        base: String?,
    ): Double {
        calls.add(Call(amount, currency, date))
        return when (val kind = spec.s("kind")) {
            "identity" -> amount
            "flat" -> {
                val rate = spec.o("rates").dOrNull(currency)
                    ?: throw DomainException("no rate for $currency")
                amount * rate
            }
            "dated" -> {
                if (currency == "EUR") {
                    amount
                } else {
                    val rate = date?.let { spec.o("ratesByDate").oOrNull(currency)?.dOrNull(it) }
                        ?: throw DomainException("no rate for $currency on ${date ?: "spot"}")
                    amount * rate
                }
            }
            else -> error("unknown fx kind $kind")
        }
    }
}
