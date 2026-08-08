package at.bettertrack.app.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The full-screen-sheet architecture (owner order 2026-08-08), pinned on the JVM.
 *
 * Owner verbatim: *"every subpage behaves like a popup that comes from the bottom
 * up and can be swiped down."* The two rules that decide whether that feels right
 * — how far a drag moves the sheet, and what letting go means — are pure, so they
 * are pinned here rather than in a device gesture. The dismissal stack is pinned
 * too, because it is what makes 45 screens' unchanged `onBack: () -> Unit` reach
 * the sheet that is actually on top.
 */
class BtSheetTest {

    private val height = 2000f
    private val flick = 420f

    // ── The drag: three bands, one notch ─────────────────────────────────────

    @Test
    fun `below the notch the sheet tracks the finger exactly`() {
        assertEquals(0.25f, sheetDragTravel(0f, 500f, height), 1e-4f)
        assertEquals(0.5f, sheetDragTravel(0.25f, 500f, height), 1e-4f)
    }

    @Test
    fun `the notch stiffens the drag`() {
        // The boundary has to be FELT — it is the difference between going back
        // one page and closing the whole stack. Inside the band the same finger
        // buys strictly less travel than it did just above it.
        val below = sheetDragTravel(SHEET_NOTCH_START - 0.2f, 200f, height) -
            (SHEET_NOTCH_START - 0.2f)
        val inside = sheetDragTravel(SHEET_NOTCH_START + 0.02f, 200f, height) -
            (SHEET_NOTCH_START + 0.02f)
        assertTrue("the notch must cost more finger", inside < below)
        assertEquals(below * SHEET_NOTCH_RESISTANCE, inside, 1e-3f)
    }

    @Test
    fun `past the notch the sheet tracks the finger again`() {
        // A user who has decided to close the stack must not have to fight the
        // phone the rest of the way down.
        val step = sheetDragTravel(SHEET_NOTCH_END + 0.05f, 200f, height) -
            (SHEET_NOTCH_END + 0.05f)
        assertEquals(200f / height, step, 1e-3f)
    }

    @Test
    fun `the notch is crossable and the sheet still reaches the bottom`() {
        var travel = 0f
        repeat(40) { travel = sheetDragTravel(travel, 200f, height) }
        assertEquals("a long pull must still bottom out", 1f, travel, 1e-4f)
    }

    @Test
    fun `the drag retraces itself on the way back up`() {
        // The mapping carries the FINGER's distance, not the sheet's position, so
        // the notch is a detent in both directions rather than a one-way ratchet.
        val down = sheetDragTravel(0f, 1600f, height)
        assertTrue("must be inside or past the notch", down > SHEET_NOTCH_START)
        assertEquals(0f, sheetDragTravel(down, -1600f, height), 1e-3f)
    }

    @Test
    fun `travel and pull are exact inverses`() {
        listOf(0f, 0.2f, SHEET_NOTCH_START, 0.6f, SHEET_NOTCH_END, 0.9f, 1f).forEach { t ->
            assertEquals(t, sheetTravelFor(sheetPullFor(t)), 1e-4f)
        }
    }

    @Test
    fun `a sheet cannot be pulled up past its rest position`() {
        assertEquals(0f, sheetDragTravel(0f, -500f, height), 1e-4f)
        assertEquals(0f, sheetDragTravel(0.1f, -500f, height), 1e-4f)
    }

    @Test
    fun `an unmeasured sheet does not move`() {
        assertEquals(0.3f, sheetDragTravel(0.3f, 500f, 0f), 1e-4f)
    }

    // ── The release: settle, back one, close all ─────────────────────────────

    @Test
    fun `a pull short of the notch springs back`() {
        assertEquals(
            SheetRelease.SETTLE,
            sheetRelease(SHEET_NOTCH_START - 0.01f, 0f, flick),
        )
        assertEquals(SheetRelease.SETTLE, sheetRelease(0.1f, 0f, flick))
    }

    @Test
    fun `releasing inside the notch goes back one level`() {
        assertEquals(SheetRelease.BACK_ONE, sheetRelease(SHEET_NOTCH_START, 0f, flick))
        assertEquals(
            SheetRelease.BACK_ONE,
            sheetRelease(SHEET_NOTCH_END - 0.01f, 0f, flick),
        )
    }

    @Test
    fun `releasing past the notch closes the whole stack`() {
        assertEquals(SheetRelease.CLOSE_ALL, sheetRelease(SHEET_NOTCH_END, 0f, flick))
        assertEquals(SheetRelease.CLOSE_ALL, sheetRelease(1f, 0f, flick))
    }

    @Test
    fun `a downward flick buys stage one and never the whole stack`() {
        // Closing everything is the destructive end of this gesture, and the spec
        // makes overcoming the notch the price of it. A flick pays no such price.
        assertEquals(SheetRelease.BACK_ONE, sheetRelease(0.05f, flick, flick))
        assertEquals(SheetRelease.BACK_ONE, sheetRelease(0.4f, flick * 10f, flick))
    }

    @Test
    fun `a flick back UP settles from anywhere`() {
        assertEquals(SheetRelease.SETTLE, sheetRelease(0.6f, -flick, flick))
        assertEquals(SheetRelease.SETTLE, sheetRelease(0.95f, -flick, flick))
    }

    @Test
    fun `a slow drift back up from past the notch still closes the stack`() {
        // Only a real flick overrides the distance; letting go almost still at
        // 90% down is where the sheet is, and that is what it means.
        assertEquals(SheetRelease.CLOSE_ALL, sheetRelease(0.9f, -10f, flick))
    }

    @Test
    fun `the notch sits between a half and three quarters of the travel`() {
        // The owner's spec, as a number. Pinned so tuning stays inside the shape
        // he asked for rather than drifting out of it.
        assertTrue(SHEET_NOTCH_START >= 0.45f && SHEET_NOTCH_START <= 0.55f)
        assertTrue(SHEET_NOTCH_END >= 0.70f && SHEET_NOTCH_END <= 0.80f)
        assertTrue(SHEET_NOTCH_START < SHEET_NOTCH_END)
        assertTrue(SHEET_NOTCH_RESISTANCE > 0f && SHEET_NOTCH_RESISTANCE < 1f)
    }

    @Test
    fun `the notch stays reachable by a real thumb`() {
        // The bug the device pass found: resistance is a budget, not a feeling.
        // Crossing the band costs (END - START) / RESISTANCE of sheet height in
        // FINGER travel, and at the first value that came to 0.98 of a sheet —
        // longer than the sheet is tall, so stage two could not be reached at all.
        // Whatever these are tuned to, the whole journey has to fit in a stroke.
        val fingerToCloseAll = sheetPullFor(SHEET_NOTCH_END)
        assertTrue(
            "reaching close-all costs $fingerToCloseAll sheet-heights of finger, " +
                "which no thumb can travel",
            fingerToCloseAll <= 0.85f,
        )
        assertTrue("...but it must still be a deliberate pull", fingerToCloseAll >= 0.6f)
    }

    // ── The visible half of the notch ────────────────────────────────────────

    @Test
    fun `the grabber reports the stage the release rule would take`() {
        // The pill is the only always-visible chrome, so it is where the notch is
        // drawn. It must never disagree with what letting go would actually do.
        listOf(0f, 0.3f, SHEET_NOTCH_START, 0.6f, SHEET_NOTCH_END, 0.9f, 1f).forEach { t ->
            val expected = when (sheetRelease(t, 0f, flick)) {
                SheetRelease.SETTLE -> SheetStage.IDLE
                SheetRelease.BACK_ONE -> SheetStage.BACK
                SheetRelease.CLOSE_ALL -> SheetStage.CLOSE_ALL
            }
            assertEquals("stage must match the release rule at travel=$t", expected, sheetStageOf(t))
        }
    }

    // ── The depth axis: a rightward swipe walks back out ─────────────────────

    @Test
    fun `a short rightward swipe springs back`() {
        assertFalse(
            sheetBackSwipeCommits(SHEET_BACK_SWIPE_FRACTION - 0.01f, 0f, flick),
        )
    }

    @Test
    fun `a rightward swipe past the threshold goes back one`() {
        assertTrue(sheetBackSwipeCommits(SHEET_BACK_SWIPE_FRACTION, 0f, flick))
        assertTrue(sheetBackSwipeCommits(0.8f, 0f, flick))
    }

    @Test
    fun `a rightward flick goes back from anywhere`() {
        assertTrue(sheetBackSwipeCommits(0.02f, flick, flick))
    }

    @Test
    fun `a flick back to the LEFT cancels`() {
        assertFalse(sheetBackSwipeCommits(0.8f, -flick, flick))
    }

    // ── The dismissal stack ──────────────────────────────────────────────────

    @Test
    fun `back reaches the sheet that is on top`() {
        // Composition order is stack order, so the last registration is the top
        // sheet. This is the whole reason 45 screens kept their `onBack: () ->
        // Unit` signature through the migration.
        val popped = mutableListOf<String>()
        val host = BtSheetHostState(pop = { popped += "pop" })
        val lower = { popped += "lower"; Unit }
        val upper = { popped += "upper"; Unit }
        host.push(lower)
        host.push(upper)
        host.dismissTop()
        assertEquals(listOf("upper"), popped)
    }

    @Test
    fun `dismissing the top sheet uncovers the one beneath it`() {
        val popped = mutableListOf<String>()
        val host = BtSheetHostState(pop = { })
        val lower = { popped += "lower"; Unit }
        val upper = { popped += "upper"; Unit }
        host.push(lower)
        host.push(upper)
        assertEquals(2, host.depth)
        host.remove(upper)
        assertEquals(1, host.depth)
        host.dismissTop()
        assertEquals(listOf("lower"), popped)
    }

    @Test
    fun `back with no sheet open is a no-op, not a crash`() {
        // The graph's floor draws nothing and has nothing to dismiss. System back
        // there is the shell's business (return to the first tab, then exit), and
        // this must not be in the way of it.
        val host = BtSheetHostState(pop = { })
        host.dismissTop()
        assertEquals(0, host.depth)
    }

    @Test
    fun `a sheet that leaves withdraws its own dismissal`() {
        // The `DisposableEffect` contract. A stale entry left behind would make
        // the next back press ask a dead composition to animate itself away, and
        // the sheet actually on screen would stay put.
        val host = BtSheetHostState(pop = { })
        val dismiss = { }
        host.push(dismiss)
        host.remove(dismiss)
        assertEquals(0, host.depth)
    }

    @Test
    fun `stacked-ness comes from the graph, not from what is composed`() {
        // The distinction that decides whether a swipe down CLOSES a subpage or
        // goes back one level, and the reason it cannot be `depth`.
        //
        // A covered sheet is dropped from composition when its exit transition
        // ends, so `depth` stops counting it seconds after the upper sheet
        // arrives — and after a rotation (this activity locks no orientation and
        // handles no configChanges, so it IS recreated) the covered sheet is
        // restored to the back stack without ever being composed. Reading the
        // graph is what survives both.
        val onStack = BtSheetHostState(pop = { }, sheetBelow = { true })
        assertTrue("a sub-subpage has a sheet under it", onStack.hasSheetBelow())
        assertEquals("...even with nothing composed", 0, onStack.depth)

        val root = BtSheetHostState(pop = { }, sheetBelow = { false })
        assertFalse("a root sheet has none", root.hasSheetBelow())
    }

    @Test
    fun `a host with no graph wired reports no sheet below`() {
        // The default. "No idea" must read as a ROOT sheet, because that is
        // today's behaviour and the conservative answer.
        assertFalse(BtSheetHostState(pop = { }).hasSheetBelow())
    }

    @Test
    fun `the pop is the sheet's to call, not the screen's`() {
        // A screen's `onBack` runs `dismissTop`, which animates; only at the end
        // of that travel does the sheet call `pop`. If a screen could reach `pop`
        // directly the sheet would vanish instead of leaving.
        var pops = 0
        val host = BtSheetHostState(pop = { pops++ })
        host.push { /* a sheet that is still animating */ }
        host.dismissTop()
        assertEquals("dismissTop must not pop the graph itself", 0, pops)
        host.pop()
        assertEquals(1, pops)
    }
}
