package at.bettertrack.app.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The corrected sheet gestures (owner, 2026-08-08), pinned on the JVM.
 *
 * The shipped model was rejected in three places and two of them are pure rules,
 * so they are pinned here rather than in a device gesture: what a swipe-down
 * MEANS at each depth, and where the resistance sits.
 */
class BtSheetGesturesTest {

    private val height = 2000f
    private val flick = 420f

    // ── The dead zone: an overscroll jiggle is not a navigation ──────────────

    @Test
    fun `a jiggle inside the dead zone never navigates, at either depth`() {
        listOf(true, false).forEach { stacked ->
            assertEquals(
                "stacked=$stacked",
                SheetRelease.SETTLE,
                sheetRelease(SHEET_DEAD_ZONE - 0.001f, 0f, flick, stacked),
            )
        }
    }

    @Test
    fun `the dead zone beats velocity`() {
        // The whole point: an overscroll bounce arrives as a few pixels of travel
        // WITH a real fling behind it. Distance decides first.
        assertEquals(SheetRelease.SETTLE, sheetRelease(0.02f, flick * 10f, flick, true))
        assertEquals(SheetRelease.SETTLE, sheetRelease(0.02f, flick * 10f, flick, false))
    }

    @Test
    fun `the dead zone is a real fraction of the sheet, not a pixel or two`() {
        assertTrue(SHEET_DEAD_ZONE >= 0.08f && SHEET_DEAD_ZONE <= 0.16f)
    }

    // ── Depth >= 2: release at or before the resistance = back ONE ───────────

    @Test
    fun `releasing anywhere before the resistance goes back one page`() {
        listOf(SHEET_DEAD_ZONE, 0.3f, 0.5f, SHEET_NOTCH_START).forEach { t ->
            assertEquals("travel=$t", SheetRelease.BACK_ONE, sheetRelease(t, 0f, flick, true))
        }
    }

    @Test
    fun `releasing inside the resistance band is still only back one`() {
        // Crossing the band is the price of stage two. Letting go halfway through
        // has not paid it, and must not be charged for it either.
        val middle = (SHEET_NOTCH_START + SHEET_NOTCH_END) / 2f
        assertEquals(SheetRelease.BACK_ONE, sheetRelease(middle, 0f, flick, true))
        assertEquals(SheetRelease.BACK_ONE, sheetRelease(SHEET_NOTCH_END - 0.001f, 0f, flick, true))
    }

    @Test
    fun `dragging past the resistance closes the entire stack`() {
        assertEquals(SheetRelease.CLOSE_ALL, sheetRelease(SHEET_NOTCH_END, 0f, flick, true))
        assertEquals(SheetRelease.CLOSE_ALL, sheetRelease(1f, 0f, flick, true))
    }

    @Test
    fun `the resistance sits before three quarters and close-all beyond it`() {
        // The owner's spec as numbers, so tuning stays inside the shape he asked
        // for: "meets the first resistance before ~3/4", "past it beyond ~3/4".
        assertTrue("resistance must start before 3/4", SHEET_NOTCH_START < 0.75f)
        assertTrue("...but late in the travel", SHEET_NOTCH_START >= 0.65f)
        assertTrue("close-all must be beyond 3/4", SHEET_NOTCH_END >= 0.75f)
        assertTrue(SHEET_NOTCH_START < SHEET_NOTCH_END)
        assertTrue(SHEET_NOTCH_RESISTANCE > 0f && SHEET_NOTCH_RESISTANCE < 1f)
    }

    @Test
    fun `a flick back UP settles from anywhere`() {
        assertEquals(SheetRelease.SETTLE, sheetRelease(0.6f, -flick, flick, true))
        assertEquals(SheetRelease.SETTLE, sheetRelease(0.95f, -flick, flick, true))
        assertEquals(SheetRelease.SETTLE, sheetRelease(0.6f, -flick, flick, false))
    }

    @Test
    fun `a slow drift back up from past the resistance still closes the stack`() {
        assertEquals(SheetRelease.CLOSE_ALL, sheetRelease(0.9f, -10f, flick, true))
    }

    // ── Depth 1: unchanged full pull-down-to-close ───────────────────────────

    @Test
    fun `a depth-1 sheet closes on distance or on a flick`() {
        assertEquals(SheetRelease.SETTLE, sheetRelease(SHEET_CLOSE_FRACTION - 0.01f, 0f, flick, false))
        assertEquals(SheetRelease.BACK_ONE, sheetRelease(SHEET_CLOSE_FRACTION, 0f, flick, false))
        assertEquals(SheetRelease.BACK_ONE, sheetRelease(0.3f, flick, flick, false))
    }

    @Test
    fun `a depth-1 sheet has no close-all, because closing IS closing everything`() {
        listOf(0.2f, 0.5f, 0.9f, 1f).forEach { t ->
            assertFalse(
                "travel=$t must never be CLOSE_ALL at depth 1",
                sheetRelease(t, 0f, flick, false) == SheetRelease.CLOSE_ALL,
            )
        }
    }

    @Test
    fun `the depth-1 commit point is where it always was`() {
        assertEquals(0.50f, SHEET_CLOSE_FRACTION, 1e-6f)
    }
}
