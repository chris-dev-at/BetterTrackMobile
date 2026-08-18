package at.bettertrack.app.ui.cash

import at.bettertrack.app.data.db.CashSourceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Owner defect 2026-08-17 (item 7): the in-app **Bezahlt** / **Erhalten**
 * buttons opened on the main wallet even when the Cash screen was narrowed to a
 * different source, while the Cash Wallet widget honoured the source it was
 * configured with. Both now go through [cashEntrySourcePreselection], so the two
 * surfaces cannot disagree again.
 */
class CashEntrySourcePreselectionTest {

    private fun src(id: String, main: Boolean = false) = CashSourceEntity(
        id = id,
        portfolioId = "p1",
        name = id,
        kind = "bank",
        isMain = main,
        balanceEur = 100.0,
        archivedAt = null,
    )

    /** The picker's universe is always the ACTIVE list, main first. */
    private val active = listOf(src("main", main = true), src("bank"), src("wallet"))

    @Test
    fun `a selected source is the one the sheet opens on`() {
        assertEquals(
            "bank",
            cashEntrySourcePreselection(prefillSourceId = null, requestedSourceId = "bank", sources = active),
        )
        assertEquals(
            "wallet",
            cashEntrySourcePreselection(prefillSourceId = null, requestedSourceId = "wallet", sources = active),
        )
    }

    @Test
    fun `all sources - a null scope - falls through to the main wallet`() {
        assertEquals(
            "main",
            cashEntrySourcePreselection(prefillSourceId = null, requestedSourceId = null, sources = active),
        )
    }

    @Test
    fun `a source that is no longer active falls back to main`() {
        // Archived (so `activeSources` dropped it) or deleted outright — the id
        // is simply absent from the list the sheet can select from. Seeding it
        // would leave the picker on a wallet the user cannot see.
        assertEquals(
            "main",
            cashEntrySourcePreselection(prefillSourceId = null, requestedSourceId = "archived", sources = active),
        )
    }

    @Test
    fun `a queued op keeps its own source, whatever the screen scope is`() {
        // Editing a queued movement must never move money to another wallet.
        assertEquals(
            "wallet",
            cashEntrySourcePreselection(prefillSourceId = "wallet", requestedSourceId = "bank", sources = active),
        )
    }

    @Test
    fun `null only when nothing matches and there is no main wallet`() {
        val noMain = listOf(src("a"), src("b"))
        assertNull(cashEntrySourcePreselection(null, null, noMain))
        assertNull(cashEntrySourcePreselection(null, "gone", noMain))
        assertEquals("b", cashEntrySourcePreselection(null, "b", noMain))
    }

    @Test
    fun `transfer TO never opens on the FROM side`() {
        // Scope = "bank": FROM is bank, so TO has to be something else.
        val from = cashEntrySourcePreselection(null, "bank", active)
        assertEquals("bank", from)
        val to = cashTransferToPreselection(prefillToSourceId = null, fromId = from, sources = active)
        assertEquals("main", to)
    }

    @Test
    fun `transfer with no scope keeps the old main-to-other default`() {
        val from = cashEntrySourcePreselection(null, null, active)
        assertEquals("main", from)
        assertEquals("bank", cashTransferToPreselection(null, from, active))
    }

    @Test
    fun `a queued transfer restores both of its own sides`() {
        val from = cashEntrySourcePreselection("wallet", "bank", active)
        assertEquals("wallet", from)
        assertEquals("bank", cashTransferToPreselection("bank", from, active))
    }
}
