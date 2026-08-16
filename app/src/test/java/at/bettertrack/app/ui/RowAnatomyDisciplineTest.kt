package at.bettertrack.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The four-slot anatomy the owner settled on for BOTH money lists, and the one
 * thing about it that is invisible in a diff: **which figure sits where**.
 *
 * ## The contract (owner, 2026-08-17, third and final pass)
 *
 * *"on the overview under portfolios you swap the bottom 2 so bottom left is %
 * and bottom right is the value. and do the same arrangement with the holdings
 * in portfolio."*
 *
 * | | left | right |
 * |---|---|---|
 * | **top** | name (prominent) | current value (NEUTRAL) |
 * | **bottom** | gain/loss % (coloured) | gain/loss € (coloured) |
 *
 * Identical for `HomePortfolioRow` and `HoldingRow` — that sameness IS the
 * design, so it is asserted twice against the same table rather than once
 * against whichever row happens to be under review.
 *
 * ## Why a source scan
 *
 * The project has no Compose UI test suite (`androidTest` holds one instrumented
 * stub), and these rows are pure layout — no view model decides the order, so
 * there is nothing below the composable to assert instead. The arrangement was
 * re-spec'd three times in two days from the same four ingredients, and every
 * change was *moving one of them*. A reviewer reading a diff that swaps two
 * `MoneyText` calls has no way to tell which arrangement is the approved one;
 * this file records it.
 */
class RowAnatomyDisciplineTest {

    private fun source(path: String): String {
        val candidates = listOf(File("src/main/java/$path"), File("app/src/main/java/$path"))
        return (
            candidates.firstOrNull { it.isFile }
                ?: error("source not found; tried ${candidates.map { it.absolutePath }}")
            ).readText()
    }

    /**
     * The body of [function], by brace matching from its signature, from the
     * left column onwards.
     *
     * Trimming to the first `Column(Modifier.weight(1f))` is what makes the
     * ordering assertions honest: `HoldingRow` opens with a status rail whose
     * argument mentions BOTH P&L fields (`rangeRail(pct ?: eur)`), and a naive
     * `indexOf` would read the rail's mention as the row's first slot.
     */
    private fun columns(source: String, function: String): String {
        val start = source.indexOf(function)
        require(start >= 0) { "$function not found — was it renamed?" }
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) {
                    val body = source.substring(open, i + 1)
                    val left = body.indexOf("Column(Modifier.weight(1f))")
                    require(left >= 0) { "$function no longer opens with a weighted left column" }
                    return body.substring(left)
                }
            }
        }
        error("unbalanced braces after $function")
    }

    /**
     * The rest of the argument list [marker] sits in — from the `value = …`
     * argument to the closing paren of the `MoneyText(` call around it — so a
     * colour assertion can never read the NEXT call's arguments.
     */
    private fun restOfCall(body: String, marker: String): String {
        val at = body.indexOf(marker)
        require(at >= 0) { "$marker not found — was the row rewritten?" }
        var depth = 1
        for (i in at until body.length) {
            when (body[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return body.substring(at, i)
            }
        }
        error("unbalanced parens after $marker")
    }

    /** Asserts [markers] appear in the given order inside [body]. */
    private fun assertOrder(label: String, body: String, vararg markers: String) {
        val positions = markers.map { marker ->
            val at = body.indexOf(marker)
            assertTrue("$label: `$marker` is gone from the row", at >= 0)
            marker to at
        }
        positions.zipWithNext().forEach { (first, second) ->
            assertTrue(
                "$label: expected `${first.first}` before `${second.first}` " +
                    "(${first.second} vs ${second.second}) — the owner's anatomy moved",
                first.second < second.second,
            )
        }
    }

    private fun portfolioRow() =
        columns(source("at/bettertrack/app/ui/home/HomeScreen.kt"), "private fun HomePortfolioRow(")

    private fun holdingRow() = columns(
        source("at/bettertrack/app/ui/portfolio/PortfolioOverviewScreen.kt"),
        "private fun HoldingRow(",
    )

    @Test
    fun `the portfolio row reads name, percent, value, euro`() {
        assertOrder(
            "portfolio row",
            portfolioRow(),
            "portfolio.name",
            "totals.unrealizedPnlPct",
            "totals.totalValueEur",
            "totals.unrealizedPnlEur",
        )
    }

    @Test
    fun `the holding row wears the identical arrangement`() {
        assertOrder(
            "holding row",
            holdingRow(),
            "holding.assetName",
            "holding.unrealizedPnlPct",
            "holding.marketValueEur",
            "holding.unrealizedPnlEur",
        )
    }

    @Test
    fun `both rows keep the value neutral and both verdicts coloured`() {
        // "momentaner totalwert in normal": the value must not pick up a sign
        // colour — that is what lets the coloured pair read as the verdict.
        val pf = portfolioRow()
        val pfValue = restOfCall(pf, "value = totals.totalValueEur")
        assertTrue("the portfolio value must stay neutral: $pfValue", !pfValue.contains("deltaColor"))
        val pfEur = restOfCall(pf, "value = totals.unrealizedPnlEur")
        assertTrue(
            "the portfolio P&L € must be sign-coloured with an explicit sign: $pfEur",
            pfEur.contains("deltaColor") && pfEur.contains("showSign = true"),
        )

        val hold = holdingRow()
        val holdValue = restOfCall(hold, "value = value,")
        assertTrue(
            "the holding's position value must stay neutral: $holdValue",
            holdValue.contains("bt.textPrimary") && !holdValue.contains("deltaColor"),
        )
        val holdEur = restOfCall(hold, "value = plEur")
        assertTrue(
            "the holding P&L € must be sign-coloured with an explicit sign: $holdEur",
            holdEur.contains("deltaColor") && holdEur.contains("showSign = true"),
        )
    }

    @Test
    fun `the top line stays prominent and the bottom line secondary in both rows`() {
        // The hierarchy is the other half of the spec: swapping the two slots
        // must not also swap their weights, or the row starts shouting its
        // delta and whispering its value.
        listOf("portfolio row" to portfolioRow(), "holding row" to holdingRow()).forEach { (label, row) ->
            val top = row.indexOf("BtTheme.type.moneySmall")
            val bottom = row.lastIndexOf("BtTheme.type.numberCaption")
            assertTrue("$label: the value lost its prominent style", top >= 0)
            assertTrue("$label: the bottom line lost its caption style", bottom > top)
        }
    }

    /**
     * The quantity left the holdings row on the owner's instruction (*"remove
     * the (23.12 NVIDIA)"*). The RULE that formatted it is deliberately kept —
     * he has specified it twice — so this pins that the row does not quietly
     * grow it back without a new instruction.
     */
    @Test
    fun `the holding row carries no quantity`() {
        val row = holdingRow()
        listOf("formatHoldingQuantity(", "formatHoldingSubline(", "holding.quantity").forEach { gone ->
            assertTrue("the holding row grew `$gone` back", !row.contains(gone))
        }
    }
}
