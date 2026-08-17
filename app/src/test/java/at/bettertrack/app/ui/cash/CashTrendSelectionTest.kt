package at.bettertrack.app.ui.cash

import at.bettertrack.app.data.api.dto.CashTrendPointDto
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The cash-flow chart's selection (owner ask 2026-08-16, repeated 2026-08-17:
 * *"man sollte beim cashflow diagram draufdrücken können … so dass man einen
 * monat selektieren kann"*).
 *
 * The hit test is the part worth pinning: it decides which month a finger lands
 * on, and getting it wrong is a chart that selects the neighbour of whatever the
 * user aimed at — the single most infuriating class of touch bug, and one that
 * looks like a rendering problem rather than an arithmetic one.
 */
class CashTrendSelectionTest {

    private fun point(month: String, inflow: Double, outflow: Double) =
        CashTrendPointDto(month = month, inflow = inflow, outflow = outflow)

    private val series = listOf(
        point("2026-03", 100.0, 40.0),
        point("2026-04", 0.0, 0.0),
        point("2026-05", 250.0, 300.0),
        point("2026-06", 90.0, 10.0),
    )

    // ── Hit testing ─────────────────────────────────────────────────────────

    @Test
    fun `each column claims its own equal share of the width`() {
        // 400px over 4 columns = 100px cells.
        assertEquals(0, trendIndexAt(0f, 400f, 4))
        assertEquals(0, trendIndexAt(99f, 400f, 4))
        assertEquals(1, trendIndexAt(100f, 400f, 4))
        assertEquals(2, trendIndexAt(250f, 400f, 4))
        assertEquals(3, trendIndexAt(399f, 400f, 4))
    }

    @Test
    fun `a finger past either edge clamps instead of falling off the series`() {
        // A drag that leaves the chart must keep reporting the last bar, not an
        // index the caller would use to read out of bounds.
        assertEquals(0, trendIndexAt(-50f, 400f, 4))
        assertEquals(3, trendIndexAt(4000f, 400f, 4))
    }

    @Test
    fun `an empty series is not selectable`() {
        assertEquals(-1, trendIndexAt(10f, 400f, 0))
    }

    @Test
    fun `a single bar owns the whole canvas`() {
        assertEquals(0, trendIndexAt(0f, 400f, 1))
        assertEquals(0, trendIndexAt(399f, 400f, 1))
    }

    @Test
    fun `a degenerate canvas never divides by zero`() {
        assertEquals(0, trendIndexAt(10f, 0f, 4))
        assertEquals(0, trendIndexAt(Float.NaN, 400f, 4))
    }

    // ── Sticky selection ────────────────────────────────────────────────────

    @Test
    fun `tapping a new month selects it`() {
        assertEquals("2026-05", toggleTrendMonth(null, "2026-05"))
        assertEquals("2026-06", toggleTrendMonth("2026-05", "2026-06"))
    }

    @Test
    fun `tapping the selected month clears the selection`() {
        // The owner's reset affordance, on the same gesture that made the state.
        assertNull(toggleTrendMonth("2026-05", "2026-05"))
    }

    @Test
    fun `a selection whose month left the window resolves to nothing`() {
        // The trailing window slides; a stale key must not read out as a wrong
        // month, it must read out as no selection.
        assertNull(resolveTrendSelection(series, "2026-01"))
        assertNull(resolveTrendSelection(series, null))
        assertEquals("2026-05", resolveTrendSelection(series, "2026-05")?.month)
    }

    // ── The figures ─────────────────────────────────────────────────────────

    @Test
    fun `net is signed in both directions`() {
        assertEquals(60.0, trendNet(point("2026-03", 100.0, 40.0)), 0.0001)
        assertEquals(-50.0, trendNet(point("2026-05", 250.0, 300.0)), 0.0001)
        assertEquals(0.0, trendNet(point("2026-04", 0.0, 0.0)), 0.0001)
    }

    @Test
    fun `a non-finite month contributes nothing rather than NaN`() {
        val broken = point("2026-07", Double.NaN, Double.POSITIVE_INFINITY)
        assertEquals(0.0, trendNet(broken), 0.0001)
        val totals = trendTotals(listOf(broken, point("2026-08", 10.0, 4.0)))
        assertEquals(10.0, totals.inflow, 0.0001)
        assertEquals(4.0, totals.outflow, 0.0001)
    }

    @Test
    fun `the default readout is the whole window`() {
        val totals = trendTotals(series)
        assertEquals(440.0, totals.inflow, 0.0001)
        assertEquals(350.0, totals.outflow, 0.0001)
        assertEquals(90.0, trendNet(totals), 0.0001)
    }

    @Test
    fun `an empty series totals to zero, not to an exception`() {
        val totals = trendTotals(emptyList())
        assertEquals(0.0, totals.inflow, 0.0001)
        assertEquals(0.0, totals.outflow, 0.0001)
    }

    // ── Naming ──────────────────────────────────────────────────────────────

    @Test
    fun `the readout heading names the month in full, with its year`() {
        assertEquals("August 2026", trendMonthTitle("2026-08", Locale.ENGLISH))
        assertEquals("August 2026", trendMonthTitle("2026-08", Locale.GERMAN))
        assertEquals("März 2026", trendMonthTitle("2026-03", Locale.GERMAN))
    }

    @Test
    fun `an unparseable bucket keeps its wire identity`() {
        assertEquals("nonsense", trendMonthTitle("nonsense", Locale.ENGLISH))
        assertNull(trendMonthRange("nonsense"))
    }

    @Test
    fun `the ledger door gets the month's inclusive first and last day`() {
        assertEquals(
            LocalDate.of(2026, 2, 1) to LocalDate.of(2026, 2, 28),
            trendMonthRange("2026-02"),
        )
        // A leap February is the case a hand-rolled "day 28" would get wrong.
        assertEquals(
            LocalDate.of(2028, 2, 1) to LocalDate.of(2028, 2, 29),
            trendMonthRange("2028-02"),
        )
    }
}
