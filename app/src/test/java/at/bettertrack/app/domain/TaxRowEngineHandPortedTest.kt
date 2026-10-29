package at.bettertrack.app.domain

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `packages/domain/src/__tests__/taxRowEngine.test.ts`, hand-ported (plan §3.4
 * step 4) — listed in `app/src/test/resources/domain-vectors/MANIFEST.json`
 * with the same reason.
 *
 * **Why these could not be vectors.** #1512 shipped the row-engine truth table
 * (`taxVectors/rowEngine.ts`) as a *shared conformance vector set*: twelve frozen
 * row shapes × six living regimes, each with a hand-listed `expected` outcome
 * ("READ THE EXPECTATIONS AS DATA, NOT AS A RULE"), replayed by the server's
 * `countryState` suite and the paranoid client's `taxEngine` suite so the two
 * hand-mirrored classifiers can never drift silently. `tax.json` already carries
 * every pair as a `taxEngineForRow` vector — but the generator records what the
 * TypeScript engine **returned**. If the platform's classifier and its own
 * declared table ever drifted apart, a recorded vector would faithfully copy the
 * drift and stay green. Only asserting against the DECLARATION closes that loop,
 * and a declaration is fixture data, not an engine call — the
 * `storageDriftVectors.ts` precedent.
 *
 * So the generator emits the table verbatim as `taxRowEngineVectors.json` and
 * this suite drives the Kotlin [taxEngineForRow] against the platform's own
 * stated outcomes, plus the error-FIELD assertions the vector runner cannot make
 * (a vector asserts the error's name and message, not its `taxCountry`).
 */
class TaxRowEngineHandPortedTest {

    data class RowVector(
        val id: String,
        val row: TaxRowEngineFacts,
        val living: TaxRowEngine,
        /** `expected.engine`, or `null` when the declaration is `{ throws }`. */
        val expectedEngine: TaxRowEngine?,
        val expectedThrows: String?,
    )

    companion object {
        private val FIXTURE: JsonObject by lazy {
            val stream = TaxRowEngineHandPortedTest::class.java
                .getResourceAsStream("/domain-vectors/taxRowEngineVectors.json")
                ?: error(
                    "Missing /domain-vectors/taxRowEngineVectors.json — regenerate with " +
                        "`node --experimental-transform-types tools/domain-vectors/generate.ts`",
                )
            VECTOR_JSON.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
        }

        private val LIVING_REGIMES: List<String> by lazy {
            FIXTURE["livingRegimes"]!!.jsonArray.map { it.jsonPrimitive.content }
        }

        private val VECTORS: List<RowVector> by lazy {
            FIXTURE["vectors"]!!.jsonArray.map { e ->
                val v = e.jsonObject
                val row = v["row"]!!.jsonObject
                val expected = v["expected"]!!.jsonObject
                RowVector(
                    id = v["id"]!!.jsonPrimitive.content,
                    row = TaxRowEngineFacts(
                        taxMode = row["taxMode"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
                        taxCountry = row["taxCountry"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
                    ),
                    living = v["living"]!!.jsonPrimitive.content,
                    expectedEngine = expected["engine"]?.jsonPrimitive?.content,
                    expectedThrows = expected["throws"]?.jsonPrimitive?.content,
                )
            }
        }
    }

    // =======================================================================
    // The table's own shape — fixture data, not an engine result
    // =======================================================================

    /** "spans every living regime with every row family exactly once". */
    @Test
    fun `the table spans every living regime with every row family exactly once`() {
        assertEquals("packages/domain/src/taxVectors/rowEngine.ts", FIXTURE["source"]!!.jsonPrimitive.content)
        assertEquals(listOf("none", "manual", "AT", "DE", "FI", "custom"), LIVING_REGIMES)
        assertEquals(12 * 6, VECTORS.size)
        assertEquals(VECTORS.size, VECTORS.map { it.id }.toSet().size)
        // Every family lists its outcomes in LIVING_REGIMES order, so the ids
        // cycle through the regimes family by family.
        VECTORS.forEachIndexed { index, vector ->
            assertEquals(vector.id, LIVING_REGIMES[index % LIVING_REGIMES.size], vector.living)
            assertTrue(vector.id, vector.id.endsWith(" / living ${vector.living}"))
        }
        // Each declaration is exactly one of the two shapes.
        VECTORS.forEach { assertTrue(it.id, (it.expectedEngine == null) != (it.expectedThrows == null)) }
    }

    // =======================================================================
    // The classifier against the DECLARED outcomes
    // =======================================================================

    /** "reproduces the committed vector: %s" — against the declaration, not the recording. */
    @Test
    fun `taxEngineForRow reproduces every committed vector against its declared outcome`() {
        for (vector in VECTORS) {
            if (vector.expectedThrows != null) {
                assertEquals(vector.id, "unsupported-country", vector.expectedThrows)
                assertThrows(vector.id, TaxRowClassificationError::class.java) {
                    taxEngineForRow(vector.row, vector.living)
                }
            } else {
                assertEquals(vector.id, vector.expectedEngine, taxEngineForRow(vector.row, vector.living))
            }
        }
        // The negative space is exactly the four unwired/mis-spelled countries,
        // under every regime.
        assertEquals(4 * 6, VECTORS.count { it.expectedThrows != null })
    }

    // =======================================================================
    // Error fields — the vector runner asserts name + message, not the fields
    // =======================================================================

    /** "names the offending country in the classification error and never falls through to AT". */
    @Test
    fun `the classification error carries the offending country and never falls through to AT`() {
        val us = assertThrows(TaxRowClassificationError::class.java) {
            taxEngineForRow(TaxRowEngineFacts("country_specific", "US"), "AT")
        }
        assertTrue(us.message!!.contains("\"US\""))
        assertEquals("US", us.taxCountry)

        val de = assertThrows(TaxRowClassificationError::class.java) {
            taxEngineForRow(TaxRowEngineFacts("country_specific", "de"), "manual")
        }
        // `expect(caught).toBeInstanceOf(TaxRowClassificationError)` and
        // `.name === 'TaxRowClassificationError'`: the class IS the name here —
        // TaxVectorTest maps it back to the TypeScript `Error.name` for the vectors.
        assertEquals("de", de.taxCountry)
        assertTrue(de is DomainException)
    }

    /** "rejects an unknown frozen mode rather than guessing an engine" — the field. */
    @Test
    fun `an unknown frozen mode is refused with the row's country on the error`() {
        val e = assertThrows(TaxRowClassificationError::class.java) {
            taxEngineForRow(TaxRowEngineFacts("flat_rate", null), "manual")
        }
        assertNull(e.taxCountry)
        assertEquals("Tax engine: unknown frozen tax mode \"flat_rate\"", e.message)
    }

    /** `frozenTaxCountryEngine` → "fails LOUD on %j": the field carries the literal, untrimmed and uncased. */
    @Test
    fun `frozenTaxCountryEngine fails loud with the offending literal on the error`() {
        for (country in listOf("US", "de", "AT ", "", "FI\n", "DEU")) {
            val e = assertThrows(TaxRowClassificationError::class.java) { frozenTaxCountryEngine(country) }
            assertEquals(country, e.taxCountry)
        }
    }

    /** `costBasisStrategyForEngine` → "refuses a custom row without one": no country on that error. */
    @Test
    fun `a custom row without a parameter snapshot is refused with no country on the error`() {
        val e = assertThrows(TaxRowClassificationError::class.java) {
            costBasisStrategyForEngine("custom", null)
        }
        assertNull(e.taxCountry)
    }
}
