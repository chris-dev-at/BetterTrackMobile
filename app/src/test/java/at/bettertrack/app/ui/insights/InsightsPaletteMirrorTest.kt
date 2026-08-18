package at.bettertrack.app.ui.insights

import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The print palette is a hand-copied mirror of `BtLightColors.chartSeries`.
 *
 * A PDF page has no composition to read a theme from, so the ramp has to be
 * resolved to plain ints somewhere. That copy is a hazard with a known shape —
 * the widget package hit it first and is pinned by `BtWidgetChartPaletteMirrorTest`
 * — so this test reads `BtColors.kt` and asserts the copy still matches. A
 * retuned ramp in the app must not leave exported reports printing last season's
 * categories.
 *
 * The LIGHT ramp specifically: the report is light-first because paper is, and a
 * dark-mode ramp on white paper is the wrong contrast pair.
 */
class InsightsPaletteMirrorTest {

    private fun projectFile(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.isFile }
            ?: error("$relative not found; tried ${candidates.map { it.absolutePath }}")
    }

    /** The `chartSeries = listOf( … )` literals of `BtLightColors`, in order. */
    private fun lightSeries(): List<String> {
        val src = projectFile("src/main/java/at/bettertrack/app/ui/theme/BtColors.kt").readText()
        val start = src.indexOf("val BtLightColors = BtColors(")
        assertTrue("BtLightColors not found in BtColors.kt", start >= 0)
        val table = src.substring(start, src.indexOf("\n)", start))
        val block = Regex("""chartSeries = listOf\((.*?)\n\s*\)""", RegexOption.DOT_MATCHES_ALL)
            .find(table)
        assertTrue("BtLightColors has no chartSeries block", block != null)
        return Regex("""Color\(0[xX]([0-9A-Fa-f]{8})\)""")
            .findAll(block!!.groupValues[1])
            .map { it.groupValues[1].uppercase(Locale.ROOT).takeLast(6) }
            .toList()
    }

    private fun hex(argb: Int): String =
        String.format(Locale.ROOT, "%06X", argb and 0x00FFFFFF)

    @Test
    fun `the print ramp mirrors the light chartSeries, slot for slot`() {
        val expected = lightSeries()
        val actual = insightsPdfPalette().series.map(::hex)
        assertTrue("the app ramp looks empty — did BtColors.kt change shape?", expected.size >= 10)
        assertEquals(expected, actual)
    }

    @Test
    fun `the print ramp has as many slots as the app ramp`() {
        assertEquals(lightSeries().size, insightsPdfPalette().series.size)
    }

    /**
     * The paper values from the study. These are ink-on-paper choices rather
     * than theme tokens, so they are pinned here instead of mirrored.
     */
    @Test
    fun `the report paper, panel, border and ink match the study`() {
        val palette = insightsPdfPalette()
        assertEquals("FDFCF8", hex(palette.paper))
        assertEquals("FFFFFF", hex(palette.panel))
        assertEquals("D9D7D1", hex(palette.border))
        assertEquals("171717", hex(palette.ink))
        assertEquals("686868", hex(palette.muted))
        assertEquals("F6B82E", hex(palette.gold))
    }

    /**
     * Money direction on paper uses the LIGHT pair. The dark emerald and rose
     * are tuned for a near-black ground and lose their contrast on white.
     */
    @Test
    fun `money direction on paper uses the light gain and loss`() {
        val palette = insightsPdfPalette()
        assertEquals("0F7853", hex(palette.gain))
        assertEquals("B23A4E", hex(palette.loss))
    }

    /**
     * Colours in this file are written channel-wise so `BtThemeDisciplineTest`
     * stays satisfied: the app's palette may only come from `BtTheme`, and this
     * one legitimate exception must not look like a theme literal.
     */
    @Test
    fun `the palette file declares no colour literal the theme guard would reject`() {
        val src = projectFile("src/main/java/at/bettertrack/app/ui/insights/InsightsPalette.kt")
            .readText()
        assertTrue(
            "the print palette must build colours channel-wise",
            src.contains("private fun argb("),
        )
        assertFalse(
            "the platform colour helper is a JVM stub; the palette must not import it",
            src.lineSequence().any { it.trim() == "import android.graphics.Color" },
        )
        assertTrue(
            "no hex colour literals belong under ui/",
            Regex("""Color\(0[xX][0-9A-Fa-f]{8}\)""").findAll(src).none(),
        )
    }
}
