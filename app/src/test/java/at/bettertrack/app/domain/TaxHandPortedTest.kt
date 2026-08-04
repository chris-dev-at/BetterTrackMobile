package at.bettertrack.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `tax` vitest cases the vector generator deliberately skipped, hand-ported
 * (plan §3.4 step 4). Each is listed in
 * `app/src/test/resources/domain-vectors/MANIFEST.json` with its reason.
 *
 * Three kinds live here, matching [CashLedgerHandPortedTest]'s taxonomy:
 *
 *  1. **Non-finite inputs** — `Number.NaN` / `±Infinity`, which JSON cannot carry,
 *     so they cannot travel in a vector file.
 *  2. **Signed zero** — `Object.is(x, 0)` distinguishes `+0` from `−0`, which JSON
 *     does not (the generator refuses to emit `−0` at all).
 *  3. **Relations between two calls** — a vector records ONE function's output; it
 *     cannot assert that two functions agree. Both sides travel as vectors, and
 *     the relation is asserted here: `tax.floorCents` ≡ `cashLedger.floorCents`,
 *     the default cost-basis strategy ≡ `'moving-average'`, and
 *     `settleCustomYear(AT_AS_CUSTOM_PARAMS) ≡ settleAtYear` (the issue's required
 *     expressibility proof).
 */
class TaxHandPortedTest {

    // =======================================================================
    // 1. floorCents — the tax module's OWN quantizer (plan §3.3 rule 3)
    // =======================================================================

    /**
     * `tax.test.ts` → "floorCents (local mirror) / matches cashLedger.floorCents on
     * the boundary cases exactly". The whole point of the case is that two
     * separately-declared functions agree; only a two-call assertion can say that.
     */
    @Test
    fun `tax floorCents matches the cashLedger floorCents on every boundary case`() {
        val cases = listOf(
            0.0,
            0.005,
            -0.005,
            1.005,
            -1.005,
            2.675,
            100.004999,
            100.006,
            8.61,
            0.1 + 0.2,
            123.456,
            -76.545,
        )
        for (value in cases) {
            assertEquals(
                "floorCents($value)",
                floorCents(value),
                taxFloorCents(value),
                0.0,
            )
        }
    }

    /**
     * `tax.test.ts` → "rejects non-finite amounts". The two ports must ALSO differ
     * in exactly one way: the error class. `cashLedger.floorCents` raises
     * `CashLedgerError`, `tax.floorCents` raises [TaxComputationError] — which is
     * why plan §3.3 rule 3 insists they be ported separately.
     */
    @Test
    fun `floorCents rejects non-finite amounts`() {
        for ((value, rendered) in listOf(
            Double.NaN to "NaN",
            Double.POSITIVE_INFINITY to "Infinity",
            Double.NEGATIVE_INFINITY to "-Infinity",
        )) {
            val e = assertThrows(TaxComputationError::class.java) { taxFloorCents(value) }
            assertEquals("Cannot floor a non-finite EUR amount, got $rendered.", e.message)
        }
        // The class distinction is contract, not incidental.
        assertThrows(CashLedgerError::class.java) { floorCents(Double.NaN) }
    }

    /**
     * `cashLedger`'s suite pins `Object.is(floorCents(-0.005), 0)`; the tax copy
     * inherits the guarantee and nothing in the vector file can express it (JSON
     * has no `-0`).
     */
    @Test
    fun `floorCents never returns negative zero`() {
        for (value in listOf(-0.0, -0.004, -0.005, -0.009999)) {
            val result = taxFloorCents(value)
            assertEquals(0.0, result, 0.0)
            assertEquals(
                "floorCents($value) must be POSITIVE zero",
                0L,
                result.toRawBits(),
            )
        }
    }

    /**
     * Plan §3.3 rule 7: `tax.ts` re-declares `QTY_EPSILON` locally rather than
     * importing `holdings`'. The two are separate declarations by design, and the
     * TypeScript comment claims they mirror each other — pin that claim.
     */
    @Test
    fun `the tax quantity epsilon mirrors the holdings one`() {
        assertEquals(QTY_EPSILON, TAX_QTY_EPSILON, 0.0)
        assertEquals(1e-9, TAX_QTY_EPSILON, 0.0)
        assertEquals(1e-8, QTY_STORAGE_QUANTUM, 0.0)
    }

    // =======================================================================
    // 2. Non-finite inputs elsewhere in the module
    // =======================================================================

    @Test
    fun `year targets reject a non-finite pool`() {
        for ((value, rendered) in listOf(
            Double.NaN to "NaN",
            Double.POSITIVE_INFINITY to "Infinity",
        )) {
            assertEquals(
                "Year pool must be a finite EUR amount, got $rendered.",
                assertThrows(TaxComputationError::class.java) { atYearTargetEur(value) }.message,
            )
            assertEquals(
                "Year pool must be a finite EUR amount, got $rendered.",
                assertThrows(TaxComputationError::class.java) { fiYearTargetEur(value) }.message,
            )
        }
    }

    @Test
    fun `settleAtYear rejects non-finite input`() {
        val base = AtYearSettlementInput(
            existingGainsEur = emptyList(),
            existingDividendsEur = emptyList(),
            heldEur = 0.0,
            newEvents = emptyList(),
        )
        assertEquals(
            "New event amount must be a finite EUR amount, got NaN.",
            assertThrows(TaxComputationError::class.java) {
                settleAtYear(base.copy(newEvents = listOf(NewAtEvent("sell_gain", Double.NaN))))
            }.message,
        )
        assertEquals(
            "heldEur must be a finite EUR amount, got Infinity.",
            assertThrows(TaxComputationError::class.java) {
                settleAtYear(base.copy(heldEur = Double.POSITIVE_INFINITY))
            }.message,
        )
        assertEquals(
            "Existing realized gain must be a finite EUR amount, got NaN.",
            assertThrows(TaxComputationError::class.java) {
                settleAtYear(base.copy(existingGainsEur = listOf(Double.NaN)))
            }.message,
        )
        assertEquals(
            "Existing dividend must be a finite EUR amount, got NaN.",
            assertThrows(TaxComputationError::class.java) {
                settleAtYear(base.copy(existingDividendsEur = listOf(Double.NaN)))
            }.message,
        )
        // The FI settlement shares settlePoolYear, so it must reject identically.
        assertEquals(
            "heldEur must be a finite EUR amount, got Infinity.",
            assertThrows(TaxComputationError::class.java) {
                settleFiYear(base.copy(heldEur = Double.POSITIVE_INFINITY))
            }.message,
        )
    }

    @Test
    fun `taxMovementForDelta rejects a non-finite delta`() {
        assertEquals(
            "Settlement delta must be a finite EUR amount, got NaN.",
            assertThrows(TaxComputationError::class.java) {
                taxMovementForDelta(Double.NaN)
            }.message,
        )
        // A negative zero posts nothing, exactly as `deltaEur === 0` does in JS.
        assertNull(taxMovementForDelta(-0.0))
    }

    @Test
    fun `manualTaxEur rejects a non-finite base`() {
        assertEquals(
            "Manual tax base must be a finite EUR amount, got NaN.",
            assertThrows(TaxComputationError::class.java) {
                manualTaxEur(ManualTaxInput(baseEur = Double.NaN, taxRatePct = 10.0))
            }.message,
        )
        assertEquals(
            "Manual tax amount must be a finite non-negative EUR amount, got Infinity.",
            assertThrows(TaxComputationError::class.java) {
                manualTaxEur(
                    ManualTaxInput(baseEur = 100.0, taxAmountEur = Double.POSITIVE_INFINITY),
                )
            }.message,
        )
        assertEquals(
            "Manual tax rate must be between 0 and 100, got NaN.",
            assertThrows(TaxComputationError::class.java) {
                manualTaxEur(ManualTaxInput(baseEur = 100.0, taxRatePct = Double.NaN))
            }.message,
        )
    }

    @Test
    fun `deYearOutcome rejects non-finite aggregates`() {
        val base = DeYearAggregates(0.0, 0.0, 0.0, 0.0, 0.0)
        assertEquals(
            "Sonstige pot in must be a finite non-negative EUR amount, got NaN.",
            assertThrows(TaxComputationError::class.java) {
                deYearOutcome(base.copy(sonstigePotInEur = Double.NaN))
            }.message,
        )
        assertEquals(
            "Aktien sale P/L must be a finite EUR amount, got NaN.",
            assertThrows(TaxComputationError::class.java) {
                deYearOutcome(base.copy(aktienSalePnlEur = Double.NaN))
            }.message,
        )
        assertEquals(
            "Dividends sum must be a finite EUR amount, got Infinity.",
            assertThrows(TaxComputationError::class.java) {
                deYearOutcome(base.copy(dividendsEur = Double.POSITIVE_INFINITY))
            }.message,
        )
        assertEquals(
            "heldEur must be a finite EUR amount, got NaN.",
            assertThrows(TaxComputationError::class.java) {
                settleDeYear(
                    DeYearSettlementInput(0.0, 0.0, emptyList(), Double.NaN, emptyList()),
                )
            }.message,
        )
    }

    @Test
    fun `settleCustomYear rejects non-finite input`() {
        val base = CustomYearSettlementInput(
            params = AT_AS_CUSTOM_PARAMS,
            carry = initialCustomCarry(),
            existingEvents = emptyList(),
            heldEur = 0.0,
            newEvents = emptyList(),
        )
        assertEquals(
            "Custom tax rate must be between 0 and 100, got NaN.",
            assertThrows(TaxComputationError::class.java) {
                settleCustomYear(base.copy(params = AT_AS_CUSTOM_PARAMS.copy(ratePct = Double.NaN)))
            }.message,
        )
        assertEquals(
            "heldEur must be a finite EUR amount, got Infinity.",
            assertThrows(TaxComputationError::class.java) {
                settleCustomYear(base.copy(heldEur = Double.POSITIVE_INFINITY))
            }.message,
        )
        assertEquals(
            "Cumulative pool must be a finite EUR amount, got NaN.",
            assertThrows(TaxComputationError::class.java) {
                settleCustomYear(
                    base.copy(carry = CustomCarry(0.0, Double.NaN, 0.0)),
                )
            }.message,
        )
        assertEquals(
            "Custom event amount must be a finite EUR amount, got NaN.",
            assertThrows(TaxComputationError::class.java) {
                settleCustomYear(
                    base.copy(newEvents = listOf(CustomTaxableEvent("sell_gain", Double.NaN))),
                )
            }.message,
        )
    }

    @Test
    fun `viennaYearOf rejects an unparseable timestamp`() {
        for (bad in listOf("not-a-date", "", "2026-13-45T00:00:00Z", "yesterday")) {
            val e = assertThrows(TaxComputationError::class.java) { viennaYearOf(bad) }
            assertEquals("Timestamp must be ISO-8601 date/time, got $bad", e.message)
        }
    }

    // =======================================================================
    // 3. Relations between two calls
    // =======================================================================

    /**
     * `deTaxEngine.test.ts` → "defaults to the moving average: omitting the strategy
     * is the pre-V5-P4 replay". Both replays travel as vectors; the equality
     * *between* them is what this case is about.
     */
    @Test
    fun `the default cost-basis strategy is the moving-average replay`() {
        val log = listOf(
            TaxableTransaction("b1", "asset-1", "buy", 100.0, 100.0, 0.0, "2024-01-10T12:00:00.000Z"),
            TaxableTransaction("b2", "asset-1", "buy", 100.0, 200.0, 0.0, "2024-03-15T12:00:00.000Z"),
            TaxableTransaction("s1", "asset-1", "sell", 100.0, 180.0, 0.0, "2024-06-20T12:00:00.000Z"),
            TaxableTransaction("s2", "asset-1", "sell", 50.0, 210.0, 0.0, "2024-11-05T12:00:00.000Z"),
        )
        assertEquals(realizedSellsEur(log, "moving-average"), realizedSellsEur(log))
        // And the average genuinely differs from FIFO on this log (#576 S2).
        assertEquals(3000.0, realizedSellsEur(log)[0].realizedPnlEur, 0.0)
        assertEquals(8000.0, realizedSellsEur(log, "fifo")[0].realizedPnlEur, 0.0)
        // An unknown strategy string is NOT validated by realizedSellsEur — it
        // falls through to the moving average, exactly as the TypeScript's
        // `strategy === 'fifo' ? … : …` does.
        assertEquals(realizedSellsEur(log, "moving-average"), realizedSellsEur(log, "lifo"))
    }

    /**
     * `customTax.test.ts` → "custom-as-AT parity (the required expressibility
     * test)": a custom parameter set configured like Austria MUST reproduce the AT
     * engine, fixture for fixture. Both engines' outputs travel as vectors; this is
     * the equality between them, over all ten AT inputs of the suite.
     */
    @Test
    fun `AT_AS_CUSTOM_PARAMS reproduces settleAtYear output for output`() {
        assertEquals(
            CustomTaxParams(27.5, true, true, true, false, "moving-average"),
            AT_AS_CUSTOM_PARAMS,
        )
        for (input in AT_PARITY_INPUTS) {
            val at = settleAtYear(input)
            val custom = settleCustomYear(
                CustomYearSettlementInput(
                    params = AT_AS_CUSTOM_PARAMS,
                    carry = initialCustomCarry(),
                    existingEvents =
                        input.existingGainsEur.map { CustomTaxableEvent("sell_gain", it) } +
                            input.existingDividendsEur.map { CustomTaxableEvent("dividend", it) },
                    heldEur = input.heldEur,
                    newEvents = input.newEvents.map { CustomTaxableEvent(it.kind, it.amountEur) },
                ),
            )
            assertEquals(at.correctionDeltaEur, custom.correctionDeltaEur, 0.0)
            assertEquals(at.newEventDeltasEur, custom.newEventDeltasEur)
            assertEquals(at.heldAfterEur, custom.heldAfterEur, 0.0)
        }
    }

    /**
     * `customTax.test.ts` → "hard Jan-1 reset with carry off: a fresh year starts
     * from a clean carry" — the carry-out identity is a comparison against another
     * call's result ([initialCustomCarry]), not a literal.
     */
    @Test
    fun `an AT-shaped net-loss year hands on the empty carry`() {
        val y1 = customYearOutcome(
            AT_AS_CUSTOM_PARAMS,
            initialCustomCarry(),
            listOf(CustomTaxableEvent("sell_gain", -400.0)),
        )
        assertEquals(0.0, y1.targetEur, 0.0)
        assertEquals(initialCustomCarry(), y1.carryOut)
        assertEquals(
            listOf(55.0),
            settleCustomYear(
                CustomYearSettlementInput(
                    params = AT_AS_CUSTOM_PARAMS,
                    carry = y1.carryOut,
                    existingEvents = emptyList(),
                    heldEur = 0.0,
                    newEvents = listOf(CustomTaxableEvent("sell_gain", 200.0)),
                ),
            ).newEventDeltasEur,
        )
    }

    /**
     * Plan §3.3 rule 4, written down as a test: the year buckets of this module are
     * ORDERED LISTS supplied by the caller, never year-keyed objects — so the
     * traversal that feeds the pot accumulation is the list order, and reordering
     * the list is observable. (`tax.ts` keys nothing by year; its only `Map` is
     * `positions`, keyed by assetId and never iterated.)
     */
    @Test
    fun `the cross-year pot chain follows list order, not a numeric key order`() {
        val lossYear = listOf(
            DeTaxableEvent("sell_gain", -800.0, "aktien"),
            DeTaxableEvent("sell_gain", -300.0, "sonstige"),
        )
        val gainYear = listOf(DeTaxableEvent("sell_gain", 500.0, "aktien"))
        // Loss then gain: the gain consumes 500 of the Aktien pot.
        assertEquals(DePots(300.0, 300.0), deCarryPots(listOf(lossYear, gainYear)))
        // Gain then loss: the gain is taxed against an empty pot, so the whole
        // loss carries — a different answer from the same two years.
        assertEquals(DePots(800.0, 300.0), deCarryPots(listOf(gainYear, lossYear)))
        assertEquals(DePots(0.0, 0.0), deCarryPots(emptyList()))
    }

    /**
     * The same order-sensitivity for the custom engine's carry chain, and the
     * documented "an event-less year passes the pot through unchanged" rule.
     */
    @Test
    fun `the custom carry chain follows list order`() {
        val p = AT_AS_CUSTOM_PARAMS.copy(carryForward = true)
        val loss = listOf(CustomTaxableEvent("sell_gain", -300.0))
        val gain = listOf(CustomTaxableEvent("sell_gain", 100.0))
        assertEquals(200.0, customCarryForYears(p, listOf(loss, gain)).potEur, 0.0)
        assertEquals(300.0, customCarryForYears(p, listOf(gain, loss)).potEur, 0.0)
        assertEquals(
            customCarryForYears(p, listOf(loss)),
            customCarryForYears(p, listOf(loss, emptyList())),
        )
    }

    /**
     * Plan §3.3 rule 5, written down: the replay orders by epoch-ms, never by the
     * ISO string. `'.' < 'Z'` in a lexicographic compare, so a string sort would
     * replay `…T10:00:00.500Z` BEFORE `…T10:00:00Z` and price the sell against an
     * empty position.
     */
    @Test
    fun `the replay orders by epoch-ms, not lexicographically`() {
        val log = listOf(
            TaxableTransaction("b1", "asset-1", "buy", 1.0, 100.0, 0.0, "2026-01-01T10:00:00Z"),
            TaxableTransaction("s1", "asset-1", "sell", 1.0, 150.0, 0.0, "2026-01-01T10:00:00.500Z"),
        )
        assertTrue("a string sort would have thrown an oversell here", log[1].executedAt < log[0].executedAt)
        val realizations = realizedSellsEur(log)
        assertEquals(1, realizations.size)
        assertEquals(50.0, realizations[0].realizedPnlEur, 0.0)
    }

    companion object {
        /** The ten AT settlement inputs `customTax.test.ts` proves parity over. */
        internal val AT_PARITY_INPUTS: List<AtYearSettlementInput> = listOf(
            AtYearSettlementInput(
                emptyList(), emptyList(), 0.0, listOf(NewAtEvent("sell_gain", 450.0)),
            ),
            AtYearSettlementInput(
                listOf(450.0), emptyList(), 123.75, listOf(NewAtEvent("sell_gain", -100.0)),
            ),
            AtYearSettlementInput(
                emptyList(), emptyList(), 0.0, listOf(NewAtEvent("sell_gain", -100.0)),
            ),
            AtYearSettlementInput(
                listOf(-100.0), emptyList(), 0.0, listOf(NewAtEvent("sell_gain", 450.0)),
            ),
            AtYearSettlementInput(
                listOf(100.0), emptyList(), 27.5, listOf(NewAtEvent("sell_gain", -500.0)),
            ),
            AtYearSettlementInput(
                emptyList(), emptyList(), 0.0, listOf(NewAtEvent("dividend", 100.0)),
            ),
            AtYearSettlementInput(
                listOf(-100.0), emptyList(), 0.0, listOf(NewAtEvent("dividend", 60.0)),
            ),
            AtYearSettlementInput(
                emptyList(),
                emptyList(),
                0.0,
                listOf(NewAtEvent("sell_gain", 450.0), NewAtEvent("sell_gain", -100.0)),
            ),
            AtYearSettlementInput(listOf(300.0), emptyList(), 123.75, emptyList()),
            AtYearSettlementInput(
                emptyList(), emptyList(), 0.0, listOf(NewAtEvent("sell_gain", 33.33)),
            ),
        )
    }
}
