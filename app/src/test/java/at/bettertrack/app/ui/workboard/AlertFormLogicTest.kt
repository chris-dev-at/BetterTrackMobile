package at.bettertrack.app.ui.workboard

import at.bettertrack.app.data.repo.AlertKind
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
    fun `currency symbol resolves known codes and falls back to the raw code`() {
        assertEquals("$", currencySymbol("USD", Locale.US))
        assertEquals("€", currencySymbol("EUR", Locale.GERMANY))
        assertEquals("ZZZ", currencySymbol("ZZZ", Locale.US)) // invalid ISO code → raw
    }

    @Test
    fun `price prefixes the native currency symbol to the formatted number`() {
        assertEquals("$150", formatAlertPrice(150.0, "USD", Locale.US))
        assertEquals("€80,50", formatAlertPrice(80.5, "EUR", Locale.GERMANY))
    }
}
