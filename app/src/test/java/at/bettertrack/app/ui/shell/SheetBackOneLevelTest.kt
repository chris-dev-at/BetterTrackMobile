package at.bettertrack.app.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * One back is one level, in the sheet layer (owner device pass 2026-09-01, #5).
 *
 * *"One Back collapses two stacked sheet levels: Portfolio → Bargeld →
 * Transaktionen, a single Back returns to the portfolio detail, skipping
 * Bargeld. Reproduced twice."*
 *
 * Two mechanisms could produce that, and both are closed here.
 *
 *  1. **The unclaimed window.** The layer's handler used to be `enabled = depth
 *     > 0 && !leaving`, so for the ~260ms a dismissal takes to play it claimed
 *     nothing. Sitting directly beneath it in the dispatcher is the
 *     `NavController`'s own callback, enabled the whole time — so a second back
 *     event inside that window (a bouncy edge swipe, an impatient second press)
 *     went straight to the graph and popped the NEXT entry with no animation.
 *     Two levels, the second one instantly, which reads as one back eating both.
 *  2. **The unaddressed pop.** `popBackStack()` deletes whatever is on top, and
 *     the sheet's pop is a promise made 260ms before it is kept. If anything
 *     else pops in between, the promise lands on an innocent page.
 *
 * Both rules are pure, so they are pinned here; the source assertions at the end
 * hold the layer to actually consulting them.
 */
class SheetBackOneLevelTest {

    @Test
    fun `at the floor the sheet layer does not claim back`() {
        // The shell's tab handler and then the system own it there.
        assertEquals(SheetBack.NOT_OURS, sheetBackAction(depth = 0, leaving = false))
        assertEquals(SheetBack.NOT_OURS, sheetBackAction(depth = 0, leaving = true))
    }

    @Test
    fun `a settled sheet takes back and pops one`() {
        assertEquals(SheetBack.TAKE, sheetBackAction(depth = 1, leaving = false))
        assertEquals(SheetBack.TAKE, sheetBackAction(depth = 2, leaving = false))
        assertEquals(SheetBack.TAKE, sheetBackAction(depth = 7, leaving = false))
    }

    /**
     * The fix for #5: a press arriving mid-dismissal is CONSUMED and dropped.
     *
     * Consumed matters as much as dropped. Returning "not ours" would hand the
     * very same event to the NavController underneath, which is the defect —
     * the point is that the sheet layer answers it and does nothing.
     */
    @Test
    fun `a back arriving mid-dismissal is swallowed, at every depth`() {
        (1..4).forEach { depth ->
            assertEquals(
                "depth=$depth: a second back during the exit animation must not reach " +
                    "the graph — see this test's KDoc.",
                SheetBack.SWALLOW,
                sheetBackAction(depth, leaving = true),
            )
        }
    }

    @Test
    fun `the layer claims back for the whole of a dismissal`() {
        // Restated as the property the `enabled` expression has to have: while
        // anything is stacked, there is NO value of `leaving` for which the
        // sheet layer declines the event.
        listOf(true, false).forEach { leaving ->
            assertTrue(
                "leaving=$leaving",
                sheetBackAction(depth = 2, leaving = leaving) != SheetBack.NOT_OURS,
            )
        }
    }

    @Test
    fun `a dismissal pops the page it was started for`() {
        assertTrue(sheetPopIsAddressed(startedOn = "entry-a", topNow = "entry-a"))
    }

    @Test
    fun `a dismissal whose page already left pops nothing`() {
        // Somebody else got there first. Popping now would take the parent.
        assertFalse(sheetPopIsAddressed(startedOn = "entry-a", topNow = "entry-b"))
        assertFalse(sheetPopIsAddressed(startedOn = "entry-a", topNow = null))
    }

    @Test
    fun `an unaddressed dismissal is refused rather than aimed at the top`() {
        // No key at all means the layer could not name what it was dismissing,
        // and "whatever is on top" is precisely the behaviour being removed.
        assertFalse(sheetPopIsAddressed(startedOn = null, topNow = "entry-a"))
        assertFalse(sheetPopIsAddressed(startedOn = null, topNow = null))
    }

    // ── The wiring ──────────────────────────────────────────────────────────

    private fun shellSource(name: String): String {
        val roots = listOf(
            File("src/main/java/at/bettertrack/app/ui/shell"),
            File("app/src/main/java/at/bettertrack/app/ui/shell"),
        )
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("shell sources not found; tried ${roots.map { it.absolutePath }}")
        return File(root, name).readText()
    }

    @Test
    fun `the sheet layer claims back on depth alone and routes it through the rule`() {
        val stack = shellSource("BtSheetStack.kt")
        assertTrue(
            "BtSheetStack's PredictiveBackHandler is gated on `!leaving` again. That is " +
                "the unclaimed window the NavController's own callback pops into — see " +
                "this test's KDoc, and owner device pass #5.",
            !stack.contains("enabled = depth > 0 && !leaving"),
        )
        assertTrue(
            "BtSheetStack no longer enables its PredictiveBackHandler on depth alone.",
            stack.contains("PredictiveBackHandler(enabled = depth > 0)"),
        )
        assertTrue(
            "BtSheetStack no longer decides what a back press means through " +
                "[sheetBackAction]; the rule this file pins is not the rule it runs.",
            stack.contains("sheetBackAction(depth, leaving)"),
        )
    }

    @Test
    fun `the sheet layer's pop is addressed to the page that started it`() {
        val stack = shellSource("BtSheetStack.kt")
        assertTrue(
            "BtSheetStack pops unconditionally at the end of the dismissal animation " +
                "again. A pop by position, promised 260ms in advance, is how a page that " +
                "nobody dismissed gets deleted — it must go through [sheetPopIsAddressed].",
            stack.contains("if (sheetPopIsAddressed(startedOn, topKeyNow.value)) hostNow.pop()"),
        )
    }
}
