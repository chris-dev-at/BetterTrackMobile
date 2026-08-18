package at.bettertrack.app.ui.insights

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page's layout model: what is visible, in what order, and what a restore
 * is allowed to touch.
 *
 * The load-bearing promise here is the one the confirmation dialog makes out
 * loud — "Deine Finanzdaten und gespeicherten Insight-Einstellungen bleiben
 * erhalten." Restoring the default VIEW must not discard a single saved card
 * setting, and the only way to guarantee that is to keep layout and card
 * configuration in separate stores. The last test asserts the separation
 * structurally.
 */
class InsightsPageTest {

    @Test
    fun `a new page is the default five in rank order`() {
        assertEquals(BT_INSIGHTS_DEFAULT, BtInsightsPage.DEFAULT.visible)
        assertTrue(BtInsightsPage.DEFAULT.isDefault)
    }

    @Test
    fun `hidden is everything the catalog knows that is not on the page`() {
        val page = BtInsightsPage.DEFAULT
        assertEquals(7, page.hidden.size)
        assertEquals(12, page.visible.size + page.hidden.size)
        assertTrue(page.hidden.none { it in page.visible })
        // Rank order, so the catalog reads the same way everywhere.
        assertEquals(page.hidden.sortedBy { it.spec.rank }, page.hidden)
    }

    @Test
    fun `showing a hidden insight appends it and hiding removes it`() {
        val shown = insightsPageShow(BtInsightsPage.DEFAULT, BtInsight.DIVIDENDS)
        assertEquals(BtInsight.DIVIDENDS, shown.visible.last())
        assertEquals(6, shown.visible.size)

        val hidden = insightsPageHide(shown, BtInsight.DIVIDENDS)
        assertEquals(BtInsightsPage.DEFAULT.visible, hidden.visible)
    }

    @Test
    fun `showing an insight that is already visible changes nothing`() {
        val page = BtInsightsPage.DEFAULT
        assertEquals(page, insightsPageShow(page, BtInsight.ASSET_CLASSES))
    }

    @Test
    fun `hiding an insight that is not visible changes nothing`() {
        val page = BtInsightsPage.DEFAULT
        assertEquals(page.visible, insightsPageHide(page, BtInsight.TAX_SUMMARY).visible)
    }

    @Test
    fun `moving an insight reorders it by one place`() {
        val page = BtInsightsPage.DEFAULT
        val moved = insightsPageMove(page, BtInsight.DAILY_MOVERS, -1)
        assertEquals(
            listOf(
                BtInsight.PORTFOLIO_DEVELOPMENT,
                BtInsight.DAILY_MOVERS,
                BtInsight.ASSET_CLASSES,
                BtInsight.MONTHLY_CASHFLOW,
                BtInsight.BUDGETS_SPENDING,
            ),
            moved.visible,
        )
    }

    /**
     * The TalkBack "move up" action on the first row is a no-op, not an error —
     * a screen reader user cannot see a rejected gesture, so clamping is kinder
     * than failing.
     */
    @Test
    fun `moving past either end clamps instead of failing`() {
        val page = BtInsightsPage.DEFAULT
        assertEquals(page.visible, insightsPageMove(page, BtInsight.PORTFOLIO_DEVELOPMENT, -1).visible)
        assertEquals(page.visible, insightsPageMove(page, BtInsight.BUDGETS_SPENDING, 1).visible)
        assertEquals(page.visible, insightsPageMove(page, BtInsight.TAX_SUMMARY, -1).visible)
    }

    @Test
    fun `reordering by index moves the row and keeps every other one`() {
        val page = BtInsightsPage.DEFAULT
        val moved = insightsPageReorder(page, from = 4, to = 0)
        assertEquals(BtInsight.BUDGETS_SPENDING, moved.visible.first())
        assertEquals(page.visible.toSet(), moved.visible.toSet())
        assertEquals(5, moved.visible.size)
    }

    @Test
    fun `an out-of-range reorder is clamped or ignored`() {
        val page = BtInsightsPage.DEFAULT
        assertEquals(page.visible, insightsPageReorder(page, from = 99, to = 0).visible)
        assertEquals(
            BtInsight.PORTFOLIO_DEVELOPMENT,
            insightsPageReorder(page, from = 0, to = 99).visible.last(),
        )
    }

    // ── Codec ───────────────────────────────────────────────────────────────

    @Test
    fun `the default page stores nothing so it keeps following the catalog`() {
        assertNull(insightsPageEncode(BtInsightsPage.DEFAULT))
    }

    @Test
    fun `a customised page round-trips`() {
        val page = BtInsightsPage(
            listOf(BtInsight.DIVIDENDS, BtInsight.PORTFOLIO_DEVELOPMENT, BtInsight.TAX_SUMMARY),
        )
        assertEquals(page, insightsPageDecode(insightsPageEncode(page)))
    }

    @Test
    fun `an unknown insight name is dropped rather than fatal`() {
        val decoded = insightsPageDecode("DIVIDENDS,SOMETHING_NEW,TAX_SUMMARY")
        assertEquals(listOf(BtInsight.DIVIDENDS, BtInsight.TAX_SUMMARY), decoded.visible)
    }

    @Test
    fun `an unreadable or empty page falls back to the default five`() {
        assertEquals(BtInsightsPage.DEFAULT, insightsPageDecode(null))
        assertEquals(BtInsightsPage.DEFAULT, insightsPageDecode(""))
        assertEquals(BtInsightsPage.DEFAULT, insightsPageDecode("NOPE,ALSO_NOPE"))
    }

    @Test
    fun `duplicates in a stored page are collapsed`() {
        val decoded = insightsPageDecode("DIVIDENDS,DIVIDENDS")
        assertEquals(listOf(BtInsight.DIVIDENDS), decoded.visible)
    }

    // ── The promise the restore dialog makes ────────────────────────────────

    /**
     * `Standardansicht wiederherstellen` writes the page key and nothing else.
     * The card keys live under a different prefix and are never touched, which
     * is what makes the dialog's promise true rather than merely intended.
     */
    @Test
    fun `restoring the default view cannot reach a saved card setting`() {
        val roots = listOf(
            File("src/main/java/at/bettertrack/app"),
            File("app/src/main/java/at/bettertrack/app"),
        )
        val root = roots.firstOrNull { it.isDirectory } ?: error("sources not found")

        val prefs = File(root, "data/prefs/InsightsPrefs.kt").readText()
        val setPage = prefs.substringAfter("fun setPage(").substringBefore("private fun loadCards")
        assertFalse(
            "setPage must not touch a card key",
            setPage.contains("KEY_CARD_PREFIX"),
        )

        val vm = File(root, "ui/insights/InsightsStudioViewModel.kt").readText()
        val restore = vm.substringAfter("fun restoreDefaultPage()").substringBefore("private fun writePage")
        assertFalse(
            "restoreDefaultPage must not clear card overrides",
            restore.contains("setCard"),
        )
        assertTrue("restoreDefaultPage must clear the page key", restore.contains("setPage(null)"))
    }
}
