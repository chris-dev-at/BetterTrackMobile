package at.bettertrack.app.ui.portfolio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MB-04 wiring guard. [heldQuantityForEdit] is only a fix where the form reads
 * the holding: the uncovered-sell state, the oversell validation, the Max chip's
 * availability and the Max fill each looked the held quantity up on their own,
 * so a lookup that bypasses the helper brings the double-counted edit back for
 * that one surface. Every held-quantity lookup in the form must go through it,
 * and the edit's original trade must be recorded where a synced edit loads.
 */
class TransactionFormHeldWiringTest {

    private val source: String by lazy {
        val path = "at/bettertrack/app/ui/portfolio/TransactionFormScreen.kt"
        val candidates = listOf(File("src/main/java/$path"), File("app/src/main/java/$path"))
        (candidates.firstOrNull { it.isFile } ?: error("source not found; tried ${candidates.map { it.absolutePath }}"))
            .readText()
    }

    private val lookup = Regex("""\.firstOrNull \{ it\.assetId == \w+\.assetId \}\?\.quantity""")

    @Test
    fun `every held-quantity lookup in the form goes through heldQuantityForEdit`() {
        val lines = source.lines().filter { lookup.containsMatchIn(it) }
        // Precision: the scan must still be finding the four lookups it guards.
        assertTrue("expected the 4 held-quantity lookups, found ${lines.size}", lines.size >= 4)
        val bare = lines.filterNot { it.contains("heldQuantityForEdit(") }
        assertTrue("held-quantity lookups bypassing heldQuantityForEdit:\n${bare.joinToString("\n")}", bare.isEmpty())
    }

    @Test
    fun `a synced edit records the trade it replaces and a queued edit does not`() {
        assertTrue(
            "the EditSynced load no longer records the original trade",
            Regex("""_editedTrade\.value = EditedTrade\(\s*tx\.assetId""").containsMatchIn(source),
        )
        // A queued op never reached the holding, so nothing may be added back.
        val queuedBranch = source.substringAfter("mode = FormMode.EditQueued(").substringBefore("txId != null ->")
        assertTrue("a queued edit must not adjust the holding", !queuedBranch.contains("_editedTrade"))
    }
}
