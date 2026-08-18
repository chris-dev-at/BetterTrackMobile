package at.bettertrack.app.ui.insights

import at.bettertrack.app.ui.charts.viz.BtVizForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog's honesty rails, as tests.
 *
 * The round-6 study's central claim is that BetterTrack shows only what its data
 * can support, and its central mechanism is that a control which makes no
 * semantic sense is **absent rather than disabled**. That is a claim about a
 * data table, so it is checkable — and worth checking, because the failure mode
 * is silent: adding a period picker to a stichtag insight would compile, run,
 * and quietly promise a six-month allocation the server never computed.
 */
class InsightsCatalogTest {

    @Test
    fun `the catalog has twelve insights ranked one to twelve`() {
        assertEquals(12, BtInsight.entries.size)
        assertEquals((1..12).toList(), BT_INSIGHTS_RANKED.map { it.spec.rank })
    }

    @Test
    fun `exactly five insights are on by default, and they are the study's five`() {
        assertEquals(
            listOf(
                BtInsight.PORTFOLIO_DEVELOPMENT,
                BtInsight.ASSET_CLASSES,
                BtInsight.DAILY_MOVERS,
                BtInsight.MONTHLY_CASHFLOW,
                BtInsight.BUDGETS_SPENDING,
            ),
            BT_INSIGHTS_DEFAULT,
        )
    }

    @Test
    fun `the default five are exactly the top five ranks`() {
        assertEquals(BT_INSIGHTS_RANKED.take(5), BT_INSIGHTS_DEFAULT)
    }

    /**
     * The headline rail: "current allocation has no historical period unless the
     * server supplies a historical snapshot; Tagesbewegungen cannot become an
     * arbitrary range".
     */
    @Test
    fun `a stichtag or session insight offers no period control at all`() {
        val timeless = BtInsight.entries.filter {
            it.spec.timing in setOf(
                BtInsightTiming.SNAPSHOT,
                BtInsightTiming.SESSION,
                BtInsightTiming.BUDGET_MONTH,
            )
        }
        assertTrue("no stichtag insights found — the catalog changed shape", timeless.isNotEmpty())
        timeless.forEach {
            assertEquals(
                "${it.name} offers a period control it cannot honour",
                emptyList<BtInsightPeriodKind>(),
                insightPeriodKinds(it),
            )
        }
    }

    /** "Steuerübersicht cannot pretend a non-calendar range is a tax year." */
    @Test
    fun `the tax summary offers calendar years and nothing else`() {
        assertEquals(
            listOf(BtInsightPeriodKind.CALENDAR_YEAR),
            insightPeriodKinds(BtInsight.TAX_SUMMARY),
        )
        assertFalse(insightAcceptsCalendarYear(BtInsight.TAX_SUMMARY, isCalendarYear = false))
        assertTrue(insightAcceptsCalendarYear(BtInsight.TAX_SUMMARY, isCalendarYear = true))
    }

    @Test
    fun `every non-tax insight survives a non-calendar frame`() {
        BtInsight.entries
            .filter { it.spec.timing != BtInsightTiming.CALENDAR_YEAR }
            .forEach {
                assertTrue(
                    "${it.name} was refused a non-calendar frame it can answer",
                    insightAcceptsCalendarYear(it, isCalendarYear = false),
                )
            }
    }

    /**
     * A share of a whole cannot express a direction, so a signed insight may
     * never offer part-to-whole geometry. Split a loss into a treemap and −143 €
     * becomes an area indistinguishable from a gain of the same size.
     */
    @Test
    fun `signed insights offer no part-to-whole form`() {
        val partToWhole = setOf(
            BtVizForm.TREEMAP,
            BtVizForm.MOSAIC,
            BtVizForm.STACKED_BAR,
            BtVizForm.RING,
            BtVizForm.DONUT,
            BtVizForm.WAFFLE,
            BtVizForm.BUBBLES,
        )
        BtInsight.entries.filter { it.spec.signed }.forEach { insight ->
            val offending = insight.spec.forms.filter { it in partToWhole }
            assertTrue(
                "${insight.name} is signed but offers part-to-whole forms: $offending",
                offending.isEmpty(),
            )
        }
    }

    /**
     * Market value and cost basis are two independent quantities, not two parts
     * of one whole, so the paired track is the ONLY form and the configurator
     * draws no `Darstellung` row at all.
     */
    @Test
    fun `market value versus cost basis exposes no form picker`() {
        assertEquals(emptyList<BtVizForm>(), BtInsight.VALUE_VS_BASIS.spec.forms)
        assertEquals(null, BtInsight.VALUE_VS_BASIS.spec.family)
    }

    @Test
    fun `the time series exposes no form picker either`() {
        assertEquals(emptyList<BtVizForm>(), BtInsight.PORTFOLIO_DEVELOPMENT.spec.forms)
        assertEquals(null, BtInsight.PORTFOLIO_DEVELOPMENT.spec.family)
        assertTrue(BtInsight.PORTFOLIO_DEVELOPMENT.spec.seriesChoice)
    }

    /** One session against another is not a comparison a reader can act on. */
    @Test
    fun `daily movers and unrealized P slash L offer no comparison`() {
        assertEquals(BtInsightCompare.NONE, BtInsight.DAILY_MOVERS.spec.compare)
        assertEquals(BtInsightCompare.NONE, BtInsight.UNREALIZED_PL.spec.compare)
        assertEquals(BtInsightCompare.NONE, BtInsight.VALUE_VS_BASIS.spec.compare)
    }

    @Test
    fun `every form list that exists starts with the automatic option`() {
        BtInsight.entries.filter { it.spec.forms.isNotEmpty() }.forEach {
            assertEquals(
                "${it.name}'s picker does not lead with Automatisch",
                BtVizForm.AUTO,
                it.spec.forms.first(),
            )
        }
    }

    /** Six fixed asset classes need no Top-N; the study says so explicitly. */
    @Test
    fun `asset classes needs no Top-N control`() {
        assertEquals(emptyList<Any>(), BtInsight.ASSET_CLASSES.spec.topN)
    }

    /**
     * Tax components are euro facts. Printing them as shares of each other would
     * invent a denominator the tax authority never used.
     */
    @Test
    fun `the tax summary offers no share labels`() {
        assertFalse(BtInsight.TAX_SUMMARY.spec.labels)
    }

    @Test
    fun `only insights with a real cash slice offer a cash toggle`() {
        val withCash = BtInsight.entries.filter { it.spec.cashToggle }.toSet()
        assertEquals(
            setOf(
                BtInsight.ASSET_CLASSES,
                BtInsight.HOLDING_CONCENTRATION,
                BtInsight.LIQUID_FUNDS,
            ),
            withCash,
        )
    }

    @Test
    fun `every insight belongs to exactly one group and every group is used`() {
        val groups = BtInsight.entries.map { it.spec.group }.toSet()
        assertEquals(BtInsightGroup.entries.toSet(), groups)
    }
}
