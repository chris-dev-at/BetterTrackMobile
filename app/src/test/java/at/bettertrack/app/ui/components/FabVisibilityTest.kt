package at.bettertrack.app.ui.components

import org.junit.Assert.assertEquals
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

    // ── The empty-state rule (Fable design review, 2026-08-04) ──────────────

    @Test
    fun `content keeps the fab`() {
        assertTrue(fabVisibleForList(resolved = true, empty = false))
    }

    @Test
    fun `an empty list stands the fab down so the empty state owns the CTA`() {
        assertFalse(fabVisibleForList(resolved = true, empty = true))
    }

    @Test
    fun `an unresolved screen shows no fab either way`() {
        // The anti-flicker half of the rule: popping a FAB in over a skeleton
        // and pulling it away when the list turns out to be empty is exactly
        // the flash the rule exists to prevent.
        assertFalse(fabVisibleForList(resolved = false, empty = true))
        assertFalse(fabVisibleForList(resolved = false, empty = false))
    }

    @Test
    fun `only CONTENT keeps the fab across every list surface`() {
        // Exhaustive over the enum so a new surface cannot be added without
        // deciding what the FAB does on it.
        BtListSurface.entries.forEach { surface ->
            assertEquals(
                "FAB visibility on $surface",
                surface == BtListSurface.CONTENT,
                fabVisibleForList(surface),
            )
        }
    }

    @Test
    fun `the two overloads agree wherever both apply`() {
        assertEquals(
            fabVisibleForList(BtListSurface.CONTENT),
            fabVisibleForList(resolved = true, empty = false),
        )
        assertEquals(
            fabVisibleForList(BtListSurface.EMPTY),
            fabVisibleForList(resolved = true, empty = true),
        )
        assertEquals(
            fabVisibleForList(BtListSurface.SKELETON),
            fabVisibleForList(resolved = false, empty = false),
        )
    }
}
