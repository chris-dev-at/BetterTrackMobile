package at.bettertrack.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A chart is drawn at the size that FITS, or it is not drawn.
 *
 * Owner 2026-08-18: *"some widget charts are CUT OFF"*. The families sized
 * their plots as `(card - padding - headerEstimate - footerEstimate)
 * .coerceAtLeast(40f)`, and that floor is what turned a shortfall into a
 * clipped chart: when the estimate came up short — or simply when the card was
 * too small — the arithmetic handed back 40dp of plot that there was no room
 * for, and the column overflowed the card with the chart (the last child) taking
 * the cut.
 *
 * [btWidgetChartHeightDp] replaces the floor with an honest null. These tests
 * pin the property the old code could not have: **the returned height plus what
 * the caller reserved never exceeds the card.**
 */
class BtWidgetChartFitTest {

    @Test
    fun `a roomy card gets the leftover height, less the slack`() {
        val h = btWidgetChartHeightDp(cardHeightDp = 250f, reservedDp = 100f)
        assertEquals(250f - 100f - BT_WIDGET_CHART_SLACK_DP, h!!, 0.001f)
    }

    @Test
    fun `the chart plus everything reserved never exceeds the card`() {
        // The whole point. Swept across every plausible card height on this
        // launcher (1 row = 120dp, 2 rows = 250dp, 4 rows ≈ 510dp) against
        // every plausible reservation.
        var drawn = 0
        for (card in 40..520 step 5) {
            for (reserved in 0..500 step 5) {
                val h = btWidgetChartHeightDp(card.toFloat(), reserved.toFloat())
                if (h != null) {
                    drawn++
                    assertTrue(
                        "chart of ${h}dp + ${reserved}dp reserved overflows a ${card}dp card",
                        h + reserved <= card.toFloat(),
                    )
                    assertTrue("a drawn chart is never below the legible floor", h >= BT_WIDGET_MIN_CHART_DP)
                }
            }
        }
        assertTrue("the sweep must actually draw charts, or it proves nothing", drawn > 100)
    }

    @Test
    fun `a card with no room returns null instead of a floor`() {
        // The exact old failure: header + footer already eat the card. The old
        // code answered "40dp"; the honest answer is "do not draw it".
        assertNull(btWidgetChartHeightDp(cardHeightDp = 120f, reservedDp = 118f))
        assertNull(btWidgetChartHeightDp(cardHeightDp = 120f, reservedDp = 200f))
        assertNull("a negative remainder is not a small chart", btWidgetChartHeightDp(40f, 300f))
    }

    @Test
    fun `the boundary is the legible floor, not a hair under it`() {
        val reserved = 100f
        val exact = reserved + BT_WIDGET_MIN_CHART_DP + BT_WIDGET_CHART_SLACK_DP
        assertEquals(BT_WIDGET_MIN_CHART_DP, btWidgetChartHeightDp(exact, reserved)!!, 0.001f)
        assertNull(btWidgetChartHeightDp(exact - 0.5f, reserved))
    }

    @Test
    fun `slack absorbs an estimate that came up short`() {
        // The caller under-estimated its footer by 3dp. With the slack held
        // back, the chart still fits inside the real card.
        val card = 250f
        val estimated = 150f
        val actual = estimated + 3f
        val h = btWidgetChartHeightDp(card, estimated)
        assertNotNull(h)
        assertTrue("a 3dp estimate miss must not clip the chart", h!! + actual <= card)
    }

    @Test
    fun `the bitmap for a fitted chart is capped but never degenerate`() {
        // Pairs with the fit rule: whatever height survives, the raster stays
        // inside the parcel budget and never collapses to zero.
        val h = btWidgetChartHeightDp(cardHeightDp = 510f, reservedDp = 40f)!!
        val (w, px) = btWidgetBitmapSize(widthDp = 366f, heightDp = h, density = 3f)
        assertTrue(w in 1..BT_WIDGET_BITMAP_MAX_EDGE_PX)
        assertTrue(px in 1..BT_WIDGET_BITMAP_MAX_EDGE_PX)
    }

    @Test
    fun `a one-row strip cannot host a chart once its text is reserved`() {
        // 1 row = 120dp on this launcher (dossier). A subject line plus a big
        // value plus card padding is already most of it — which is precisely
        // the size class where the old floor produced a clipped plot.
        assertNull(btWidgetChartHeightDp(cardHeightDp = 120f, reservedDp = 90f))
    }
}
