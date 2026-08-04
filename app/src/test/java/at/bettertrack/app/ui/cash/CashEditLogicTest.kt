package at.bettertrack.app.ui.cash

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PATCH body is where this feature can go quietly wrong: the server schema
 * is `.strict()` and refuses both unknown keys and an empty body, and `note`
 * distinguishes "clear me" (JSON null) from "leave me" (key absent). So these
 * tests assert the exact key set, not just the happy path.
 */
class CashEditLogicTest {

    @Test
    fun `an untouched form produces no body at all`() {
        // An empty PATCH is a 400 — callers must be able to short-circuit.
        assertNull(buildCashMovementPatch(CashEditIntent()))
    }

    @Test
    fun `baseSeq alone is not a change`() {
        // The server's refine() explicitly requires a key other than baseSeq.
        assertNull(buildCashMovementPatch(CashEditIntent(baseSeq = 7)))
    }

    @Test
    fun `only the changed field is sent`() {
        val body = buildCashMovementPatch(CashEditIntent(amountEur = 25.0))
        assertEquals(setOf("amountEur"), body?.keys)
        assertEquals(JsonPrimitive(25.0), body?.get("amountEur"))
    }

    @Test
    fun `clearing a note sends an explicit json null`() {
        // `explicitNulls = false` would have dropped a Kotlin null, making
        // "clear this note" unexpressible — hence the dedicated flag.
        val body = buildCashMovementPatch(CashEditIntent(clearNote = true))
        assertEquals(setOf("note"), body?.keys)
        assertEquals(JsonNull, body?.get("note"))
    }

    @Test
    fun `clearNote wins over a stale note value`() {
        val body = buildCashMovementPatch(CashEditIntent(note = "leftover", clearNote = true))
        assertEquals(JsonNull, body?.get("note"))
    }

    @Test
    fun `a new note is sent as a string`() {
        val body = buildCashMovementPatch(CashEditIntent(note = "broker charge"))
        assertEquals(JsonPrimitive("broker charge"), body?.get("note"))
    }

    @Test
    fun `kind travels as its wire token`() {
        val body = buildCashMovementPatch(CashEditIntent(kind = CashKind.FEE))
        assertEquals(JsonPrimitive("fee"), body?.get("kind"))
    }

    @Test
    fun `baseSeq rides along once there is a real change`() {
        val body = buildCashMovementPatch(CashEditIntent(amountEur = 5.0, baseSeq = 12))
        assertEquals(setOf("amountEur", "baseSeq"), body?.keys)
        assertEquals(JsonPrimitive(12), body?.get("baseSeq"))
    }

    @Test
    fun `a full edit sends every touched key and nothing more`() {
        val body = buildCashMovementPatch(
            CashEditIntent(
                kind = CashKind.WITHDRAWAL,
                amountEur = 10.5,
                sourceId = "src-1",
                executedAt = "2026-08-01T12:00:00Z",
                note = "n",
            ),
        )
        assertEquals(
            setOf("kind", "amountEur", "sourceId", "executedAt", "note"),
            body?.keys,
        )
    }

    @Test
    fun `edit form only accepts hand-typed kinds`() {
        assertEquals(CashKind.FEE, editableKindOrNull("fee"))
        assertNull(editableKindOrNull("dividend"))
        assertNull(editableKindOrNull("tax_withholding"))
        assertNull(editableKindOrNull("nonsense"))
    }

    @Test
    fun `edit form works in positive magnitudes`() {
        // Ledger amounts are signed; the wire wants a magnitude and derives the
        // sign from the kind, so an outflow must not prefill as negative.
        assertEquals(42.0, editAmountMagnitude(-42.0), 0.0001)
        assertEquals(42.0, editAmountMagnitude(42.0), 0.0001)
    }

    @Test
    fun `derived rows never reach the patch builder via the ui`() {
        for (wire in listOf("buy", "sell_proceeds", "dividend", "transfer_in")) {
            assertFalse(isEditableCashKind(wire))
            assertNull(editableKindOrNull(wire))
        }
        assertTrue(isEditableCashKind("withdrawal"))
    }
}
