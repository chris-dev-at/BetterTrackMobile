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
        // Both steps have to land short of SHEET_NOTCH_START, or this stops
        // testing the linear band and starts testing the notch by accident.
        assertEquals(0.1f, sheetDragTravel(0f, 200f, height, stacked = true), 1e-4f)
        assertEquals(0.3f, sheetDragTravel(0.1f, 400f, height, stacked = true), 1e-4f)
        assertTrue("the band under test must be below the notch", 0.3f < SHEET_NOTCH_START)
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
        val insideBand = (SHEET_NOTCH_START + SHEET_NOTCH_END) / 2f
        listOf(0f, 0.2f, SHEET_NOTCH_START, insideBand, SHEET_NOTCH_END, 0.9f, 1f).forEach { t ->
            assertEquals(t, sheetTravelFor(sheetPullFor(t)), 1e-4f)
        }
    }

    @Test
    fun `the close-all zone stays reachable by a real thumb`() {
        // Resistance is a budget, not a feeling: crossing the band costs
        // (END - START) / RESISTANCE of sheet height in FINGER travel, and an
        // earlier build put stage two beyond any thumb by getting this wrong.
        //
        // Re-cut for the 2026-08-09 geometry. Moving the notch up while making it
        // stiffer pulls this number in two directions at once, which is exactly
        // why it needs a guard: 0.36/0.44 at resistance 0.40 costs
        // 0.36 + 0.08/0.40 = 0.56 sheet-heights, about 1255px of the test phone's
        // 2241px sheet. Comfortably inside one stroke — the old 0.80 was not —
        // while still far enough that no ordinary dismissal reaches it by
        // accident.
        val fingerToCloseAll = sheetPullFor(SHEET_NOTCH_END)
        assertTrue(
            "reaching close-all costs $fingerToCloseAll sheet-heights of finger",
            fingerToCloseAll <= 0.62f,
        )
        assertTrue("...but it must stay a deliberate pull", fingerToCloseAll >= 0.42f)
    }

    @Test
    fun `crossing the notch costs distinctly more finger than the band is wide`() {
        // The stiffening, stated as the thing the thumb actually feels: the last
        // slice of travel before close-all must cost more than double its own
        // width in finger movement, or the "stronger" half of the order is not in
        // the numbers however early the boundary sits.
        val band = SHEET_NOTCH_END - SHEET_NOTCH_START
        val fingerAcross = sheetPullFor(SHEET_NOTCH_END) - sheetPullFor(SHEET_NOTCH_START)
        assertTrue("band $band costs only $fingerAcross of finger", fingerAcross >= band * 2f)
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

    // ── The detent haptic: once, on the way in ──────────────────────────────

    @Test
    fun `the detent fires on the crossing into close-all`() {
        assertTrue(sheetNotchCrossed(SHEET_NOTCH_END - 0.01f, SHEET_NOTCH_END, stacked = true))
        assertTrue(sheetNotchCrossed(0f, 1f, stacked = true))
    }

    @Test
    fun `holding past the notch fires nothing more`() {
        // The owner asked for a vibration "once you go past it", and once is the
        // whole specification: a finger resting past the boundary still produces
        // a stream of move events, and answering each of them would turn a detent
        // into a buzz.
        assertFalse(sheetNotchCrossed(SHEET_NOTCH_END, SHEET_NOTCH_END, stacked = true))
        assertFalse(sheetNotchCrossed(SHEET_NOTCH_END, 0.9f, stacked = true))
        assertFalse(sheetNotchCrossed(0.9f, 1f, stacked = true))
    }

    @Test
    fun `retreating below the notch and crossing again fires again`() {
        // Not a re-arm hack: going back is the user changing their mind, and
        // coming forward again is them making the decision a second time.
        assertFalse(sheetNotchCrossed(0.9f, 0.2f, stacked = true))
        assertTrue(sheetNotchCrossed(0.2f, 0.9f, stacked = true))
    }

    @Test
    fun `pulling back up across the notch is silent`() {
        assertFalse(sheetNotchCrossed(SHEET_NOTCH_END, SHEET_NOTCH_END - 0.01f, stacked = true))
    }

    @Test
    fun `a depth-1 sheet has no detent, because it has no second stage`() {
        listOf(0f to 0.9f, 0.2f to 1f).forEach { (from, to) ->
            assertFalse("$from -> $to", sheetNotchCrossed(from, to, stacked = false))
        }
    }

    @Test
    fun `the detent fires exactly where the release rule changes its mind`() {
        // The haptic is a promise about what letting go will do. If the two ever
        // disagreed, the buzz would be a lie — so they are pinned to each other
        // rather than to a shared constant that one of them could stop using.
        val step = 0.005f
        var travel = 0f
        var buzzed = false
        while (travel <= 1f) {
            val next = travel + step
            if (sheetNotchCrossed(travel, next, stacked = true)) {
                assertFalse("the detent must fire only once", buzzed)
                buzzed = true
                assertEquals(
                    "the detent must land on the CLOSE_ALL boundary",
                    SheetRelease.CLOSE_ALL,
                    sheetRelease(next, 0f, flick, true),
                )
                assertEquals(
                    "...and the frame before it must still be BACK_ONE",
                    SheetRelease.BACK_ONE,
                    sheetRelease(travel, 0f, flick, true),
                )
            }
            travel = next
        }
        assertTrue("a full pull must cross the notch at all", buzzed)
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
