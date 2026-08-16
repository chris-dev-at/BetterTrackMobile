package at.bettertrack.app.ui

import at.bettertrack.app.ui.components.BT_DESCRIPTION_MAX_CHARS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The transaction/cash DESCRIPTION, owner 2026-08-17: *"mache notiz wichtiger
 * für transaktionen und nenne es nicht notiz sondern etwas wichtigeres wie
 * beschreibung"*.
 *
 * Three things were asked for and all three are the kind that rot quietly:
 *
 *  1. **The word.** "Notiz"/"Note" is gone from the field everywhere.
 *  2. **The position.** It is no longer the last thing on the transaction form;
 *     it sits in the input flow, before the cards that qualify a trade.
 *  3. **One field, four call sites.** The trade form and all three cash sheets
 *     share [at.bettertrack.app.ui.components.BtDescriptionField], so the label,
 *     the height, the 1000-char clamp and the counter cannot drift apart.
 *
 * The wire field is still `note` and this test says nothing about that — the
 * rename was user-facing copy only, and `ApiOpExecutorTest` / `CashEditLogicTest`
 * remain the guards that the JSON key never moved.
 */
class DescriptionFieldDisciplineTest {

    private fun source(path: String): String {
        val candidates = listOf(File("src/main/java/$path"), File("app/src/main/java/$path"))
        return (
            candidates.firstOrNull { it.isFile }
                ?: error("source not found; tried ${candidates.map { it.absolutePath }}")
            ).readText()
    }

    private fun res(qualifier: String): String {
        val name = "src/main/res/values$qualifier/strings.xml"
        val candidates = listOf(File(name), File("app/$name"))
        return (
            candidates.firstOrNull { it.isFile }
                ?: error("strings.xml not found; tried ${candidates.map { it.absolutePath }}")
            ).readText()
    }

    private val txForm = "at/bettertrack/app/ui/portfolio/TransactionFormScreen.kt"
    private val cash = "at/bettertrack/app/ui/cash/CashScreen.kt"

    @Test
    fun `the field is called Description in both languages`() {
        assertTrue(
            "the EN label is not the owner's word",
            res("").contains("""<string name="bt_txform_description">Description (optional)</string>"""),
        )
        assertTrue(
            "the DE label is not the owner's word",
            res("-de").contains("""<string name="bt_txform_description">Beschreibung (optional)</string>"""),
        )
    }

    @Test
    fun `no user-facing string calls it a note any more`() {
        // The retired key must not come back, in either file, and no string may
        // reintroduce the old word for THIS field. Deliberately narrow: the app
        // is full of legitimate `*_note` footnote keys, which is exactly why the
        // check is on the retired key and on the two words as a field label.
        listOf("" to "EN", "-de" to "DE").forEach { (qualifier, label) ->
            val text = res(qualifier)
            assertTrue(
                "$label: the retired bt_txform_note key is back",
                !text.contains("""name="bt_txform_note""""),
            )
        }
        assertTrue(
            "a DE string still labels the field 'Notiz'",
            !res("-de").contains(">Notiz"),
        )
    }

    @Test
    fun `all four entry points share the one component`() {
        val form = source(txForm)
        val sheets = source(cash)
        assertEquals(
            "the transaction form must host exactly one description field",
            1,
            Regex("BtDescriptionField\\(").findAll(form).count(),
        )
        assertEquals(
            "all three cash sheets (entry, correction, transfer) must host one each",
            3,
            Regex("BtDescriptionField\\(").findAll(sheets).count(),
        )
    }

    @Test
    fun `the cap is the contract's, enforced in one place, and shown`() {
        assertEquals(1000, BT_DESCRIPTION_MAX_CHARS)
        val component = source("at/bettertrack/app/ui/components/BtTextField.kt")
        assertTrue(
            "the component must clamp to the constant",
            component.contains("it.take(BT_DESCRIPTION_MAX_CHARS)"),
        )
        assertTrue(
            "the counter must be shown next to the field",
            component.contains("R.string.bt_txform_description_counter"),
        )
        // The old ad-hoc 900 clamps are gone from both screens: a second clamp
        // is a second cap, and the one that fires first wins silently.
        listOf(txForm to "transaction form", cash to "cash sheets").forEach { (path, label) ->
            assertTrue("$label still carries a hand-rolled 900-char clamp", !source(path).contains("take(900)"))
        }
    }

    @Test
    fun `the description sits in the input flow, not at the end of the form`() {
        val form = source(txForm)
        val field = form.indexOf("BtDescriptionField(")
        val fee = form.indexOf("R.string.bt_txform_fee")
        val coupling = form.indexOf("CashCouplingCard(")
        assertTrue("the description field is gone from the transaction form", field >= 0)
        assertTrue(
            "the description must follow the economic fields (fee at $fee, field at $field)",
            fee in 0 until field,
        )
        assertTrue(
            "the description must come BEFORE the cash-coupling card, not after it — " +
                "being last is the position the owner rejected (field at $field, card at $coupling)",
            field < coupling,
        )
    }

    /**
     * The rows the description now appears on. It was write-only before: the app
     * asked for it on every entry and never showed it again, which is the whole
     * reason it read as an afterthought.
     */
    @Test
    fun `both money rows render the description, stripped and ellipsized`() {
        listOf(
            "at/bettertrack/app/ui/portfolio/TransactionsScreen.kt" to "displayNote(tx.note)",
            cash to "displayNote(movement.note)",
        ).forEach { (path, call) ->
            val row = source(path)
            assertTrue("$path no longer renders the description ($call)", row.contains(call))
            // `?.let` and not `orEmpty()`: an absent description must render
            // NOTHING, never a blank line that makes the list ragged.
            assertTrue("$path must skip the line entirely when there is none", row.contains("$call?.let"))
        }
    }
}
