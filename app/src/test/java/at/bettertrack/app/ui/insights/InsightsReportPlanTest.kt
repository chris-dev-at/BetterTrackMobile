package at.bettertrack.app.ui.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The report's page arithmetic.
 *
 * The builder promises a page count before anything is rendered, and the
 * finished PDF prints `Seite x von y` in its footer. Both read
 * [reportPageCount], so a bug here is a document that contradicts the screen
 * that produced it. The study's own worked example — five insights → eight
 * pages — is the anchor case.
 */
class InsightsReportPlanTest {

    private fun frame(calendarYear: Boolean = false) =
        BtReportFrame(BtInsightPeriod.ONE_YEAR, emptySet(), calendarYear)

    @Test
    fun `the study's worked example holds - five insights make eight pages`() {
        assertEquals(8, reportPageCount(5))
    }

    @Test
    fun `the formula is cover plus contents plus one per insight plus provenance`() {
        // 1 + 1 + n + 1 while n fits on one contents page.
        assertEquals(4, reportPageCount(1))
        assertEquals(5, reportPageCount(2))
        assertEquals(11, reportPageCount(8))
    }

    @Test
    fun `a ninth section buys a second contents page`() {
        assertEquals(1, reportContentsPageCount(8))
        assertEquals(2, reportContentsPageCount(9))
        // 1 cover + 2 contents + 9 sections + 1 provenance
        assertEquals(13, reportPageCount(9))
        // All twelve: 1 + 2 + 12 + 1
        assertEquals(16, reportPageCount(12))
    }

    /**
     * An empty selection is not a one-page report. The builder disables export
     * rather than producing a cover with nothing behind it.
     */
    @Test
    fun `an empty selection has no pages at all`() {
        assertEquals(0, reportPageCount(0))
        assertEquals(0, reportContentsPageCount(0))
        assertEquals(0L, reportEstimateBytes(0))
    }

    @Test
    fun `section page numbers follow the cover and the contents`() {
        assertEquals(3, reportFirstSectionPage(5))
        assertEquals(3, reportSectionPage(5, 0))
        assertEquals(7, reportSectionPage(5, 4))
        assertEquals(8, reportProvenancePage(5))
    }

    @Test
    fun `provenance is always the last page`() {
        (1..12).forEach { n ->
            assertEquals("with $n sections", reportPageCount(n), reportProvenancePage(n))
        }
    }

    @Test
    fun `sections are numbered from one and land on consecutive pages`() {
        val selection = BT_INSIGHTS_DEFAULT
        val sections = reportSections(selection)
        assertEquals(listOf(1, 2, 3, 4, 5), sections.map { it.number })
        assertEquals(listOf(3, 4, 5, 6, 7), sections.map { it.page })
        assertEquals(selection, sections.map { it.insight })
    }

    // ── Frame reconciliation ────────────────────────────────────────────────

    /** "A frame change explicitly unchecks incompatible cards and announces how many." */
    @Test
    fun `a non-calendar frame unchecks the tax summary and says so`() {
        val selection = listOf(BtInsight.ASSET_CLASSES, BtInsight.TAX_SUMMARY)
        val change = reportReconcileSelection(selection, frame(calendarYear = false))
        assertEquals(listOf(BtInsight.ASSET_CLASSES), change.selected)
        assertEquals(listOf(BtInsight.TAX_SUMMARY), change.removed)
        assertEquals(1, change.removedCount)
    }

    @Test
    fun `a calendar-year frame keeps the tax summary`() {
        val selection = listOf(BtInsight.ASSET_CLASSES, BtInsight.TAX_SUMMARY)
        val change = reportReconcileSelection(selection, frame(calendarYear = true))
        assertEquals(selection, change.selected)
        assertEquals(0, change.removedCount)
    }

    @Test
    fun `recommended is the default five, minus what the frame cannot render`() {
        assertEquals(BT_INSIGHTS_DEFAULT, reportRecommendedSelection(frame(calendarYear = true)))
        // None of the default five is calendar-year-only, so a plain range keeps
        // all five — the recommendation is never silently thinned.
        assertEquals(BT_INSIGHTS_DEFAULT, reportRecommendedSelection(frame(calendarYear = false)))
    }

    // ── Ordering ────────────────────────────────────────────────────────────

    /**
     * The report must read in the order the user arranged on the page; a
     * document whose sections appeared in a different order from the screen they
     * were chosen on would read as a different document.
     */
    @Test
    fun `the page order wins over catalog rank`() {
        val page = BtInsightsPage(
            listOf(
                BtInsight.BUDGETS_SPENDING,
                BtInsight.PORTFOLIO_DEVELOPMENT,
                BtInsight.ASSET_CLASSES,
            ),
        )
        val ordered = reportOrderSelection(
            listOf(BtInsight.ASSET_CLASSES, BtInsight.BUDGETS_SPENDING, BtInsight.PORTFOLIO_DEVELOPMENT),
            page,
        )
        assertEquals(page.visible, ordered)
    }

    @Test
    fun `an insight that is not on the page falls in behind, by rank`() {
        val page = BtInsightsPage(listOf(BtInsight.BUDGETS_SPENDING))
        val ordered = reportOrderSelection(
            listOf(BtInsight.DIVIDENDS, BtInsight.BUDGETS_SPENDING, BtInsight.UNREALIZED_PL),
            page,
        )
        assertEquals(
            listOf(BtInsight.BUDGETS_SPENDING, BtInsight.UNREALIZED_PL, BtInsight.DIVIDENDS),
            ordered,
        )
    }

    @Test
    fun `a duplicated selection still produces one section each`() {
        val ordered = reportOrderSelection(
            listOf(BtInsight.ASSET_CLASSES, BtInsight.ASSET_CLASSES),
            BtInsightsPage.DEFAULT,
        )
        assertEquals(listOf(BtInsight.ASSET_CLASSES), ordered)
    }

    @Test
    fun `the size estimate grows with the page count and is never negative`() {
        val one = reportEstimateBytes(1)
        val five = reportEstimateBytes(5)
        assertTrue(one > 0)
        assertTrue(five > one)
    }
}
