package at.bettertrack.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `cashLedger` vitest cases the vector generator deliberately skipped,
 * hand-ported (plan §3.4 step 4). Each is listed in
 * `app/src/test/resources/domain-vectors/MANIFEST.json` with its reason.
 *
 * Three kinds live here, matching `DomainHandPortedTest`'s taxonomy:
 *
 *  1. **Non-finite inputs** — `Number.NaN` / `±Number.POSITIVE_INFINITY`, which
 *     JSON cannot carry, so they cannot travel in a vector file.
 *  2. **Error detail and identity** — assertions on an error's *fields*, on the
 *     *reference* it carries, and on which class it is NOT.
 *  3. **Signed zero and non-mutation** — `Object.is(x, 0)` distinguishes `+0`
 *     from `−0`, which JSON does not (the generator refuses to emit `−0` at all);
 *     "does not mutate the input" is a property of the call, not of its output.
 */
class CashLedgerHandPortedTest {

    private fun mv(kind: String, amountEur: Double, occurredAt: String) =
        CashMovement(kind, amountEur, occurredAt)

    private fun smv(sourceId: String, kind: String, amountEur: Double, occurredAt: String) =
        SourcedCashMovement(kind, amountEur, occurredAt, sourceId)

    // =======================================================================
    // 1. Non-finite inputs (JSON cannot carry NaN / ±Infinity)
    // =======================================================================

    @Test
    fun `floorCents rejects a non-finite amount`() {
        val inf = assertThrows(CashLedgerError::class.java) {
            floorCents(Double.POSITIVE_INFINITY)
        }
        assertEquals("Cannot floor a non-finite EUR amount, got Infinity.", inf.message)

        val nan = assertThrows(CashLedgerError::class.java) { floorCents(Double.NaN) }
        assertEquals("Cannot floor a non-finite EUR amount, got NaN.", nan.message)
    }

    @Test
    fun `cashBalance rejects non-finite amounts`() {
        val expected = mapOf(
            Double.NaN to "NaN",
            Double.POSITIVE_INFINITY to "Infinity",
            Double.NEGATIVE_INFINITY to "-Infinity",
        )
        for ((amount, rendered) in expected) {
            val e = assertThrows(CashLedgerError::class.java) {
                cashBalance(listOf(mv("deposit", amount, "2026-01-05")))
            }
            assertEquals(
                "Movement amountEur must be a finite non-zero number, got $rendered (movement 0).",
                e.message,
            )
        }
    }

    @Test
    fun `applyCashMovement rejects a non-finite starting balance`() {
        for ((balance, rendered) in
            listOf(Double.NaN to "NaN", Double.POSITIVE_INFINITY to "Infinity")
        ) {
            val e = assertThrows(CashLedgerError::class.java) {
                applyCashMovement(balance, mv("deposit", 10.0, "2026-01-05"))
            }
            assertEquals(
                "Starting balance must be a finite non-negative number of EUR, got $rendered.",
                e.message,
            )
        }
    }

    @Test
    fun `netWorthSeries rejects a non-finite holdings value`() {
        val e = assertThrows(CashLedgerError::class.java) {
            netWorthSeries(
                NetWorthSeriesInput(
                    holdingsValues = listOf(ValuePoint("2026-01-05", Double.NaN)),
                    movements = emptyList(),
                    today = "2026-01-05",
                ),
            )
        }
        assertEquals(
            "Holdings value on 2026-01-05 must be a finite number, got NaN",
            e.message,
        )
    }

    @Test
    fun `pairedTransferMovements rejects a non-finite amount`() {
        val e = assertThrows(CashLedgerError::class.java) {
            pairedTransferMovements(
                CashTransferInput(
                    fromSourceId = "a",
                    toSourceId = "b",
                    amountEur = Double.NaN,
                    occurredAt = "2026-02-01T12:00:00Z",
                ),
            )
        }
        assertEquals("Transfer amountEur must be a strictly positive number, got NaN.", e.message)
    }

    @Test
    fun `setBalanceDelta rejects non-finite operands`() {
        val target = assertThrows(CashLedgerError::class.java) {
            setBalanceDelta(100.0, Double.POSITIVE_INFINITY)
        }
        assertEquals(
            "Set-balance target must be a finite non-negative number of EUR, got Infinity.",
            target.message,
        )

        val current = assertThrows(CashLedgerError::class.java) {
            setBalanceDelta(Double.NaN, 100.0)
        }
        assertEquals(
            "Set-balance current balance must be a finite number of EUR, got NaN.",
            current.message,
        )
    }

    // =======================================================================
    // 2. Error detail and identity
    // =======================================================================

    @Test
    fun `InsufficientCashError carries the balance the movement and the exact shortfall`() {
        val movement = mv("buy", -150.0, "2026-01-05T09:00:00Z")
        val e = assertThrows(InsufficientCashError::class.java) {
            applyCashMovement(100.0, movement)
        }
        assertEquals(100.0, e.balanceEur, 0.0)
        // `expect(err.movement).toBe(movement)` — REFERENTIAL identity, not equality.
        assertSame(movement, e.movement)
        assertEquals(50.0, e.shortfallEur, 0.0)
        assertTrue(e.message!!.contains("150"))
        // The full audited message, including JS number rendering ("150", not "150.0").
        assertEquals(
            "Insufficient cash: buy of -150 € at 2026-01-05T09:00:00Z " +
                "exceeds the available balance of 100 € by 50 €.",
            e.message,
        )
    }

    @Test
    fun `InsufficientCashError is not a CashLedgerError`() {
        // A valid movement, just unaffordable — the service layer maps the two to
        // different responses, so they must stay separate classes.
        val e = assertThrows(InsufficientCashError::class.java) {
            applyCashMovement(0.0, mv("withdrawal", -1.0, "2026-01-05"))
        }
        assertFalse("InsufficientCashError must not be a CashLedgerError", e is CashLedgerError)
        assertTrue("both are still DomainExceptions", (e as Any) is DomainException)
    }

    @Test
    fun `projectCashLedgerBySource rejection keeps the source attribution`() {
        val e = assertThrows(InsufficientCashError::class.java) {
            projectCashLedgerBySource(
                listOf(
                    smv("main", "deposit", 10_000.0, "2026-01-05T09:00:00Z"),
                    smv("bank", "deposit", 100.0, "2026-01-05T10:00:00Z"),
                    // bank has only 100
                    smv("bank", "withdrawal", -150.0, "2026-01-06T10:00:00Z"),
                ),
            )
        }
        assertEquals(100.0, e.balanceEur, 0.0)
        assertEquals(50.0, e.shortfallEur, 0.0)
        assertEquals("bank", (e.movement as SourcedCashMovement).sourceId)
    }

    // =======================================================================
    // 3. Signed zero and non-mutation
    // =======================================================================

    @Test
    fun `floorCents never returns negative zero`() {
        // `expect(Object.is(floorCents(-0.005), 0)).toBe(true)` — a floored-away
        // outflow must be +0, because -0 would print as "-0,00 €" and would flip
        // the sign of anything that divides by it downstream. `assertEquals(…, 0.0)`
        // cannot see the difference (IEEE says -0.0 == 0.0), so check the bits.
        assertPositiveZero(floorCents(-0.005))

        // The withdraw-all residue: 100.006 reports as 100.00, and what is left
        // over floors to exactly +0 — which is what makes "withdraw everything"
        // land on a clean zero balance.
        val trueBalance = cashBalance(listOf(mv("deposit", 100.006, "2026-01-01T00:00:00Z")))
        val reported = floorCents(trueBalance)
        assertEquals(100.0, reported, 0.0)
        assertTrue(reported <= trueBalance + CASH_EPSILON)
        assertPositiveZero(floorCents(trueBalance - reported))
    }

    private fun assertPositiveZero(value: Double) {
        assertEquals(0.0, value, 0.0)
        assertTrue(
            "expected +0.0 but was -0.0",
            java.lang.Double.doubleToRawLongBits(value) == 0L,
        )
    }

    @Test
    fun `projectCashLedger does not mutate the input list`() {
        val movements = listOf(
            mv("buy", -500.0, "2026-01-06T10:00:00Z"),
            mv("deposit", 1000.0, "2026-01-05T10:00:00Z"),
        )
        val entries = projectCashLedger(movements)
        // The replay reorders internally (deposit first) …
        assertEquals(listOf("deposit", "buy"), entries.map { it.movement.kind })
        // … and the caller's list is untouched.
        assertEquals("buy", movements[0].kind)
        assertEquals("deposit", movements[1].kind)
        assertNotNull(entries)
    }
}
