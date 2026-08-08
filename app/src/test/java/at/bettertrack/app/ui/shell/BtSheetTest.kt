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

    // ── The drag ─────────────────────────────────────────────────────────────

    @Test
    fun `a drag moves the sheet by its own fraction of the height`() {
        assertEquals(0.25f, sheetDragTravel(0f, 500f, height), 1e-4f)
        assertEquals(0.5f, sheetDragTravel(0.25f, 500f, height), 1e-4f)
    }

    @Test
    fun `a sheet cannot be pulled up past its rest position`() {
        // There is nothing above a settled sheet but the scrim strip, so an
        // upward drag that has already closed the gap must stop rather than lift
        // the sheet off the top of the screen.
        assertEquals(0f, sheetDragTravel(0f, -500f, height), 1e-4f)
        assertEquals(0f, sheetDragTravel(0.1f, -500f, height), 1e-4f)
    }

    @Test
    fun `one height of travel is the whole dismissal`() {
        assertEquals(1f, sheetDragTravel(0.9f, 5000f, height), 1e-4f)
    }

    @Test
    fun `an unmeasured sheet does not move`() {
        // Before the first layout pass the height is 0. Dividing by it would put
        // the sheet at infinity on the frame before it is first drawn.
        assertEquals(0.3f, sheetDragTravel(0.3f, 500f, 0f), 1e-4f)
    }

    // ── The release ──────────────────────────────────────────────────────────

    @Test
    fun `a short slow pull springs back`() {
        assertFalse(sheetDismissOnRelease(travel = 0.1f, velocityY = 0f, velocityThresholdPx = flick))
    }

    @Test
    fun `a pull past the threshold dismisses`() {
        assertTrue(sheetDismissOnRelease(travel = SHEET_DISMISS_FRACTION, velocityY = 0f, velocityThresholdPx = flick))
        assertTrue(sheetDismissOnRelease(travel = 0.6f, velocityY = 0f, velocityThresholdPx = flick))
    }

    @Test
    fun `a downward flick dismisses whatever distance it covered`() {
        assertTrue(sheetDismissOnRelease(travel = 0.05f, velocityY = flick, velocityThresholdPx = flick))
    }

    @Test
    fun `a flick back UP settles even from past the threshold`() {
        // The same rule the retired tab swipe used, and the one users have in
        // their hands from every other sheet on the phone: a gesture the user
        // changed their mind about must land where they changed it to, not where
        // it had got to. Without the direction check a fast upward flick from
        // half-open would read as "past the distance threshold" and dismiss.
        assertFalse(sheetDismissOnRelease(travel = 0.6f, velocityY = -flick, velocityThresholdPx = flick))
    }

    @Test
    fun `a slow drift back up from past the threshold still dismisses`() {
        // Only a real flick overrides the distance; letting go almost still at
        // 60% down is a dismissal, because that is where the sheet is.
        assertTrue(sheetDismissOnRelease(travel = 0.6f, velocityY = -10f, velocityThresholdPx = flick))
    }

    // ── The dismissal stack ──────────────────────────────────────────────────

    @Test
    fun `back reaches the sheet that is on top`() {
        // Composition order is stack order, so the last registration is the top
        // sheet. This is the whole reason 45 screens kept their `onBack: () ->
        // Unit` signature through the migration.
        val popped = mutableListOf<String>()
        val host = BtSheetHostState { popped += "pop" }
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
        val host = BtSheetHostState { }
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
        val host = BtSheetHostState { }
        host.dismissTop()
        assertEquals(0, host.depth)
    }

    @Test
    fun `a sheet that leaves withdraws its own dismissal`() {
        // The `DisposableEffect` contract. A stale entry left behind would make
        // the next back press ask a dead composition to animate itself away, and
        // the sheet actually on screen would stay put.
        val host = BtSheetHostState { }
        val dismiss = { }
        host.push(dismiss)
        host.remove(dismiss)
        assertEquals(0, host.depth)
    }

    @Test
    fun `the pop is the sheet's to call, not the screen's`() {
        // A screen's `onBack` runs `dismissTop`, which animates; only at the end
        // of that travel does the sheet call `pop`. If a screen could reach `pop`
        // directly the sheet would vanish instead of leaving.
        var pops = 0
        val host = BtSheetHostState { pops++ }
        host.push { /* a sheet that is still animating */ }
        host.dismissTop()
        assertEquals("dismissTop must not pop the graph itself", 0, pops)
        host.pop()
        assertEquals(1, pops)
    }
}
