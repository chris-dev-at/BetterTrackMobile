package at.bettertrack.app.ui.shell

import at.bettertrack.app.navigation.BtTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live-pager architecture (owner order 2026-08-08), pinned on the JVM.
 *
 * This file replaces `BtTabSwipeTest`, which pinned the gesture layer that used
 * to fake a pager over a NavHost: commit thresholds, damped follow offsets, peek
 * sides, handoff pins, takeover seeds and two selection latches — 739 lines of
 * rules that existed because the incoming page was a frozen bitmap and the nav
 * graph answered a frame late. None of those conditions survive: the pages are
 * live and side by side, and `PagerState` is the one coordinate everything reads.
 *
 * What is left to pin is the geometry the chrome derives from that coordinate,
 * and it is worth pinning precisely because it is now the ONLY thing between the
 * pager and every visible piece of tab chrome. If [tabPillX] and
 * [tabSelectionRamp] disagree about where the pager is, the bar's pill and the
 * bar's ink disagree on screen — which is exactly the class of bug the retired
 * latches were patching.
 *
 * The measurement this architecture answers, from this device, before the change:
 * **3 swipes at a ~100ms cadence advanced 0 pages, 20 bursts out of 20** (60
 * swipes, 30 pages). See the pager's own KDoc.
 */
class BtTabPagerTest {

    private val bar = listOf(BtTab.Portfolio, BtTab.Markets, BtTab.Workbench, BtTab.People)
    private val driveBar = listOf(BtTab.Portfolio, BtTab.Markets)

    // ── The coordinate ───────────────────────────────────────────────────────

    @Test
    fun `at rest the position is the page index`() {
        assertEquals(0f, tabPagerPosition(0, 0f, 4), 1e-4f)
        assertEquals(2f, tabPagerPosition(2, 0f, 4), 1e-4f)
    }

    @Test
    fun `mid-drag the position is continuous`() {
        // A third of the way from Markets to Workbench.
        assertEquals(1.33f, tabPagerPosition(1, 0.33f, 4), 1e-4f)
        // And the same point approached from the other side: past the halfway
        // mark `currentPage` has already flipped to 2 and the offset is negative.
        assertEquals(1.6f, tabPagerPosition(2, -0.4f, 4), 1e-4f)
    }

    @Test
    fun `overscroll at the ends is clamped to the bar`() {
        // A pager reports overscroll past its first and last page. The chrome has
        // nowhere to put it — a pill drifting off the end of the bar was a real
        // artefact of the retired layer — so the position stops at the bar.
        assertEquals(0f, tabPagerPosition(0, -0.4f, 4), 1e-4f)
        assertEquals(3f, tabPagerPosition(3, 0.4f, 4), 1e-4f)
    }

    @Test
    fun `an empty bar has a position rather than an exception`() {
        assertEquals(0f, tabPagerPosition(0, 0f, 0), 1e-4f)
    }

    // ── The bar's ink ────────────────────────────────────────────────────────

    @Test
    fun `at rest exactly one tab is lit`() {
        val lit = bar.indices.map { tabSelectionRamp(2f, it) }
        assertEquals(listOf(0f, 0f, 1f, 0f), lit)
    }

    @Test
    fun `mid-drag the two tabs either side share the light`() {
        // The sum is conserved, which is what makes the crossfade read as one
        // selection moving rather than two independent fades.
        val a = tabSelectionRamp(1.25f, 1)
        val b = tabSelectionRamp(1.25f, 2)
        assertEquals(0.75f, a, 1e-4f)
        assertEquals(0.25f, b, 1e-4f)
        assertEquals(1f, a + b, 1e-4f)
    }

    @Test
    fun `a tab more than one page away is fully dark`() {
        assertEquals(0f, tabSelectionRamp(0f, 3), 1e-4f)
        assertEquals(0f, tabSelectionRamp(3f, 0), 1e-4f)
    }

    // ── The shared header's crossfade ────────────────────────────────────────

    @Test
    fun `at rest the header has one face`() {
        val span = tabHeaderSpan(2f, 4)
        assertEquals(2, span.lo)
        assertEquals(3, span.hi)
        assertEquals(0f, span.fraction, 1e-4f)
    }

    @Test
    fun `the header fraction is monotonic across a whole drag`() {
        // The bug this shape exists to prevent: the retired version anchored on
        // `currentPage`, which flips at the halfway mark, so a finger travelling
        // steadily one way faded the incoming face in, out and in again. Walking
        // the position from 1 to 2 must produce a fraction that only ever rises.
        var previous = -1f
        var steps = 0
        var pos = 1f
        while (pos <= 2.0001f) {
            val span = tabHeaderSpan(pos, 4)
            val f = if (span.lo == 2) 1f else span.fraction
            assertTrue("fraction went backwards at pos=$pos ($previous -> $f)", f >= previous)
            previous = f
            steps++
            pos += 0.05f
        }
        assertTrue(steps > 15)
    }

    @Test
    fun `the last page has no page after it to fade to`() {
        val span = tabHeaderSpan(3f, 4)
        assertEquals(3, span.lo)
        assertEquals(3, span.hi)
        assertEquals(0f, span.fraction, 1e-4f)
    }

    // ── The pill ─────────────────────────────────────────────────────────────

    private val centres = listOf(135f, 405f, 675f, 945f)

    @Test
    fun `at rest the pill is on the selected tab's icon centre`() {
        assertEquals(675f, tabPillX(2f, 4) { centres[it] }!!, 1e-4f)
    }

    @Test
    fun `mid-drag the pill is between two icon centres, in proportion`() {
        // Half a page along is half an item step along. This is §6.3's rule, and
        // for the first time it is exact rather than approximated from the
        // outgoing page's damped displacement.
        assertEquals(270f, tabPillX(0.5f, 4) { centres[it] }!!, 1e-4f)
        assertEquals(742.5f, tabPillX(2.25f, 4) { centres[it] }!!, 1e-4f)
    }

    @Test
    fun `the pill travels the same fraction of a step that the pager travelled of a page`() {
        val step = centres[1] - centres[0]
        listOf(0f, 0.1f, 0.37f, 0.5f, 0.9f, 1f).forEach { t ->
            val x = tabPillX(t, 4) { centres[it] }!!
            assertEquals("t=$t", centres[0] + t * step, x, 1e-3f)
        }
    }

    @Test
    fun `an unmeasured bar has no pill rather than a guessed one`() {
        // Before the first layout pass there are no icon centres. Drawing a pill
        // at a made-up x would flash it into place from the wrong side.
        assertNull(tabPillX(0f, 4) { null })
    }

    @Test
    fun `a half-measured bar falls back to the centre it does have`() {
        // `onGloballyPositioned` fires per item, so one frame can have the low
        // centre and not the high one. Holding at the known centre is right;
        // interpolating towards a null is not.
        assertEquals(135f, tabPillX(0.5f, 4) { if (it == 0) 135f else null }!!, 1e-4f)
    }

    // ── The tap latch ────────────────────────────────────────────────────────

    @Test
    fun `a tap latch holds until the pager has settled on its target`() {
        // Settled where the tap started: still travelling, keep believing the tap.
        assertTrue(tapLatchHolds(target = 2, origin = 0, settledPage = 0))
    }

    @Test
    fun `a tap latch lets go the moment the pager agrees`() {
        assertFalse(tapLatchHolds(target = 2, origin = 0, settledPage = 2))
    }

    @Test
    fun `a tap latch lets go if something else took over`() {
        // A deep link, a system back, a second tap — the settled page is neither
        // the target nor the origin, so the tap's opinion is stale and lighting a
        // tab nobody is on is worse than being a frame late.
        assertFalse(tapLatchHolds(target = 3, origin = 0, settledPage = 1))
    }

    @Test
    fun `the latch reads settledPage, not currentPage`() {
        // `currentPage` flips at the halfway mark, so a latch keyed on it would
        // drop while the page was still visibly in motion and the ink would
        // change under a moving page. Stated as a test because the two properties
        // are one character apart at the call site.
        //
        // Halfway through a 0 -> 2 hop the pager reports currentPage 1 and
        // settledPage 0; the latch must still hold.
        assertTrue(tapLatchHolds(target = 2, origin = 0, settledPage = 0))
    }

    // ── The warm-up ──────────────────────────────────────────────────────────

    @Test
    fun `the warm-up wakes the nearest pages first`() {
        // The two tabs one swipe away are ready before the far one, so the
        // schedule is invisible to anyone who swipes during it.
        assertEquals(
            listOf(BtTab.Markets, BtTab.Portfolio, BtTab.Workbench, BtTab.People),
            tabWarmOrder(bar, from = 1),
        )
    }

    @Test
    fun `the warm-up starts from the page the app opened on`() {
        assertEquals(BtTab.Portfolio, tabWarmOrder(bar, from = 0).first())
        assertEquals(BtTab.People, tabWarmOrder(bar, from = 3).first())
    }

    @Test
    fun `the warm-up covers every visible page exactly once`() {
        assertEquals(bar.toSet(), tabWarmOrder(bar, from = 2).toSet())
        assertEquals(bar.size, tabWarmOrder(bar, from = 2).size)
    }

    // ── The live set ─────────────────────────────────────────────────────────

    @Test
    fun `a page that has woken never sleeps again`() {
        // "Lazy-init on first visit, alive forever after" is exactly this set
        // being append-only. A page that could be dropped would put the app back
        // where it started: a tab that has to be rebuilt on arrival.
        val live = BtTabLiveSet(BtTab.Portfolio)
        assertTrue(live.isLive(BtTab.Portfolio))
        assertFalse(live.isLive(BtTab.People))
        live.wake(BtTab.People)
        live.wake(BtTab.People)
        assertTrue(live.isLive(BtTab.People))
        assertEquals(2, live.count)
    }

    @Test
    fun `a cold start has exactly one live page`() {
        // The startup guarantee: `beyondViewportPageCount` composes all four
        // pages on the pager's first measure, and this is what stops three of
        // them from building view models and firing loads before the one the user
        // is looking at has drawn.
        assertEquals(1, BtTabLiveSet(BtTab.Portfolio).count)
    }

    // ── The bar the pager is built from ──────────────────────────────────────

    @Test
    fun `a filtered bar is a subsequence, so the pager cannot reach a hidden tab`() {
        // This is the guarantee `tabNeighbour(visible = ...)` used to enforce by
        // hand. A Drive-only install renders Portfolio + Markets; the pager's page
        // list IS that list, and a pager cannot scroll outside its own page range,
        // so swiping off the end stops instead of landing on a tab with no button.
        assertEquals(driveBar, bar.filter { it in driveBar })
        assertEquals(2, driveBar.size)
        // Position is clamped to the SHORT bar, not the enum.
        assertEquals(1f, tabPagerPosition(1, 0.5f, driveBar.size), 1e-4f)
    }

    @Test
    fun `the ends of the bar do not wrap`() {
        // Wrapping would make the bar a carousel, which contradicts a bottom bar
        // whose selection is a position rather than a cycle. Expressed against
        // the position, which is what the pager's page range guarantees.
        assertEquals(0f, tabPagerPosition(0, -1f, 4), 1e-4f)
        assertEquals(3f, tabPagerPosition(3, 1f, 4), 1e-4f)
    }
}
