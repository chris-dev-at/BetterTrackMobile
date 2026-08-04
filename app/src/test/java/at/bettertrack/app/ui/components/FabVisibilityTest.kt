package at.bettertrack.app.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6 P1-7: the overview FAB permanently covered the allocation legend's value
 * column. It now hides while the user scrolls down and returns on the way up —
 * the rule is a pure function so the behaviour is pinned without a device.
 */
class FabVisibilityTest {

    @Test
    fun `scrolling down hides the fab`() {
        assertFalse(nextFabVisible(current = true, deltaY = -40f))
    }

    @Test
    fun `scrolling up shows it again`() {
        assertTrue(nextFabVisible(current = false, deltaY = 40f))
    }

    @Test
    fun `jitter inside the dead band never flips the state`() {
        // A fling's tail and a fingertip's wobble both land here; either edge of
        // the band must leave whatever state the FAB was already in untouched.
        assertTrue(nextFabVisible(current = true, deltaY = 0f))
        assertTrue(nextFabVisible(current = true, deltaY = -3f))
        assertTrue(nextFabVisible(current = true, deltaY = 3f))
        assertFalse(nextFabVisible(current = false, deltaY = 0f))
        assertFalse(nextFabVisible(current = false, deltaY = -3f))
        assertFalse(nextFabVisible(current = false, deltaY = 3f))
    }

    @Test
    fun `the rule is idempotent in the direction it already resolved`() {
        assertFalse(nextFabVisible(current = false, deltaY = -80f))
        assertTrue(nextFabVisible(current = true, deltaY = 80f))
    }

    @Test
    fun `a custom threshold moves the dead band`() {
        assertTrue(nextFabVisible(current = true, deltaY = -20f, threshold = 50f))
        assertFalse(nextFabVisible(current = true, deltaY = -60f, threshold = 50f))
    }

    @Test
    fun `one downward gesture then back up round trips`() {
        var visible = true
        listOf(-12f, -30f, -25f).forEach { visible = nextFabVisible(visible, it) }
        assertFalse(visible)
        listOf(18f, 22f).forEach { visible = nextFabVisible(visible, it) }
        assertTrue(visible)
    }
}
