package at.bettertrack.app.ui.format

import at.bettertrack.app.ui.portfolio.formatHoldingQuantity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Rule 3b — the holding-row quantity (owner UI batch 2026-08-16).
 *
 * The two examples the owner gave are the contract and lead the file; the rest
 * pins the rule they imply: a three-digit precision budget, integer part first,
 * truncated (never rounded up), trailing zeros dropped, full integer part
 * always, sub-0.001 dust keeping its first two significant digits.
 */
class HoldingQuantityFormatTest {

    private val de = Locale.GERMAN
    private val en = Locale.ENGLISH

    // ── The owner's examples, verbatim ──────────────────────────────────────

    @Test fun `owner example one - crypto fraction truncates to three decimals`() {
        assertEquals("0.042", formatHoldingQuantity(0.0424512, en))
        assertEquals("0,042", formatHoldingQuantity(0.0424512, de))
    }

    @Test fun `owner example two - repeating fraction keeps one decimal`() {
        assertEquals("11.6", formatHoldingQuantity(11.66666667, en))
        assertEquals("11,6", formatHoldingQuantity(11.66666667, de))
    }

    // ── The rule those examples imply ───────────────────────────────────────

    @Test fun `whole numbers print plain - no ticker, no decimals`() {
        assertEquals("4", formatHoldingQuantity(4.0, en))
        assertEquals("4", formatHoldingQuantity(4.0, de))
    }

    @Test fun `truncation never rounds up`() {
        // 0.0429 rounds to 0.043; the row must not claim the extra fraction.
        assertEquals("0.042", formatHoldingQuantity(0.0429999, en))
        assertEquals("11.6", formatHoldingQuantity(11.6999, en))
    }

    @Test fun `the integer part exhausts the budget at three digits`() {
        assertEquals("123", formatHoldingQuantity(123.456, en))
    }

    @Test fun `the integer part is never cut`() {
        assertEquals("1,234", formatHoldingQuantity(1234.5, en))
        assertEquals("1.234", formatHoldingQuantity(1234.5, de))
    }

    @Test fun `one integer digit leaves two decimals`() {
        assertEquals("1.25", formatHoldingQuantity(1.256, en))
    }

    @Test fun `trailing zeros are dropped, not padded`() {
        assertEquals("0.5", formatHoldingQuantity(0.5, en))
        assertEquals("2.5", formatHoldingQuantity(2.50, en))
    }

    @Test fun `dust keeps its first two significant digits`() {
        assertEquals("0.00012", formatHoldingQuantity(0.00012345, en))
        assertEquals("0,00012", formatHoldingQuantity(0.00012345, de))
    }

    @Test fun `single-digit dust does not gain a false second digit`() {
        assertEquals("0.0004", formatHoldingQuantity(0.0004, en))
    }

    @Test fun `zero renders as zero`() {
        assertEquals("0", formatHoldingQuantity(0.0, en))
    }

    @Test fun `negative quantities truncate toward zero`() {
        assertEquals("-11.6", formatHoldingQuantity(-11.66666667, en))
        assertEquals("-0.042", formatHoldingQuantity(-0.0424512, en))
    }
}
