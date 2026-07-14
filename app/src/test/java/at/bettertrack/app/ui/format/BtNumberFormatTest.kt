package at.bettertrack.app.ui.format

import at.bettertrack.app.ui.components.formatEur
import at.bettertrack.app.ui.components.formatMoney
import at.bettertrack.app.ui.components.formatPercent
import at.bettertrack.app.ui.market.formatPrice
import at.bettertrack.app.ui.portfolio.formatQuantity
import at.bettertrack.app.ui.portfolio.formatWeight
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Web-parity number-display sweep (Task A / PLATFORM_ASKS #18/#19, web PR #442).
 * Every rule + edge case from the directive, asserted through both the shared
 * core and the public helpers that delegate to it. DE = [Locale.GERMAN],
 * EN = [Locale.ENGLISH]. Non-breaking spaces in ICU output are not used by our
 * formatters (we emit plain spaces), so string equality is exact.
 */
class BtNumberFormatTest {

    private val de = Locale.GERMAN
    private val en = Locale.ENGLISH

    // ── Rule 1 — fiat money ─────────────────────────────────────────────────

    @Test fun `money DE symbol-last grouping and comma`() {
        assertEquals("1.234,56 €", formatEur(1234.56, de))
    }

    @Test fun `money EN symbol-last grouping and dot`() {
        assertEquals("1,234.56 €", formatEur(1234.56, en))
    }

    @Test fun `money USD is symbol-last with dollar sign`() {
        assertEquals("-50,00 $", formatMoney(-50.0, "USD", de))
        assertEquals("1,234.56 $", formatMoney(1234.56, "USD", en))
    }

    @Test fun `money always shows exactly two decimals`() {
        assertEquals("42,00 €", formatEur(42.0, de))
        assertEquals("1.234,50 €", formatEur(1234.5, de))
    }

    @Test fun `money rounds half away from zero`() {
        assertEquals("2,13 €", formatEur(2.125, de))
        assertEquals("-2,13 €", formatEur(-2.125, de))
    }

    @Test fun `money collapses negative zero`() {
        assertEquals("0,00 €", formatEur(0.0, de))
        assertEquals("0,00 €", formatEur(-0.0, de))
    }

    @Test fun `money showSign prefixes plus for positive only`() {
        assertEquals("+10,00 €", formatEur(10.0, de, showSign = true))
        assertEquals("-10,00 €", formatEur(-10.0, de, showSign = true))
        assertEquals("0,00 €", formatEur(0.0, de, showSign = true))
    }

    @Test fun `money non-finite renders em dash`() {
        assertEquals(BT_EM_DASH, btFormatMoneyCore(null, "EUR", de, false))
        assertEquals(BT_EM_DASH, btFormatMoneyCore(Double.NaN, "EUR", de, false))
        assertEquals(BT_EM_DASH, btFormatMoneyCore(Double.POSITIVE_INFINITY, "EUR", de, false))
        assertEquals(BT_EM_DASH, btFormatMoneyCore(Double.NEGATIVE_INFINITY, "EUR", de, false))
    }

    @Test fun `money unknown currency falls back to code symbol-last`() {
        assertEquals("150,00 ZZZ", formatMoney(150.0, "ZZZ", de))
    }

    // ── Rule 2 — percent ────────────────────────────────────────────────────

    @Test fun `percent DE has a space before the symbol`() {
        assertEquals("2,50 %", formatPercent(2.5, de, showSign = false))
    }

    @Test fun `percent EN has no space before the symbol`() {
        assertEquals("2.50%", formatPercent(2.5, en, showSign = false))
    }

    @Test fun `percent two decimals half away from zero`() {
        assertEquals("2,13 %", formatPercent(2.125, de, showSign = false))
        assertEquals("-2,13 %", formatPercent(-2.125, de, showSign = false))
    }

    @Test fun `signed percent prepends plus for positive`() {
        assertEquals("+2,50 %", formatPercent(2.5, de, showSign = true))
        assertEquals("+2.50%", formatPercent(2.5, en, showSign = true))
    }

    @Test fun `signed percent keeps the minus for negative`() {
        assertEquals("-1,50 %", formatPercent(-1.5, de, showSign = true))
    }

    @Test fun `signed percent shows nothing for zero`() {
        assertEquals("0,00 %", formatPercent(0.0, de, showSign = true))
        assertEquals("0.00%", formatPercent(0.0, en, showSign = true))
    }

    @Test fun `signed percent that rounds to zero drops the sign`() {
        // A tiny magnitude rounds to 0,00 → no leading + and no stray minus.
        assertEquals("0,00 %", btFormatPercentCore(0.001, de, signed = true))
        assertEquals("0,00 %", btFormatPercentCore(-0.001, de, signed = true))
    }

    @Test fun `percent non-finite renders em dash`() {
        assertEquals(BT_EM_DASH, btFormatPercentCore(null, de, false))
        assertEquals(BT_EM_DASH, btFormatPercentCore(Double.NaN, de, false))
        assertEquals(BT_EM_DASH, btFormatPercentCore(Double.POSITIVE_INFINITY, de, true))
    }

    @Test fun `weight is the unsigned two-decimal percent full string`() {
        assertEquals("30,00 %", formatWeight(30.0, de))
        assertEquals("33.33%", formatWeight(33.333, en))
    }

    // ── Rule 3 — quantities ─────────────────────────────────────────────────

    @Test fun `quantity whole numbers render plain`() {
        assertEquals("12", formatQuantity(12.0, de))
        assertEquals("0", formatQuantity(-0.0, de))
    }

    @Test fun `quantity fractional trims trailing zeros`() {
        assertEquals("1,5", formatQuantity(1.5, de))
        assertEquals("0,12345678", formatQuantity(0.12345678, de))
    }

    @Test fun `quantity supports up to eight decimals`() {
        assertEquals("1,12345679", formatQuantity(1.123456789, de))
    }

    @Test fun `quantity groups large whole numbers`() {
        assertEquals("1.234.567", formatQuantity(1234567.0, de))
        assertEquals("1,234,567", formatQuantity(1234567.0, en))
    }

    @Test fun `quantity non-finite renders em dash`() {
        assertEquals(BT_EM_DASH, btFormatQuantityCore(null, de))
        assertEquals(BT_EM_DASH, btFormatQuantityCore(Double.NaN, de))
    }

    // ── Rule 4 — unit prices ────────────────────────────────────────────────

    @Test fun `sub-cent unit price shows up to six significant decimals`() {
        assertEquals("0,000012 €", formatPrice(0.000012, "EUR", de))
    }

    @Test fun `sub-cent unit price rounds to six significant figures`() {
        assertEquals("0,00123457 €", formatPrice(0.00123456789, "EUR", de))
    }

    @Test fun `sub-cent negative unit price keeps its sign`() {
        assertEquals("-0,000012 €", formatPrice(-0.000012, "EUR", de))
    }

    @Test fun `unit price of exactly zero uses rule one`() {
        assertEquals("0,00 €", formatPrice(0.0, "EUR", de))
    }

    @Test fun `unit price at or above one cent uses rule one`() {
        assertEquals("0,01 €", formatPrice(0.01, "EUR", de))
        assertEquals("1.234,50 €", formatPrice(1234.5, "EUR", de))
        assertEquals("1,234.50 $", formatPrice(1234.5, "USD", en))
    }

    @Test fun `unit price non-finite renders em dash`() {
        assertEquals(BT_EM_DASH, btFormatUnitPriceCore(Double.NaN, "EUR", de))
        assertEquals(BT_EM_DASH, btFormatUnitPriceCore(null, "EUR", de))
    }

    // ── Rule 5 — currency symbol resolution (locale-driven) ─────────────────

    @Test fun `currency symbol resolves common codes and falls back`() {
        assertEquals("€", btMoneySymbol("EUR", de))
        assertEquals("$", btMoneySymbol("USD", en))
        assertEquals("ZZZ", btMoneySymbol("ZZZ", en))
    }
}
