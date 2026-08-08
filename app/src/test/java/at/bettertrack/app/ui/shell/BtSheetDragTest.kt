package at.bettertrack.app.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How far a finger moves the sheet, and what the grabber says about it. */
class BtSheetDragTest {

    private val height = 2000f
    private val flick = 420f

    // ── Depth 1: linear, no phantom detent ──────────────────────────────────

    @Test
    fun `a depth-1 sheet tracks the finger exactly, all the way down`() {
        var travel = 0f
        repeat(4) { travel = sheetDragTravel(travel, 250f, height, stacked = false) }
        assertEquals(0.5f, travel, 1e-4f)
        // A detent at depth 1 would be resistance that means nothing: there is no
        // second stage to protect, because closing IS closing everything.
        assertEquals(0.75f, sheetDragTravel(0.5f, 500f, height, stacked = false), 1e-4f)
    }

    // ── Depth >= 2: three bands, one notch ──────────────────────────────────

    @Test
    fun `below the notch the sheet tracks the finger exactly`() {
        assertEquals(0.25f, sheetDragTravel(0f, 500f, height, stacked = true), 1e-4f)
        assertEquals(0.5f, sheetDragTravel(0.25f, 500f, height, stacked = true), 1e-4f)
    }

    @Test
    fun `the notch stiffens the drag`() {
        val below = sheetDragTravel(SHEET_NOTCH_START - 0.2f, 100f, height, true) -
            (SHEET_NOTCH_START - 0.2f)
        val inside = sheetDragTravel(SHEET_NOTCH_START + 0.01f, 100f, height, true) -
            (SHEET_NOTCH_START + 0.01f)
        assertTrue("the notch must cost more finger", inside < below)
        assertEquals(below * SHEET_NOTCH_RESISTANCE, inside, 1e-3f)
    }

    @Test
    fun `past the notch the sheet tracks the finger again`() {
        val step = sheetDragTravel(SHEET_NOTCH_END + 0.05f, 200f, height, true) -
            (SHEET_NOTCH_END + 0.05f)
        assertEquals(200f / height, step, 1e-3f)
    }

    @Test
    fun `the notch is crossable and the sheet still reaches the bottom`() {
        var travel = 0f
        repeat(40) { travel = sheetDragTravel(travel, 200f, height, stacked = true) }
        assertEquals("a long pull must still bottom out", 1f, travel, 1e-4f)
    }

    @Test
    fun `the drag retraces itself on the way back up`() {
        val down = sheetDragTravel(0f, 1600f, height, stacked = true)
        assertTrue("must be inside or past the notch", down > SHEET_NOTCH_START)
        assertEquals(0f, sheetDragTravel(down, -1600f, height, stacked = true), 1e-3f)
    }

    @Test
    fun `travel and pull are exact inverses`() {
        listOf(0f, 0.2f, SHEET_NOTCH_START, 0.76f, SHEET_NOTCH_END, 0.9f, 1f).forEach { t ->
            assertEquals(t, sheetTravelFor(sheetPullFor(t)), 1e-4f)
        }
    }

    @Test
    fun `the close-all zone stays reachable by a real thumb`() {
        // Resistance is a budget, not a feeling: crossing the band costs
        // (END - START) / RESISTANCE of sheet height in FINGER travel, and an
        // earlier build put stage two beyond any thumb by getting this wrong.
        val fingerToCloseAll = sheetPullFor(SHEET_NOTCH_END)
        assertTrue(
            "reaching close-all costs $fingerToCloseAll sheet-heights of finger",
            fingerToCloseAll <= 0.86f,
        )
        assertTrue("...but it must stay a deliberate, full-screen pull", fingerToCloseAll >= 0.7f)
    }

    @Test
    fun `a sheet cannot be pulled up past its rest position`() {
        assertEquals(0f, sheetDragTravel(0f, -500f, height, stacked = true), 1e-4f)
        assertEquals(0f, sheetDragTravel(0.1f, -500f, height, stacked = false), 1e-4f)
    }

    @Test
    fun `an unmeasured sheet does not move`() {
        assertEquals(0.3f, sheetDragTravel(0.3f, 500f, 0f, stacked = true), 1e-4f)
        assertEquals(0.3f, sheetDragTravel(0.3f, 500f, 0f, stacked = false), 1e-4f)
    }

    // ── The visible half of the rule ────────────────────────────────────────

    @Test
    fun `the grabber reports the stage the release rule would take`() {
        // The pill is the only always-visible chrome, so it is where the rule is
        // drawn. It must never disagree with what letting go would actually do.
        val points = listOf(0f, 0.05f, SHEET_DEAD_ZONE, 0.3f, 0.5f, SHEET_NOTCH_START, SHEET_NOTCH_END, 0.9f, 1f)
        listOf(true, false).forEach { stacked ->
            points.forEach { t ->
                val expected = when (sheetRelease(t, 0f, flick, stacked)) {
                    SheetRelease.SETTLE -> SheetStage.IDLE
                    SheetRelease.BACK_ONE -> SheetStage.BACK
                    SheetRelease.CLOSE_ALL -> SheetStage.CLOSE_ALL
                }
                assertEquals("travel=$t stacked=$stacked", expected, sheetStageOf(t, stacked))
            }
        }
    }

    // ── The depth axis: a rightward swipe walks back out ────────────────────

    @Test
    fun `a short rightward swipe springs back`() {
        assertFalse(sheetBackSwipeCommits(SHEET_BACK_SWIPE_FRACTION - 0.01f, 0f, flick))
    }

    @Test
    fun `a rightward swipe past the threshold goes back one`() {
        assertTrue(sheetBackSwipeCommits(SHEET_BACK_SWIPE_FRACTION, 0f, flick))
        assertTrue(sheetBackSwipeCommits(0.8f, 0f, flick))
    }

    @Test
    fun `a rightward flick goes back, but not from inside the dead zone`() {
        assertTrue(sheetBackSwipeCommits(0.1f, flick, flick))
        assertFalse(sheetBackSwipeCommits(SHEET_BACK_SWIPE_DEAD_ZONE - 0.01f, flick * 5f, flick))
    }

    @Test
    fun `a flick back to the LEFT cancels`() {
        assertFalse(sheetBackSwipeCommits(0.8f, -flick, flick))
    }

    // ── The parent plane's geometry ─────────────────────────────────────────

    @Test
    fun `the parent plane lands exactly at rest when the top page is fully out`() {
        // The seam the whole "visually connected" fix turns on: at slide = 1 the
        // parent sits at translationX 0, which is where it will be drawn as the
        // NEW top page one frame later. Anything else is a visible jump at the pop.
        val width = 1080f
        val parentAt = { slide: Float -> -(1f - slide) * width * SHEET_DEPTH_PARALLAX }
        assertEquals(0f, parentAt(1f), 1e-4f)
        assertEquals(-width, parentAt(0f), 1e-4f)
    }

    @Test
    fun `the depth axis moves the two planes together, like the main pager`() {
        assertEquals("pager-exact means 1:1", 1f, SHEET_DEPTH_PARALLAX, 1e-6f)
    }
}
