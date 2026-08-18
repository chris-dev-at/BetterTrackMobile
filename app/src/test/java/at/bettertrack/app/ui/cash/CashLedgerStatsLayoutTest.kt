package at.bettertrack.app.ui.cash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The ledger stats card's restyle (owner 2026-08-17): *"make the 'for this
 * selection' stand out a bit more. like move the xx movements text below it and
 * remove the for this selection up top and just put on the bottom corner
 * selected or something."*
 *
 * A source scan for the same reason [CashActionOrderTest] is one: the order of
 * a `Column`'s children is not a value any function returns, and re-adding a
 * heading above the figure compiles and reads perfectly naturally.
 */
class CashLedgerStatsLayoutTest {

    private fun ledgerScreen(): String {
        val name = "src/main/java/at/bettertrack/app/ui/cash/CashLedgerScreen.kt"
        val candidates = listOf(File(name), File("app/$name"))
        return (
            candidates.firstOrNull { it.isFile }
                ?: error("CashLedgerScreen.kt not found; tried ${candidates.map { it.absolutePath }}")
            ).readText()
    }

    /** The `CashLedgerStatsCard` composable body, by brace matching. */
    private fun statsCard(): String {
        val source = ledgerScreen()
        val start = source.indexOf("private fun CashLedgerStatsCard(")
        require(start >= 0) { "CashLedgerStatsCard is gone from the ledger subpage" }
        val open = source.indexOf(") {", start) + 2
        require(open > 2) { "CashLedgerStatsCard has no body" }
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        error("unbalanced braces in CashLedgerStatsCard")
    }

    @Test
    fun `the card spends no line on a heading`() {
        assertFalse(
            "the \"For this selection\" heading is back at the top of the stats card — the owner " +
                "removed it on 2026-08-17 so the figures could lead",
            statsCard().contains("R.string.bt_ledger_stats_title"),
        )
    }

    @Test
    fun `the net figure leads and carries the weight`() {
        val card = statsCard()
        val net = card.indexOf("R.string.bt_ledger_stats_net")
        val inCell = card.indexOf("R.string.bt_ledger_stats_in")
        assertTrue("the net figure is gone from the stats card", net >= 0)
        assertTrue("the in/out cells are gone from the stats card", inCell >= 0)
        assertTrue("net must be the card's first figure, above in/out", net < inCell)
        assertTrue(
            "the lead figure lost its hero weight — moneyLarge is what the removed heading paid for",
            card.substringAfter("R.string.bt_ledger_stats_net").take(400).contains("moneyLarge"),
        )
    }

    @Test
    fun `the movement count sits below the figures`() {
        val card = statsCard()
        val out = card.indexOf("R.string.bt_ledger_stats_out")
        val count = card.indexOf("R.plurals.bt_cash_summary_movements")
        assertTrue("the movement count is gone from the stats card", count >= 0)
        assertTrue(
            "the count is back above/beside the figures; the owner moved it BELOW them",
            out < count,
        )
    }

    @Test
    fun `the scope marker sits in the bottom corner`() {
        val card = statsCard()
        val marker = card.indexOf("R.string.bt_ledger_stats_scope")
        assertTrue("the card lost its scope marker — it must stay honest about what it counts", marker >= 0)
        // Last of the card's own resource references: nothing may be added under it.
        val net = card.indexOf("R.string.bt_ledger_stats_net")
        val export = card.indexOf("R.string.bt_ledger_export_title")
        assertTrue("the export action is gone from the stats card", export >= 0)
        assertTrue("the scope marker must come after everything else in the card", marker > net)
        assertTrue("the scope marker belongs in the bottom row, after the export action", marker > export)
    }

    @Test
    fun `the reset affordance survived the restyle`() {
        val card = statsCard()
        assertTrue(
            "the way out of a narrowed view vanished with the heading it used to sit next to",
            card.contains("R.string.bt_ledger_reset_filters"),
        )
        assertTrue(
            "the reset must stay conditional on an actually narrowed selection",
            card.contains("if (narrowed)"),
        )
    }
}
