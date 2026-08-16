package at.bettertrack.app.ui.portfolio

import at.bettertrack.app.data.repo.HistoryPoint
import at.bettertrack.app.data.repo.HistoryRange
import at.bettertrack.app.ui.charts.rangeWordRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hero chart's control row after the owner's 2026-08-16 batch: 6M is gone
 * from the PORTFOLIO picker, the two pickers share one row below the canvas
 * when the measured arithmetic allows it, and the delta line speaks in words.
 */
class ChartControlsTest {

    // ── A1: the range set ───────────────────────────────────────────────────

    @Test
    fun `the portfolio picker no longer offers 6M`() {
        assertFalse(HistoryRange.M6 in PORTFOLIO_RANGES)
    }

    @Test
    fun `every other served window survives, in reading order`() {
        assertEquals(
            listOf(
                HistoryRange.D1,
                HistoryRange.W1,
                HistoryRange.M1,
                HistoryRange.Y1,
                HistoryRange.MAX,
            ),
            PORTFOLIO_RANGES,
        )
    }

    @Test
    fun `the default window is still offered`() {
        assertTrue(HistoryRange.DEFAULT in PORTFOLIO_RANGES)
    }

    @Test
    fun `dropping 6M is a display decision - the wire enum still carries it`() {
        // Widgets and the asset page still request it; only this picker stopped
        // offering it. If the enum ever loses the window this stops compiling,
        // which is the correct alarm.
        assertEquals("6M", HistoryRange.M6.wire)
    }

    // ── A4: the delta line's vocabulary ─────────────────────────────────────

    @Test
    fun `every window has its own word`() {
        val words = HistoryRange.entries.map { rangeWordRes(it) }
        assertEquals(words.size, words.toSet().size)
    }

    @Test
    fun `the range delta is the last server point minus the first`() {
        val points = listOf(
            HistoryPoint(epochMillis = 1L, valueEur = 1_000.0),
            HistoryPoint(epochMillis = 2L, valueEur = 1_040.0),
            HistoryPoint(epochMillis = 3L, valueEur = 1_025.5),
        )
        assertEquals(25.5, rangeDeltaEur(points)!!, 1e-9)
    }

    @Test
    fun `a series that cannot carry a difference stays silent`() {
        assertNull(rangeDeltaEur(emptyList()))
        assertNull(rangeDeltaEur(listOf(HistoryPoint(1L, 1_000.0))))
    }

    @Test
    fun `a losing window reports a negative delta`() {
        val points = listOf(HistoryPoint(1L, 500.0), HistoryPoint(2L, 420.0))
        assertEquals(-80.0, rangeDeltaEur(points)!!, 1e-9)
    }

    // ── A2: the side-by-side fit arithmetic ─────────────────────────────────
    //
    // The compact mode track is 105dp at fontScale 1.0; a 360dp-class phone
    // offers 328dp between the gutters. "Max" measures ~25dp at SemiBold
    // labelMedium — the pair MUST fit there, because that phone is the phone
    // the owner asked on.

    @Test
    fun `the pair shares a row on a 360dp-class phone at default font scale`() {
        assertTrue(
            chartControlsFitSideBySide(
                availableWidthDp = 328f,
                modeTrackWidthDp = 105f,
                widestRangeLabelDp = 25f,
            ),
        )
    }

    @Test
    fun `a narrow window stacks instead of squeezing`() {
        assertFalse(
            chartControlsFitSideBySide(
                availableWidthDp = 240f,
                modeTrackWidthDp = 105f,
                widestRangeLabelDp = 25f,
            ),
        )
    }

    @Test
    fun `accessibility-scaled labels stack instead of wrapping`() {
        // ~fontScale 1.6: the widest label outgrows the share the row can give.
        assertFalse(
            chartControlsFitSideBySide(
                availableWidthDp = 328f,
                modeTrackWidthDp = 137f,
                widestRangeLabelDp = 40f,
            ),
        )
    }

    @Test
    fun `the threshold is the label plus its breathing room exactly`() {
        // Available = mode + gap + track inset (6) + 4 gaps (8) + 5 shares.
        val label = 25f
        val breathing = CHART_CONTROLS_RANGE_BREATHING.value
        val exactly = 105f + CHART_CONTROLS_GAP.value + 6f + 8f + 5 * (label + 2 * breathing)
        assertTrue(chartControlsFitSideBySide(exactly, 105f, label))
        assertFalse(chartControlsFitSideBySide(exactly - 1f, 105f, label))
    }
}
