package at.bettertrack.app.ui.portfolio

import at.bettertrack.app.data.prefs.BtChartMode
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
    fun `an unset preference opens on the balance chart`() {
        // The mode that existed before this feature; a first run must not change
        // what a returning user's chart looks like.
        assertEquals(BtChartMode.BALANCE, chartModeFromName(null))
    }

    @Test
    fun `every mode round-trips through its stored name`() {
        BtChartMode.entries.forEach { mode ->
            assertEquals(mode, chartModeFromName(mode.name))
        }
    }

    @Test
    fun `an unrecognised stored mode falls back instead of crashing`() {
        // A downgrade, or a hand-edited pref: names are stored rather than
        // ordinals precisely so this is a miss and not a misread.
        assertEquals(BtChartMode.BALANCE, chartModeFromName("SOMETHING_ELSE"))
        assertEquals(BtChartMode.BALANCE, chartModeFromName(""))
        assertEquals(BtChartMode.BALANCE, chartModeFromName("balance"))
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
