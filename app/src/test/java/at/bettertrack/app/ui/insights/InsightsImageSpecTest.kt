package at.bettertrack.app.ui.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared image's layout bounds.
 *
 * A poster is composed once, off screen, into a file the user may publish. There
 * is no layout inspector on the other side of that share sheet and no second
 * chance after it is posted, so the geometry is asserted here instead: every
 * band inside its safe area, no two bands overlapping, and the study's exact
 * pixel numbers where it gave them.
 */
class InsightsImageSpecTest {

    private val square = insightImageLayout(BtInsightImageFormat.SQUARE)
    private val story = insightImageLayout(BtInsightImageFormat.STORY)

    @Test
    fun `the two formats are the sizes every social surface resamples from`() {
        assertEquals(1080, BtInsightImageFormat.SQUARE.widthPx)
        assertEquals(1080, BtInsightImageFormat.SQUARE.heightPx)
        assertEquals(1080, BtInsightImageFormat.STORY.widthPx)
        assertEquals(1920, BtInsightImageFormat.STORY.heightPx)
    }

    @Test
    fun `the square keeps the study's 72 px safe area and 936 px content column`() {
        assertEquals(72, square.sideInsetPx)
        assertEquals(936f, square.contentWidthPx, 0.01f)
        assertEquals(936f, square.chartField.width, 0.01f)
        assertEquals(500f, square.chartField.height, 0.01f)
    }

    @Test
    fun `the story keeps 80 px sides and a 920 x 820 chart field`() {
        assertEquals(80, story.sideInsetPx)
        assertEquals(920f, story.contentWidthPx, 0.01f)
        assertEquals(920f, story.chartField.width, 0.01f)
        assertEquals(820f, story.chartField.height, 0.01f)
    }

    /**
     * The story's exclusion zones are the whole reason it is recomposed rather
     * than scaled: a social story UI paints its own chrome over roughly the
     * first 180 px and the last 240 px.
     */
    @Test
    fun `the story clears the platform overlays at both ends`() {
        assertEquals(180, story.topClearPx)
        assertEquals(1920 - 240, story.bottomClearPx)
        assertTrue("content starts inside the top overlay", story.brandRow.top >= 180f)
        assertTrue("content runs into the bottom overlay", story.footer.bottom <= 1680f)
    }

    @Test
    fun `every band of every format stays inside its safe area`() {
        listOf(square, story).forEach { layout ->
            layout.bands.forEach { band ->
                assertTrue(
                    "${layout.format} band starts left of the safe area",
                    band.left >= layout.contentLeftPx - 0.01f,
                )
                assertTrue(
                    "${layout.format} band runs past the right safe area",
                    band.right <= layout.contentRightPx + 0.01f,
                )
                assertTrue(
                    "${layout.format} band starts above the top clearance",
                    band.top >= layout.topClearPx - 0.01f,
                )
                assertTrue(
                    "${layout.format} band runs below the bottom clearance",
                    band.bottom <= layout.bottomClearPx + 0.01f,
                )
            }
        }
    }

    @Test
    fun `no two bands overlap in either format`() {
        listOf(square, story).forEach { layout ->
            val bands = layout.bands
            bands.indices.forEach { i ->
                (i + 1 until bands.size).forEach { j ->
                    val a = bands[i]
                    val b = bands[j]
                    val disjoint = a.bottom <= b.top + 0.01f || b.bottom <= a.top + 0.01f
                    assertTrue("${layout.format} bands $i and $j overlap vertically", disjoint)
                }
            }
        }
    }

    @Test
    fun `bands are declared in top-to-bottom order`() {
        listOf(square, story).forEach { layout ->
            layout.bands.zipWithNext().forEach { (a, b) ->
                assertTrue("${layout.format} bands are out of order", a.top <= b.top)
            }
        }
    }

    @Test
    fun `chart labels never fall below the study's readable floor`() {
        listOf(square, story).forEach {
            assertTrue("${it.format} chart labels are too small", it.chartLabelSizePx >= 20f)
        }
    }

    @Test
    fun `the story sets larger type than the square, as designed`() {
        assertTrue(story.titleSizePx > square.titleSizePx)
        assertTrue(story.headlineSizePx > square.headlineSizePx)
    }

    // ── File names ──────────────────────────────────────────────────────────

    @Test
    fun `an image file name follows the study's example`() {
        assertEquals(
            "BetterTrack_Aufteilung_2026-08-16_quadrat.png",
            insightImageFileName("Aufteilung", "2026-08-16", "quadrat"),
        )
        assertEquals(
            "BetterTrack_Aufteilung_2026-08-16_story.png",
            insightImageFileName("Aufteilung", "2026-08-16", "story"),
        )
    }

    @Test
    fun `a multi-word subject becomes one hyphenated token`() {
        assertEquals(
            "BetterTrack_Budgets-&-Ausgaben_2026-08-18_quadrat.png",
            insightImageFileName("Budgets & Ausgaben", "2026-08-18", "quadrat"),
        )
    }

    @Test
    fun `a report file name carries scope and both dates`() {
        assertEquals(
            "BetterTrack_Insights_Alle-Depots_2025-09-01_bis_2026-08-18.pdf",
            insightReportFileName("Alle Depots", "2025-09-01", "2026-08-18", "bis"),
        )
    }

    @Test
    fun `a same-day report collapses to one date`() {
        assertEquals(
            "BetterTrack_Insights_Alle-Depots_2026-08-18.pdf",
            insightReportFileName("Alle Depots", "2026-08-18", "2026-08-18", "bis"),
        )
    }

    /**
     * A portfolio NAME is user data, and user data does not get to pick a
     * directory. Every separator a file system reserves is neutralised.
     */
    @Test
    fun `a hostile portfolio name cannot escape into a path`() {
        val name = insightImageFileName("../../etc/passwd", "2026-08-18", "quadrat")
        assertFalse(name.contains("/"))
        assertFalse(name.contains("\\"))
        assertTrue(name.endsWith(".png"))
    }

    @Test
    fun `sanitising also caps the length so a long name cannot break a file system`() {
        val long = sanitizeInsightFileName("x".repeat(400))
        assertTrue(long.length <= 160)
    }
}
