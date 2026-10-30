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
 *
 * Since the owner's 2026-10-29 order (close everything with about half the
 * drag) the thresholds are resolved per sheet by [sheetDragModel]; these rules
 * run against the reference phone sheet, and [BtSheetDragTuningTest] runs the
 * order's own numbers on both devices.
 */
class BtSheetGesturesTest {

    private val model = SheetDragDevices.phone
    private val flick = model.flingPx

    // ── The dead zone: an overscroll jiggle is not a navigation ──────────────

    @Test
    fun `a jiggle inside the dead zone never navigates, at either depth`() {
        listOf(true, false).forEach { stacked ->
            assertEquals(
                "stacked=$stacked",
                SheetRelease.SETTLE,
                sheetRelease(model.backOne - 0.001f, 0f, stacked, model),
            )
        }
    }

    @Test
    fun `the dead zone beats velocity`() {
        // The whole point: an overscroll bounce arrives as a few pixels of travel
        // WITH a real fling behind it. Distance decides first.
        assertEquals(SheetRelease.SETTLE, sheetRelease(0.02f, flick * 10f, true, model))
        assertEquals(SheetRelease.SETTLE, sheetRelease(0.02f, flick * 10f, false, model))
    }

    @Test
    fun `the dead zone is a real fraction of the sheet, not a pixel or two`() {
        val deadZone = BtSheetDragTuning.BACK_ONE_FRACTION
        assertTrue(deadZone >= 0.08f && deadZone <= 0.16f)
    }

    // ── Depth >= 2: release at or before the resistance = back ONE ───────────

    @Test
    fun `releasing anywhere before the resistance goes back one page`() {
        // Order 2026-10-29: the resistance now begins 40dp past the one-level
        // point, so the sample points are the model's own, not 0.2/0.3 of the
        // sheet (those now sit past close-all).
        val between = (model.backOne + model.resistOnset) / 2f
        listOf(model.backOne, between, model.resistOnset).forEach { t ->
            assertEquals("travel=$t", SheetRelease.BACK_ONE, sheetRelease(t, 0f, true, model))
        }
    }

    @Test
    fun `releasing inside the resistance band is still only back one`() {
        // Crossing the band is the price of stage two. Letting go halfway through
        // has not paid it, and must not be charged for it either.
        val middle = (model.resistOnset + model.closeAll) / 2f
        assertEquals(SheetRelease.BACK_ONE, sheetRelease(middle, 0f, true, model))
        assertEquals(SheetRelease.BACK_ONE, sheetRelease(model.closeAll - 0.001f, 0f, true, model))
    }

    @Test
    fun `dragging past the resistance closes the entire stack`() {
        assertEquals(SheetRelease.CLOSE_ALL, sheetRelease(model.closeAll, 0f, true, model))
        assertEquals(SheetRelease.CLOSE_ALL, sheetRelease(1f, 0f, true, model))
    }

    @Test
    fun `the resistance sits between the stages, and gives more than it takes`() {
        // Was "a quarter down" (owner 2026-08-09, second pass: "1/4 below the top
        // and not at half way"). The order of 2026-10-29 halves the close-all
        // distance, which lands it BEFORE a quarter, so the onset now rides inside
        // the band. Still pinned: it begins after the one-level point, ends at
        // close-all, and the sheet keeps at least half the finger in it.
        assertTrue("the band must begin before it ends", model.resistOnset < model.closeAll)
        assertTrue("...and clear of the dead zone", model.resistOnset > model.backOne)
        assertTrue("the sheet must keep at least half the finger", BtSheetDragTuning.RESISTANCE >= 0.5f)
        assertTrue("...but the resistance must still be felt", BtSheetDragTuning.RESISTANCE < 1f)
    }

    @Test
    fun `there is still a real back-one band to release in`() {
        // Moving the boundary up shortens the first stage. It must not shorten it
        // to nothing: between letting go of the dead zone and reaching close-all
        // there has to be room to mean "back one page" on purpose.
        //
        // Order 2026-10-29: the band is now a fixed 96dp of FINGER (was 0.31 of the
        // sheet), so it is measured in dp — on the phone sheet 96dp is 0.109 of the
        // height in finger and 0.077 of it in sheet travel.
        val fingerBandDp = (model.closeAllPull - model.backOne) * SheetDragDevices.PHONE_SHEET_DP
        assertTrue("back-one band is only ${fingerBandDp}dp of finger", fingerBandDp >= 90f)
        val sheetBandDp = (model.closeAll - model.backOne) * SheetDragDevices.PHONE_SHEET_DP
        assertTrue("back-one band is only ${sheetBandDp}dp of the sheet", sheetBandDp >= 60f)
    }

    @Test
    fun `a flick back UP settles from anywhere`() {
        assertEquals(SheetRelease.SETTLE, sheetRelease(0.6f, -flick, true, model))
        assertEquals(SheetRelease.SETTLE, sheetRelease(0.95f, -flick, true, model))
        assertEquals(SheetRelease.SETTLE, sheetRelease(0.6f, -flick, false, model))
    }

    @Test
    fun `a slow drift back up from past the resistance still closes the stack`() {
        assertEquals(SheetRelease.CLOSE_ALL, sheetRelease(0.9f, -10f, true, model))
    }

    // ── Depth 1: one stage, closing IS closing everything ───────────────────

    @Test
    fun `a depth-1 sheet closes on distance or on a flick`() {
        val close = model.closeAllPull
        assertEquals(SheetRelease.SETTLE, sheetRelease(close - 0.01f, 0f, false, model))
        assertEquals(SheetRelease.BACK_ONE, sheetRelease(close, 0f, false, model))
        assertEquals(SheetRelease.BACK_ONE, sheetRelease(model.backOne, model.closeFlingPx, false, model))
    }

    @Test
    fun `a depth-1 sheet has no close-all, because closing IS closing everything`() {
        listOf(0.2f, 0.5f, 0.9f, 1f).forEach { t ->
            assertFalse(
                "travel=$t must never be CLOSE_ALL at depth 1",
                sheetRelease(t, 0f, false, model) == SheetRelease.CLOSE_ALL,
            )
        }
    }

    @Test
    fun `the depth-1 commit point is the nested close-all finger distance`() {
        // Was pinned at 0.50 of the sheet ("where it always was"). The order of
        // 2026-10-29 halves the close-everything pull, and at depth 1 the only
        // pull there is closes everything — so it takes the same finger as the
        // nested close-all (0.224 of the phone sheet, was 0.50).
        val nestedFinger = sheetPullFor(model.closeAll, model)
        assertEquals(nestedFinger, model.closeAllPull, 1e-5f)
        assertTrue("depth-1 close must be well under the old 0.50", model.closeAllPull <= 0.30f)
    }
}
