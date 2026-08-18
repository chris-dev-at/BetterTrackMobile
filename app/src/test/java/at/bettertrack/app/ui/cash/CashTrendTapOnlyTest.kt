package at.bettertrack.app.ui.cash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The cash-flow chart is **tap only** (owner ruling 2026-08-17): *"beim cashflow
 * diagramm soll ich nicht scrollen können (weil das stört das hoch und runter
 * scrollen) sondern ich soll einfach klicken können auf einen monat."*
 *
 * A source scan, because the bug is not a value any pure function returns — it
 * is a gesture handler CONSUMING horizontal moves, which starves the enclosing
 * LazyColumn of the same pointer stream and makes the page feel stuck. The chart
 * had that scrub for one round and it has to stay gone; `detectTapGestures` is
 * the replacement precisely because it never consumes the drag.
 */
class CashTrendTapOnlyTest {

    private fun summarySection(): String {
        val name = "src/main/java/at/bettertrack/app/ui/cash/CashSummarySection.kt"
        val candidates = listOf(File(name), File("app/$name"))
        return (
            candidates.firstOrNull { it.isFile }
                ?: error("CashSummarySection.kt not found; tried ${candidates.map { it.absolutePath }}")
            ).readText()
    }

    /** The `CashTrendsBlock` composable body, by brace matching. */
    private fun trendsBlock(): String {
        val source = summarySection()
        val start = source.indexOf("fun CashTrendsBlock(")
        require(start >= 0) { "CashTrendsBlock is gone from the cash summary section" }
        // The signature's own `) {`, so a default value containing parentheses
        // (`onOpenMonth: ((String) -> Unit)?`) cannot fool the brace walk.
        val open = source.indexOf(") {", start) + 2
        require(open > 2) { "CashTrendsBlock has no body" }
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        error("unbalanced braces in CashTrendsBlock")
    }

    @Test
    fun `the chart selects on a tap`() {
        val block = trendsBlock()
        assertTrue(
            "the cash-flow chart lost its tap handler — a month can no longer be selected",
            block.contains("detectTapGestures"),
        )
        assertTrue(
            "the sticky toggle semantics are gone (tapping the selected month must clear it)",
            block.contains("toggleTrendMonth"),
        )
        assertTrue(
            "the tap no longer maps an x to a month",
            block.contains("trendIndexAt"),
        )
    }

    @Test
    fun `the chart has no drag-scrub to steal the page scroll`() {
        val block = trendsBlock()
        listOf(
            "awaitEachGesture" to "hand-rolled pointer loop",
            "awaitFirstDown" to "hand-rolled pointer loop",
            "touchSlop" to "drag-slop detection",
            "changedToUpIgnoreConsumed" to "hand-rolled up handling",
            "detectHorizontalDragGestures" to "horizontal drag detector",
            "detectDragGestures" to "drag detector",
        ).forEach { (needle, what) ->
            assertFalse(
                "$what ($needle) is back in the cash-flow chart — the owner removed the " +
                    "drag-scrub on 2026-08-17 because it fought the page's vertical scroll",
                block.contains(needle),
            )
        }
        // The one that actually caused it: a pointer change being consumed.
        assertFalse(
            "the chart consumes pointer changes again — that is exactly what starved the " +
                "enclosing LazyColumn of the vertical drag",
            block.contains(".consume()"),
        )
    }

    @Test
    fun `the tap still fires the shared scrub haptic`() {
        val block = trendsBlock()
        assertTrue(
            "the per-selection detent is gone; the chart must keep using the shared BtScrubTicker",
            block.contains("ticker.crossed("),
        )
    }
}
