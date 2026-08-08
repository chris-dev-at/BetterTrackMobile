package at.bettertrack.app.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dismissal bridge: how 45 screens' unchanged `onBack: () -> Unit` reaches a
 * sheet that has an animation to play before the graph may forget it.
 */
class BtSheetHostTest {

    @Test
    fun `back reaches the layer that is on top`() {
        val calls = mutableListOf<String>()
        val host = BtSheetHostState(pop = { calls += "pop" })
        val lower = { calls += "lower"; Unit }
        val upper = { calls += "upper"; Unit }
        host.push(lower)
        host.push(upper)
        host.dismissTop()
        assertEquals(listOf("upper"), calls)
    }

    @Test
    fun `a layer that leaves withdraws its own dismissal`() {
        // The DisposableEffect contract. A stale entry would ask a dead
        // composition to animate itself away, and nothing would move.
        val host = BtSheetHostState(pop = { })
        val dismiss = { }
        host.push(dismiss)
        assertEquals(1, host.depth)
        host.remove(dismiss)
        assertEquals(0, host.depth)
    }

    @Test
    fun `back with nothing open is a no-op, not a crash`() {
        // At the graph's floor, back is the shell's business (return to the first
        // tab, then exit). This must not be in the way of it.
        val host = BtSheetHostState(pop = { })
        host.dismissTop()
        assertEquals(0, host.depth)
    }

    @Test
    fun `the pop is the layer's to call, not the screen's`() {
        // If a screen could reach `pop` directly the sheet would vanish instead of
        // leaving: popBackStack deletes the destination on the spot.
        var pops = 0
        val host = BtSheetHostState(pop = { pops++ })
        host.push { /* a layer that is still animating */ }
        host.dismissTop()
        assertEquals("dismissTop must not pop the graph itself", 0, pops)
        host.pop()
        assertEquals(1, pops)
    }

    @Test
    fun `close-all is a different question for the graph than back`() {
        var pops = 0
        var popAlls = 0
        val host = BtSheetHostState(pop = { pops++ }, popAll = { popAlls++ })
        host.popAll()
        assertEquals(0, pops)
        assertEquals(1, popAlls)
    }

    @Test
    fun `a host wired only for single-level behaviour degrades to pop`() {
        var pops = 0
        val host = BtSheetHostState(pop = { pops++ })
        host.popAll()
        assertEquals("popAll must not silently do nothing", 1, pops)
    }
}
