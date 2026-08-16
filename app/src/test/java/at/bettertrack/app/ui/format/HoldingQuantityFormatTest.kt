package at.bettertrack.app.ui.format

import at.bettertrack.app.ui.portfolio.formatHoldingQuantity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Rule 3b — the holding-row quantity, as re-spec'd by the owner on 2026-08-17:
 * *"max 2 comma so for stuff like 5.6666667 dont do 5.6 but 5.66 and for all the
 * rest 2 not null digits behind the coma … and for numbers like BTC if you have
 * less then 1 total so you have 0.42331 BTC it should take 3 coma values instead
 * of 2"*.
 *
 * That replaces the 2026-08-16 reading (a three-digit budget spent on the
 * integer part first, which rendered `11.66666667` as `11.6`). The examples he
 * gave are the contract and lead the file; the rest pins the rule they imply —
 * two decimals at or above one, three below it, truncated, trailing zeros
 * dropped, and a sub-`0.001` fraction keeping two significant digits rather than
 * collapsing to `0`.
 */
class HoldingQuantityFormatTest {

    private val de = Locale.GERMAN
    private val en = Locale.ENGLISH

    // ── The owner's examples, verbatim ──────────────────────────────────────

    @Test fun `owner example one - two decimals at or above one, not one decimal`() {
        assertEquals("5.66", formatHoldingQuantity(5.6666667, en))
        assertEquals("5,66", formatHoldingQuantity(5.6666667, de))
    }

    @Test fun `owner example two - the two-decimal rule survives a second integer digit`() {
        // The previous rule spent the budget on the integer part and printed
        // "11.6" here. The owner's wording ("max 2 comma") makes the decimals
        // independent of how long the integer part is.
        assertEquals("11.66", formatHoldingQuantity(11.66666667, en))
        assertEquals("11,66", formatHoldingQuantity(11.66666667, de))
    }

    @Test fun `owner example three - a sub-one crypto quantity keeps three decimals`() {
        assertEquals("0.423", formatHoldingQuantity(0.42331, en))
        assertEquals("0,423", formatHoldingQuantity(0.42331, de))
    }

    @Test fun `owner example four - the original crypto fraction still truncates to three`() {
        assertEquals("0.042", formatHoldingQuantity(0.0424512, en))
        assertEquals("0,042", formatHoldingQuantity(0.0424512, de))
    }

    // ── The rule those examples imply ───────────────────────────────────────

    @Test fun `whole numbers print plain`() {
        assertEquals("4", formatHoldingQuantity(4.0, en))
        assertEquals("4", formatHoldingQuantity(4.0, de))
    }

    @Test fun `truncation never rounds up`() {
        // A rounded-up quantity claims the user owns more than they do.
        assertEquals("0.042", formatHoldingQuantity(0.0429999, en))
        assertEquals("11.69", formatHoldingQuantity(11.699999, en))
        assertEquals("5.99", formatHoldingQuantity(5.999999, en))
    }

    @Test fun `a long integer part no longer eats the decimals`() {
        assertEquals("123.45", formatHoldingQuantity(123.456, en))
        assertEquals("1,234.5", formatHoldingQuantity(1234.5, en))
        assertEquals("1.234,5", formatHoldingQuantity(1234.5, de))
    }

    @Test fun `one exactly is a quantity of one or more, so two decimals`() {
        // The boundary the two branches meet at: 1 is NOT the sub-one case.
        assertEquals("1.23", formatHoldingQuantity(1.23456, en))
        assertEquals("1", formatHoldingQuantity(1.0, en))
    }

    @Test fun `trailing zeros are dropped, not padded`() {
        assertEquals("0.5", formatHoldingQuantity(0.5, en))
        assertEquals("2.5", formatHoldingQuantity(2.50, en))
        assertEquals("0.1", formatHoldingQuantity(0.1004, en))
    }

    @Test fun `dust keeps its first two significant digits`() {
        // "2 not null digits behind the coma": three decimals would print 0.
        assertEquals("0.00042", formatHoldingQuantity(0.00042, en))
        assertEquals("0,00042", formatHoldingQuantity(0.00042, de))
        assertEquals("0.00012", formatHoldingQuantity(0.00012345, en))
    }

    @Test fun `single-digit dust does not gain a false second digit`() {
        assertEquals("0.0004", formatHoldingQuantity(0.0004, en))
    }

    @Test fun `zero renders as zero`() {
        assertEquals("0", formatHoldingQuantity(0.0, en))
    }

    @Test fun `negative quantities truncate toward zero`() {
        assertEquals("-11.66", formatHoldingQuantity(-11.66666667, en))
        assertEquals("-0.042", formatHoldingQuantity(-0.0424512, en))
    }
}
