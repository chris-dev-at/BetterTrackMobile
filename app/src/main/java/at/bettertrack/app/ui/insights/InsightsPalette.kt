package at.bettertrack.app.ui.insights

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import at.bettertrack.app.ui.theme.BtTheme

/**
 * The two palettes the exporters paint with, resolved to plain ARGB ints.
 *
 * A bitmap and a PDF page cannot carry a theme pair — they are one set of
 * pixels, chosen once — so every colour has to be resolved before the renderer
 * starts. This file is the only place that resolution happens, which is what
 * keeps the exported artefacts on the app's palette instead of a second,
 * slowly-drifting copy of it.
 *
 * Colours are written channel-wise (`argb(253, 252, 248)`) rather than as
 * hex literals on purpose: `BtThemeDisciplineTest` scans every file under `ui/`
 * and bans `Color(0x…)` literals and named `Color.XXX` constants, so that the
 * app's palette can only come from `BtTheme`. The print palette below is the one
 * legitimate exception — it is ink on paper, not a theme. [argb] is plain
 * arithmetic rather than the platform's colour helper, which is a stub on the
 * JVM and would make [InsightsPaletteMirrorTest] compare a column of zeroes.
 */

/**
 * The A4 report palette: **light-first, always**.
 *
 * The study is explicit that the PDF does not follow the app theme — "white
 * paper prints and reads better than a dark full-bleed document". A dark PDF
 * also costs the reader a toner cartridge and an unreadable printout, which is
 * a worse outcome than a document that does not match the phone it came from.
 * Only the in-app *preview* of that document follows the system theme.
 */
fun insightsPdfPalette(): BtInsightPaintTheme = BtInsightPaintTheme(
    paper = argb(253, 252, 248),
    panel = argb(255, 255, 255),
    border = argb(217, 215, 209),
    ink = argb(23, 23, 23),
    muted = argb(104, 104, 104),
    gold = argb(246, 184, 46),
    // The light-mode money direction, not the dark one: on paper the dark
    // variants are the legible pair, and emerald/red still mean only direction.
    gain = argb(15, 120, 83),
    loss = argb(178, 58, 78),
    series = PRINT_SERIES,
    rest = argb(110, 114, 118),
    cash = argb(122, 130, 139),
    inkOnFill = argb(247, 249, 251),
    inkOnPale = argb(11, 14, 20),
)

/**
 * The round-5 categorical ramp in its LIGHT values.
 *
 * A verbatim mirror of `BtLightColors.chartSeries`, resolved here because a PDF
 * has no composition to read the theme from. The widget package already keeps a
 * mirror of the same ramp for the same reason, and is pinned to the app's by a
 * test; this list is pinned by `InsightsPaletteMirrorTest`.
 */
private val PRINT_SERIES: List<Int> = listOf(
    argb(31, 106, 196),
    argb(184, 67, 26),
    argb(18, 128, 91),
    argb(150, 96, 10),
    argb(185, 58, 104),
    argb(97, 84, 198),
    argb(0, 136, 122),
    argb(160, 56, 50),
    argb(107, 138, 26),
    argb(142, 70, 173),
)

/**
 * The shared-image palette: the app's CURRENT theme.
 *
 * The study says social graphics follow the system theme, and that is the right
 * call for a different reason than the PDF's: a poster is published as-is, so it
 * should look like the app the user is showing off, not like a document.
 */
@Composable
fun rememberInsightImagePalette(): BtInsightPaintTheme {
    val bt = BtTheme.colors
    return remember(bt) {
        BtInsightPaintTheme(
            paper = bt.bg.toArgb(),
            panel = bt.surface.toArgb(),
            border = bt.groupBorder.toArgb(),
            ink = bt.textPrimary.toArgb(),
            muted = bt.textMuted.toArgb(),
            gold = bt.gold.toArgb(),
            gain = bt.gain.toArgb(),
            loss = bt.loss.toArgb(),
            series = bt.chartSeries.map { it.toArgb() },
            rest = bt.chartRest.toArgb(),
            cash = bt.chartCash.toArgb(),
            inkOnFill = bt.chartInkOnFill.toArgb(),
            inkOnPale = bt.chartInkOnPaleFill.toArgb(),
        )
    }
}

/**
 * Pack three channels into an opaque ARGB int.
 *
 * Deliberately arithmetic rather than the platform's colour helper: that helper
 * is a stub under JVM unit tests and returns 0, so a mirror test asserting this
 * palette against the app's ramp would compare zero to zero and pass while the
 * real report printed a column of black.
 */
private fun argb(r: Int, g: Int, b: Int): Int =
    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
