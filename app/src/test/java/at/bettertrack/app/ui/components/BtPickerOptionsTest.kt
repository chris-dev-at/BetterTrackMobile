package at.bettertrack.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [btPickerOptionsWithSelected] — the one piece of the picker that is logic
 * rather than layout.
 *
 * The rule it encodes: a "closed" choice set is only closed as of THIS build. A
 * server that grows a currency, or an account configured on a newer platform,
 * would otherwise open a picker in which nothing is ticked — which says "you
 * have not chosen" about a setting the user demonstrably has. The synthesised
 * entry is the difference between "your value is X, and I don't have a nicer
 * name for it" and silence.
 */
class BtPickerOptionsTest {

    private val currencies = listOf(
        BtPickerOption("EUR", "EUR"),
        BtPickerOption("USD", "USD"),
        BtPickerOption("CHF", "CHF"),
        BtPickerOption("GBP", "GBP"),
    )

    @Test
    fun `a known selection leaves the list untouched`() {
        val out = btPickerOptionsWithSelected(currencies, "USD")
        // Identity, not just equality: the common path must not rebuild the list.
        assertSame(currencies, out)
    }

    @Test
    fun `no selection leaves the list untouched`() {
        assertSame(currencies, btPickerOptionsWithSelected(currencies, null))
        assertSame(currencies, btPickerOptionsWithSelected(currencies, ""))
        assertSame(currencies, btPickerOptionsWithSelected(currencies, "   "))
    }

    @Test
    fun `an unknown selection is appended so it can be shown as current`() {
        val out = btPickerOptionsWithSelected(currencies, "JPY")
        assertEquals(currencies.size + 1, out.size)
        assertEquals(BtPickerOption("JPY", "JPY"), out.last())
        // Appended, never inserted — the known options keep their contract order.
        assertEquals(currencies, out.dropLast(1))
    }

    @Test
    fun `the synthesised entry labels itself with the wire value`() {
        // There is nothing else to call it: the label would otherwise be a blank
        // row that ticks itself, which is worse than the raw token.
        val out = btPickerOptionsWithSelected(currencies, "XAU")
        assertEquals("XAU", out.last().value)
        assertEquals("XAU", out.last().label)
        assertEquals(null, out.last().supporting)
    }

    @Test
    fun `whitespace around a selection is not a different value`() {
        // A trimmed value that IS known must not produce a duplicate row.
        assertSame(currencies, btPickerOptionsWithSelected(currencies, " EUR "))
        // …and a trimmed value that is not known is added in its trimmed form.
        assertEquals("JPY", btPickerOptionsWithSelected(currencies, " JPY ").last().value)
    }

    @Test
    fun `an empty option list still shows the current value`() {
        val out = btPickerOptionsWithSelected(emptyList(), "EUR")
        assertEquals(listOf(BtPickerOption("EUR", "EUR")), out)
    }

    @Test
    fun `matching is on the wire value, not the label`() {
        val options = listOf(BtPickerOption(value = "System", label = "Follow system"))
        assertSame(options, btPickerOptionsWithSelected(options, "System"))
        // The user-facing label is not an identifier and must never match.
        assertTrue(btPickerOptionsWithSelected(options, "Follow system").size == 2)
    }
}
