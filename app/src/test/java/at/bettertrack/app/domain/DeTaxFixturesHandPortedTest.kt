package at.bettertrack.app.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor
import kotlin.math.min

/**
 * `packages/domain/src/__tests__/deTaxFixtures.test.ts`, hand-ported (plan §3.4
 * step 4) — all 15 of its cases, listed in
 * `app/src/test/resources/domain-vectors/MANIFEST.json` under the
 * `deTaxFixtures/…` function names, with the same reason.
 *
 * **Why these could not be vectors.** That suite does not call the engine to
 * produce a value and compare it; it asserts that the 1109-line *fixture data* of
 * `deTaxFixtures.ts` — 8 scenarios of hand-computed German tax outcomes — is
 * internally consistent: ids unique, the trade log never oversells, proceeds
 * really are `qty · price − fee`, the year aggregates really are the sum of the
 * per-event figures, the year-end numbers really follow the researched §16
 * 2026-07-17 formula (pots, one-directional cross-offset, allowance, both floors),
 * pots really chain year to year, and the settlement steps really sum to the
 * year's target. A `{fn, input, output}` vector records one call; it cannot say
 * any of that.
 *
 * So the generator emits the fixture set verbatim as `deTaxFixtures.json` and this
 * suite replays the assertions in Kotlin — which additionally proves the ported
 * [taxFloorCents] and [viennaYearOf] reproduce the platform's own reference
 * arithmetic on real German tax data.
 *
 * The engine-side cases of `deTaxEngine.test.ts` are NOT here: they travel as
 * vectors in `tax.json` (`realizedSellsEur` / `deCarryPots` / `settleDeYear`, one
 * per scenario, per year and per settlement step). The single exception — the
 * "FIFO must NOT equal moving average" inequality against the fixture literals —
 * is at the bottom of this file, because it needs the fixture data.
 */
class DeTaxFixturesHandPortedTest {

    // =======================================================================
    // Fixture model (mirrors deTaxFixtures.ts, decoded from the emitted JSON)
    // =======================================================================

    data class FixtureTx(
        val id: String,
        val assetId: String,
        val category: String,
        val side: String,
        val quantity: Double,
        val priceEur: Double,
        val feeEur: Double,
        val executedAt: String,
    )

    data class FixtureDividend(
        val id: String,
        val assetId: String,
        val grossEur: Double,
        val executedAt: String,
    )

    data class ExpectedSell(
        val id: String,
        val category: String,
        val proceedsEur: Double,
        val fifoCostBasisEur: Double,
        val realizedPnlEur: Double,
        /** Stated only where a scenario proves FIFO ≠ moving average. */
        val movingAveragePnlEur: Double?,
    )

    data class ExpectedStep(val eventId: String, val deltaEur: Double, val heldAfterEur: Double)

    data class ExpectedYear(
        val year: Int,
        val aktienPotInEur: Double,
        val sonstigePotInEur: Double,
        val aktienSalePnlEur: Double,
        val sonstigeSalePnlEur: Double,
        val dividendsEur: Double,
        val taxableBeforeAllowanceEur: Double,
        val allowanceUsedEur: Double,
        val allowanceRemainingEur: Double,
        val taxableBaseEur: Double,
        val kapestEur: Double,
        val soliEur: Double,
        val totalTaxEur: Double,
        val aktienPotOutEur: Double,
        val sonstigePotOutEur: Double,
        val steps: List<ExpectedStep>,
    )

    data class Scenario(
        val id: String,
        val title: String,
        val ruleRefs: List<String>,
        val description: String,
        val transactions: List<FixtureTx>,
        val dividends: List<FixtureDividend>,
        val expectedSells: List<ExpectedSell>,
        val expectedYears: List<ExpectedYear>,
    )

    companion object {
        private fun decodeScenario(o: JsonObject) = Scenario(
            id = o.s("id"),
            title = o.s("title"),
            ruleRefs = o.a("ruleRefs").map { it.jsonPrimitive.content },
            description = o.s("description"),
            transactions = o.a("transactions").map { e ->
                val t = e.jsonObject
                FixtureTx(
                    id = t.s("id"),
                    assetId = t.s("assetId"),
                    category = t.s("category"),
                    side = t.s("side"),
                    quantity = t.d("quantity"),
                    priceEur = t.d("priceEur"),
                    feeEur = t.d("feeEur"),
                    executedAt = t.s("executedAt"),
                )
            },
            dividends = o.a("dividends").map { e ->
                val d = e.jsonObject
                FixtureDividend(d.s("id"), d.s("assetId"), d.d("grossEur"), d.s("executedAt"))
            },
            expectedSells = o.a("expectedSells").map { e ->
                val s = e.jsonObject
                ExpectedSell(
                    id = s.s("id"),
                    category = s.s("category"),
                    proceedsEur = s.d("proceedsEur"),
                    fifoCostBasisEur = s.d("fifoCostBasisEur"),
                    realizedPnlEur = s.d("realizedPnlEur"),
                    movingAveragePnlEur = s.dOrNull("movingAveragePnlEur"),
                )
            },
            expectedYears = o.a("expectedYears").map { e ->
                val y = e.jsonObject
                ExpectedYear(
                    year = y.i("year"),
                    aktienPotInEur = y.d("aktienPotInEur"),
                    sonstigePotInEur = y.d("sonstigePotInEur"),
                    aktienSalePnlEur = y.d("aktienSalePnlEur"),
                    sonstigeSalePnlEur = y.d("sonstigeSalePnlEur"),
                    dividendsEur = y.d("dividendsEur"),
                    taxableBeforeAllowanceEur = y.d("taxableBeforeAllowanceEur"),
                    allowanceUsedEur = y.d("allowanceUsedEur"),
                    allowanceRemainingEur = y.d("allowanceRemainingEur"),
                    taxableBaseEur = y.d("taxableBaseEur"),
                    kapestEur = y.d("kapestEur"),
                    soliEur = y.d("soliEur"),
                    totalTaxEur = y.d("totalTaxEur"),
                    aktienPotOutEur = y.d("aktienPotOutEur"),
                    sonstigePotOutEur = y.d("sonstigePotOutEur"),
                    steps = y.a("steps").map { se ->
                        val st = se.jsonObject
                        ExpectedStep(st.s("eventId"), st.d("deltaEur"), st.d("heldAfterEur"))
                    },
                )
            },
        )

        internal val FIXTURES: List<Scenario> by lazy {
            val stream = DeTaxFixturesHandPortedTest::class.java
                .getResourceAsStream("/domain-vectors/deTaxFixtures.json")
                ?: error(
                    "Missing /domain-vectors/deTaxFixtures.json — regenerate with " +
                        "`node --experimental-strip-types tools/domain-vectors/generate.ts`",
                )
            val text = stream.bufferedReader().use { it.readText() }
            VECTOR_JSON.parseToJsonElement(text)
                .jsonObject["scenarios"]!!
                .jsonArray
                .map { decodeScenario(it.jsonObject) }
        }
    }

    /**
     * `centsOf` of the vitest suite: `Math.round(v * 100)`.
     *
     * **Plan §3.3 rule 3, and a genuine trap.** JavaScript's `Math.round` rounds a
     * half **toward +∞** (`Math.round(-1.5) === -1`), and returns `-0` for
     * `Math.round(-0.5)`. Kotlin's `kotlin.math.round` rounds a half **away from
     * zero** (`round(-1.5) == -2.0`) — the opposite answer on every negative half,
     * and negative halves are exactly what a tax-refund fixture is full of. So this
     * is written as `floor(x + 0.5)`, which IS the ECMAScript definition, rather
     * than as any Kotlin rounding helper. (`java.lang.Math.round` agrees, being
     * `floor(x + 0.5)` too; `kotlin.math.round` does not.)
     */
    private fun centsOf(v: Double): Double = floor(v * 100 + 0.5)

    private fun assertCentsEqual(actual: Double, expected: Double, label: String) {
        assertEquals(label, centsOf(expected), centsOf(actual), 0.0)
    }

    /** The year's taxable events (sells + dividends — never buys), chronological. */
    private fun taxableEventsOf(scenario: Scenario): List<Triple<String, Double, Int>> {
        val sells = scenario.transactions.filter { it.side == "sell" }.map {
            Triple(it.id, jsDateParse(it.executedAt), viennaYearOf(it.executedAt))
        }
        val dividends = scenario.dividends.map {
            Triple(it.id, jsDateParse(it.executedAt), viennaYearOf(it.executedAt))
        }
        return (sells + dividends).sortedWith { a, b ->
            val delta = a.second - b.second
            if (delta < 0.0) -1 else if (delta > 0.0) 1 else 0
        }
    }

    /**
     * The researched DE year-end aggregation (§16 2026-07-17) applied to a year's
     * STATED aggregates — the reference the hand-computed fields must obey. This is
     * the suite's own reimplementation, deliberately NOT [deYearOutcome]: the point
     * is that the fixtures satisfy the researched formula independently.
     */
    private data class YearEndRef(
        val aktienPotOut: Double,
        val sonstigePotOut: Double,
        val taxableBeforeAllowance: Double,
        val allowanceUsed: Double,
        val taxableBase: Double,
        val kapest: Double,
        val soli: Double,
    )

    private fun recomputeYearEnd(y: ExpectedYear): YearEndRef {
        val aktienRemainder = y.aktienSalePnlEur - y.aktienPotInEur
        val sonstigeRemainder = y.dividendsEur + y.sonstigeSalePnlEur - y.sonstigePotInEur
        var aktienPositive = maxOf(0.0, aktienRemainder)
        val aktienPotOut = maxOf(0.0, -aktienRemainder)
        var sonstigePotOut = 0.0
        if (sonstigeRemainder < 0) {
            val crossOffset = min(-sonstigeRemainder, aktienPositive)
            aktienPositive -= crossOffset
            sonstigePotOut = -sonstigeRemainder - crossOffset
        }
        val taxableBeforeAllowance = aktienPositive + maxOf(0.0, sonstigeRemainder)
        val allowanceUsed = min(DE_SPARER_PAUSCHBETRAG_EUR, taxableBeforeAllowance)
        val taxableBase = taxableBeforeAllowance - allowanceUsed
        val kapest = taxFloorCents(DE_KAPEST_RATE * taxableBase)
        val soli = taxFloorCents(DE_SOLI_RATE * kapest)
        return YearEndRef(
            aktienPotOut,
            sonstigePotOut,
            taxableBeforeAllowance,
            allowanceUsed,
            taxableBase,
            kapest,
            soli,
        )
    }

    private fun byId(id: String): Scenario =
        FIXTURES.first { it.id == id }

    // =======================================================================
    // DE tax fixture catalog
    // =======================================================================

    @Test
    fun `contains the eight mandated scenarios with unique ids`() {
        assertEquals(
            listOf(
                "de-simple-gain",
                "de-fifo-multi-lot",
                "de-allowance-exhaustion",
                "de-aktien-loss-ringfenced",
                "de-sonstige-loss-cross-offset",
                "de-rounding-truncation",
                "de-year-boundary-carry",
                "de-intra-year-refund",
            ),
            FIXTURES.map { it.id },
        )
        assertEquals(FIXTURES.size, FIXTURES.map { it.id }.toSet().size)
    }

    @Test
    fun `documents every scenario with statute references`() {
        for (s in FIXTURES) {
            assertTrue(s.id, s.ruleRefs.isNotEmpty())
            assertTrue(s.id, s.title.isNotEmpty())
            assertTrue(s.id, s.description.isNotEmpty())
            assertTrue(s.id, s.expectedYears.isNotEmpty())
        }
    }

    // =======================================================================
    // Per scenario (the suite's describe.each)
    // =======================================================================

    @Test
    fun `has valid, coherent inputs -- dates parse, amounts sane, ids unique`() {
        for (s in FIXTURES) {
            val ids = mutableSetOf<String>()
            for (t in s.transactions) {
                assertTrue("duplicate id ${t.id}", ids.add(t.id))
                // viennaYearOf throws on anything unparseable, and returns an Int.
                assertTrue("${s.id}/${t.id}", viennaYearOf(t.executedAt) > 0)
                assertTrue("${s.id}/${t.id} quantity", t.quantity > 0)
                assertTrue("${s.id}/${t.id} price", t.priceEur >= 0)
                assertTrue("${s.id}/${t.id} fee", t.feeEur >= 0)
            }
            for (d in s.dividends) {
                assertTrue("duplicate id ${d.id}", ids.add(d.id))
                assertTrue("${s.id}/${d.id}", viennaYearOf(d.executedAt) > 0)
                assertTrue("${s.id}/${d.id} gross", d.grossEur > 0)
            }
        }
    }

    @Test
    fun `never sells more units than were bought before the sell`() {
        for (s in FIXTURES) {
            val ordered = s.transactions.sortedWith { a, b ->
                val delta = jsDateParse(a.executedAt) - jsDateParse(b.executedAt)
                if (delta < 0.0) -1 else if (delta > 0.0) 1 else 0
            }
            val held = mutableMapOf<String, Double>()
            for (t in ordered) {
                val current = held[t.assetId] ?: 0.0
                if (t.side == "buy") {
                    held[t.assetId] = current + t.quantity
                } else {
                    assertTrue("oversell in ${s.id}/${t.id}", current + 1e-9 >= t.quantity)
                    held[t.assetId] = current - t.quantity
                }
            }
        }
    }

    @Test
    fun `states exactly one expected realization per sell, with matching category`() {
        for (s in FIXTURES) {
            val sells = s.transactions.filter { it.side == "sell" }.sortedWith { a, b ->
                val delta = jsDateParse(a.executedAt) - jsDateParse(b.executedAt)
                if (delta < 0.0) -1 else if (delta > 0.0) 1 else 0
            }
            assertEquals(s.id, sells.map { it.id }, s.expectedSells.map { it.id })
            for (expected in s.expectedSells) {
                val sell = sells.first { it.id == expected.id }
                assertEquals("${s.id}/${sell.id} category", sell.category, expected.category)
                // proceeds = qty · price − fee (§20 Abs. 4 Satz 1 EStG).
                assertCentsEqual(
                    expected.proceedsEur,
                    sell.quantity * sell.priceEur - sell.feeEur,
                    "${s.id}/${sell.id} proceeds",
                )
                assertCentsEqual(
                    expected.realizedPnlEur,
                    expected.proceedsEur - expected.fifoCostBasisEur,
                    "${s.id}/${sell.id} pnl",
                )
                if (expected.movingAveragePnlEur != null) {
                    assertTrue(
                        "${s.id}/${sell.id}: the stated moving-average P/L must diverge",
                        centsOf(expected.movingAveragePnlEur) != centsOf(expected.realizedPnlEur),
                    )
                }
            }
        }
    }

    @Test
    fun `year aggregates reconcile with the per-event inputs`() {
        for (s in FIXTURES) {
            val years = s.expectedYears.map { it.year }
            assertEquals("${s.id}: years must be ascending", years.sorted(), years)
            assertEquals("${s.id}: years must be unique", years.toSet().size, years.size)

            for (event in taxableEventsOf(s)) {
                assertTrue("year of ${event.first} missing", years.contains(event.third))
            }

            for (y in s.expectedYears) {
                val sellsInYear = s.expectedSells.filter { e ->
                    val t = s.transactions.first { it.id == e.id }
                    viennaYearOf(t.executedAt) == y.year
                }
                var aktienPnl = 0.0
                for (e in sellsInYear.filter { it.category == "aktien" }) aktienPnl += e.realizedPnlEur
                var sonstigePnl = 0.0
                for (e in sellsInYear.filter { it.category == "sonstige" }) {
                    sonstigePnl += e.realizedPnlEur
                }
                var dividends = 0.0
                for (d in s.dividends.filter { viennaYearOf(it.executedAt) == y.year }) {
                    dividends += d.grossEur
                }

                assertCentsEqual(y.aktienSalePnlEur, aktienPnl, "${s.id}/${y.year} aktien pnl")
                assertCentsEqual(y.sonstigeSalePnlEur, sonstigePnl, "${s.id}/${y.year} sonstige pnl")
                assertCentsEqual(y.dividendsEur, dividends, "${s.id}/${y.year} dividends")
            }
        }
    }

    @Test
    fun `follows the researched year-target formula -- pots, cross-offset, allowance, floors`() {
        for (s in FIXTURES) {
            for (y in s.expectedYears) {
                val ref = recomputeYearEnd(y)
                val label = "${s.id}/${y.year}"
                assertCentsEqual(
                    y.taxableBeforeAllowanceEur,
                    ref.taxableBeforeAllowance,
                    "$label taxableBefore",
                )
                assertCentsEqual(y.allowanceUsedEur, ref.allowanceUsed, "$label allowanceUsed")
                assertCentsEqual(y.taxableBaseEur, ref.taxableBase, "$label base")
                assertCentsEqual(y.kapestEur, ref.kapest, "$label kapest")
                assertCentsEqual(y.soliEur, ref.soli, "$label soli")
                assertCentsEqual(y.totalTaxEur, y.kapestEur + y.soliEur, "$label total")
                assertCentsEqual(y.aktienPotOutEur, ref.aktienPotOut, "$label aktien pot out")
                assertCentsEqual(y.sonstigePotOutEur, ref.sonstigePotOut, "$label sonstige pot out")

                // Allowance identities: per-year budget, never negative, no carry.
                assertTrue(label, y.allowanceUsedEur >= 0)
                assertCentsEqual(
                    y.allowanceUsedEur + y.allowanceRemainingEur,
                    DE_SPARER_PAUSCHBETRAG_EUR,
                    "$label allowance budget",
                )
                // Pots are stored positive.
                assertTrue(label, y.aktienPotInEur >= 0)
                assertTrue(label, y.sonstigePotInEur >= 0)
                assertTrue(label, y.aktienPotOutEur >= 0)
                assertTrue(label, y.sonstigePotOutEur >= 0)
            }
        }
    }

    @Test
    fun `chains pots across consecutive listed years`() {
        for (s in FIXTURES) {
            for (i in 1 until s.expectedYears.size) {
                val prev = s.expectedYears[i - 1]
                val next = s.expectedYears[i]
                assertEquals(s.id, prev.year + 1, next.year)
                assertCentsEqual(next.aktienPotInEur, prev.aktienPotOutEur, "${s.id} aktien carry")
                assertCentsEqual(
                    next.sonstigePotInEur,
                    prev.sonstigePotOutEur,
                    "${s.id} sonstige carry",
                )
            }
            assertEquals(s.id, 0.0, s.expectedYears[0].aktienPotInEur, 0.0)
            assertEquals(s.id, 0.0, s.expectedYears[0].sonstigePotInEur, 0.0)
        }
    }

    @Test
    fun `settlement steps cover the year events chronologically and chain to the target`() {
        for (s in FIXTURES) {
            val events = taxableEventsOf(s)
            for (y in s.expectedYears) {
                val eventsInYear = events.filter { it.third == y.year }
                assertEquals(
                    "${s.id}/${y.year}",
                    eventsInYear.map { it.first },
                    y.steps.map { it.eventId },
                )

                var held = 0.0
                for (step in y.steps) {
                    // Deltas are stored cent-quantized (they become movements).
                    assertEquals(
                        "${s.id}/${y.year}/${step.eventId} delta is cent-quantized",
                        step.deltaEur,
                        taxFloorCents(step.deltaEur),
                        0.0,
                    )
                    held = (centsOf(held) + centsOf(step.deltaEur)) / 100
                    assertCentsEqual(
                        step.heldAfterEur,
                        held,
                        "${s.id}/${y.year}/${step.eventId} held",
                    )
                    // Tax held is never negative — losses park, they never pre-refund.
                    assertTrue("${s.id}/${y.year}/${step.eventId}", step.heldAfterEur >= 0)
                }
                assertCentsEqual(held, y.totalTaxEur, "${s.id}/${y.year} final held")
            }
        }
    }

    // =======================================================================
    // Acceptance pins (issue #576)
    // =======================================================================

    @Test
    fun `the FIFO scenario provably differs from moving average in total`() {
        val s = byId("de-fifo-multi-lot")
        var fifoTotal = 0.0
        for (e in s.expectedSells) fifoTotal += e.realizedPnlEur
        var maTotal = 0.0
        for (e in s.expectedSells) maTotal += e.movingAveragePnlEur ?: 0.0
        assertTrue(s.expectedSells.all { it.movingAveragePnlEur != null })
        assertEquals(centsOf(8500.0), centsOf(fifoTotal), 0.0)
        assertEquals(centsOf(6000.0), centsOf(maTotal), 0.0)
    }

    @Test
    fun `the allowance scenario exhausts EUR 1,000 partially, then fully`() {
        val year = byId("de-allowance-exhaustion").expectedYears[0]
        assertEquals(
            listOf(0.0, 7912.0, 10550.0),
            year.steps.map { centsOf(it.deltaEur) },
        )
        assertEquals(0.0, year.allowanceRemainingEur, 0.0)
    }

    @Test
    fun `the ring-fence scenario taxes the dividend while the Aktien loss carries out`() {
        val year = byId("de-aktien-loss-ringfenced").expectedYears[0]
        assertTrue(year.taxableBaseEur > 0)
        assertEquals(1500.0, year.aktienPotOutEur, 0.0)
    }

    @Test
    fun `a refund-of-already-withheld step exists and stays within what was withheld`() {
        val year = byId("de-intra-year-refund").expectedYears[0]
        val refund = year.steps.firstOrNull { it.deltaEur < 0 }
        assertNotNull(refund)
        assertEquals(-19782.0, centsOf(refund!!.deltaEur), 0.0)
        // The cross-offset scenario also refunds mid-year (§43a Abs. 3 Satz 2).
        val crossYear = byId("de-sonstige-loss-cross-offset").expectedYears[0]
        assertTrue(crossYear.steps.any { it.deltaEur < 0 })
    }

    @Test
    fun `pots carry across the year boundary while the allowance resets`() {
        val years = byId("de-year-boundary-carry").expectedYears
        assertEquals(DE_SPARER_PAUSCHBETRAG_EUR, years[0].allowanceRemainingEur, 0.0)
        assertEquals(800.0, years[1].aktienPotInEur, 0.0)
        assertEquals(300.0, years[1].sonstigePotInEur, 0.0)
        assertEquals(DE_SPARER_PAUSCHBETRAG_EUR, years[1].allowanceUsedEur, 0.0)
    }

    @Test
    fun `Soli is 5,5 percent of the floored KapESt, floored -- never rounded up`() {
        val year = byId("de-rounding-truncation").expectedYears[0]
        // 0.25 · 1,344.42 = 336.105 → 336.10 (not 336.11); 0.055 · 336.10 =
        // 18.4855 → 18.48 (not 18.49; §4 Satz 2 SolzG).
        assertEquals(336.1, year.kapestEur, 0.0)
        assertEquals(18.48, year.soliEur, 0.0)
        // The ported quantizer must land on exactly those two numbers.
        assertEquals(336.1, taxFloorCents(DE_KAPEST_RATE * 1344.42), 0.0)
        assertEquals(18.48, taxFloorCents(DE_SOLI_RATE * 336.1), 0.0)
    }

    // =======================================================================
    // The one engine case of deTaxEngine.test.ts that needs the fixture data
    // =======================================================================

    /**
     * `deTaxEngine.test.ts` → "the moving-average strategy reproduces the stated
     * divergent P/L — never the FIFO one". Both engine outputs travel as vectors;
     * the load-bearing part is an INEQUALITY against the fixture literals, which
     * only this suite can see.
     */
    @Test
    fun `the moving-average strategy reproduces the stated divergent P-L, never the FIFO one`() {
        for (s in FIXTURES) {
            val divergent = s.expectedSells.filter { it.movingAveragePnlEur != null }
            if (divergent.isEmpty()) continue
            val txs = s.transactions.map {
                TaxableTransaction(
                    id = it.id,
                    assetId = it.assetId,
                    side = it.side,
                    quantity = it.quantity,
                    priceEur = it.priceEur,
                    feeEur = it.feeEur,
                    executedAt = it.executedAt,
                )
            }
            val averaged = realizedSellsEur(txs, "moving-average").associateBy { it.id }
            for (expected in divergent) {
                val actual = averaged.getValue(expected.id)
                assertCentsEqual(
                    actual.realizedPnlEur,
                    expected.movingAveragePnlEur!!,
                    "${s.id}/${expected.id} avg pnl",
                )
                assertTrue(
                    "${s.id}/${expected.id}: FIFO and moving average must differ",
                    centsOf(actual.realizedPnlEur) != centsOf(expected.realizedPnlEur),
                )
            }
        }
    }
}
