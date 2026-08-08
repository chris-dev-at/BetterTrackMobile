package at.bettertrack.app.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The graph's remaining motion, pinned.
 *
 * ## What this file used to test, and why it does not any more
 *
 * R3's spec had two idioms and a rule that picked between them by the PAIR of
 * routes, so most of this file was about that rule: `routeKey` stripping typed
 * arguments, `isLateral` requiring BOTH sides to be tab routes, `lateralForward`
 * reading direction from bar order. Every one of those tests is deleted, and none
 * of them regressed — the navigations they described stopped existing.
 *
 *  - There are no tab routes, so nothing can be lateral. A tab hop is a pager
 *    scroll ([BtTabPager]); the finger IS the motion.
 *  - There are no pushes over a tab, so nothing is hierarchical. A subpage is a
 *    [BtSheet], which owns its whole travel because a drag and a transition
 *    cannot share the job.
 *
 * One case is left, and it is the one neither of those two can cover: a sheet
 * opening over another sheet. That is what remains here.
 */
class BtNavMotionTest {

    @Test
    fun `the graph animates only the sheet-over-sheet hand-over`() {
        // Both directions of the one surviving pair exist. If either were null the
        // covered sheet would be dropped from composition the instant it was
        // covered, and the live tab page would flash through the gap.
        assertNotNull(BtNavMotion.stackRecede())
        assertNotNull(BtNavMotion.stackReturn())
    }

    @Test
    fun `the hand-over keeps R3's 300ms rhythm`() {
        // The shapes are gone; the rhythm is the thing worth keeping, because it
        // is what made the app feel like one app across forty destinations. It is
        // also the sheet's own budget: a covered sheet must stay composed for at
        // least as long as the arriving sheet takes to cover it, or the tab page
        // shows through the gap.
        assertEquals(300, BtNavMotion.DURATION_TOTAL_MS)
    }
}
