package at.bettertrack.app.ui.cash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cash-kind table is the app's copy of a server enum, so the tests that
 * matter are (a) it covers every value the wire can send and (b) it never
 * crashes or offers a doomed action on a value it does not know.
 */
class CashKindTest {

    /**
     * The platform's `cash_movement_kind` Postgres enum, verbatim and in
     * declaration order, read off the running v5 backend. If the platform adds a
     * kind this test is the tripwire.
     */
    private val wireEnum = listOf(
        "deposit",
        "withdrawal",
        "buy",
        "sell_proceeds",
        "transfer_out",
        "transfer_in",
        "dividend",
        "tax_withholding",
        "tax_refund",
        "fee",
    )

    @Test
    fun `every wire kind maps to a modelled kind`() {
        for (wire in wireEnum) {
            assertEquals("unmapped wire kind: $wire", wire, CashKind.fromWire(wire)?.wire)
        }
    }

    @Test
    fun `model carries no kind the wire does not have`() {
        for (kind in CashKind.entries) {
            assertTrue("stale modelled kind: ${kind.wire}", kind.wire in wireEnum)
        }
    }

    @Test
    fun `only hand-typed kinds are editable`() {
        assertTrue(isEditableCashKind("deposit"))
        assertTrue(isEditableCashKind("withdrawal"))
        assertTrue(isEditableCashKind("fee"))
    }

    @Test
    fun `derived kinds are never offered for edit`() {
        // These 409 CASH_MOVEMENT_NOT_EDITABLE server-side — edit the parent instead.
        for (wire in listOf(
            "buy", "sell_proceeds", "transfer_out", "transfer_in",
            "dividend", "tax_withholding", "tax_refund",
        )) {
            assertFalse("derived kind offered for edit: $wire", isEditableCashKind(wire))
        }
    }

    @Test
    fun `unknown kind is tolerated and not editable`() {
        assertNull(CashKind.fromWire("interest_credit"))
        assertFalse(isEditableCashKind("interest_credit"))
        assertFalse(isEditableCashKind(""))
    }

    @Test
    fun `entry chooser offers exactly the hand-typed kinds`() {
        assertEquals(CashKind.entries.filter { it.handTyped }, CASH_ENTRY_KINDS)
    }
}
