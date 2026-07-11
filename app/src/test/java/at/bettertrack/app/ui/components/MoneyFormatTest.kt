package at.bettertrack.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Step-19 currency-label tests: per-asset native prices carry the asset's own
 * currency symbol (the tx form's "€"-only suffix was wrong for USD assets),
 * while EUR routes through the same de-AT formatter so nothing else shifts.
 */
class MoneyFormatTest {

    @Test
    fun `currency symbol resolves the common codes`() {
        assertEquals("$", currencySymbol("USD", Locale.US))
        assertEquals("€", currencySymbol("EUR", Locale.GERMANY))
        assertEquals("£", currencySymbol("GBP", Locale.UK))
    }

    @Test
    fun `currency symbol is case-insensitive on the code`() {
        assertEquals("$", currencySymbol("usd", Locale.US))
    }

    @Test
    fun `an unknown currency code falls back to the raw code`() {
        assertEquals("ZZZ", currencySymbol("ZZZ", Locale.US))
    }

    @Test
    fun `EUR formats de-AT style with grouping dot and decimal comma`() {
        // The DE spot-check from the brief: "1.234,56".
        val de = formatMoney(1234.56, "EUR", Locale.GERMAN)
        assertTrue("expected 1.234,56 in <$de>", de.contains("1.234,56"))
    }

    @Test
    fun `USD formats in the native currency, not euros`() {
        val us = formatMoney(1234.56, "USD", Locale.US)
        assertTrue("expected a dollar sign in <$us>", us.contains("$"))
        assertTrue("expected US grouping in <$us>", us.contains("1,234.56"))
        assertTrue("must not be a euro amount", !us.contains("€"))
    }

    @Test
    fun `EUR through formatMoney equals formatEur exactly`() {
        val locale = Locale.GERMAN
        assertEquals(formatEur(2500.0, locale), formatMoney(2500.0, "EUR", locale))
    }

    @Test
    fun `an unknown currency still renders a symbol-prefixed number`() {
        val out = formatMoney(150.0, "ZZZ", Locale.US)
        assertTrue("expected the raw code prefix in <$out>", out.contains("ZZZ"))
        assertTrue("expected the number in <$out>", out.contains("150"))
    }

    @Test
    fun `showSign prefixes a plus only for positive values`() {
        assertTrue(formatMoney(10.0, "USD", Locale.US, showSign = true).startsWith("+"))
        assertTrue(!formatMoney(-10.0, "USD", Locale.US, showSign = true).startsWith("+"))
    }
}
