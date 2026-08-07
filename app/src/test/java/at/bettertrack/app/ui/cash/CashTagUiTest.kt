package at.bettertrack.app.ui.cash

import androidx.compose.ui.graphics.Color
import at.bettertrack.app.ui.theme.BtDarkColors
import at.bettertrack.app.ui.theme.BtLightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

/**
 * Pure rendering rules for the cash-classification UI.
 *
 * Both subjects here take **server data straight into a list row**, so the tests
 * lean on the malformed cases: a tag tint is an arbitrary user-chosen string
 * from the web, and a budget's numbers are money that can legitimately be zero
 * or negative. Neither may throw, and neither may produce geometry that breaks
 * the row.
 */
class CashTagUiTest {

    // ── Tag tints ─────────────────────────────────────────────────────────────

    /**
     * The fallback is a THEME token now (`BtColors.tagFallback`), passed in by
     * the caller, so the parser has no colour of its own to assert. A sentinel
     * that could never be produced by parsing proves the fallback branch is the
     * one that fired.
     */
    private val FB = Color(0xFF010203)

    private fun parse(raw: String?) = parseTagColor(raw, FB)

    @Test
    fun `a six-digit hex tint parses opaque`() {
        assertEquals(Color(0xFF22C55E), parse("#22c55e"))
        assertEquals(Color(0xFFEF4444), parse("#ef4444"))
    }

    @Test
    fun `the leading hash is optional and case does not matter`() {
        assertEquals(parse("#22c55e"), parse("22c55e"))
        assertEquals(parse("#22c55e"), parse("#22C55E"))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(parse("#22c55e"), parse("  #22c55e  "))
    }

    @Test
    fun `an eight-digit value keeps its alpha`() {
        assertEquals(Color(0x8022C55E), parse("#8022c55e"))
    }

    @Test
    fun `malformed absent and nonsense tints fall back instead of throwing`() {
        // This is wire data rendered inside a LazyColumn row — a crash here would
        // take down the whole ledger for one bad colour string.
        val fallback = parse(null)
        listOf("", "   ", "#", "#12345", "#1234567", "red", "#gggggg", "0x22c55e")
            .forEach { assertEquals("expected fallback for '$it'", fallback, parse(it)) }
    }

    @Test
    fun `the fallback tint is opaque in both themes so a dot is always visible`() {
        // The parser returns whatever it is handed, so the guarantee lives on the
        // token itself — a translucent fallback would render an invisible dot.
        assertEquals(1f, BtDarkColors.tagFallback.alpha, 0.0f)
        assertEquals(1f, BtLightColors.tagFallback.alpha, 0.0f)
    }

    @Test
    fun `the offered palette is unique and all well-formed`() {
        assertEquals(CashTagPalette.size, CashTagPalette.toSet().size)
        CashTagPalette.forEach {
            assertTrue("malformed palette entry $it", Regex("^#[0-9a-f]{6}$").matches(it))
            // Every offered tint must survive its own parser.
            assertEquals(1f, parse(it).alpha, 0.0f)
        }
    }

    // ── Budget bar geometry ───────────────────────────────────────────────────

    @Test
    fun `an untouched budget is empty and a half-spent one is half`() {
        assertEquals(0f, budgetFraction(spent = 0.0, amount = 400.0), 0.0f)
        assertEquals(0.5f, budgetFraction(spent = 200.0, amount = 400.0), 0.0001f)
    }

    @Test
    fun `an exceeded budget clamps to full rather than overflowing the track`() {
        // The FACT of exceeding is carried by colour and the "over" figure; the
        // bar must never draw wider than its own track.
        assertEquals(1f, budgetFraction(spent = 900.0, amount = 400.0), 0.0f)
        assertEquals(1f, budgetFraction(spent = 400.0, amount = 400.0), 0.0f)
    }

    @Test
    fun `a refund-heavy month cannot draw a negative bar`() {
        // Net inflow on a budgeted tag is a real ledger state (refunds), and a
        // negative width would crash the layout.
        assertEquals(0f, budgetFraction(spent = -120.0, amount = 400.0), 0.0f)
    }

    @Test
    fun `a zero or negative target degrades instead of dividing by zero`() {
        assertEquals(0f, budgetFraction(spent = 0.0, amount = 0.0), 0.0f)
        assertEquals(1f, budgetFraction(spent = 10.0, amount = 0.0), 0.0f)
        assertEquals(1f, budgetFraction(spent = 10.0, amount = -5.0), 0.0f)
    }

    @Test
    fun `non-finite money never produces a non-finite width`() {
        listOf(
            Double.NaN to 400.0,
            400.0 to Double.NaN,
            Double.POSITIVE_INFINITY to 400.0,
            400.0 to Double.POSITIVE_INFINITY,
        ).forEach { (spent, amount) ->
            val f = budgetFraction(spent, amount)
            assertTrue("non-finite fraction for $spent/$amount", f.isFinite())
            assertTrue("out of range for $spent/$amount", f in 0f..1f)
        }
    }

    // ── Month wire format ─────────────────────────────────────────────────────

    @Test
    fun `the wire month is zero-padded YYYY-MM`() {
        // The endpoints validate against ^\d{4}-(0[1-9]|1[0-2])$ — an unpadded
        // "2026-8" is a 400, not a lenient parse.
        assertEquals("2026-08", wireMonth(YearMonth.of(2026, 8)))
        assertEquals("2026-01", wireMonth(YearMonth.of(2026, 1)))
        assertEquals("2026-12", wireMonth(YearMonth.of(2026, 12)))
        assertEquals("0999-03", wireMonth(YearMonth.of(999, 3)))
    }

    @Test
    fun `stepping across a year boundary stays well-formed`() {
        val dec = YearMonth.of(2026, 12)
        assertEquals("2027-01", wireMonth(dec.plusMonths(1)))
        assertEquals("2026-11", wireMonth(dec.minusMonths(1)))
        val jan = YearMonth.of(2026, 1)
        assertEquals("2025-12", wireMonth(jan.minusMonths(1)))
    }
}
