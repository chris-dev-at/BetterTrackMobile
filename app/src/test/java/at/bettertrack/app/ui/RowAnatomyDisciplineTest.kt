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
        val body = bodyOf(source, function)
        val left = body.indexOf("Column(Modifier.weight(1f))")
        require(left >= 0) { "$function no longer opens with a weighted left column" }
        return body.substring(left)
    }

    /**
     * [function]'s whole body, by brace matching from its signature.
     *
     * [columns] trims this to the content stacks, which is right for the
     * arrangement assertions and wrong for the chrome ones: the container a row
     * is wrapped in is the first thing [columns] throws away.
     */
    private fun bodyOf(source: String, function: String): String {
        val start = source.indexOf(function)
        require(start >= 0) { "$function not found — was it renamed?" }
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        error("unbalanced braces after $function")
    }

    /**
     * The argument list of the first [call] in [body], parens included.
     *
     * Distinct from [restOfCall], which starts *inside* a call at one of its
     * arguments: this one starts at the call itself, so [call] is expected to
     * end in `(` and the nested `fillMaxWidth()`-style parens are matched rather
     * than tripped over.
     */
    private fun argsOf(body: String, call: String): String {
        val at = body.indexOf(call)
        require(at >= 0) { "$call not found — was it renamed?" }
        val open = at + call.length - 1
        var depth = 0
        for (i in open until body.length) {
            when (body[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return body.substring(open, i + 1)
            }
        }
        error("unbalanced parens in $call")
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

    private fun holdingRow() = columns(source(OVERVIEW), "private fun HoldingRow(")

    private fun holdingRowBody() = bodyOf(source(OVERVIEW), "private fun HoldingRow(")

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
        //
        // Both rows name the SAME money token, and that is now a pinned fact
        // rather than a coincidence. On 2026-08-17 the holdings row was sent up
        // the ramp to `titleMedium`/`moneyMedium` on the reading that *"make it
        // normal again"* meant bigger; he looked at it and said *"why did the
        // holdings text increase insanely. just leave it like it was in v0.120
        // … the sizing and looks take from 0.120"*. v0.120 is `db3a049`, and its
        // row is exactly `titleSmall` / `moneySmall` / `numberCaption` with
        // 12dp vertical padding and 2dp stack gaps — which is what ships. The
        // ARRANGEMENT and the ticker stay today's; only the type came back.
        listOf(
            Triple("portfolio row", portfolioRow(), "BtTheme.type.moneySmall"),
            Triple("holding row", holdingRow(), "BtTheme.type.moneySmall"),
        ).forEach { (label, row, prominent) ->
            val top = row.indexOf(prominent)
            val bottom = row.lastIndexOf("BtTheme.type.numberCaption")
            assertTrue("$label: the value lost its prominent style ($prominent)", top >= 0)
            assertTrue("$label: the bottom line lost its caption style", bottom > top)
            // And the prominent slot must still out-rank the caption slot: a row
            // that printed its delta in the money style and its value in the
            // caption style would pass the ordering check above by accident.
            assertTrue(
                "$label: the value slot must not use the caption style",
                !row.substring(top, minOf(top + 120, row.length)).contains("numberCaption"),
            )
        }
    }

    /**
     * The ticker annotation is the NAME'S SIZE, and stays secondary on the two
     * axes that cost no size (owner, 2026-08-17: *"on the holdings in the
     * portfolio also make the text for the short names (NVDA or BAYN.DE) be the
     * same size as the text next to it"*).
     *
     * The token is compared to whatever the NAME uses rather than pinned to the
     * literal `titleSmall`, because "the same size as the text next to it" is a
     * relationship, not a value: if the name's type is ever retuned again, this
     * fails unless the annotation is retuned with it — which is the whole
     * instruction.
     */
    @Test
    fun `the holding row's ticker matches the name's size and stays muted and lighter`() {
        val row = holdingRow()
        val name = restOfCall(row, "text = holding.assetName,")
        val ticker = restOfCall(row, "text = ticker,")
        val token = Regex("""MaterialTheme\.typography\.(\w+)""")

        val nameToken = token.find(name)?.groupValues?.get(1)
        assertTrue("the holding name no longer names a Material type token: $name", nameToken != null)
        val tickerToken = token.find(ticker)?.groupValues?.get(1)
        assertTrue(
            "the ticker must wear the NAME's type token ($nameToken), not $tickerToken — " +
                "\"the same size as the text next to it\"",
            tickerToken == nameToken,
        )
        // Same size ⇒ colour and weight are the ONLY things left to make it
        // read as an annotation. Losing either turns it into a second title.
        assertTrue("the ticker lost its muted ink: $ticker", ticker.contains("bt.textMuted"))
        assertTrue(
            "the ticker must stay lighter than the name's SemiBold: $ticker",
            ticker.contains("FontWeight.Normal"),
        )
        assertTrue("the name lost its primary ink: $name", name.contains("bt.textPrimary"))
    }

    /**
     * …and the pair still truncates the right way round now that the ticker is
     * wider: the NAME takes the leftover width and ellipsizes, the ticker is
     * unweighted so it claims its intrinsic width first and stays whole. A
     * truncated name is still recognisable; a truncated ticker identifies
     * nothing.
     */
    @Test
    fun `the holding row ellipsizes the name and never the ticker`() {
        val row = holdingRow()
        val name = restOfCall(row, "text = holding.assetName,")
        val ticker = restOfCall(row, "text = ticker,")

        assertTrue("the name must ellipsize: $name", name.contains("TextOverflow.Ellipsis"))
        assertTrue(
            "the name must take the LEFTOVER width (weight(1f, fill = false)): $name",
            name.contains("weight(1f, fill = false)"),
        )
        assertTrue(
            "the ticker must stay unweighted so it is measured at its intrinsic width: $ticker",
            !ticker.contains(".weight("),
        )
        assertTrue(
            "the ticker must not ellipsize — a cut ticker identifies nothing: $ticker",
            !ticker.contains("TextOverflow.Ellipsis"),
        )
    }

    /**
     * The quantity left the holdings row on the owner's instruction (*"remove
     * the (23.12 NVIDIA)"*). The RULE that formatted it is deliberately kept —
     * he has specified it twice — so this pins that the row does not quietly
     * grow it back without a new instruction.
     */
    /**
     * The overview's rank, and the two moves that buy it (owner, 2026-08-17):
     *
     * *"cash und transaktionen können ja mehr weißlich statt grau werden und die
     * holdings einfach weniger prominentere hintergrund farbe. **nicht gleich die
     * hintergrund farbe entfernen. sondern nur leichter machen.**"*
     *
     * Both halves are pinned here because each one alone is a no-op: a weaker
     * holdings fill with grey quick-link labels leaves nothing leading, and
     * brighter labels over an equally loud list is the layout he started from.
     *
     * The failure modes this guards are not hypothetical — they are the three
     * corrections he has already issued on this row. It was made **smaller**
     * (twice: "why did the holdings text increase insanely … just leave it like
     * it was in v0.120", then the tightening pass), and it was made
     * **card-less** (transparent fill, no hairline — the reading of "less
     * important" he explicitly headed off with *"nicht gleich die hintergrund
     * farbe entfernen"*). So this asserts the fill is WEAKENED and, separately,
     * that it still EXISTS; and it asserts no size anywhere, because size is
     * never the lever on this page.
     */
    @Test
    fun `the holdings row stands down by fill, not by deletion`() {
        val card = argsOf(holdingRowBody(), "BtCard(")
        assertTrue(
            "the holdings row must stay `quiet = true` — the weakened fill is the " +
                "only thing letting the quick links out-rank a list this long: $card",
            card.contains("quiet = true"),
        )
    }

    /**
     * …and `quiet` has to keep meaning *weaker*, not *gone*. Asserted at the
     * component, because that is where the deletion was and where a future
     * "simplify" pass would put it back.
     */
    @Test
    fun `a quiet card is still a card`() {
        val card = bodyOf(source("at/bettertrack/app/ui/components/BtCards.kt"), "fun BtCard(")
        assertTrue(
            "a quiet card must be painted `surfaceQuiet` — a weaker fill, not no fill. " +
                "The owner rejected the transparent version: \"nicht gleich die " +
                "hintergrund farbe entfernen. sondern nur leichter machen.\"",
            card.contains("quiet -> bt.surfaceQuiet"),
        )
        assertTrue(
            "a quiet card must keep its hairline — in light it is the ONLY separator " +
                "there is, because page and card are both #FFFFFF",
            !card.contains("quiet -> null") && !card.contains("Color.Transparent"),
        )
    }

    /**
     * The louder half. The chip's own chrome is deliberately NOT asserted beyond
     * existing: an earlier pass grew this chip and he reversed it, so the licence
     * here is exactly one property — the label's ink.
     */
    @Test
    fun `the quick-link chips lead with a near-white label on the card they kept`() {
        val chip = bodyOf(source(OVERVIEW), "private fun QuickStatChip(")
        val surface = argsOf(chip, "Surface(")
        assertTrue(
            "the quick-link chip lost its filled surface: $surface",
            surface.contains("color = bt.surface"),
        )
        assertTrue(
            "the quick-link chip lost its hairline: $surface",
            surface.contains("border = BorderStroke(1.dp, bt.border)"),
        )
        assertTrue(
            "the quick-link chip must not stand down too — then nothing out-ranks " +
                "anything and the note is unanswered: $surface",
            !surface.contains("quiet"),
        )
        val label = restOfCall(chip, "text = label,")
        assertTrue(
            "the quick-link label must be near-white, not grey — \"cash und " +
                "transaktionen können ja mehr weißlich statt grau werden\": $label",
            label.contains("color = bt.textPrimary"),
        )
    }

    @Test
    fun `the holding row carries no quantity`() {
        val row = holdingRow()
        listOf("formatHoldingQuantity(", "formatHoldingSubline(", "holding.quantity").forEach { gone ->
            assertTrue("the holding row grew `$gone` back", !row.contains(gone))
        }
    }

    private companion object {
        const val OVERVIEW = "at/bettertrack/app/ui/portfolio/PortfolioOverviewScreen.kt"
    }
}
