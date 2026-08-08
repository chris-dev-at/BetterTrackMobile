package at.bettertrack.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chart control surfaces' WIDTH arithmetic (owner ask 2026-08-08).
 *
 * The ask was *"make the timespan selection for the graph the same design as the
 * €% € % thingy. and maybe put them side by side but you figure out what looks
 * better."* The second half is a layout question with a numeric answer, and this
 * is where that answer lives so it stays an answer rather than a memory: the two
 * pickers cannot share a row on a phone, and the range picker therefore divides
 * the width under the chart instead of racing the mode picker across it.
 *
 * Everything here is dp arithmetic on [BtSegmented]'s own geometry constants, so
 * it runs on the JVM and fails the moment somebody changes the padding that the
 * conclusion rests on.
 *
 * ## Where the label widths come from
 *
 * Advance widths of the shipping type (`labelMedium`, 12sp, Roboto/system, with
 * M3's 0.5sp tracking), summed per label. They are inputs to the decision, not
 * assertions about the font: the component measures the real text at runtime
 * with a `TextMeasurer` and feeds it to the same functions asserted here. These
 * constants exist so the DECISION can be re-derived without a device.
 */
class BtSegmentedGeometryTest {

    private companion object {
        /** A 360dp-class phone, minus the app's 16dp gutters. */
        const val PHONE_CONTENT_WIDTH = 328f

        /** The same width inside a 16dp-padded card (asset page, backtest card). */
        const val CARD_CONTENT_WIDTH = 296f

        /** `Max` — the longest range label in either language, at fontScale 1.0. */
        const val LABEL_MAX = 25f

        /** The other five, for the sum below: 1D, 1W, 1M, 6M, 1Y. */
        val RANGE_LABELS_1X = listOf(16.0f, 18.9f, 18.5f, 18.5f, 15.4f, LABEL_MAX)

        /** `€`, `%`, `€%` — the display picker's three marks. */
        val MODE_LABELS_1X = listOf(7.2f, 10.3f, 16.3f)

        /** A segment's side padding when it hugs its content, ×2 for both sides. */
        const val CONTENT_SEGMENT_PADDING = 28f

        /** The display picker's floor, which is what its segments actually take. */
        const val MODE_SEGMENT_FLOOR = 46f

        /** The gap between the two controls, had they shared a row. */
        const val ROW_GAP = 8f

        /**
         * What a content-hugging [BtSegmented] measures, end to end: the track's
         * two 3dp insets, the gaps between segments, and every segment's own
         * padding + label.
         */
        fun contentWidth(labels: List<Float>, floor: Float = 0f): Float {
            val track = 2 * SEGMENTED_TRACK_INSET.value
            val gaps = (labels.size - 1) * SEGMENTED_SEGMENT_GAP.value
            val segments = labels.sumOf { maxOf(it + CONTENT_SEGMENT_PADDING, floor).toDouble() }
            return track + gaps + segments.toFloat()
        }

        fun scaled(labels: List<Float>, fontScale: Float) = labels.map { it * fontScale }
    }

    // ── The layout decision: why the two pickers are not side by side ─────────

    @Test
    fun `the two pickers cannot share a row on a phone`() {
        // The mode picker takes its floor (three one-glyph marks pinned to 46dp);
        // the range picker hugs six labels. Both at the system default size.
        val mode = contentWidth(MODE_LABELS_1X, floor = MODE_SEGMENT_FLOOR)
        val range = contentWidth(RANGE_LABELS_1X)
        val together = mode + ROW_GAP + range

        // 148dp + 8dp + 296dp = 452dp, against 328dp of content width.
        assertEquals(148f, mode, 1f)
        assertEquals(296f, range, 2f)
        assertTrue(
            "side by side needs ${together}dp of $PHONE_CONTENT_WIDTH",
            together > PHONE_CONTENT_WIDTH,
        )
        // Not marginal — 38% over. Nothing short of deleting a window or
        // squeezing the targets closes a gap that size, which is the finding.
        assertTrue(together / PHONE_CONTENT_WIDTH > 1.35f)
    }

    @Test
    fun `side by side gets further out of reach as the font grows`() {
        val at13 = contentWidth(scaled(MODE_LABELS_1X, 1.3f), floor = MODE_SEGMENT_FLOOR * 1.3f) +
            ROW_GAP +
            contentWidth(scaled(RANGE_LABELS_1X, 1.3f))
        // ~527dp against 328dp: 61% over at the top of the supported band.
        assertTrue(at13 > PHONE_CONTENT_WIDTH * 1.55f)
    }

    @Test
    fun `six content-sized segments do not even fit alone at font scale 1_3`() {
        // The other half of the decision, and the reason the range row divides
        // the width rather than hugging its labels: hugging overflows a 360dp
        // phone by itself once the system font is turned up.
        val alone = contentWidth(scaled(RANGE_LABELS_1X, 1.3f))
        assertTrue("range row alone is ${alone}dp", alone > PHONE_CONTENT_WIDTH)
    }

    // ── The width policy the range picker actually uses ───────────────────────

    @Test
    fun `the portfolio hero's six windows divide the width comfortably`() {
        val share = equalSegmentShareDp(PHONE_CONTENT_WIDTH, segmentCount = 6)
        assertEquals(52f, share, 0.5f)
        assertTrue(rangeSegmentsFitEqually(PHONE_CONTENT_WIDTH, 6, LABEL_MAX))
        // 13.5dp of air per side around the longest label — the display picker
        // above the chart gives its marks 14–19dp, so the two controls framing
        // the canvas read at the same rhythm. That is the owner's "same design".
        assertEquals(13.5f, (share - LABEL_MAX) / 2f, 0.5f)
    }

    @Test
    fun `the six still divide the width at font scale 1_3`() {
        // The band the app supports. Beyond it the row is allowed to scroll
        // rather than squeeze — see the fallback test below.
        assertTrue(rangeSegmentsFitEqually(PHONE_CONTENT_WIDTH, 6, LABEL_MAX * 1.3f))
        assertFalse(rangeSegmentsFitEqually(PHONE_CONTENT_WIDTH, 6, LABEL_MAX * 1.6f))
    }

    @Test
    fun `the asset page's eight windows take the scrolling path instead`() {
        // 38.5dp a segment would leave `Max` under 7dp of air per side — a label
        // jammed against its pill, and a target narrower than the six-window row
        // on the same phone. The component measures this and scrolls instead.
        val share = equalSegmentShareDp(PHONE_CONTENT_WIDTH, segmentCount = 8)
        assertEquals(38.5f, share, 0.5f)
        assertFalse(rangeSegmentsFitEqually(PHONE_CONTENT_WIDTH, 8, LABEL_MAX))
        // And inside a card, where it actually renders, it is tighter still.
        assertFalse(rangeSegmentsFitEqually(CARD_CONTENT_WIDTH, 8, LABEL_MAX))
    }

    @Test
    fun `the backtest card's four windows divide it with room to spare`() {
        assertTrue(rangeSegmentsFitEqually(CARD_CONTENT_WIDTH, 4, LABEL_MAX))
        // Even at the top of the font band.
        assertTrue(rangeSegmentsFitEqually(CARD_CONTENT_WIDTH, 4, LABEL_MAX * 1.3f))
    }

    // ── The arithmetic itself ────────────────────────────────────────────────

    @Test
    fun `an equal share accounts for the track and every gap`() {
        // Six segments: two 3dp insets and five 2dp gaps come off the top.
        assertEquals((100f - 6f - 10f) / 6f, equalSegmentShareDp(100f, 6), 0.001f)
        // One segment has no gaps at all.
        assertEquals(100f - 6f, equalSegmentShareDp(100f, 1), 0.001f)
    }

    @Test
    fun `a control with no options never claims to fit`() {
        // Guards the division: an empty range set is a bug elsewhere, not a
        // divide-by-zero here.
        assertEquals(0f, equalSegmentShareDp(328f, 0), 0.001f)
        assertFalse(rangeSegmentsFitEqually(328f, 0, LABEL_MAX))
    }

    @Test
    fun `a width too small for the labels is never divided`() {
        // The fallback has to trigger on narrow screens too, not only on long
        // option lists.
        assertFalse(rangeSegmentsFitEqually(200f, 6, LABEL_MAX))
    }
}
