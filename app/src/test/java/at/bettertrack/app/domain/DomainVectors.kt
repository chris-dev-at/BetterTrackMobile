package at.bettertrack.app.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

/**
 * Shared plumbing for the domain conformance harness.
 *
 * The vectors in `app/src/test/resources/domain-vectors/` were produced by
 * running the PINNED platform TypeScript (`tools/domain-vectors/generate.ts`),
 * so everything here decodes them, drives the Kotlin port, and compares with
 * **exact** `Double` equality.
 *
 * Doubles survive the JSON round-trip losslessly: JavaScript writes the shortest
 * string that reparses to the identical double, and `String.toDouble()` on the
 * JVM correctly rounds. Parsing goes through the raw literal (`content`) rather
 * than any intermediate representation so nothing is re-formatted on the way in.
 */

internal val VECTOR_JSON = Json { ignoreUnknownKeys = true }

internal fun loadVectorFile(module: String): List<JsonObject> {
    val stream = object {}.javaClass.getResourceAsStream("/domain-vectors/$module.json")
        ?: error(
            "Missing /domain-vectors/$module.json — regenerate with " +
                "`node --experimental-strip-types tools/domain-vectors/generate.ts`",
        )
    val text = stream.bufferedReader().use { it.readText() }
    return VECTOR_JSON.parseToJsonElement(text).jsonObject["vectors"]!!.jsonArray.map { it.jsonObject }
}

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
// Encoders: Kotlin results -> canonical JsonElement
// ---------------------------------------------------------------------------
//
// Encoding the ACTUAL result and structurally comparing it against the expected
// JSON gives one comparison code path (and therefore one place where exactness
// is enforced) instead of a bespoke assertion per result type.

internal fun num(value: Double): JsonElement = JsonPrimitive(value)
internal fun numOrNull(value: Double?): JsonElement =
    if (value == null) JsonNull else JsonPrimitive(value)

internal fun encodePositionState(p: PositionState): JsonElement = buildJsonObject {
    put("quantity", num(p.quantity))
    put("avgCost", num(p.avgCost))
    put("realizedPnl", num(p.realizedPnl))
    put(
        "realizations",
        buildJsonArray {
            p.realizations.forEach {
                add(
                    buildJsonObject {
                        put("index", JsonPrimitive(it.index))
                        put("realizedPnl", num(it.realizedPnl))
                    },
                )
            }
        },
    )
}

internal fun encodeHoldings(list: List<Holding>): JsonElement = buildJsonArray {
    list.forEach { h ->
        add(
            buildJsonObject {
                put("assetId", JsonPrimitive(h.assetId))
                put("currency", JsonPrimitive(h.currency))
                put("quantity", num(h.quantity))
                put("avgCost", num(h.avgCost))
                put("realizedPnl", num(h.realizedPnl))
                put("price", numOrNull(h.price))
                put("marketValueEur", numOrNull(h.marketValueEur))
                put("costBasisEur", numOrNull(h.costBasisEur))
                put("unrealizedPnlEur", numOrNull(h.unrealizedPnlEur))
                put("unrealizedPnlPct", numOrNull(h.unrealizedPnlPct))
                put("dayChangeEur", numOrNull(h.dayChangeEur))
                put("dayChangePct", numOrNull(h.dayChangePct))
            },
        )
    }
}

internal fun encodeDatedSeries(
    dates: List<String>,
    values: List<Double>,
    valueKey: String,
): JsonElement = buildJsonArray {
    dates.indices.forEach { i ->
        add(
            buildJsonObject {
                put("date", JsonPrimitive(dates[i]))
                put(valueKey, num(values[i]))
            },
        )
    }
}

internal fun encodeSeriesStats(s: SeriesStats): JsonElement = buildJsonObject {
    put("totalReturnPct", num(s.totalReturnPct))
    put("cagrPct", numOrNull(s.cagrPct))
    put("maxDrawdownPct", num(s.maxDrawdownPct))
    put(
        "bestDay",
        s.bestDay?.let {
            buildJsonObject {
                put("date", JsonPrimitive(it.date))
                put("returnPct", num(it.returnPct))
            }
        } ?: JsonNull,
    )
    put(
        "worstDay",
        s.worstDay?.let {
            buildJsonObject {
                put("date", JsonPrimitive(it.date))
                put("returnPct", num(it.returnPct))
            }
        } ?: JsonNull,
    )
}

internal fun encodeContributions(rows: List<ContributionShare>): JsonElement = buildJsonArray {
    rows.forEach {
        add(
            buildJsonObject {
                put("assetId", JsonPrimitive(it.assetId))
                put("weight", num(it.weight))
                put("contributionPct", num(it.contributionPct))
            },
        )
    }
}

internal fun encodeMetricMap(m: Map<ComparisonMetric, Double?>): JsonElement = buildJsonObject {
    COMPARISON_METRICS.forEach { put(it.wire, numOrNull(m[it])) }
}

internal fun encodeComparison(c: SeriesComparison): JsonElement = buildJsonObject {
    put("baselineId", JsonPrimitive(c.baselineId))
    put(
        "series",
        buildJsonArray {
            c.series.forEach {
                add(
                    buildJsonObject {
                        put("id", JsonPrimitive(it.id))
                        put("metrics", encodeMetricMap(it.metrics))
                        put("deltas", encodeMetricMap(it.deltas))
                    },
                )
            }
        },
    )
}

// ---------------------------------------------------------------------------
// The comparator — exact by default
// ---------------------------------------------------------------------------

/**
 * Structural comparison of an expected (generated-from-TypeScript) JSON value
 * against the encoded Kotlin result.
 *
 * Numbers are compared with `assertEquals(expected, actual, 0.0)` — **bit-exact**
 * — unless [tolerance] is non-null, which is only ever the case for the handful
 * of vectors documented in `DomainVectorTest.TOLERANCES`.
 */
internal fun assertJsonEquals(
    path: String,
    expected: JsonElement,
    actual: JsonElement,
    tolerance: Double?,
) {
    when {
        expected is JsonNull || actual is JsonNull -> {
            assertEquals("$path: null-ness differs", expected.toString(), actual.toString())
        }

        expected is JsonObject && actual is JsonObject -> {
            assertEquals(
                "$path: key sets differ",
                expected.keys.sorted().toString(),
                actual.keys.sorted().toString(),
            )
            expected.keys.forEach {
                assertJsonEquals("$path.$it", expected[it]!!, actual[it]!!, tolerance)
            }
        }

        expected is JsonArray && actual is JsonArray -> {
            assertEquals("$path: length differs", expected.size, actual.size)
            expected.indices.forEach {
                assertJsonEquals("$path[$it]", expected[it], actual[it], tolerance)
            }
        }

        expected is JsonPrimitive && actual is JsonPrimitive -> {
            val expectedNumber = if (expected.isString) null else expected.content.toDoubleOrNull()
            val actualNumber = if (actual.isString) null else actual.content.toDoubleOrNull()
            if (expectedNumber != null && actualNumber != null) {
                val delta = tolerance?.let { rel -> rel * kotlin.math.abs(expectedNumber) } ?: 0.0
                assertEquals(
                    "$path: expected ${expected.content} but was ${actual.content}",
                    expectedNumber,
                    actualNumber,
                    delta,
                )
            } else {
                assertEquals(path, expected.content, actual.content)
            }
        }

        else -> fail("$path: shape differs — expected $expected, was $actual")
    }
}

/** Assert the port threw the same error class and the same message as the TypeScript. */
internal fun assertThrewLike(expected: JsonObject, thrown: Throwable?) {
    val expectedName = expected.s("name")
    val expectedMessage = expected.s("message")
    if (thrown == null) {
        fail("expected $expectedName(\"$expectedMessage\") but nothing was thrown")
        return
    }
    val actualName = if (thrown is OversellError) "OversellError" else "Error"
    assertEquals("error class", expectedName, actualName)
    assertEquals("error message", expectedMessage, thrown.message)
}

// ---------------------------------------------------------------------------
// The FX fake — reproduces the exact converters the vitest suites used
// ---------------------------------------------------------------------------

/**
 * Deterministic [CurrencyConverter] rebuilt from the `fx` table the generator
 * emitted alongside each input, so the Kotlin replay sees byte-identical rates.
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

// ---------------------------------------------------------------------------
// Input decoders
// ---------------------------------------------------------------------------

internal fun decodeTransaction(o: JsonObject) = Transaction(
    assetId = o.s("assetId"),
    side = if (o.s("side") == "buy") TransactionSide.BUY else TransactionSide.SELL,
    quantity = o.d("quantity"),
    price = o.d("price"),
    fee = o.d("fee"),
    executedAt = o.s("executedAt"),
    allowUncovered = o.bOrNull("allowUncovered"),
    uncoveredEntryPrice = o.dOrNull("uncoveredEntryPrice"),
)

internal fun decodeTransactions(a: JsonArray) = a.map { decodeTransaction(it.jsonObject) }

internal fun decodePricePoint(o: JsonObject) = PricePoint(o.s("date"), o.d("close"))

internal fun decodeValueAsset(o: JsonObject) = ValueOverTimeAsset(
    assetId = o.s("assetId"),
    currency = o.s("currency"),
    prices = o.a("prices").map { decodePricePoint(it.jsonObject) },
)

internal fun decodeHoldingAsset(o: JsonObject) = HoldingAssetInput(
    assetId = o.s("assetId"),
    currency = o.s("currency"),
    quote = o.oOrNull("quote")?.let { HoldingQuote(it.d("price"), it.dOrNull("prevClose")) },
)

internal fun decodeStatPoint(o: JsonObject) = StatSeriesPoint(o.s("date"), o.d("value"))

internal fun decodeMonthly(a: JsonArray) =
    a.map { MonthlyIndexPoint(it.jsonObject.s("month"), it.jsonObject.d("value")) }

internal fun decodeDeflator(o: JsonObject): Deflator =
    if (o.s("kind") == "flat") {
        Deflator.Flat(o.d("pctPerYear"))
    } else {
        Deflator.Index(decodeMonthly(o.a("monthly")))
    }

internal fun decodeMetricVector(o: JsonObject): ComparisonMetricVector {
    val map = LinkedHashMap<ComparisonMetric, Double?>()
    COMPARISON_METRICS.forEach { map[it] = o.dOrNull(it.wire) }
    return map
}

internal fun decodeValuePoints(a: JsonArray) =
    a.map { ValuePoint(it.jsonObject.s("date"), it.jsonObject.d("valueEur")) }

internal fun decodeFlowPoints(a: JsonArray) =
    a.map { FlowPoint(it.jsonObject.s("date"), it.jsonObject.d("flowEur")) }

internal fun decodePerformancePoints(a: JsonArray) =
    a.map { PerformancePoint(it.jsonObject.s("date"), it.jsonObject.d("pct")) }
