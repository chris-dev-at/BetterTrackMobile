package at.bettertrack.app.ui.shell

import at.bettertrack.app.navigation.BtTab
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

    // ── The commit threshold ──────────────────────────────────────────────────

    @Test
    fun `a measured page commits at half its width`() {
        // The launcher rule the owner asked for: let go and you land on whichever
        // page is mostly under you.
        assertEquals(540f, swipeCommitDistancePx(1080f, 168f), 1e-6f)
    }

    @Test
    fun `an unmeasured page falls back to the fixed distance`() {
        // One frame before the first layout, and under reduced motion, where
        // nothing tracks the finger and there is no "mostly under you" to read.
        assertEquals(168f, swipeCommitDistancePx(0f, 168f), 1e-6f)
    }

    // ── The 1:1 follow, where there is a page to reveal ───────────────────────

    @Test
    fun `the page tracks the finger exactly when a neighbour exists`() {
        // Not damped, not scaled: the home-screen reference is a strip of pages
        // that moves as far as the finger does. Any deviation here is the phone
        // disagreeing with the hand.
        listOf(-8f, -120f, -539f, 240f, 1000f).forEach { dx ->
            assertEquals(
                "1:1 follow broke at dx=$dx",
                dx,
                swipeFollowOffset(dx, pageWidthPx = 1080f, edgeMaxPx = 105f, hasNeighbour = true),
                1e-6f,
            )
        }
    }

    @Test
    fun `the follow stops at one page so a third page is never pulled in`() {
        assertEquals(-1080f, swipeFollowOffset(-3000f, 1080f, 105f, true), 1e-6f)
        assertEquals(1080f, swipeFollowOffset(3000f, 1080f, 105f, true), 1e-6f)
    }

    // ── The damped overscroll hint, at the ends of the bar ────────────────────

    @Test
    fun `the edge hint tracks the finger closely at first`() {
        // Small drags should feel 1:1-ish, or the gesture reads as laggy.
        val offset = swipeFollowOffset(-8f, 1080f, edgeMaxPx = 40f, hasNeighbour = false)
        assertEquals(-8f, offset, 1.0f)
    }

    @Test
    fun `the edge hint never exceeds its cap in either direction`() {
        val max = 40f
        listOf(-5000f, -400f, 400f, 5000f).forEach { dx ->
            val offset = swipeFollowOffset(dx, 1080f, max, hasNeighbour = false)
            assertEquals(
                "edge offset $offset for dx=$dx escaped the ±$max cap",
                true,
                offset in -max..max,
            )
        }
    }

    @Test
    fun `there is no wrap-around — the last tab only hints`() {
        // The whole difference between a page turn and the end of the bar.
        val hint = swipeFollowOffset(-900f, 1080f, 40f, hasNeighbour = false)
        val turn = swipeFollowOffset(-900f, 1080f, 40f, hasNeighbour = true)
        assertTrue("edge hint should stay a nudge, was $hint", hint > -41f)
        assertEquals(-900f, turn, 1e-6f)
    }

    @Test
    fun `the follow is off entirely under reduced motion`() {
        // Reduced motion hands in both widths as zero: the hop still happens on
        // release, it simply does not animate under the finger.
        assertEquals(0f, swipeFollowOffset(-500f, 0f, 0f, hasNeighbour = true), 0f)
        assertEquals(0f, swipeFollowOffset(-500f, 0f, 0f, hasNeighbour = false), 0f)
    }

    @Test
    fun `the follow keeps the sign of the drag`() {
        assertEquals(true, swipeFollowOffset(-30f, 1080f, 40f, false) < 0f)
        assertEquals(true, swipeFollowOffset(30f, 1080f, 40f, false) > 0f)
        assertEquals(0f, swipeFollowOffset(0f, 1080f, 40f, false), 0.001f)
        assertEquals(0f, swipeFollowOffset(0f, 1080f, 40f, true), 0.001f)
    }

    // ── The second page: when it exists, and where ────────────────────────────

    @Test
    fun `nothing is revealed until the finger moves`() {
        // THE idle guarantee. The peek layer is composed only when this is
        // non-null, so an app sitting still draws exactly one page — no
        // neighbouring tab, no second recording, no cost.
        assertEquals(null, swipePeekSide(0f))
    }

    @Test
    fun `dragging left reveals the tab to the right`() {
        assertEquals(true, swipePeekSide(-1f))
        assertEquals(false, swipePeekSide(1f))
    }

    @Test
    fun `at rest no tab is peeked`() {
        // The composition-level half of the same guarantee.
        assertEquals(
            null,
            swipePeekTab(handoff = null, peekSide = null, current = BtTab.Markets, visible = BtTab.entries),
        )
    }

    @Test
    fun `a live drag peeks the neighbour on the revealed side`() {
        assertEquals(
            BtTab.Workbench,
            swipePeekTab(null, peekSide = true, current = BtTab.Markets, visible = BtTab.entries),
        )
        assertEquals(
            BtTab.Portfolio,
            swipePeekTab(null, peekSide = false, current = BtTab.Markets, visible = BtTab.entries),
        )
    }

    @Test
    fun `the ends of the bar peek nothing`() {
        // No wrap: there is no page beyond the last tab, so none is drawn and
        // the gesture layer shows the damped hint instead.
        assertEquals(
            null,
            swipePeekTab(null, peekSide = true, current = BtTab.People, visible = BtTab.entries),
        )
        assertEquals(
            null,
            swipePeekTab(null, peekSide = false, current = BtTab.Portfolio, visible = BtTab.entries),
        )
    }

    @Test
    fun `a peek only walks the tabs this mode actually shows`() {
        // Drive-only: Portfolio ↔ Markets and stop. Peeking a tab the bar does
        // not render would promise a page the release cannot deliver.
        val driveOnly = listOf(BtTab.Portfolio, BtTab.Markets)
        assertEquals(BtTab.Markets, swipePeekTab(null, true, BtTab.Portfolio, driveOnly))
        assertEquals(null, swipePeekTab(null, true, BtTab.Markets, driveOnly))
    }

    @Test
    fun `a committed hop keeps its target peeked while the NavHost catches up`() {
        // The handoff pin outranks the drag, and survives the drag's own state
        // being cleared — it is what covers the swap.
        assertEquals(
            BtTab.People,
            swipePeekTab(handoff = BtTab.People, peekSide = null, current = BtTab.Portfolio, visible = BtTab.entries),
        )
    }

    // ── The seam between the two pages ────────────────────────────────────────

    @Test
    fun `the incoming page sits exactly one page away`() {
        // Hard-adjacent, edge to edge: at rest the neighbour is exactly off
        // screen, so there is never a gap or an overlap at the seam.
        assertEquals(1080f, peekPageOffsetPx(0f, 1080f, forward = true, handedOff = false), 1e-6f)
        assertEquals(-1080f, peekPageOffsetPx(0f, 1080f, forward = false, handedOff = false), 1e-6f)
    }

    @Test
    fun `the two pages move as one strip`() {
        // The gap between them is one page width at every point of the drag —
        // this is the whole of "visually connected".
        listOf(-1f, -200f, -1079f, -1080f).forEach { offset ->
            val gap = peekPageOffsetPx(offset, 1080f, forward = true, handedOff = false) - offset
            assertEquals("seam drifted at offset=$offset", 1080f, gap, 1e-6f)
        }
    }

    @Test
    fun `a completed forward turn lands the incoming page at rest`() {
        assertEquals(0f, peekPageOffsetPx(-1080f, 1080f, forward = true, handedOff = false), 1e-6f)
        assertEquals(0f, peekPageOffsetPx(1080f, 1080f, forward = false, handedOff = false), 1e-6f)
    }

    @Test
    fun `the handoff pins the incoming page at rest`() {
        // The gesture layer snaps the NavHost back to 0 so the new destination
        // composes where it belongs. Without this pin the peek would follow that
        // snap straight back off screen and uncover the tab being left — the
        // flash the handoff exists to remove.
        assertEquals(0f, peekPageOffsetPx(0f, 1080f, forward = true, handedOff = true), 1e-6f)
        assertEquals(0f, peekPageOffsetPx(-1080f, 1080f, forward = true, handedOff = true), 1e-6f)
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
    fun `a completed page turn leads the indicator by exactly one step`() {
        // End to end with the real geometry of the verification phone (1080px
        // wide). This is what changed with the connected pager: Batch 1's capped
        // follow could only ever lead by about a tenth of a step, so the pill
        // crawled while the page nudged. Now the page travels a full width and
        // the pill arrives exactly one item along — precisely where the nav
        // graph then holds it, so the handoff is invisible in the bar too.
        val pageWidthPx = 1080f
        val pageOffsetPx = swipeFollowOffset(-3000f, pageWidthPx, 105f, hasNeighbour = true)
        assertEquals(-pageWidthPx, pageOffsetPx, 1e-6f)
        assertEquals(1f, tabIndicatorLead(pageOffsetPx, pageWidthPx), 1e-6f)
    }

    @Test
    fun `the edge hint leads the indicator by a nudge, not a step`() {
        // At the ends of the bar the page is still capped and damped, so the
        // pill must stay a nudge too — a bar that ran a whole step while the
        // page moved 40dp would arrive somewhere the content never went.
        val edgeMaxPx = 40f * 2.625f
        val barWidthPx = 411.43f * 2.625f
        val pageOffsetPx = swipeFollowOffset(-1000f, barWidthPx, edgeMaxPx, hasNeighbour = false)
        assertTrue("page should saturate leftwards, was $pageOffsetPx", pageOffsetPx < -100f)
        val lead = tabIndicatorLead(pageOffsetPx, barWidthPx)
        assertTrue("lead was $lead", lead in 0.05f..0.15f)
    }
}

/**
 * The two pure helpers the 2026-08-07 chrome work added: the bottom bar's
 * selection latch and the shared top bar's hand-over fraction.
 *
 * Both exist to answer the same owner report from opposite ends of the screen —
 * *"it jumps back for a brief second where you used to be"* — and both do it by
 * believing the COMMIT rather than the coordinate for the length of a hand-off.
 * That is exactly the kind of rule that is cheap to state, invisible to review
 * and impossible to notice breaking, which is what these cases are for.
 */
class BtChromeHandoffTest {

    // ── The bottom bar's selection latch ────────────────────────────────────

    @Test
    fun `at rest the nav graph owns the selection`() {
        assertEquals(1f, tabSelectionFraction(null, BtTab.Markets, true))
        assertEquals(0f, tabSelectionFraction(null, BtTab.Markets, false))
    }

    @Test
    fun `a committed hop wins over a nav graph that has not caught up`() {
        // The exact frame the bug lived in: the swipe has committed to Markets,
        // the nav graph still says Portfolio. The bar must say Markets.
        assertEquals(1f, tabSelectionFraction(BtTab.Markets, BtTab.Markets, false))
        assertEquals(0f, tabSelectionFraction(BtTab.Markets, BtTab.Portfolio, true))
    }

    @Test
    fun `the latch agrees with the nav graph once it catches up`() {
        // Nothing changes when the two sources agree, which is what makes this a
        // latch rather than a second source of truth.
        assertEquals(1f, tabSelectionFraction(BtTab.Markets, BtTab.Markets, true))
    }

    @Test
    fun `only the committed tab is selected while a hop is in flight`() {
        val lit = BtTab.entries.filter { tabSelectionFraction(BtTab.People, it, true) == 1f }
        assertEquals(listOf(BtTab.People), lit)
    }

    // ── The shared top bar's hand-over ──────────────────────────────────────

    @Test
    fun `the bar's content does not move until the page does`() {
        assertEquals(0f, tabHeaderSwapFraction(0f, 1080f, handedOff = false))
    }

    @Test
    fun `the bar's content hands over in step with the page`() {
        assertEquals(0.5f, tabHeaderSwapFraction(-540f, 1080f, handedOff = false))
        // Direction-blind: dragging either way is the same amount of hand-over.
        assertEquals(0.5f, tabHeaderSwapFraction(540f, 1080f, handedOff = false))
        assertEquals(1f, tabHeaderSwapFraction(-1080f, 1080f, handedOff = false))
    }

    @Test
    fun `the hand-over is clamped to one page`() {
        assertEquals(1f, tabHeaderSwapFraction(-5000f, 1080f, handedOff = false))
    }

    @Test
    fun `a committed hop pins the hand-over complete`() {
        // THE case. `btTabSwipe` snaps the offset back to zero the instant it
        // tells the NavHost to swap, so a raw reading says "no swipe in
        // progress" while the shell is still showing the OLD tab's face over the
        // NEW tab's page — the top-bar twin of the bottom bar's flicker.
        assertEquals(1f, tabHeaderSwapFraction(0f, 1080f, handedOff = true))
    }

    @Test
    fun `an unmeasured page hands nothing over`() {
        assertEquals(0f, tabHeaderSwapFraction(-540f, 0f, handedOff = false))
    }
}
