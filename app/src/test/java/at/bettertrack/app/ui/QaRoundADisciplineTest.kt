package at.bettertrack.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The four fixes from the 2026-09-01 device pass that live entirely inside
 * composables, pinned by source scan.
 *
 * ## Why a source scan
 *
 * The project has no Compose UI test suite (`androidTest` holds one instrumented
 * stub), and each of these four is a rendering decision with no logic beneath it
 * to assert instead — a threshold in a `BoxWithConstraints`, a branch that draws
 * nothing, a parameter that must reach a call site. Every one of them is also a
 * decision a well-meaning tidy-up would undo, because each looks arbitrary in a
 * diff. This file records what the owner actually saw and what was changed.
 *
 * Same technique, same reasons, as `RowAnatomyDisciplineTest`.
 */
class QaRoundADisciplineTest {

    private fun source(path: String): String {
        val candidates = listOf(File("src/main/java/$path"), File("app/src/main/java/$path"))
        return (
            candidates.firstOrNull { it.isFile }
                ?: error("source not found; tried ${candidates.map { it.absolutePath }}")
            ).readText()
    }

    private val viz by lazy { source("at/bettertrack/app/ui/charts/viz/BtVizChart.kt") }
    private val configSheet by lazy { source("at/bettertrack/app/ui/insights/InsightConfigSheet.kt") }
    private val studio by lazy { source("at/bettertrack/app/ui/insights/InsightsStudioScreen.kt") }
    private val heatmap by lazy { source("at/bettertrack/app/ui/market/HeatmapScreen.kt") }

    // ── #17 · the config sheet states the period that is in force ───────────

    @Test
    fun `the insight config sheet never invents a period from the option list`() {
        // Device: the sheet said `Zeitraum: 1 Monat` while the page chip said
        // `1 Jahr` and the live preview inside the same sheet rendered
        // `02.09.2025 – 02.09.2026`. `periodKinds.first()` was the invented value;
        // nothing downstream ever read it.
        assertFalse(
            "the config sheet is guessing a period again (periodKinds.first())",
            configSheet.contains("periodKinds.first()"),
        )
        assertTrue(
            "the sheet must be handed the effective period",
            configSheet.contains("effectivePeriod: BtInsightPeriod"),
        )
        assertTrue(
            "the Zeitraum row must fall back to the effective period",
            configSheet.contains("draft.period?.kind ?: effectivePeriod.kind"),
        )
        assertTrue(
            "the studio must pass the effective period in",
            studio.contains("effectivePeriod = vm.effectivePeriodFor(insight, today)"),
        )
    }

    // ── #19 · a narrow treemap tile shrinks its amount before dropping it ───

    @Test
    fun `a narrow treemap tile keeps its amount at a smaller size`() {
        // Device: the "Krypto" tile printed name + share and dropped its €, the
        // only tile on the card without a value — which reads as missing DATA.
        assertTrue(
            "the amount's width floor must be the narrow tier",
            viz.contains("w >= VIZ_TILE_AMOUNT_MIN_W"),
        )
        assertTrue(
            "the narrow tier needs a smaller-type fallback",
            viz.contains("val amountTight = w < VIZ_TILE_AMOUNT_FULL_W"),
        )
        assertTrue(
            "the amount must actually render at the smaller style when tight",
            viz.contains("if (amountTight)"),
        )
    }

    // ── #20 · an empty ranked-bar chart paints no gridlines ─────────────────

    @Test
    fun `the empty ranked-bar chart draws nothing behind its message`() {
        // Device: three full-width rules above "Noch keine Daten" — three rules
        // over an empty plot are indistinguishable from three bars of length zero.
        assertTrue(
            "RANKED_BARS must draw no placeholder geometry when empty",
            viz.contains("BtVizForm.RANKED_BARS -> Unit"),
        )
    }

    // ── #28 · a quote-less heatmap tile says so ─────────────────────────────

    @Test
    fun `a heatmap tile with no quote can print a placeholder`() {
        // Device: the custom asset "Anthropic" printed a bare name among tiles
        // that all printed percentages.
        assertTrue(
            "BtVizHeatmap must accept a placeholder for a missing change",
            viz.contains("missingChangeText: String? = null"),
        )
        assertTrue(
            "the tile must fall back to it rather than to an empty string",
            viz.contains("cell.changePct?.let(changeText) ?: missingChangeText.orEmpty()"),
        )
        assertTrue(
            "the watchlist/positions heatmap must supply the dash",
            heatmap.contains("missingChangeText = stringResource(R.string.bt_value_dash)"),
        )
    }

    /**
     * The default stays null on purpose: the widget preview's folded `Andere`
     * bucket carries a null change because it IS an aggregate with no single
     * move, and stamping a dash on it would invent an absence rather than report
     * one. Only a caller that knows the null means "no quote" passes the
     * placeholder.
     */
    @Test
    fun `the placeholder is opt-in, not a blanket dash`() {
        assertTrue(viz.contains("missingChangeText: String? = null"))
    }
}
