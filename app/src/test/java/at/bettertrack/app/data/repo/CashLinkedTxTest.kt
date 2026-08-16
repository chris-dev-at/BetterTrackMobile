package at.bettertrack.app.data.repo

import at.bettertrack.app.data.db.CashMovementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cash-linked edit's decision layer (owner order 2026-08-16: *"i want this
 * to just work"*).
 *
 * These are the two judgements that decide whether a user's correction lands or
 * dead-ends, and both are invisible at runtime until they are wrong:
 *
 *  · [cashLinkOf] reconstructs which wallet a trade was coupled to. Get the
 *    wallet wrong and the re-booked trade quietly moves someone's money to a
 *    different account — a silent, plausible-looking corruption.
 *  · [txEditRoute] decides PATCH vs re-book. Route a cash-linked economic edit
 *    to PATCH and the user gets the dead end this work exists to remove; route
 *    a note edit to the re-book and the app destroys and recreates a ledger row
 *    (changing its id) to fix a typo.
 *
 * Both are pure, so both are checked here rather than on a device.
 */
class CashLinkedTxTest {

    private fun movement(
        id: String,
        kind: String,
        transactionId: String?,
        sourceId: String = "wallet-main",
    ) = CashMovementEntity(
        id = id,
        portfolioId = "p1",
        sourceId = sourceId,
        kind = kind,
        amountEur = 100.0,
        transactionId = transactionId,
        transferId = null,
        counterpartSourceId = null,
        executedAt = "2026-08-16T10:00:00Z",
        executedAtMs = 1_755_338_400_000L,
        note = null,
        createdAt = "2026-08-16T10:00:00Z",
    )

    // ── Reconstructing the coupling ─────────────────────────────────────────

    @Test
    fun `a buy leg means the trade was paid from that wallet`() {
        val link = cashLinkOf("tx1", listOf(movement("m1", "buy", "tx1", sourceId = "wallet-savings")))
        assertTrue(link.payFromCash)
        assertFalse(link.addProceedsToCash)
        assertEquals("wallet-savings", link.cashSourceId)
        assertTrue(link.linked)
    }

    @Test
    fun `a sell_proceeds leg means the proceeds landed in that wallet`() {
        val link = cashLinkOf("tx1", listOf(movement("m1", "sell_proceeds", "tx1")))
        assertFalse(link.payFromCash)
        assertTrue(link.addProceedsToCash)
        assertEquals("wallet-main", link.cashSourceId)
    }

    @Test
    fun `a transaction with no movements is not linked`() {
        val link = cashLinkOf("tx1", emptyList())
        assertFalse(link.linked)
        assertNull(link.cashSourceId)
    }

    @Test
    fun `movements of OTHER transactions never leak into the link`() {
        // The DAO query is by transactionId, but callers may hand over a whole
        // portfolio's movements — the filter has to hold either way, or one
        // trade's wallet would be read off another trade's leg.
        val link = cashLinkOf(
            "tx1",
            listOf(movement("m1", "buy", "tx2", sourceId = "wallet-other")),
        )
        assertFalse(link.linked)
        assertNull(link.cashSourceId)
    }

    @Test
    fun `standalone cash movements are not a link`() {
        // A deposit carries no transactionId at all; a withdrawal that somehow
        // pointed at one still is not a TRADE leg and must not be read as the
        // trade's funding.
        val link = cashLinkOf(
            "tx1",
            listOf(
                movement("m1", "deposit", null),
                movement("m2", "withdrawal", "tx1"),
            ),
        )
        assertFalse(link.linked)
    }

    @Test
    fun `the wallet comes from the trade leg, not from an unrelated movement listed first`() {
        val link = cashLinkOf(
            "tx1",
            listOf(
                movement("m0", "deposit", null, sourceId = "wallet-decoy"),
                movement("m1", "buy", "tx1", sourceId = "wallet-savings"),
            ),
        )
        assertEquals("wallet-savings", link.cashSourceId)
    }

    // ── Which delivery an edit gets ─────────────────────────────────────────

    private val linked = CashLink(payFromCash = true, addProceedsToCash = false, cashSourceId = "w1")
    private val unlinked = CashLink(payFromCash = false, addProceedsToCash = false, cashSourceId = null)

    @Test
    fun `an economic edit of a cash-linked trade is re-booked`() {
        assertEquals(
            TxEditRoute.REBOOK,
            txEditRoute(financial = true, noteChanged = false, link = linked),
        )
    }

    @Test
    fun `an economic edit of an unlinked trade stays a plain patch`() {
        assertEquals(
            TxEditRoute.PATCH,
            txEditRoute(financial = true, noteChanged = false, link = unlinked),
        )
    }

    @Test
    fun `a note-only edit of a cash-linked trade stays a plain patch`() {
        // The server's guard does not fire on the note, and re-booking would
        // change the transaction's id to fix a typo.
        assertEquals(
            TxEditRoute.PATCH,
            txEditRoute(financial = false, noteChanged = true, link = linked),
        )
    }

    @Test
    fun `an edit that changed nothing sends nothing`() {
        assertEquals(
            TxEditRoute.NOTHING,
            txEditRoute(financial = false, noteChanged = false, link = linked),
        )
    }

    @Test
    fun `an economic edit that ALSO touches the note is still re-booked`() {
        assertEquals(
            TxEditRoute.REBOOK,
            txEditRoute(financial = true, noteChanged = true, link = linked),
        )
    }

    // ── What counts as economic ─────────────────────────────────────────────

    @Test
    fun `each of the five guarded fields makes an edit financial`() {
        val none = isFinancialEdit(false, false, false, false, false)
        assertFalse(none)
        assertTrue(isFinancialEdit(sideChanged = true, false, false, false, false))
        assertTrue(isFinancialEdit(false, quantityChanged = true, false, false, false))
        assertTrue(isFinancialEdit(false, false, priceChanged = true, false, false))
        assertTrue(isFinancialEdit(false, false, false, feeChanged = true, false))
        assertTrue(isFinancialEdit(false, false, false, false, dateChanged = true))
    }
}
