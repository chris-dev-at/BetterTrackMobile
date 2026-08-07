package at.bettertrack.app.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The swipe-between-tabs gesture (owner ask 2026-08-07), pinned where it is pure.
 *
 * The gesture itself needs a device; the DECISION it makes on release — commit or
 * spring back, and which way — does not, and that decision is where a swipe feels
 * either responsive or unreliable. Distances are in pixels here; the composable
 * converts its dp constants once and hands them in.
 */
class BtTabSwipeTest {

    private val distance = 64f
    private val velocity = 400f

    @Test
    fun `a short drag with no flick does nothing`() {
        assertEquals(TabSwipe.None, tabSwipeOutcome(-20f, 0f, distance, velocity))
        assertEquals(TabSwipe.None, tabSwipeOutcome(20f, 0f, distance, velocity))
    }

    @Test
    fun `a drag past the distance threshold commits`() {
        // Negative dx = the finger travelled LEFT = the next tab to the right.
        assertEquals(TabSwipe.Forward, tabSwipeOutcome(-64f, 0f, distance, velocity))
        assertEquals(TabSwipe.Back, tabSwipeOutcome(64f, 0f, distance, velocity))
    }

    @Test
    fun `a fast flick commits before it has travelled far`() {
        assertEquals(TabSwipe.Forward, tabSwipeOutcome(-12f, -900f, distance, velocity))
        assertEquals(TabSwipe.Back, tabSwipeOutcome(12f, 900f, distance, velocity))
    }

    @Test
    fun `a flick back the other way does not commit the drag it undid`() {
        // Dragged a little left, then flicked right and released: the user
        // changed their mind, so the page must stay. This is the case a bare
        // `abs(velocity) > threshold` check gets wrong.
        assertEquals(TabSwipe.None, tabSwipeOutcome(-12f, 900f, distance, velocity))
        assertEquals(TabSwipe.None, tabSwipeOutcome(12f, -900f, distance, velocity))
    }

    @Test
    fun `a long drag still commits even if the flick disagrees`() {
        // Past the distance threshold the drag speaks for itself — the finger
        // has moved the page far enough that springing back would be the surprise.
        assertEquals(TabSwipe.Forward, tabSwipeOutcome(-200f, 900f, distance, velocity))
        assertEquals(TabSwipe.Back, tabSwipeOutcome(200f, -900f, distance, velocity))
    }

    @Test
    fun `an untouched pointer is never a swipe`() {
        assertEquals(TabSwipe.None, tabSwipeOutcome(0f, 0f, distance, velocity))
        assertEquals(TabSwipe.None, tabSwipeOutcome(0f, 5000f, distance, velocity))
    }

    // ── The damped follow ──────────────────────────────────────────────────────

    @Test
    fun `the follow tracks the finger closely at first`() {
        // Small drags should feel 1:1-ish, or the gesture reads as laggy.
        val max = 40f
        val offset = swipeFollowOffset(-8f, max)
        assertEquals(-8f, offset, 1.0f)
    }

    @Test
    fun `the follow never exceeds its cap in either direction`() {
        val max = 40f
        listOf(-5000f, -400f, 400f, 5000f).forEach { dx ->
            val offset = swipeFollowOffset(dx, max)
            assertEquals(
                "follow offset $offset for dx=$dx escaped the ±$max cap",
                true,
                offset in -max..max,
            )
        }
    }

    @Test
    fun `the follow is off when the cap is zero (reduced motion)`() {
        assertEquals(0f, swipeFollowOffset(-500f, 0f), 0f)
    }

    @Test
    fun `the follow keeps the sign of the drag`() {
        assertEquals(true, swipeFollowOffset(-30f, 40f) < 0f)
        assertEquals(true, swipeFollowOffset(30f, 40f) > 0f)
        assertEquals(0f, swipeFollowOffset(0f, 40f), 0.001f)
    }

    // ── The bottom bar's share of the same gesture (B2 §6.3) ────────────────

    @Test
    fun `the indicator leads by the same fraction the page moved`() {
        // The contract in one line: page moves a tenth of a page, indicator
        // moves a tenth of an item step. Not a whole step — a bar that arrives
        // where the content never went is worse than a bar that does not move.
        assertEquals(0.1f, tabIndicatorLead(-108f, 1080f), 1e-6f)
        assertEquals(0.25f, tabIndicatorLead(-270f, 1080f), 1e-6f)
    }

    @Test
    fun `the indicator travels opposite to the page`() {
        // Finger LEFT ⇒ page offset negative ⇒ the tab to the RIGHT is coming ⇒
        // the indicator must move right (positive). Getting this backwards is
        // the single most likely way to break the bar, so it is pinned.
        assertEquals(true, tabIndicatorLead(-40f, 1080f) > 0f)
        assertEquals(true, tabIndicatorLead(40f, 1080f) < 0f)
    }

    @Test
    fun `a settled gesture leaves the indicator exactly where the graph put it`() {
        // At rest the nav graph is the only writer (§6.3's arbiter).
        assertEquals(0f, tabIndicatorLead(0f, 1080f), 0f)
    }

    @Test
    fun `an unmeasured bar leads by nothing rather than dividing by zero`() {
        assertEquals(0f, tabIndicatorLead(-40f, 0f), 0f)
        assertEquals(0f, tabIndicatorLead(-40f, -1f), 0f)
    }

    @Test
    fun `the real follow cap produces a nudge, not a jump`() {
        // End to end with the actual constants: 40dp of damped follow on a
        // 411dp-wide bar at density 2.625 (the verification phone). A drag far
        // past the cap saturates the page at -40dp, and the indicator answers
        // with roughly a tenth of a step — the same order as the page's own
        // movement, which is the whole design rule.
        val followMaxPx = 40f * 2.625f
        val barWidthPx = 411.43f * 2.625f
        val pageOffsetPx = swipeFollowOffset(-1000f, followMaxPx)
        assertTrue("page should saturate leftwards, was $pageOffsetPx", pageOffsetPx < -100f)
        val lead = tabIndicatorLead(pageOffsetPx, barWidthPx)
        assertTrue("lead was $lead", lead in 0.05f..0.15f)
    }
}
