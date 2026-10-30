package at.bettertrack.app.ui.workboard

import at.bettertrack.app.data.repo.AlertKind
import at.bettertrack.app.ui.format.BtDiscreetMode
import at.bettertrack.app.ui.format.btMoneySymbol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Pure-logic tests for the price-alert form (owner ask 2026-07-10): threshold
 * parse, the per-kind validity caps, and locale-aware number/price formatting.
 * Mirrors the TransactionFormLogic test style.
 */
class AlertFormLogicTest {

    // ── Threshold parse (comma/dot tolerant, positive-only) ───────────────────

    @Test
    fun `parse accepts dot and comma decimals and rejects non-positive`() {
        assertEquals(150.0, parseAlertThreshold("150")!!, 1e-9)
        assertEquals(150.5, parseAlertThreshold("150.50")!!, 1e-9)
        assertEquals(150.5, parseAlertThreshold("150,50")!!, 1e-9) // de-AT comma
        assertNull(parseAlertThreshold("0"))     // must be > 0
        assertNull(parseAlertThreshold("-5"))    // negatives rejected
        assertNull(parseAlertThreshold(""))
        assertNull(parseAlertThreshold("abc"))
    }

    // ── Per-kind validity: down kinds cap at 100 %, up kinds uncapped ─────────

    @Test
    fun `down-percent kinds cap at 100 while up kinds are uncapped`() {
        assertTrue(alertThresholdValid(AlertKind.PctDownFromRef, 100.0))
        assertFalse(alertThresholdValid(AlertKind.PctDownFromRef, 100.01))
        assertFalse(alertThresholdValid(AlertKind.PctDayDown, 250.0))
        // Up kinds can moonshot past 100 %.
        assertTrue(alertThresholdValid(AlertKind.PctUpFromRef, 500.0))
        assertTrue(alertThresholdValid(AlertKind.PctDayUp, 999.0))
        // Price kinds are unbounded above.
        assertTrue(alertThresholdValid(AlertKind.PriceAbove, 100_000.0))
    }

    @Test
    fun `null or non-positive threshold is never valid for any kind`() {
        for (k in AlertKind.entries) {
            assertFalse(alertThresholdValid(k, null))
            assertFalse(alertThresholdValid(k, 0.0))
            assertFalse(alertThresholdValid(k, -1.0))
        }
    }

    // ── Number formatting: whole stays whole, else two decimals, locale sep ───

    @Test
    fun `format keeps whole numbers whole and pads fractions to two places`() {
        assertEquals("5", formatAlertNumber(5.0, Locale.US))
        assertEquals("150", formatAlertNumber(150.0, Locale.US))
        assertEquals("80.50", formatAlertNumber(80.5, Locale.US))
        assertEquals("3.25", formatAlertNumber(3.25, Locale.US))
        // German uses a comma decimal separator.
        assertEquals("5", formatAlertNumber(5.0, Locale.GERMANY))
        assertEquals("80,50", formatAlertNumber(80.5, Locale.GERMANY))
    }

    // ── Currency symbol + symbol-prefixed price ───────────────────────────────

    @Test
    fun `currency prefix resolves known codes and falls back to the raw code`() {
        assertEquals("$", alertThresholdPrefix(AlertKind.PriceAbove, "USD", Locale.US))
        assertEquals("€", alertThresholdPrefix(AlertKind.PriceAbove, "EUR", Locale.GERMANY))
        assertEquals("ZZZ", alertThresholdPrefix(AlertKind.PriceAbove, "ZZZ", Locale.US)) // invalid ISO code → raw
    }

    @Test
    fun `price suffixes the native currency symbol, matching the app's money convention`() {
        assertEquals("150.00 $", formatAlertPrice(150.0, "USD", Locale.US))
        assertEquals("80,50 €", formatAlertPrice(80.5, "EUR", Locale.GERMANY))
    }

    // ── MB-08: one currency symbol, and the alert amounts honour discreet mode ──
    //
    // The alert sheet carried its own `currencySymbol` (`Currency.getSymbol`
    // without the format package's case folding), so the same code could print
    // a different symbol here than on every other screen: a lower-case "usd"
    // read "usd" in the alert field and "$" everywhere else.

    @Test
    fun `the threshold prefix is the format package's symbol for every code and locale`() {
        val locales = listOf(Locale.US, Locale.GERMANY, Locale.UK, Locale.JAPAN)
        for (code in listOf("EUR", "USD", "GBP", "JPY", "usd", "eur", "ZZZ")) {
            for (locale in locales) {
                assertEquals(
                    "$code in $locale",
                    btMoneySymbol(code, locale),
                    alertThresholdPrefix(AlertKind.PriceAbove, code, locale),
                )
            }
        }
    }

    @Test
    fun `percent kinds prefix a percent sign, never a currency`() {
        for (k in AlertKind.entries.filter { it.isPercent }) {
            assertEquals("%", alertThresholdPrefix(k, "USD", Locale.US))
        }
        // Negative space: the price kinds never fall back to "%".
        assertEquals("$", alertThresholdPrefix(AlertKind.PriceBelow, "USD", Locale.US))
    }

    @Test
    fun `alert prices mask in discreet mode while a percent threshold stays live`() {
        BtDiscreetMode.resetForTest()
        try {
            BtDiscreetMode.setEnabled(true)
            assertEquals("•••• $", formatAlertPrice(150.0, "USD", Locale.US))
            assertEquals("•••• €", formatAlertPrice(80.5, "EUR", Locale.GERMANY))
            // A percentage leaks nothing about the size of a portfolio (format
            // rule 6), so the "Rises 5% from …" half of the line stays readable.
            assertEquals("5", formatAlertNumber(5.0, Locale.US))
        } finally {
            BtDiscreetMode.resetForTest()
        }
        assertEquals("150.00 $", formatAlertPrice(150.0, "USD", Locale.US))
    }
}
