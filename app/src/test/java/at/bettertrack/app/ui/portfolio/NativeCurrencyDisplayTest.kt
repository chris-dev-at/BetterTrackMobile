package at.bettertrack.app.ui.portfolio

import at.bettertrack.app.data.db.CashSourceEntity
import at.bettertrack.app.ui.conglomerate.backtestReturnColor
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The display rules behind the 2026-10-29 tablet smoke, as pure functions:
 * native figures carry their own currency, the trade form names the cash source
 * its balance comes from, an edit prefill is the stored number in the device's
 * own notation, and a backtest return is coloured by its direction.
 */
class NativeCurrencyDisplayTest {

    private val de = Locale.GERMANY
    private val en = Locale.US

    // ── C1: a USD trade is dollars (web: MoneyText currency={asset.currency}) ─

    @Test
    fun `a USD unit price carries the dollar sign, never the euro sign`() {
        val s = formatTradePrice(265.756256, "USD", de)
        assertTrue(s, s.startsWith("265,76"))
        assertTrue(s, s.contains("$"))
        assertFalse(s, s.contains("€"))
    }

    @Test
    fun `a EUR unit price stays euro`() {
        assertTrue(formatTradePrice(171.44, "EUR", de).contains("€"))
    }

    @Test
    fun `a sub-cent native price keeps its digits`() {
        val s = formatTradePrice(0.000123, "USD", en)
        assertTrue(s, s.contains("0.000123"))
    }

    @Test
    fun `a native notional and fee carry the asset currency`() {
        val notional = formatTradeAmount(transactionNotional(6.0, 265.756256), "USD", de)
        assertTrue(notional, notional.startsWith("1.594,54"))
        assertTrue(notional, notional.contains("$"))
        assertFalse(notional, notional.contains("€"))
    }

    /** A queued op from before the currency field: a bare figure, not a guessed €. */
    @Test
    fun `an unknown currency renders a bare number`() {
        assertEquals("265,76", formatTradePrice(265.756256, null, de))
        assertEquals("1.594,54", formatTradeAmount(1594.537536, null, de))
    }

    // ── C2: the form's balance is the PRIMARY source's, and says so ───────────

    private fun source(id: String, name: String, isMain: Boolean, balance: Double) =
        CashSourceEntity(id, "p1", name, "cash", isMain, balance, archivedAt = null)

    @Test
    fun `the balance line names the primary source it reads`() {
        // Growth Portfolio on 2026-10-29: Main 3.192,61 + Savings (JPM) 1.500,00.
        val sources = listOf(
            source("s2", "Savings (JPM)", isMain = false, balance = 1500.0),
            source("s1", "Main", isMain = true, balance = 3192.61),
        )
        assertEquals("Main", mainCashSourceName(sources))
        assertEquals(3192.61, mainCashSourceBalanceEur(sources)!!, 1e-9)
    }

    @Test
    fun `no primary source, or a blank name, keeps the unnamed line`() {
        assertNull(mainCashSourceName(listOf(source("s2", "Savings", isMain = false, balance = 1.0))))
        assertNull(mainCashSourceName(listOf(source("s1", "  ", isMain = true, balance = 1.0))))
    }

    // ── Edit prefill: the stored number, in the device's notation ────────────

    @Test
    fun `an edit prefill uses the locale separator and keeps every stored digit`() {
        assertEquals("265,756256", formatStoredDecimalForEdit(265.756256, de))
        assertEquals("265.756256", formatStoredDecimalForEdit(265.756256, en))
        assertEquals("6", formatStoredDecimalForEdit(6.0, de))
        assertEquals("0,05", formatStoredDecimalForEdit(0.05, de))
        assertEquals("231,49", formatStoredDecimalForEdit(231.49, de))
        assertEquals("0,0000001", formatStoredDecimalForEdit(1.0e-7, de))
        assertEquals("1234567,5", formatStoredDecimalForEdit(1234567.5, de))
    }

    /** Opening an edit must never change the stored value (the PATCH sends any difference). */
    @Test
    fun `an edit prefill parses back to the stored double exactly`() {
        val stored = listOf(265.756256, 185.39637212903224, 0.05, 56546.023438, 1.0e-7, 121.69333200000001, 6.0)
        for (loc in listOf(de, en)) {
            for (v in stored) {
                assertEquals("$v in $loc", v, parseLocalizedDecimal(formatStoredDecimalForEdit(v, loc))!!, 0.0)
            }
        }
    }

    // ── CAGR coloured like its neighbours ────────────────────────────────────

    @Test
    fun `a negative backtest return is a loss and a positive one a gain`() {
        assertEquals("loss", backtestReturnColor(-15.85, "gain", "loss"))
        assertEquals("gain", backtestReturnColor(12.0, "gain", "loss"))
        assertEquals("gain", backtestReturnColor(0.0, "gain", "loss"))
    }
}
