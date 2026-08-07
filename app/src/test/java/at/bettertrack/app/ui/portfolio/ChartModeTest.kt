package at.bettertrack.app.ui.portfolio

import at.bettertrack.app.data.prefs.BtChartMode
import at.bettertrack.app.data.prefs.DEFAULT_CHART_MODE
import at.bettertrack.app.data.prefs.chartModeFromName
import at.bettertrack.app.data.repo.HistoryPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hero chart's display modes (owner ask 2026-08-07) — the parts that are pure.
 *
 * The mode the owner actually asked for is the hybrid: *"a chart mode where the
 * curve renders the performance-% series but the scrub readout shows the balance
 * € at that point"*. Both halves of that sentence are pinned here — which series
 * the curve comes from ([BtChartMode.plotsPerformance]) and where the € comes
 * from ([balanceAt]).
 */
class ChartModeTest {

    @Test
    fun `only the balance mode plots euros`() {
        assertFalse(BtChartMode.BALANCE.plotsPerformance)
        assertTrue(BtChartMode.PERFORMANCE.plotsPerformance)
        // The hybrid's CURVE is the performance series — that is the whole idea.
        // Only its readout is money.
        assertTrue(BtChartMode.HYBRID.plotsPerformance)
    }

    @Test
    fun `only the pure percent mode colours its curve by sign`() {
        // Owner order 2026-08-07: "don't color it red or green — only color in
        // % mode." Hybrid used to inherit the gain/loss paint because this was
        // the same flag as `plotsPerformance`; splitting them is the fix, and
        // this is the assertion that keeps them split.
        assertTrue(BtChartMode.PERFORMANCE.colorsBySign)
        assertFalse(BtChartMode.HYBRID.colorsBySign)
        assertFalse(BtChartMode.BALANCE.colorsBySign)
        // The hybrid plots the % series AND stays neutral — the exact
        // combination the old single flag could not express.
        assertTrue(BtChartMode.HYBRID.plotsPerformance && !BtChartMode.HYBRID.colorsBySign)
    }

    @Test
    fun `an unset preference opens on the hybrid chart`() {
        // The default moved BALANCE → HYBRID by owner order 2026-08-07 ("make
        // this one the DEFAULT"). "Unset" is the ONLY state that moves.
        assertEquals(BtChartMode.HYBRID, chartModeFromName(null))
        assertEquals(DEFAULT_CHART_MODE, chartModeFromName(null))
    }

    @Test
    fun `an explicit stored choice survives the default move`() {
        // The other half of the migration, and the half that is easy to get
        // wrong: a user who actually picked € must stay on €, even though € is
        // no longer the default. A stored name is an explicit choice, always.
        assertEquals(BtChartMode.BALANCE, chartModeFromName("BALANCE"))
        assertEquals(BtChartMode.PERFORMANCE, chartModeFromName("PERFORMANCE"))
    }

    @Test
    fun `every mode round-trips through its stored name`() {
        BtChartMode.entries.forEach { mode ->
            assertEquals(mode, chartModeFromName(mode.name))
        }
    }

    @Test
    fun `an unrecognised stored mode falls back to the default instead of crashing`() {
        // A downgrade, or a hand-edited pref: names are stored rather than
        // ordinals precisely so this is a miss and not a misread. Note "balance"
        // lowercase is NOT a valid stored name and therefore is not an explicit
        // choice — it falls back like any other garbage.
        assertEquals(DEFAULT_CHART_MODE, chartModeFromName("SOMETHING_ELSE"))
        assertEquals(DEFAULT_CHART_MODE, chartModeFromName(""))
        assertEquals(DEFAULT_CHART_MODE, chartModeFromName("balance"))
    }

    // ── The hybrid readout ────────────────────────────────────────────────────

    @Test
    fun `the hybrid readout picks the balance the server sent for that moment`() {
        val points = listOf(
            HistoryPoint(epochMillis = 1_000L, valueEur = 100.0),
            HistoryPoint(epochMillis = 2_000L, valueEur = 200.0),
            HistoryPoint(epochMillis = 3_000L, valueEur = 300.0),
        )
        assertEquals(100.0, balanceAt(points, 1_000L)!!, 0.0001)
        assertEquals(200.0, balanceAt(points, 2_000L)!!, 0.0001)
        assertEquals(300.0, balanceAt(points, 3_000L)!!, 0.0001)
    }

    @Test
    fun `the hybrid readout never interpolates a balance the server did not send`() {
        // §7.1: the server is the only calculator. Asked for a moment BETWEEN two
        // points, this must answer with one of them — 150 would be the app
        // inventing money.
        val points = listOf(
            HistoryPoint(epochMillis = 1_000L, valueEur = 100.0),
            HistoryPoint(epochMillis = 2_000L, valueEur = 200.0),
        )
        val mid = balanceAt(points, 1_400L)!!
        assertTrue("expected a real data point, got $mid", mid == 100.0 || mid == 200.0)
        assertEquals(100.0, mid, 0.0001)
        assertEquals(200.0, balanceAt(points, 1_600L)!!, 0.0001)
    }

    @Test
    fun `the hybrid readout matches on time, not on index`() {
        // The two series are aligned by construction on the server but parsed
        // independently, so a length mismatch must not shift the readout by a
        // point. Here the balance series is SHORTER than a performance series
        // would be, and a time far past its end still resolves to its last point.
        val points = listOf(
            HistoryPoint(epochMillis = 1_000L, valueEur = 100.0),
            HistoryPoint(epochMillis = 2_000L, valueEur = 200.0),
        )
        assertEquals(200.0, balanceAt(points, 9_999L)!!, 0.0001)
        assertEquals(100.0, balanceAt(points, -9_999L)!!, 0.0001)
    }

    @Test
    fun `an empty balance series reports nothing rather than zero`() {
        // A €0 readout would be the same lie the W6 hero states exist to avoid.
        assertNull(balanceAt(emptyList(), 1_000L))
    }
}
