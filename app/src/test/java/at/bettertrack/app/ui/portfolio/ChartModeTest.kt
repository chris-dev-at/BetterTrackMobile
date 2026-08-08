package at.bettertrack.app.ui.portfolio

import at.bettertrack.app.R
import at.bettertrack.app.data.prefs.BtChartMode
import at.bettertrack.app.data.prefs.DEFAULT_CHART_MODE
import at.bettertrack.app.data.prefs.chartModeFromName
import at.bettertrack.app.data.repo.AssetRange
import at.bettertrack.app.data.repo.BacktestRange
import at.bettertrack.app.data.repo.HistoryPoint
import at.bettertrack.app.data.repo.HistoryRange
import at.bettertrack.app.ui.charts.rangeLabelRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 *
 * Extended 2026-08-08 with the three things the owner's follow-up changed: that
 * NOTHING on the hero tints by sign outside the pure % mode ([signColorAllowed],
 * the gate the readouts were missing), that the combined mode leads the picker
 * ([CHART_MODES]), and that reordering the picker cannot reinterpret anybody's
 * stored preference.
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
    fun `no readout on the hero may tint by sign outside the pure percent mode`() {
        // The hue bleed of 2026-08-08. `colorsBySign` reached exactly one
        // consumer — the canvas — while the two readouts framing it coloured
        // themselves off their own sign. `signColorAllowed` is the gate they all
        // consult now, and this is the assertion that it stays %-only.
        assertTrue(signColorAllowed(BtChartMode.PERFORMANCE))
        assertFalse(signColorAllowed(BtChartMode.HYBRID))
        // The € mode was tinted too, by the same two readouts. Owner expected it
        // neutral; it now is, under the same rule.
        assertFalse(signColorAllowed(BtChartMode.BALANCE))
    }

    @Test
    fun `the gate and the curve flag can never disagree`() {
        // One rule, not two: if a later change gives the curve its own idea of
        // when to colour, the readouts must move with it or this fails.
        BtChartMode.entries.forEach { mode ->
            assertEquals(mode.colorsBySign, signColorAllowed(mode))
        }
    }

    // ── Picker order (owner order 2026-08-08) ────────────────────────────────

    @Test
    fun `the combined mode leads the picker`() {
        assertEquals(
            listOf(BtChartMode.HYBRID, BtChartMode.BALANCE, BtChartMode.PERFORMANCE),
            CHART_MODES,
        )
        // The leading segment is the default, which is the point of moving it:
        // the selected pill is where the eye lands.
        assertEquals(DEFAULT_CHART_MODE, CHART_MODES.first())
    }

    @Test
    fun `the picker offers every mode exactly once`() {
        assertEquals(BtChartMode.entries.size, CHART_MODES.size)
        assertEquals(BtChartMode.entries.toSet(), CHART_MODES.toSet())
    }

    @Test
    fun `the picker order is a display order and not the stored one`() {
        // The reorder is safe because these two lists are allowed to differ:
        // the preference is stored by NAME, the enum's declaration order is
        // untouched, and nothing maps a segment index to a mode. If someone ever
        // "tidies" the enum to match the picker, the round-trip tests above are
        // what keep stored preferences honest — but the two orders differing is
        // itself the proof that no positional mapping exists.
        assertNotEquals(BtChartMode.entries.toList(), CHART_MODES)
        CHART_MODES.forEach { mode ->
            assertEquals(mode, chartModeFromName(mode.name))
        }
    }

    @Test
    fun `a stored preference is never read as a segment index`() {
        // What a positional mapping would have written. None of these is a mode
        // name, so every one of them is garbage that falls back to the default
        // rather than selecting the mode at that position.
        listOf("0", "1", "2").forEach { ordinalish ->
            assertEquals(DEFAULT_CHART_MODE, chartModeFromName(ordinalish))
        }
    }

    @Test
    fun `every mode has its own label and its own spoken form`() {
        val labels = BtChartMode.entries.map { chartModeLabel(it) }
        val spoken = BtChartMode.entries.map { chartModeContentDescription(it) }
        assertEquals(labels.size, labels.toSet().size)
        assertEquals(spoken.size, spoken.toSet().size)
        // The visible label is a glyph, so a spoken form is mandatory for all
        // three — a segment reading out "€%" is not an accessible name.
        BtChartMode.entries.forEach { mode ->
            assertNotEquals(chartModeLabel(mode), chartModeContentDescription(mode))
        }
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

    // ── The range picker, once it became the same control (owner 2026-08-08) ──
    //
    // *"Make the timespan selection for the graph the same design as the €% € %
    // thingy."* The range row stopped being six loose `BtChip`s and became a
    // `BtRangeSegmented`, i.e. the same `BtSegmented` the modes use. What that
    // migration must not disturb is pinned here; the WIDTH half of the same ask
    // lives in `BtSegmentedGeometryTest`.

    @Test
    fun `the range picker offers the windows in reading order`() {
        // The picker iterates `HistoryRange.entries` directly, so the enum's
        // declaration order IS the display order — shortest window first through
        // to Max. Unlike the mode picker there is no separate display list, and
        // this is the assertion that stops one appearing by accident.
        assertEquals(
            listOf(
                HistoryRange.D1,
                HistoryRange.W1,
                HistoryRange.M1,
                HistoryRange.M6,
                HistoryRange.Y1,
                HistoryRange.MAX,
            ),
            HistoryRange.entries.toList(),
        )
        // Six windows, and the one that opens is the month.
        assertEquals(6, HistoryRange.entries.size)
        assertEquals(HistoryRange.M1, HistoryRange.DEFAULT)
    }

    @Test
    fun `every window has its own label`() {
        val labels = HistoryRange.entries.map { rangeLabelRes(it) }
        assertEquals(labels.size, labels.toSet().size)
        // The exact resources, because these are the strings the owner reads:
        // German prints 1J for Y1 and the rest are language-neutral, so a
        // mis-wired id is invisible in English and wrong in German.
        assertEquals(R.string.bt_range_1d, rangeLabelRes(HistoryRange.D1))
        assertEquals(R.string.bt_range_1w, rangeLabelRes(HistoryRange.W1))
        assertEquals(R.string.bt_range_1m, rangeLabelRes(HistoryRange.M1))
        assertEquals(R.string.bt_range_6m, rangeLabelRes(HistoryRange.M6))
        assertEquals(R.string.bt_range_1y, rangeLabelRes(HistoryRange.Y1))
        assertEquals(R.string.bt_range_max, rangeLabelRes(HistoryRange.MAX))
    }

    @Test
    fun `selecting a window round-trips as the window itself`() {
        // `BtSegmented` hands `onSelect` the option object it was given, and the
        // range travels to the server as its wire string. Neither step may go
        // through a position — the same rule the mode picker has, now that the
        // two controls are the same control.
        HistoryRange.entries.forEach { range ->
            assertEquals(range, HistoryRange.fromWire(range.wire))
        }
        // What a positional mapping would have produced. None of these is a wire
        // value, so every one is a miss rather than a silent window swap.
        listOf("0", "1", "2", "3", "4", "5").forEach { ordinalish ->
            assertNull(HistoryRange.fromWire(ordinalish))
        }
    }

    @Test
    fun `adopting the range picker left the mode labels alone`() {
        // The regression the migration could plausibly cause: one shared
        // component, two callers, and a "tidy-up" that gives the modes the range
        // treatment would silently retitle the picker the owner just specified.
        assertEquals(R.string.bt_chart_mode_hybrid, chartModeLabel(BtChartMode.HYBRID))
        assertEquals(R.string.bt_chart_mode_balance, chartModeLabel(BtChartMode.BALANCE))
        assertEquals(R.string.bt_chart_mode_performance, chartModeLabel(BtChartMode.PERFORMANCE))
        // And the two label sets stay disjoint — no mode ever wears a window's
        // string, no window ever wears `€%`.
        val modeLabels = BtChartMode.entries.map { chartModeLabel(it) }.toSet()
        val rangeLabels = HistoryRange.entries.map { rangeLabelRes(it) }.toSet()
        assertTrue(modeLabels.intersect(rangeLabels).isEmpty())
    }

    // ── The other two charts' windows (coordinator ruling 2026-08-08) ─────────
    //
    // `AssetRange` and `BacktestRange` carried hardcoded English `label` fields
    // on the enums themselves, so a German reader saw `1Y` and `5Y` on an asset
    // page and `1J` on the hero — invisible until the pickers became the same
    // control, then obviously broken. The labels moved to resources; these are
    // the assertions that they stay there and stay shared.

    @Test
    fun `every asset window has its own label`() {
        val labels = AssetRange.entries.map { rangeLabelRes(it) }
        assertEquals(labels.size, labels.toSet().size)
        assertEquals(R.string.bt_range_1d, rangeLabelRes(AssetRange.D1))
        assertEquals(R.string.bt_range_1w, rangeLabelRes(AssetRange.W1))
        assertEquals(R.string.bt_range_1m, rangeLabelRes(AssetRange.M1))
        assertEquals(R.string.bt_range_3m, rangeLabelRes(AssetRange.M3))
        assertEquals(R.string.bt_range_6m, rangeLabelRes(AssetRange.M6))
        assertEquals(R.string.bt_range_1y, rangeLabelRes(AssetRange.Y1))
        assertEquals(R.string.bt_range_5y, rangeLabelRes(AssetRange.Y5))
        assertEquals(R.string.bt_range_max, rangeLabelRes(AssetRange.MAX))
    }

    @Test
    fun `every backtest window has its own label`() {
        val labels = BacktestRange.entries.map { rangeLabelRes(it) }
        assertEquals(labels.size, labels.toSet().size)
        assertEquals(R.string.bt_range_1y, rangeLabelRes(BacktestRange.Y1))
        assertEquals(R.string.bt_range_3y, rangeLabelRes(BacktestRange.Y3))
        assertEquals(R.string.bt_range_5y, rangeLabelRes(BacktestRange.Y5))
        assertEquals(R.string.bt_range_max, rangeLabelRes(BacktestRange.MAX))
    }

    @Test
    fun `a window that means the same thing reads from the same string`() {
        // The point of one vocabulary rather than three. `1Y` is the same year on
        // all three charts, so it is one resource — two copies is how the German
        // asset page drifted from the German hero in the first place.
        assertEquals(rangeLabelRes(HistoryRange.Y1), rangeLabelRes(AssetRange.Y1))
        assertEquals(rangeLabelRes(HistoryRange.Y1), rangeLabelRes(BacktestRange.Y1))
        assertEquals(rangeLabelRes(HistoryRange.MAX), rangeLabelRes(AssetRange.MAX))
        assertEquals(rangeLabelRes(HistoryRange.MAX), rangeLabelRes(BacktestRange.MAX))
        assertEquals(rangeLabelRes(AssetRange.Y5), rangeLabelRes(BacktestRange.Y5))
        assertEquals(rangeLabelRes(HistoryRange.D1), rangeLabelRes(AssetRange.D1))
        assertEquals(rangeLabelRes(HistoryRange.W1), rangeLabelRes(AssetRange.W1))
        assertEquals(rangeLabelRes(HistoryRange.M1), rangeLabelRes(AssetRange.M1))
        assertEquals(rangeLabelRes(HistoryRange.M6), rangeLabelRes(AssetRange.M6))
    }

    @Test
    fun `a window's wire value is never its label`() {
        // The labels moved; the platform vocabulary did not. `MAX` still goes to
        // the server as `MAX` in every locale, and no translator can reach it.
        assertEquals(listOf("1D", "1W", "1M", "6M", "1Y", "MAX"), HistoryRange.entries.map { it.wire })
        assertEquals(
            listOf("1D", "1W", "1M", "3M", "6M", "1Y", "5Y", "MAX"),
            AssetRange.entries.map { it.wire },
        )
        assertEquals(listOf("1Y", "3Y", "5Y", "MAX"), BacktestRange.entries.map { it.wire })
        // And the enum names are untouched too — they are what stored state and
        // the repositories key on.
        assertEquals(AssetRange.M1, AssetRange.fromWire("1M"))
        assertEquals(AssetRange.M1, AssetRange.DEFAULT)
        assertEquals(BacktestRange.Y1, BacktestRange.DEFAULT)
    }
}
