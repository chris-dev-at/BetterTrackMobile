package at.bettertrack.app.domain

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The conformance runner (plan §3.4 step 3).
 *
 * Every vector in `app/src/test/resources/domain-vectors/` is replayed against
 * the Kotlin port as its own JUnit case, and compared with **exact** `Double`
 * equality — `assertEquals(expected, actual, 0.0)`. The expected values were
 * produced by executing the pinned platform TypeScript, so a divergence here is
 * a real translation defect, not a disagreement about what the answer should be.
 *
 * A failing case names the module, the function, the original vitest case, and
 * the exact JSON path that diverged.
 */
@RunWith(Parameterized::class)
class DomainVectorTest(
    private val module: String,
    @Suppress("unused") private val label: String,
    private val vector: JsonObject,
) {

    companion object {
        /**
         * The ONLY vectors that cannot be bit-exact, with the reason.
         *
         * `deflateSeries` (flat rate) is the single case in the whole suite that
         * evaluates `Math.pow` with a non-trivial fractional exponent whose result
         * lands on a different double in V8 than on the JVM. Both engines are
         * within their specified accuracy — ECMAScript leaves `Math.pow`
         * implementation-approximated, and Java guarantees only ≤ 1 ulp — but they
         * are not required to agree, and here they differ by exactly **1 ulp**
         * (e.g. `1.1 ** -(731/365.25)`: V8 `0x3FEA71EC…D9`, JVM `…D8`). Java's
         * `StrictMath.pow` (fdlibm) gives the same answer as `Math.pow`, so there
         * is no JVM function that reproduces V8 here.
         *
         * The tolerance is therefore RELATIVE 1e-15 — about 4.5 ulp at these
         * magnitudes, i.e. tight enough that any genuine formula or
         * operation-order error still fails, while a last-bit `pow` disagreement
         * does not. Every other vector in every module, including all four
         * server-generated TWR goldens, is asserted at 0.0.
         *
         * `computeSeriesStats` / `indexAveragePctPerYear` also call `Math.pow`,
         * but their vectors' exponents happen to land on identical doubles on
         * both engines and are asserted exactly.
         */
        private val TOLERANCES: Map<Pair<String, String>, Double> = mapOf(
            ("deflateSeries" to "flat: slopes a flat nominal series downward at 10 %/yr over ~2 years")
                to 1e-15,
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}/{1}")
        fun vectors(): Collection<Array<Any>> =
            listOf("holdings", "seriesStats", "settingsScope", "serverTwrParity").flatMap { module ->
                loadVectorFile(module).map { v ->
                    arrayOf<Any>(module, "${v.s("fn")} — ${v.s("case")}", v)
                }
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
        assertJsonEquals(
            "$fn/$case",
            vector["output"]!!,
            actual!!,
            TOLERANCES[fn to case],
        )
    }

    /** Decode the vector's input, drive the Kotlin port, encode the result. */
    private fun run(fn: String, input: JsonObject): JsonElement = when (fn) {
        // ---- holdings ----
        "reducePosition" ->
            encodePositionState(reducePosition(decodeTransactions(input.a("transactions"))))

        "deriveHoldings" -> runBlocking {
            encodeHoldings(
                deriveHoldings(
                    decodeTransactions(input.a("transactions")),
                    input.a("assets").map { decodeHoldingAsset(it.jsonObject) },
                    VectorConverter(input.o("fx")),
                ),
            )
        }

        "valueOverTime" -> runBlocking {
            val series = valueOverTime(
                ValueOverTimeInput(
                    transactions = decodeTransactions(input.a("transactions")),
                    assets = input.a("assets").map { decodeValueAsset(it.jsonObject) },
                    today = input.s("today"),
                    converter = VectorConverter(input.o("fx")),
                ),
            )
            encodeDatedSeries(series.map { it.date }, series.map { it.valueEur }, "valueEur")
        }

        "costBasisOverTime" -> runBlocking {
            val series = costBasisOverTime(
                CostBasisOverTimeInput(
                    transactions = decodeTransactions(input.a("transactions")),
                    assets = input.a("assets").map { decodeValueAsset(it.jsonObject) },
                    today = input.s("today"),
                    converter = VectorConverter(input.o("fx")),
                ),
            )
            encodeDatedSeries(
                series.map { it.date },
                series.map { it.costBasisEur },
                "costBasisEur",
            )
        }

        "dailyCloseSeries" -> {
            val series = dailyCloseSeries(
                input.a("prices").map { decodePricePoint(it.jsonObject) },
                input.s("startDay"),
                input.s("endDay"),
            )
            encodeDatedSeries(series.map { it.date }, series.map { it.close }, "close")
        }

        "netFlowsOverTime" -> runBlocking {
            val currencyByAsset = LinkedHashMap<String, String>()
            input.o("currencyByAsset").forEach { (k, v) ->
                currencyByAsset[k] = (v as JsonPrimitive).content
            }
            val flows = netFlowsOverTime(
                NetFlowsInput(
                    transactions = decodeTransactions(input.a("transactions")),
                    currencyByAsset = currencyByAsset,
                    converter = VectorConverter(input.o("fx")),
                ),
            )
            encodeDatedSeries(flows.map { it.date }, flows.map { it.flowEur }, "flowEur")
        }

        "timeWeightedReturn" -> {
            val perf = timeWeightedReturn(
                decodeValuePoints(input.a("values")),
                decodeFlowPoints(input.a("flows")),
            )
            encodeDatedSeries(perf.map { it.date }, perf.map { it.pct }, "pct")
        }

        "rebasePerformance" -> {
            val perf = rebasePerformance(decodePerformancePoints(input.a("points")))
            encodeDatedSeries(perf.map { it.date }, perf.map { it.pct }, "pct")
        }

        // ---- seriesStats ----
        "computeSeriesStats" ->
            encodeSeriesStats(
                computeSeriesStats(input.a("series").map { decodeStatPoint(it.jsonObject) }),
            )

        "toPerformanceSeries" -> {
            val perf = toPerformanceSeries(input.a("series").map { decodeStatPoint(it.jsonObject) })
            encodeDatedSeries(perf.map { it.date }, perf.map { it.pct }, "pct")
        }

        "deflateSeries" -> {
            val real = deflateSeries(
                input.a("series").map { decodeStatPoint(it.jsonObject) },
                decodeDeflator(input.o("deflator")),
            )
            encodeDatedSeries(real.map { it.date }, real.map { it.value }, "value")
        }

        "indexAveragePctPerYear" ->
            numOrNull(indexAveragePctPerYear(decodeMonthly(input.a("monthly"))))

        "computeContributions" ->
            encodeContributions(
                input.a("inputs").map {
                    val o = it.jsonObject
                    ContributionInput(
                        o.s("assetId"),
                        o.d("startValue"),
                        o.d("endValue"),
                        o.d("currentValue"),
                    )
                }.let { computeContributions(it) },
            )

        "compareSeriesStats" ->
            encodeComparison(
                compareSeriesStats(
                    input.a("inputs").map {
                        ComparisonSeriesInput(
                            it.jsonObject.s("id"),
                            decodeMetricVector(it.jsonObject.o("metrics")),
                        )
                    },
                    input.s("baselineId"),
                ),
            )

        // ---- settingsScope ----
        // The layer values are arbitrary JSON, so the generic function is driven
        // with JsonElement itself. TS `null` and `undefined` both arrive as
        // `present: false`, which is exactly Kotlin's single `null`.
        "resolvePortfolioSetting" -> {
            fun layer(key: String): JsonElement? {
                val o = input.o(key)
                return if (o["present"]!!.jsonPrimitive.content.toBoolean()) o["value"]!! else null
            }
            val resolved = resolvePortfolioSetting(
                layer("override"),
                layer("userDefault"),
                input["systemDefault"]!!,
            )
            kotlinx.serialization.json.buildJsonObject {
                put("value", resolved.value)
                put("source", JsonPrimitive(resolved.source.wire))
            }
        }

        else -> error("no runner for fn=$fn")
    }
}
