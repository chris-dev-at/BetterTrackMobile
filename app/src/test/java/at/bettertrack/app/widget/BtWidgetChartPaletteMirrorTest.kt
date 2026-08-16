package at.bettertrack.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * [BtGlanceChartPalette] is a hand-copied mirror of `BtColors.chartSeries` /
 * `chartRest` / `chartCash`. This reads `BtColors.kt` and asserts the copy still
 * matches — the same job, hazard and technique as [BtWidgetPaletteMirrorTest],
 * for the chart half of the palette: a retuned ramp in the app must not leave
 * the widget donuts painting last season's slices on the home screen.
 */
class BtWidgetChartPaletteMirrorTest {

    private fun projectFile(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.isFile }
            ?: error("$relative not found; tried ${candidates.map { it.absolutePath }}")
    }

    private fun themeSource(): String =
        projectFile("src/main/java/at/bettertrack/app/ui/theme/BtColors.kt").readText()

    /** The body of `val BtDarkColors = BtColors( … )` / its light twin. */
    private fun table(name: String): String {
        val src = themeSource()
        val start = src.indexOf("val $name = BtColors(")
        assertTrue("$name not found in BtColors.kt", start >= 0)
        val end = src.indexOf("\n)", start)
        assertTrue("$name is not terminated", end > start)
        return src.substring(start, end)
    }

    /** The `chartSeries = listOf( … )` literals of one table, in order. */
    private fun seriesOf(tableName: String): List<String> {
        // Non-greedy up to the first line that is just the closing paren — a
        // plain indexOf(")") would stop at the first Color(...)'s own paren.
        val block = Regex("""chartSeries = listOf\((.*?)\n\s*\)""", RegexOption.DOT_MATCHES_ALL)
            .find(table(tableName))
        assertTrue("$tableName has no chartSeries block", block != null)
        return Regex("""Color\(0[xX]([0-9A-Fa-f]{8})\)""")
            .findAll(block!!.groupValues[1])
            .map { "#${it.groupValues[1].uppercase(Locale.ROOT)}" }
            .toList()
    }

    /** One single-`Color` token's literal out of a table. */
    private fun token(tableName: String, token: String): String? =
        Regex("""$token\s*=\s*Color\(0[xX]([0-9A-Fa-f]{8})\)""")
            .find(table(tableName))
            ?.groupValues?.get(1)?.let { "#${it.uppercase(Locale.ROOT)}" }

    private fun hex(argb: Long): String = String.format(Locale.ROOT, "#%08X", argb)

    @Test
    fun `the widget series mirrors both chartSeries ramps, slot for slot`() {
        assertEquals(
            "light chartSeries drifted from the widget mirror",
            seriesOf("BtLightColors"),
            BtGlanceChartPalette.SERIES_DAY.map { hex(it) },
        )
        assertEquals(
            "dark chartSeries drifted from the widget mirror",
            seriesOf("BtDarkColors"),
            BtGlanceChartPalette.SERIES_NIGHT.map { hex(it) },
        )
        // Ten slots is itself part of the contract — the theme KDoc's "never
        // cycle past the last slot" arithmetic depends on it.
        assertEquals(10, BtGlanceChartPalette.slotCount)
    }

    @Test
    fun `rest and cash mirror their theme tokens`() {
        assertEquals(token("BtLightColors", "chartRest"), hex(BtGlanceChartPalette.REST_DAY))
        assertEquals(token("BtDarkColors", "chartRest"), hex(BtGlanceChartPalette.REST_NIGHT))
        assertEquals(token("BtLightColors", "chartCash"), hex(BtGlanceChartPalette.CASH_DAY))
        assertEquals(token("BtDarkColors", "chartCash"), hex(BtGlanceChartPalette.CASH_NIGHT))
    }

    @Test
    fun `every chart colour is fully opaque`() {
        // A bitmap slice composites against the card like any other pixel, but a
        // translucent series colour would blend with whatever the chart painted
        // first — the theme validated these as OPAQUE inks on the card.
        val all = BtGlanceChartPalette.SERIES_DAY + BtGlanceChartPalette.SERIES_NIGHT +
            listOf(
                BtGlanceChartPalette.REST_DAY,
                BtGlanceChartPalette.REST_NIGHT,
                BtGlanceChartPalette.CASH_DAY,
                BtGlanceChartPalette.CASH_NIGHT,
            )
        val translucent = all.filterNot { (it ushr 24) == 0xFFL }
        assertTrue("chart colours with alpha: ${translucent.map { hex(it) }}", translucent.isEmpty())
    }

    @Test
    fun `both ramps are theme-aware and distinct per slot`() {
        // Same discipline as the text palette: a slot whose two sides are equal
        // never asked the theme.
        BtGlanceChartPalette.SERIES_DAY.zip(BtGlanceChartPalette.SERIES_NIGHT)
            .forEachIndexed { i, (day, night) ->
                assertTrue("chartSeries slot $i does not flip with the theme", day != night)
            }
        // And within one mode no two slots may collide — they are identity hues.
        assertEquals(10, BtGlanceChartPalette.SERIES_DAY.toSet().size)
        assertEquals(10, BtGlanceChartPalette.SERIES_NIGHT.toSet().size)
    }
}
