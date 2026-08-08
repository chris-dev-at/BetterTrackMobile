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
        listOf(SHEET_DEAD_ZONE, 0.2f, 0.3f, SHEET_NOTCH_START).forEach { t ->
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
    fun `the resistance sits early in the travel, and bites harder`() {
        // The owner moved it (2026-08-09): "way more up" and "stronger". Both
        // halves are pinned, because either one alone is a different gesture —
        // an early notch that stays soft is a bump nobody notices, and a stiff
        // notch left at 0.72 is a wall at the bottom of a pull that was already
        // long. The stage boundary must land in the zone he named.
        assertTrue("close-all must sit in the 0.35..0.45 zone", SHEET_NOTCH_END in 0.35f..0.45f)
        assertTrue("the band must begin before it", SHEET_NOTCH_START < SHEET_NOTCH_END)
        assertTrue("...and clear of the dead zone", SHEET_NOTCH_START > SHEET_DEAD_ZONE)
        assertTrue("the resistance must be a real one", SHEET_NOTCH_RESISTANCE <= 0.5f)
        assertTrue("...but not a wall", SHEET_NOTCH_RESISTANCE > 0f)
    }

    @Test
    fun `there is still a real back-one band to release in`() {
        // Moving the boundary up shortens the first stage. It must not shorten it
        // to nothing: between letting go of the dead zone and reaching the notch
        // there has to be room to mean "back one page" on purpose.
        assertTrue(
            "back-one band is only ${SHEET_NOTCH_END - SHEET_DEAD_ZONE} of the sheet",
            SHEET_NOTCH_END - SHEET_DEAD_ZONE >= 0.25f,
        )
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
