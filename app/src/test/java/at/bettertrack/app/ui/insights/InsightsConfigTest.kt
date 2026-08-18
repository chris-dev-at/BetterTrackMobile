package at.bettertrack.app.ui.insights

import at.bettertrack.app.ui.charts.viz.BtVizCanvas
import at.bettertrack.app.ui.charts.viz.BtVizConfig
import at.bettertrack.app.ui.charts.viz.BtVizForm
import at.bettertrack.app.ui.charts.viz.BtVizLabels
import at.bettertrack.app.ui.charts.viz.BtVizScope
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three-layer precedence rule.
 *
 * The owner's constraint was that the new per-card configurator "must not fight"
 * the existing per-data-family `Darstellung` preference. The decision was: the
 * family seeds, the card overrides, the density resolver has the last word about
 * what fits. These tests pin all three, plus the property that makes the whole
 * thing safe — that configuring a CARD can never write a FAMILY preference.
 */
class InsightsConfigTest {

    private val family = BtVizConfig(
        form = BtVizForm.TREEMAP,
        labels = BtVizLabels.SHARES,
        scope = BtVizScope.TOP_5,
        showCash = false,
    )

    // ── Layer 1 → 2: inheritance ────────────────────────────────────────────

    @Test
    fun `a pristine card inherits every family value`() {
        val resolved = insightVizConfig(BtInsightConfig.PRISTINE, family)
        assertEquals(BtVizForm.TREEMAP, resolved.form)
        assertEquals(BtVizLabels.SHARES, resolved.labels)
        assertEquals(BtVizScope.TOP_5, resolved.scope)
        assertFalse(resolved.showCash)
    }

    @Test
    fun `an explicit card choice wins over the family`() {
        val card = BtInsightConfig(form = BtVizForm.RING, labels = BtVizLabels.AMOUNTS)
        val resolved = insightVizConfig(card, family)
        assertEquals(BtVizForm.RING, resolved.form)
        assertEquals(BtVizLabels.AMOUNTS, resolved.labels)
        // Untouched fields keep inheriting.
        assertEquals(BtVizScope.TOP_5, resolved.scope)
    }

    /**
     * The property the owner actually asked for. A later change to the family
     * must move an inheriting card and must NOT move a card that was configured.
     */
    @Test
    fun `changing the family moves inheriting cards and leaves explicit ones alone`() {
        val inheriting = BtInsightConfig.PRISTINE
        val explicit = BtInsightConfig(form = BtVizForm.RING)
        val newFamily = family.copy(form = BtVizForm.MOSAIC)

        assertEquals(BtVizForm.MOSAIC, insightVizConfig(inheriting, newFamily).form)
        assertEquals(BtVizForm.RING, insightVizConfig(explicit, newFamily).form)
    }

    /** Focus names a datum on THIS card; it is never inherited from a family. */
    @Test
    fun `focus is per card and never inherited`() {
        val familyWithFocus = family.copy(focusKey = "sym:MSFT")
        assertNull(insightVizConfig(BtInsightConfig.PRISTINE, familyWithFocus).focusKey)
        assertEquals(
            "tag:food",
            insightVizConfig(BtInsightConfig(focusKey = "tag:food"), familyWithFocus).focusKey,
        )
    }

    // ── Layer 3: the density resolver ───────────────────────────────────────

    @Test
    fun `automatic may change geometry between compact and full`() {
        val full = insightResolvedForm(
            BtInsight.ASSET_CLASSES,
            BtInsightConfig.PRISTINE,
            BtVizConfig(),
            BtVizCanvas.APP_FULL,
        )
        val compact = insightResolvedForm(
            BtInsight.ASSET_CLASSES,
            BtInsightConfig.PRISTINE,
            BtVizConfig(),
            BtVizCanvas.APP_COMPACT,
        )
        assertEquals(BtVizForm.TREEMAP, full)
        assertEquals(BtVizForm.STACKED_BAR, compact)
    }

    @Test
    fun `an explicit form that fits is honoured at both sizes`() {
        val card = BtInsightConfig(form = BtVizForm.RANKED_BARS)
        listOf(BtVizCanvas.APP_FULL, BtVizCanvas.APP_COMPACT).forEach { canvas ->
            assertEquals(
                BtVizForm.RANKED_BARS,
                insightResolvedForm(BtInsight.ASSET_CLASSES, card, BtVizConfig(), canvas),
            )
        }
    }

    /**
     * A form that does not fit is REPORTED, not silently swapped. The surface
     * says "Bei dieser Größe nicht verfügbar" rather than drawing something the
     * user did not choose.
     */
    @Test
    fun `an explicit form that cannot fit is reported as unavailable`() {
        val card = BtInsightConfig(form = BtVizForm.BUBBLES)
        assertFalse(insightFormUnavailable(BtInsight.ASSET_CLASSES, card, BtVizCanvas.APP_FULL))
        assertTrue(insightFormUnavailable(BtInsight.ASSET_CLASSES, card, BtVizCanvas.APP_COMPACT))
    }

    @Test
    fun `an inherited or automatic form is never unavailable`() {
        assertFalse(
            insightFormUnavailable(
                BtInsight.ASSET_CLASSES,
                BtInsightConfig.PRISTINE,
                BtVizCanvas.APP_COMPACT,
            ),
        )
        assertFalse(
            insightFormUnavailable(
                BtInsight.ASSET_CLASSES,
                BtInsightConfig(form = BtVizForm.AUTO),
                BtVizCanvas.APP_COMPACT,
            ),
        )
    }

    @Test
    fun `an insight without a form family always resolves to automatic`() {
        assertEquals(
            BtVizForm.AUTO,
            insightResolvedForm(
                BtInsight.PORTFOLIO_DEVELOPMENT,
                BtInsightConfig(form = BtVizForm.TREEMAP),
                BtVizConfig(),
                BtVizCanvas.APP_FULL,
            ),
        )
    }

    // ── Reset ───────────────────────────────────────────────────────────────

    /**
     * Reset means "inherit again", not "pin the family's current value" — a
     * reset card must keep following future family changes.
     */
    @Test
    fun `reset clears the display overrides but keeps the card's subject`() {
        val card = BtInsightConfig(
            form = BtVizForm.RING,
            labels = BtVizLabels.AMOUNTS,
            topN = BtVizScope.TOP_8,
            showCash = false,
            focusKey = "cash",
            period = BtInsightPeriod(BtInsightPeriodKind.SIX_MONTHS),
            portfolioIds = setOf("p1"),
        )
        val reset = insightResetToFamily(card)

        assertNull(reset.form)
        assertNull(reset.labels)
        assertNull(reset.topN)
        assertNull(reset.showCash)
        assertNull(reset.focusKey)
        // Period and scope are the card's SUBJECT, not its Darstellung: a reset
        // must not silently retarget the card at a different year.
        assertEquals(BtInsightPeriodKind.SIX_MONTHS, reset.period?.kind)
        assertEquals(setOf("p1"), reset.portfolioIds)
        assertFalse(insightHasFormOverride(reset))
    }

    @Test
    fun `a card with no display override reports nothing to reset`() {
        assertFalse(insightHasFormOverride(BtInsightConfig.PRISTINE))
        assertFalse(
            insightHasFormOverride(
                BtInsightConfig(period = BtInsightPeriod(BtInsightPeriodKind.MAX)),
            ),
        )
        assertTrue(insightHasFormOverride(BtInsightConfig(form = BtVizForm.RING)))
    }

    // ── Layer 4: the report frame ───────────────────────────────────────────

    /**
     * "Report export injects one period and portfolio scope without overwriting
     * card settings. Other visual knobs and focus survive."
     */
    @Test
    fun `the report frame replaces period and scope and nothing else`() {
        val card = BtInsightConfig(
            form = BtVizForm.TREEMAP,
            labels = BtVizLabels.AMOUNTS,
            topN = BtVizScope.TOP_8,
            focusKey = "type:crypto",
            compare = true,
            showBudgets = false,
            period = BtInsightPeriod(BtInsightPeriodKind.ONE_MONTH),
            portfolioIds = setOf("mine"),
        )
        val reportPeriod = BtInsightPeriod(BtInsightPeriodKind.ONE_YEAR)
        val out = insightForReport(BtInsight.ASSET_CLASSES, card, reportPeriod, setOf("a", "b"))

        assertEquals(reportPeriod, out.period)
        assertEquals(setOf("a", "b"), out.portfolioIds)
        assertEquals(BtVizForm.TREEMAP, out.form)
        assertEquals(BtVizLabels.AMOUNTS, out.labels)
        assertEquals(BtVizScope.TOP_8, out.topN)
        assertEquals("type:crypto", out.focusKey)
        assertTrue(out.compare)
        assertFalse(out.showBudgets)
    }

    @Test
    fun `building a report config does not mutate the saved card`() {
        val card = BtInsightConfig(form = BtVizForm.RING, period = BtInsightPeriod.SIX_MONTHS)
        insightForReport(BtInsight.ASSET_CLASSES, card, BtInsightPeriod.ONE_YEAR, setOf("x"))
        assertEquals(BtInsightPeriodKind.SIX_MONTHS, card.period?.kind)
        assertNull(card.portfolioIds)
    }

    // ── Codec ───────────────────────────────────────────────────────────────

    @Test
    fun `a pristine card encodes to nothing so it keeps inheriting`() {
        assertNull(insightConfigEncode(BtInsightConfig.PRISTINE))
    }

    @Test
    fun `every field survives an encode and decode round trip`() {
        val card = BtInsightConfig(
            form = BtVizForm.BUBBLES,
            labels = BtVizLabels.SHARES,
            topN = BtVizScope.TOP_3,
            showCash = false,
            focusKey = "sym:MSFT",
            period = BtInsightPeriod(BtInsightPeriodKind.CUSTOM, 19_000L, 19_500L, 2026),
            portfolioIds = setOf("p1", "p2"),
            compare = true,
            series = BtInsightSeries.PERFORMANCE,
            sort = BtInsightSort.DELTA,
            grouping = BtInsightGrouping.MONTH,
            showBudgets = false,
            showFees = false,
            includeTransfers = true,
        )
        val decoded = insightConfigDecode(insightConfigEncode(card))
        assertEquals(card, decoded)
    }

    @Test
    fun `a corrupt or truncated config decodes to inherit rather than throwing`() {
        assertEquals(BtInsightConfig.PRISTINE, insightConfigDecode(null))
        assertEquals(BtInsightConfig.PRISTINE, insightConfigDecode(""))
        // A form name from a future version.
        assertNull(insightConfigDecode("SUNBURST|-|-|-|-|-|0|0|0|-|0|-|-|-|1|1|0").form)
        // Truncated after three fields: everything else falls back to inherit.
        val short = insightConfigDecode("RING|SHARES|TOP_5")
        assertEquals(BtVizForm.RING, short.form)
        assertEquals(BtVizScope.TOP_5, short.topN)
        assertTrue(short.showBudgets)
        assertTrue(short.showFees)
    }

    @Test
    fun `the codec stores enum names rather than ordinals`() {
        val encoded = insightConfigEncode(BtInsightConfig(form = BtVizForm.RING)).orEmpty()
        assertTrue("codec must store names, not ordinals: $encoded", encoded.startsWith("RING|"))
    }

    // ── The structural guarantee ────────────────────────────────────────────

    /**
     * The insights package may READ the family preference and must never WRITE
     * it. A card override that rewrote `VizPrefs` would change the cash screen's
     * spending chart as a side effect of configuring a portfolio insight — the
     * exact "fighting" the owner ruled out.
     */
    @Test
    fun `nothing in the insights package writes the family preference`() {
        val offenders = insightSources()
            .filter { it.readText().contains("vizPrefs.setConfig") }
            .map { it.name }
        assertTrue(
            "these files write the app-wide family preference from an insight card: $offenders",
            offenders.isEmpty(),
        )
    }

    private fun insightSources(): List<File> {
        val roots = listOf(
            File("src/main/java/at/bettertrack/app/ui/insights"),
            File("app/src/main/java/at/bettertrack/app/ui/insights"),
        )
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("insights sources not found; tried ${roots.map { it.absolutePath }}")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
