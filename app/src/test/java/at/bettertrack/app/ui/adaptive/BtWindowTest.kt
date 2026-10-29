package at.bettertrack.app.ui.adaptive

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window-class mapping every large-screen decision keys off (T1, 2026-10-29).
 *
 * The widths that matter are named: the owner's Galaxy Tab S8 is ~753dp wide in
 * portrait and ~1204dp in landscape at its 340dpi density; a large phone in
 * landscape lands between 600 and 840.
 */
class BtWindowTest {

    @Test
    fun `every phone in portrait is compact`() {
        listOf(0, 320, 360, 411, 599).forEach {
            assertEquals("width $it", BtWidthClass.Compact, btWidthClassFor(it))
        }
    }

    @Test
    fun `600dp is the first medium width, not the last compact one`() {
        assertEquals(BtWidthClass.Compact, btWidthClassFor(BT_MEDIUM_MIN_WIDTH_DP - 1))
        assertEquals(BtWidthClass.Medium, btWidthClassFor(BT_MEDIUM_MIN_WIDTH_DP))
    }

    @Test
    fun `the owner's tablet in portrait is medium`() {
        assertEquals(BtWidthClass.Medium, btWidthClassFor(753))
    }

    @Test
    fun `840dp is the first expanded width, not the last medium one`() {
        assertEquals(BtWidthClass.Medium, btWidthClassFor(BT_EXPANDED_MIN_WIDTH_DP - 1))
        assertEquals(BtWidthClass.Expanded, btWidthClassFor(BT_EXPANDED_MIN_WIDTH_DP))
    }

    @Test
    fun `the owner's tablet in landscape is expanded`() {
        assertEquals(BtWidthClass.Expanded, btWidthClassFor(1204))
    }

    @Test
    fun `the breakpoints are material's own`() {
        // `WindowSizeClass.minWidthDp` hands the mapping a bucket's lower bound,
        // so these two numbers must be exactly the androidx breakpoints or a
        // bucket would map into its neighbour.
        assertEquals(600, BT_MEDIUM_MIN_WIDTH_DP)
        assertEquals(840, BT_EXPANDED_MIN_WIDTH_DP)
        assertEquals(BtWidthClass.Compact, btWidthClassFor(0))
        assertEquals(BtWidthClass.Medium, btWidthClassFor(600))
        assertEquals(BtWidthClass.Expanded, btWidthClassFor(840))
    }

    @Test
    fun `the V2 large and extra-large buckets are expanded`() {
        // currentWindowAdaptiveInfoV2 reports 1200 / 1600 lower bounds for L and
        // XL windows; the shell has one wide layout, so both must land there.
        assertEquals(BtWidthClass.Expanded, btWidthClassFor(1200))
        assertEquals(BtWidthClass.Expanded, btWidthClassFor(1600))
    }

    @Test
    fun `compact content is uncapped, medium is 680dp, expanded is 760dp`() {
        assertNull("Compact must stay the phone layout", btContentMaxWidth(BtWidthClass.Compact))
        assertEquals(680.dp, btContentMaxWidth(BtWidthClass.Medium))
        assertEquals(760.dp, btContentMaxWidth(BtWidthClass.Expanded))
    }

    @Test
    fun `page sheets share the bottom sheets' 640dp cap off compact`() {
        assertNull(btSheetMaxWidth(BtWidthClass.Compact))
        assertEquals(640.dp, btSheetMaxWidth(BtWidthClass.Medium))
        assertEquals(640.dp, btSheetMaxWidth(BtWidthClass.Expanded))
    }

    @Test
    fun `a page sheet is never wider than the content column beside it`() {
        // A sheet wider than the column it opens over would read as the page
        // growing rather than something laid on top of it.
        BtWidthClass.entries.filter { it != BtWidthClass.Compact }.forEach { c ->
            val sheet = btSheetMaxWidth(c)!!
            val column = btContentMaxWidth(c)!!
            assertTrue("$c: sheet $sheet vs column $column", sheet <= column)
        }
    }

    @Test
    fun `only medium and expanded navigate from the rail`() {
        assertFalse(BtWindow(BtWidthClass.Compact).usesRail)
        assertTrue(BtWindow(BtWidthClass.Medium).usesRail)
        assertTrue(BtWindow(BtWidthClass.Expanded).usesRail)
    }

    @Test
    fun `the window derives its caps from its class`() {
        BtWidthClass.entries.forEach { c ->
            val w = BtWindow(c)
            assertEquals(btContentMaxWidth(c), w.contentMaxWidth)
            assertEquals(btSheetMaxWidth(c), w.sheetMaxWidth)
        }
    }

    @Test
    fun `anything composed without the provider gets the phone layout`() {
        // LocalBtWindow's default. A widget-config activity or a preview must not
        // suddenly grow a rail because nobody measured its window.
        assertEquals(BtWidthClass.Compact, BtWindow.Compact.widthClass)
        assertFalse(BtWindow.Compact.usesRail)
        assertNull(BtWindow.Compact.contentMaxWidth)
        assertNull(BtWindow.Compact.sheetMaxWidth)
    }

    @Test
    fun `two windows of one class are equal, so the static local does not churn`() {
        // ProvideBtWindow remembers on the class; equality is what lets a
        // freeform resize inside one class leave the subtree alone.
        assertEquals(BtWindow(BtWidthClass.Medium), BtWindow(BtWidthClass.Medium))
    }
}
