package at.bettertrack.app.widget

import at.bettertrack.app.data.db.CashMovementEntity
import at.bettertrack.app.data.db.CashSourceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Cash Wallet widget's pure half: which wallet a configured instance
 * resolves to, how a wallet that vanished is detected, and the display rules
 * for its movement rows.
 *
 * The wallet-resolution tests are the important ones. A balance widget that
 * silently shows a DIFFERENT wallet's money under the configured name is the
 * single worst thing this family could do, and it is exactly what a naive
 * `firstOrNull` fallback would produce.
 */
class BtCashWalletTest {

    private fun source(
        id: String,
        name: String,
        balance: Double = 100.0,
        main: Boolean = false,
        archived: String? = null,
    ) = CashSourceEntity(
        id = id,
        portfolioId = "pf-1",
        name = name,
        kind = "cash",
        isMain = main,
        balanceEur = balance,
        archivedAt = archived,
    )

    private fun movement(
        id: String,
        sourceId: String,
        amount: Double,
        at: Long,
        note: String? = null,
        kind: String = "withdrawal",
    ) = CashMovementEntity(
        id = id,
        portfolioId = "pf-1",
        sourceId = sourceId,
        kind = kind,
        amountEur = amount,
        transactionId = null,
        transferId = null,
        counterpartSourceId = null,
        executedAt = "2026-08-17T10:00:00Z",
        executedAtMs = at,
        note = note,
        createdAt = "2026-08-17T10:00:00Z",
    )

    // ── Resolution ───────────────────────────────────────────────────────────

    @Test
    fun `a configured wallet resolves to itself`() {
        val sources = listOf(source("a", "Alltagskasse", main = true), source("b", "Urlaub"))
        val config = BtWidgetCashConfig(sourceId = "b", sourceName = "Urlaub")
        assertEquals("b", btWidgetResolveCashSource(config, sources)?.id)
        assertFalse(btWidgetCashSourceMissing(config, sources, btWidgetResolveCashSource(config, sources)))
    }

    @Test
    fun `follow mode resolves to the primary source`() {
        val sources = listOf(source("a", "Urlaub"), source("b", "Alltagskasse", main = true))
        val resolved = btWidgetResolveCashSource(BtWidgetCashConfig(), sources)
        assertEquals("b", resolved?.id)
        // Follow mode is not "pinned", so it can never be reported as missing.
        assertFalse(btWidgetCashSourceMissing(BtWidgetCashConfig(), sources, resolved))
    }

    @Test
    fun `follow mode survives an account whose primary was archived`() {
        val sources = listOf(source("a", "Urlaub"))
        assertEquals("a", btWidgetResolveCashSource(BtWidgetCashConfig(), sources)?.id)
    }

    @Test
    fun `a recreated wallet is recovered by name`() {
        // Cash sources are server-owned and an id can rotate — deleted and
        // recreated for the same wallet. Matching the name keeps the widget
        // alive across that, exactly as btWidgetResolveBudget does for a tag.
        val config = BtWidgetCashConfig(sourceId = "old", sourceName = "Alltagskasse")
        val sources = listOf(source("new", "Alltagskasse", main = true))
        val resolved = btWidgetResolveCashSource(config, sources)
        assertEquals("new", resolved?.id)
        assertFalse(
            "a name match is a recovery, not a loss",
            btWidgetCashSourceMissing(config, sources, resolved),
        )
    }

    @Test
    fun `a pinned wallet that is gone is reported as missing, not swapped`() {
        // The failure this whole pair of functions exists to prevent: showing
        // "Urlaub: 4.310 €" when Urlaub is gone and 4.310 € is the housekeeping
        // account. Resolution still returns something (so the card has a
        // fallback), but `missing` is what the widget renders on.
        val config = BtWidgetCashConfig(sourceId = "gone", sourceName = "Urlaub")
        val sources = listOf(source("a", "Alltagskasse", balance = 4310.0, main = true))
        val resolved = btWidgetResolveCashSource(config, sources)
        assertEquals("a", resolved?.id)
        assertTrue(btWidgetCashSourceMissing(config, sources, resolved))
    }

    @Test
    fun `an archived wallet counts as gone`() {
        val config = BtWidgetCashConfig(sourceId = "a", sourceName = "Urlaub")
        val sources = listOf(
            source("a", "Urlaub", archived = "2026-08-01T00:00:00Z"),
            source("b", "Alltagskasse", main = true),
        )
        assertTrue(btWidgetCashSourceMissing(config, sources, btWidgetResolveCashSource(config, sources)))
    }

    @Test
    fun `no wallet at all resolves to nothing rather than to a zero balance`() {
        // "We don't know" is not "€0,00" — one is a state, the other is a claim.
        assertNull(btWidgetResolveCashSource(BtWidgetCashConfig(), emptyList()))
    }

    // ── Config codec and pin stash ───────────────────────────────────────────

    @Test
    fun `a pinned wallet round-trips through the stash payload`() {
        val config = BtWidgetCashConfig("s-1", "Alltagskasse", "pf-1", movements = false)
        assertEquals(config, btWidgetPinCash(btWidgetPinPayload(config)))
    }

    @Test
    fun `a stash with no wallet says nothing follow mode does not say better`() {
        assertNull(btWidgetPinCash(emptyMap()))
        assertNull(btWidgetPinCash(mapOf("sourceId" to "", "movements" to "1")))
    }

    // ── Movements ────────────────────────────────────────────────────────────

    @Test
    fun `movements are filtered to this wallet, newest first, and capped`() {
        val all = listOf(
            movement("1", "a", -10.0, at = 300),
            movement("2", "b", -20.0, at = 400),
            movement("3", "a", -30.0, at = 500),
            movement("4", "a", 40.0, at = 100),
            movement("5", "a", -50.0, at = 200),
        )
        val rows = btWidgetCashMovements(all, sourceId = "a")
        assertEquals(BT_WIDGET_CASH_MOVEMENTS_LIMIT, rows.size)
        assertEquals(listOf("3", "1", "5"), rows.map { it.id })
        assertTrue("another wallet's money must never appear", rows.none { it.sourceId == "b" })
    }

    @Test
    fun `a wallet with no movements yields an empty list, not someone else's`() {
        val all = listOf(movement("1", "b", -10.0, at = 100))
        assertTrue(btWidgetCashMovements(all, sourceId = "a").isEmpty())
    }

    // ── Tone ─────────────────────────────────────────────────────────────────

    @Test
    fun `direction comes from the sign the server sent`() {
        assertEquals(BtWidgetTone.UP, btWidgetCashTone(18.9))
        assertEquals(BtWidgetTone.DOWN, btWidgetCashTone(-42.18))
        // Exactly zero is not a gain — the same rule BtGlanceColors.tone keeps.
        assertEquals(BtWidgetTone.FLAT, btWidgetCashTone(0.0))
    }

    @Test
    fun `the derived kinds are toned, unlike the kind-based map`() {
        // The reason this widget does not reuse btWidgetMovementTone: that one
        // answers FLAT for dividend / tax_refund / tax_withholding, and a grey
        // dividend sitting among green deposits reads as a bug.
        assertEquals(BtWidgetTone.FLAT, btWidgetMovementTone("dividend"))
        assertEquals(BtWidgetTone.UP, btWidgetCashTone(12.40))
        assertEquals(BtWidgetTone.DOWN, btWidgetCashTone(-3.10))
    }

    // ── Row marks ────────────────────────────────────────────────────────────

    @Test
    fun `the row mark is the description's word initials`() {
        assertEquals("BP", btWidgetCashInitials("BILLA Plus"))
        assertEquals("MR", btWidgetCashInitials("Martin Rückzahlung"))
        assertEquals("CP", btWidgetCashInitials("Café Prückel"))
    }

    @Test
    fun `a one-word description falls back to its first two letters`() {
        assertEquals("RE", btWidgetCashInitials("REWE"))
        assertEquals("MI", btWidgetCashInitials("Miete"))
    }

    @Test
    fun `digits and punctuation do not become the mark`() {
        assertEquals("XC", btWidgetCashInitials("3x Café"))
        assertEquals("SU", btWidgetCashInitials("Supermarkt-"))
    }

    @Test
    fun `an unusable description draws no mark at all`() {
        // Better an empty gap than an empty box: the row still has its text and
        // its amount, which is what the reader came for.
        assertEquals("", btWidgetCashInitials(""))
        assertEquals("", btWidgetCashInitials("123 456"))
    }
}
