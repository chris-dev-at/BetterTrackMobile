package at.bettertrack.app.ui.portfolio

import at.bettertrack.app.data.api.dto.MoneyWeightedPct
import at.bettertrack.app.data.repo.HistoryRange
import at.bettertrack.app.data.repo.PerformancePoint
import at.bettertrack.app.data.repo.PortfolioHistory
import at.bettertrack.app.ui.format.BtDiscreetMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The window's return summary (#1669): both server figures, labelled, on every
 * range, the money-weighted one tri-state — and the explainer on MAX only.
 *
 * The labels are formatted with the SAME templates the two `strings.xml` files
 * carry (`Time-weighted %1$s` / `Zeitgewichtet %1$s`, …), so the strings asserted
 * here are the strings a user reads.
 *
 * Placement (owner order 2026-10-29): the line and its MAX explainer live on the
 * Gesamtvermögen sheet ONLY — the main portfolio screen shows the delta line and
 * nothing else under the hero figure.
 */
class ReturnSummaryLineTest {

    @After
    fun tearDown() = BtDiscreetMode.resetForTest()

    private val de = Locale.GERMANY
    private val en = Locale.ENGLISH

    private fun history(
        range: HistoryRange,
        moneyWeighted: MoneyWeightedPct,
        lastTwr: Double? = -32.73,
    ) = PortfolioHistory(
        portfolioId = "p1",
        range = range,
        baseCurrency = "EUR",
        points = emptyList(),
        performance = lastTwr?.let { listOf(PerformancePoint(0L, 0.0), PerformancePoint(86_400_000L, it)) }.orEmpty(),
        syncedAtMs = 1L,
        moneyWeighted = moneyWeighted,
    )

    private fun line(summary: ReturnSummary, locale: Locale): String {
        val (tw, mw) = if (locale == de) "Zeitgewichtet %1\$s" to "Geldgewichtet %1\$s" else "Time-weighted %1\$s" to "Money-weighted %1\$s"
        return btReturnSummaryText(
            timeWeighted = String.format(locale, tw, formatPercentForTest(summary.timeWeightedPct, locale)),
            moneyWeighted = String.format(locale, mw, moneyWeightedDisplay(summary.moneyWeighted, locale)),
        )
    }

    private fun formatPercentForTest(v: Double, locale: Locale): String =
        at.bettertrack.app.ui.components.formatPercent(v, locale)

    // ── Tri-state ───────────────────────────────────────────────────────────

    @Test
    fun `absent money-weighted figure composes no summary at all`() {
        HistoryRange.entries.forEach { range ->
            assertNull("$range", returnSummaryOf(history(range, MoneyWeightedPct.NotComputed)))
        }
    }

    @Test
    fun `explicit null renders a dash, never zero`() {
        val s = returnSummaryOf(history(HistoryRange.M1, MoneyWeightedPct.NoCapital))!!
        assertEquals("Zeitgewichtet -32,73 % · Geldgewichtet —", line(s, de))
        assertEquals("Time-weighted -32.73% · Money-weighted —", line(s, en))
    }

    @Test
    fun `a number renders verbatim at two decimals beside the time-weighted figure`() {
        val s = returnSummaryOf(history(HistoryRange.MAX, MoneyWeightedPct.Value(9.5)))!!
        assertEquals("Zeitgewichtet -32,73 % · Geldgewichtet +9,50 %", line(s, de))
        assertEquals("Time-weighted -32.73% · Money-weighted +9.50%", line(s, en))
    }

    @Test
    fun `the time-weighted half is the last point of the served performance curve`() {
        val s = returnSummaryOf(history(HistoryRange.Y1, MoneyWeightedPct.Value(1.0), lastTwr = 96.734))!!
        assertEquals(96.734, s.timeWeightedPct, 0.0)
        assertEquals("Zeitgewichtet +96,73 % · Geldgewichtet +1,00 %", line(s, de))
    }

    @Test
    fun `no time-weighted point means no line, even with a money-weighted figure`() {
        assertNull(returnSummaryOf(history(HistoryRange.M1, MoneyWeightedPct.Value(9.5), lastTwr = null)))
        assertNull(returnSummaryOf(null))
    }

    @Test
    fun `a money-weighted value that rounds to zero shows no stray sign`() {
        assertEquals("0,00 %", moneyWeightedDisplay(MoneyWeightedPct.Value(-0.001), de))
    }

    @Test
    fun `not-computed never displays as the no-capital dash`() {
        assertEquals("", moneyWeightedDisplay(MoneyWeightedPct.NotComputed, de))
    }

    @Test
    fun `percentages stay live in discreet mode`() {
        // Relative figures are never masked (rule 6) — same as the delta line.
        BtDiscreetMode.setEnabled(true)
        val s = returnSummaryOf(history(HistoryRange.M1, MoneyWeightedPct.Value(9.5)))!!
        assertEquals("Zeitgewichtet -32,73 % · Geldgewichtet +9,50 %", line(s, de))
    }

    // ── MAX vs every other range ────────────────────────────────────────────

    @Test
    fun `the explainer belongs to MAX only`() {
        HistoryRange.entries.forEach { range ->
            listOf(MoneyWeightedPct.Value(9.5), MoneyWeightedPct.NoCapital).forEach { mw ->
                val s = returnSummaryOf(history(range, mw))!!
                assertEquals("$range / $mw", range == HistoryRange.MAX, s.explainMax)
            }
        }
    }

    @Test
    fun `every range gets the summary line when the figure is present`() {
        HistoryRange.entries.forEach { range ->
            assertTrue("$range", returnSummaryOf(history(range, MoneyWeightedPct.Value(2.0))) != null)
        }
        assertFalse(returnSummaryOf(history(HistoryRange.D1, MoneyWeightedPct.Value(2.0)))!!.explainMax)
    }

    // ── Placement (owner order 2026-10-29) ──────────────────────────────────

    private fun uiSource(rel: String): String {
        val roots = listOf(
            File("src/main/java/at/bettertrack/app/ui"),
            File("app/src/main/java/at/bettertrack/app/ui"),
        )
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("ui sources not found; tried ${roots.map { it.absolutePath }}")
        // Comments stripped: the notes may name the line, only code counts.
        return File(root, rel).readLines()
            .map { it.substringBefore("//") }
            .filterNot { it.trimStart().startsWith("*") }
            .joinToString("\n")
    }

    private val summaryCalls = listOf("ReturnSummaryLine(", "ReturnSummaryExplainer(", "returnSummaryOf(")

    @Test
    fun `the main portfolio screen does not render the summary line or the MAX explainer`() {
        val overview = uiSource("portfolio/PortfolioOverviewScreen.kt")
        summaryCalls.forEach { call ->
            assertFalse("PortfolioOverviewScreen must not call $call", overview.contains(call))
        }
    }

    @Test
    fun `the Gesamtvermoegen sheet keeps the summary lines and its MAX explainer`() {
        val sheet = uiSource("home/AccountWealthSheet.kt")
        assertTrue(sheet.contains("ReturnSummaryLine("))
        assertTrue(sheet.contains("ReturnSummaryExplainer("))
    }
}
