package at.bettertrack.app.ui.cash

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The cash overview's two money buttons, owner order 2026-08-17: *"1. option ist
 * bezahlt und 2. erhalten"* — the same order (and the same words) as the
 * approved Cash-Wallet widget, so the launcher tile and the screen it opens
 * never disagree about which action comes first.
 *
 * A source scan because the order lives in a `Row`'s child order and nothing
 * below the composable decides it: `CASH_ENTRY_KINDS` pins the EDIT sheet's
 * chips (see `CashKindTest`), but these two buttons are their own list. The
 * regression this catches is somebody re-adding "money in first" out of habit —
 * which compiles, reads naturally, and is wrong.
 */
class CashActionOrderTest {

    private fun cashScreen(): String {
        val name = "src/main/java/at/bettertrack/app/ui/cash/CashScreen.kt"
        val candidates = listOf(File(name), File("app/$name"))
        return (
            candidates.firstOrNull { it.isFile }
                ?: error("CashScreen.kt not found; tried ${candidates.map { it.absolutePath }}")
            ).readText()
    }

    /** The `item(key = "actions")` block, by brace matching. */
    private fun actionsBlock(): String {
        val source = cashScreen()
        val start = source.indexOf("item(key = \"actions\")")
        require(start >= 0) { "the actions item is gone from the cash overview" }
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        error("unbalanced braces in the actions item")
    }

    @Test
    fun `paid comes before received`() {
        val block = actionsBlock()
        val paid = block.indexOf("R.string.bt_cash_withdraw")
        val received = block.indexOf("R.string.bt_cash_deposit")
        assertTrue("the paid button is gone from the cash overview", paid >= 0)
        assertTrue("the received button is gone from the cash overview", received >= 0)
        assertTrue(
            "the cash overview must offer paid FIRST, received second (owner 2026-08-17); " +
                "found paid at $paid, received at $received",
            paid < received,
        )
    }

    @Test
    fun `each money button still wears its direction as colour`() {
        val block = actionsBlock()
        // Order changed; the emerald/red pairing did not. Colour is the only
        // thing that keeps the leading red button from reading as destructive.
        val paidCall = block.substringAfter("R.string.bt_cash_withdraw").substringBefore("onClick")
        val receivedCall = block.substringAfter("R.string.bt_cash_deposit").substringBefore("onClick")
        assertTrue("the paid button lost its loss colour: $paidCall", paidCall.contains("bt.loss"))
        assertTrue("the received button lost its gain colour: $receivedCall", receivedCall.contains("bt.gain"))
    }

    @Test
    fun `transfer stays the quiet third action`() {
        val block = actionsBlock()
        val received = block.indexOf("R.string.bt_cash_deposit")
        val transfer = block.indexOf("R.string.bt_cash_transfer")
        assertTrue("the transfer action is gone", transfer >= 0)
        assertTrue("transfer must stay below the two money buttons", received < transfer)
        assertTrue(
            "transfer must stay secondary — a third toned button would make the row a menu",
            block.substringAfter("R.string.bt_cash_transfer").take(200).let { !it.contains("container =") },
        )
    }
}
