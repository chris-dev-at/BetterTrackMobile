package at.bettertrack.app.ui.tax

import at.bettertrack.app.domain.TAX_MODES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The account-level tax screen's report hand-off: WHEN it is offered.
 *
 * Web parity, verbatim — `DefaultsPanel.tsx:96` gates the same link with
 * `mode !== 'none'`, reading the server's mode (`query.data?.mode ?? 'none'`).
 * The rule is one line of code on both clients, which is exactly why it is worth
 * pinning: the failure it guards against is silent. `none` means BetterTrack
 * does not treat tax on this account, so the row points at nothing; and a mode
 * this build has never heard of is a *newer* mode, which certainly does produce
 * figures and must not be treated as if it were `none`.
 */
class TaxReportsLinkTest {

    @Test
    fun `none is the only shipped mode without a report`() {
        assertFalse(taxReportsLinkVisible("none"))
        assertTrue(taxReportsLinkVisible("manual_per_trade"))
        assertTrue(taxReportsLinkVisible("country_specific"))
        assertTrue(taxReportsLinkVisible("custom"))
    }

    /**
     * Stated against [TAX_MODES] rather than the four literals above so a mode
     * the platform adds later cannot slip in as "no reports" by default — the
     * declaration order there is contract, and `none` is its first entry.
     */
    @Test
    fun `every shipped mode except the first offers a report`() {
        assertEquals("none", TAX_MODES.first())
        TAX_MODES.drop(1).forEach { mode ->
            assertTrue("expected a report row for $mode", taxReportsLinkVisible(mode))
        }
    }

    @Test
    fun `a mode this build does not know still offers a report`() {
        // The forward-compatible case: the server moved ahead of the app.
        assertTrue(taxReportsLinkVisible("country_specific_v2"))
        assertTrue(taxReportsLinkVisible("something_new"))
    }

    @Test
    fun `a blank mode is not a mode`() {
        // Malformed payload, not a newer feature — offering a report for it
        // would be a guess dressed up as a destination.
        assertFalse(taxReportsLinkVisible(""))
        assertFalse(taxReportsLinkVisible("   "))
    }

    @Test
    fun `the check is exact, not a prefix or case fold`() {
        // "none_of_the_above" is not `none`; "None" is not a wire value at all,
        // but if one ever arrived it is likewise not the mode that means "off".
        assertTrue(taxReportsLinkVisible("none_of_the_above"))
        assertTrue(taxReportsLinkVisible("None"))
    }
}
