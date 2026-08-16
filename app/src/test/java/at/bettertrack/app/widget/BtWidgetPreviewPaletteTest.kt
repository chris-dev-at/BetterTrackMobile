package at.bettertrack.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The picker-preview palette is the THIRD copy of the widget colours: the live
 * widgets read [BtGlanceColor], the preview layouts (inflated by the launcher,
 * where no Glance code runs) read `@color/bt_widget_*` resources, day side in
 * `res/values/colors.xml` and night side in `res/values-night/colors.xml`.
 *
 * A mirror drifts, so — like [BtWidgetPaletteMirrorTest] pins BtGlanceColor to
 * `BtColors.kt` — this pins the resources to BtGlanceColor. Break the chain
 * anywhere and a picker preview quietly stops looking like the widget it
 * advertises.
 */
class BtWidgetPreviewPaletteTest {

    private fun resFile(qualifier: String): File {
        val name = "src/main/res/values$qualifier/colors.xml"
        val candidates = listOf(File(name), File("app/$name"))
        return candidates.firstOrNull { it.isFile }
            ?: error("colors.xml not found; tried ${candidates.map { it.absolutePath }}")
    }

    private fun colors(qualifier: String): Map<String, Long> =
        Regex("""<color name="([^"]+)">#([0-9A-Fa-f]{8})</color>""")
            .findAll(resFile(qualifier).readText())
            .associate { it.groupValues[1] to it.groupValues[2].uppercase().toLong(16) }

    /** Preview resource name → the BtGlanceColor token it must mirror. */
    private val tokenMirror = mapOf(
        "bt_widget_surface" to BtGlanceColor.Surface,
        "bt_widget_track" to BtGlanceColor.Border,
        "bt_widget_text_primary" to BtGlanceColor.TextPrimary,
        "bt_widget_text_secondary" to BtGlanceColor.TextSecondary,
        "bt_widget_text_muted" to BtGlanceColor.TextMuted,
        "bt_widget_gold" to BtGlanceColor.Gold,
        "bt_widget_gain" to BtGlanceColor.Gain,
        "bt_widget_loss" to BtGlanceColor.Loss,
        // Round 2: the pill/chip fills the restyled previews paint with.
        "bt_widget_chip" to BtGlanceColor.Chip,
        "bt_widget_gain_wash" to BtGlanceColor.GainWash,
        "bt_widget_loss_wash" to BtGlanceColor.LossWash,
        "bt_widget_on_gold" to BtGlanceColor.OnGold,
    )

    @Test
    fun `the preview colour resources mirror BtGlanceColor, both sides`() {
        val day = colors("")
        val night = colors("-night")
        tokenMirror.forEach { (res, token) ->
            assertEquals("$res (day) must equal BtGlanceColor.${token.name}.day", token.day, day[res])
            assertEquals(
                "$res (night) must equal BtGlanceColor.${token.name}.night",
                token.night,
                night[res],
            )
        }
    }

    @Test
    fun `the preview series slots mirror the chart palette's first hues`() {
        val day = colors("")
        val night = colors("-night")
        (1..3).forEach { slot ->
            assertEquals(
                "bt_widget_series_$slot (day)",
                BtGlanceChartPalette.SERIES_DAY[slot - 1],
                day["bt_widget_series_$slot"],
            )
            assertEquals(
                "bt_widget_series_$slot (night)",
                BtGlanceChartPalette.SERIES_NIGHT[slot - 1],
                night["bt_widget_series_$slot"],
            )
        }
    }

    @Test
    fun `the gold wash is the gold hue at preview alpha, both sides`() {
        // Same RGB as the gold token, alpha 0x22 — the soft area fill under the
        // preview curve, matching the live bitmap's wash character.
        val day = colors("")["bt_widget_gold_wash"]!!
        val night = colors("-night")["bt_widget_gold_wash"]!!
        assertEquals(BtGlanceColor.Gold.day and 0xFFFFFFL, day and 0xFFFFFFL)
        assertEquals(BtGlanceColor.Gold.night and 0xFFFFFFL, night and 0xFFFFFFL)
        assertEquals(0x22L, day shr 24)
        assertEquals(0x22L, night shr 24)
    }

    @Test
    fun `every preview colour is defined on both sides`() {
        // A resource missing its -night twin silently renders the DAY value in
        // dark pickers — plausible-looking and wrong, the worst combination.
        val day = colors("").keys.filter { it.startsWith("bt_widget_") }
        val night = colors("-night").keys.filter { it.startsWith("bt_widget_") }
        assertTrue("no bt_widget_ preview colours found — has the scan broken?", day.isNotEmpty())
        assertEquals(
            "day/night preview colour sets must match",
            day.sorted(),
            night.sorted(),
        )
    }
}
