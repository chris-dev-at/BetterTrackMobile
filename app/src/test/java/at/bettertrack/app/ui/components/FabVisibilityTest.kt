package at.bettertrack.app.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6 P1-7: the overview FAB permanently covered the allocation legend's value
 * column. It hides while the user scrolls down and returns on the way up — the
 * rule is a pure function so the behaviour is pinned without a device.
 *
 * 2026-08-10: on the portfolio overview the same state now drives a SHRINK
 * rather than a disappearance (owner: *"should it be like that??"*). The state
 * machine below is unchanged and shared; what differs is only how the two states
 * are rendered, which is [btFabIconSize] and the sizes it interpolates between.
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

    // ── The shrink (2026-08-10) ──────────────────────────────────────────────

    @Test
    fun `the mini fab is smaller than the resting one but still a tap target`() {
        assertTrue(BT_FAB_MINI_SIZE < BT_FAB_SIZE)
        // 40dp is the floor: below it this stops being a control and starts
        // being a decoration you have to aim at.
        assertTrue(BT_FAB_MINI_SIZE >= 40.dp)
    }

    @Test
    fun `the icon tracks the container between the two sizes`() {
        assertEquals(24.dp, btFabIconSize(BT_FAB_SIZE))
        assertEquals(20.dp, btFabIconSize(BT_FAB_MINI_SIZE))
        assertEquals(22.dp, btFabIconSize((BT_FAB_SIZE + BT_FAB_MINI_SIZE) / 2))
    }

    @Test
    fun `a size outside the animation's range cannot produce a runaway icon`() {
        assertEquals(24.dp, btFabIconSize(200.dp))
        assertEquals(20.dp, btFabIconSize(0.dp))
    }
}
