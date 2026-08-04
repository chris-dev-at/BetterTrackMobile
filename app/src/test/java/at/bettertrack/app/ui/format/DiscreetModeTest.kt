package at.bettertrack.app.ui.format

import at.bettertrack.app.ui.components.formatEur
import at.bettertrack.app.ui.components.formatMoney
import at.bettertrack.app.ui.components.formatPercent
import at.bettertrack.app.ui.portfolio.formatQuantity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Discreet mode is a privacy promise, so the tests are about what must NOT leak
 * as much as what renders. The critical property: masking is enforced in the
 * shared formatter core, so it applies to every helper that delegates to it
 * without each caller having to remember.
 */
class DiscreetModeTest {

    private val de = Locale.GERMANY
    private val en = Locale.US

    @Before
    fun setUp() = BtDiscreetMode.resetForTest()

    @After
    fun tearDown() = BtDiscreetMode.resetForTest()

    @Test
    fun `off by default`() {
        assertFalse(BtDiscreetMode.enabled)
        assertFalse(BtDiscreetMode.masking)
        assertEquals("1.234,56 €", formatEur(1234.56, de))
    }

    @Test
    fun `masks every money helper that goes through the core`() {
        BtDiscreetMode.setEnabled(true)
        assertEquals("•••• €", formatEur(1234.56, de))
        assertEquals("•••• $", formatMoney(99.0, "USD", en))
        // Sub-cent unit prices take a different code path and must mask too.
        assertEquals("•••• €", at.bettertrack.app.ui.market.formatPrice(0.000012, "EUR", de))
    }

    @Test
    fun `the masked amount leaks neither magnitude nor sign`() {
        BtDiscreetMode.setEnabled(true)
        val small = formatEur(1.0, de)
        val huge = formatEur(9_876_543.21, de)
        val negative = formatEur(-4321.0, de)
        assertEquals(small, huge)
        assertEquals(small, negative)
        assertFalse(small.any { it.isDigit() })
        assertFalse(small.contains("-"))
    }

    @Test
    fun `relative values stay live`() {
        BtDiscreetMode.setEnabled(true)
        // Percentages and quantities say nothing about portfolio SIZE, and
        // blanking them would make the app useless rather than discreet.
        assertEquals("+12,50 %", formatPercent(12.5, de))
        assertEquals("1,5", formatQuantity(1.5, de))
    }

    @Test
    fun `the currency symbol survives so layout does not jump`() {
        BtDiscreetMode.setEnabled(true)
        assertTrue(formatEur(10.0, de).endsWith("€"))
        assertTrue(formatMoney(10.0, "USD", en).endsWith("$"))
    }

    @Test
    fun `press and hold reveals then re-hides`() {
        BtDiscreetMode.setEnabled(true)
        assertTrue(BtDiscreetMode.masking)

        BtDiscreetMode.setRevealing(true)
        assertFalse(BtDiscreetMode.masking)
        assertEquals("1.234,56 €", formatEur(1234.56, de))

        BtDiscreetMode.setRevealing(false)
        assertTrue(BtDiscreetMode.masking)
        assertEquals("•••• €", formatEur(1234.56, de))
    }

    @Test
    fun `turning the mode off clears a stuck reveal`() {
        // Otherwise a gesture interrupted mid-press could leave the next
        // enable() silently un-masked.
        BtDiscreetMode.setEnabled(true)
        BtDiscreetMode.setRevealing(true)
        BtDiscreetMode.setEnabled(false)
        BtDiscreetMode.setEnabled(true)
        assertTrue(BtDiscreetMode.masking)
    }

    @Test
    fun `revealing while the mode is off changes nothing`() {
        BtDiscreetMode.setRevealing(true)
        assertFalse(BtDiscreetMode.masking)
        assertEquals("1.234,56 €", formatEur(1234.56, de))
    }

    @Test
    fun `absent values still render as an em dash not a mask`() {
        BtDiscreetMode.setEnabled(true)
        // "No value" and "hidden value" are different facts; conflating them
        // would make an empty portfolio look like a hidden one.
        assertEquals(BT_EM_DASH, formatEur(Double.NaN, de))
        assertNotEquals("•••• €", formatEur(Double.NaN, de))
    }

    @Test
    fun `chart axes and alert prices are masked too`() {
        BtDiscreetMode.setEnabled(true)
        // A value axis spells out the portfolio's size just as plainly as the
        // hero number does.
        assertEquals(BT_MASKED_PLAIN, at.bettertrack.app.ui.charts.axisMoney(12_345.0, de, true))
        assertFalse(
            at.bettertrack.app.ui.workboard.formatAlertPrice(150.0, "USD", en).any { it.isDigit() },
        )
    }
}
