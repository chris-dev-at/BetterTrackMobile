package at.bettertrack.app.ui.portfolio

import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.db.PortfolioEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure switcher logic (owner directive 2026-07-10 archived grouping + platform
 * #412 hard-delete): section split, the delete-result mapping (incl. the
 * LAST_ACTIVE_PORTFOLIO case), and the type-to-confirm match rule.
 */
class PortfolioSwitcherLogicTest {

    private fun portfolio(
        id: String,
        name: String = id,
        archived: Boolean = false,
        isDefault: Boolean = false,
    ) = PortfolioEntity(
        id = id,
        name = name,
        visibility = "private",
        sortOrder = 0,
        isDefault = isDefault,
        defaultPayFromCash = false,
        archivedAt = if (archived) "2026-07-10T10:00:00Z" else null,
        baseCurrency = null,
        totals = null,
        detailSyncedAtMs = null,
    )

    // ── Section split (archived grouping) ───────────────────────────────────────

    @Test
    fun `sections split active from archived and preserve input order`() {
        val all = listOf(
            portfolio("a1"),
            portfolio("z1", archived = true),
            portfolio("a2"),
            portfolio("z2", archived = true),
        )
        val sections = switcherSections(all)
        assertEquals(listOf("a1", "a2"), sections.active.map { it.id })
        assertEquals(listOf("z1", "z2"), sections.archived.map { it.id })
    }

    @Test
    fun `sections handle all-active and all-archived without loss`() {
        assertTrue(switcherSections(listOf(portfolio("a"))).archived.isEmpty())
        assertEquals(1, switcherSections(listOf(portfolio("a"))).active.size)
        val archivedOnly = switcherSections(listOf(portfolio("z", archived = true)))
        assertTrue(archivedOnly.active.isEmpty())
        assertEquals(1, archivedOnly.archived.size)
        assertEquals(SwitcherSections(emptyList(), emptyList()), switcherSections(emptyList()))
    }

    // ── Delete-result mapping ───────────────────────────────────────────────────

    @Test
    fun `a 204 maps to Success`() {
        assertEquals(PortfolioDeleteResult.Success, portfolioDeleteResult(BtResult.Ok(Unit)))
    }

    @Test
    fun `LAST_ACTIVE_PORTFOLIO maps to the dedicated LastActive case`() {
        val err = BtResult.Err(
            BtApiError(400, BtApiError.Codes.LAST_ACTIVE_PORTFOLIO, "Cannot delete the last active portfolio."),
        )
        assertEquals(PortfolioDeleteResult.LastActive, portfolioDeleteResult(err))
        assertTrue(err.error.isLastActivePortfolio)
    }

    @Test
    fun `any other error maps to Failed with the user message`() {
        val err = BtResult.Err(BtApiError(0, BtApiError.Codes.NETWORK, "No connection."))
        val result = portfolioDeleteResult(err)
        assertTrue(result is PortfolioDeleteResult.Failed)
        assertEquals("No connection.", (result as PortfolioDeleteResult.Failed).message)
        assertFalse(err.error.isLastActivePortfolio)
    }

    // ── Type-to-confirm match ───────────────────────────────────────────────────

    @Test
    fun `confirmation matches only the exact trimmed name`() {
        assertTrue(deleteConfirmationMatches("Growth", "Growth"))
        assertTrue(deleteConfirmationMatches("Growth", "  Growth  "))
        assertFalse(deleteConfirmationMatches("Growth", "growth"))
        assertFalse(deleteConfirmationMatches("Growth", "Growt"))
        assertFalse(deleteConfirmationMatches("Growth", ""))
    }

    @Test
    fun `a blank portfolio name can never be confirmed`() {
        assertFalse(deleteConfirmationMatches("", ""))
        assertFalse(deleteConfirmationMatches("   ", "   "))
    }
}
