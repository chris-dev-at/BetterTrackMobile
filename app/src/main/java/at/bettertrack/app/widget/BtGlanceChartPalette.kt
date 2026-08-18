package at.bettertrack.app.widget

import android.content.Context
import android.content.res.Configuration
import at.bettertrack.app.data.prefs.BtThemeMode

/**
 * The chart palette for widget BITMAPS, mirrored from `BtColors`.
 *
 * ## Why this exists next to [BtGlanceColor]
 *
 * Text and backgrounds cross to the launcher as `ColorProvider`s, which can carry
 * a day/night PAIR the host resolves at draw time. A chart cannot: Glance has no
 * canvas, so every diagram widget rasterises its chart into a [android.graphics.Bitmap]
 * in OUR process, and a bitmap has exactly one set of pixels. The theme therefore
 * has to be resolved BEFORE painting — see [btWidgetIsNight] — and the palette
 * here carries plain ARGB longs per side rather than providers.
 *
 * The values are a MIRROR of `BtColors.chartSeries` / `chartRest` / `chartCash`
 * (the platform's own `CATEGORICAL_SERIES`, validated in both modes), and like
 * every widget mirror it is pinned by a source-reading test —
 * `BtWidgetChartPaletteMirrorTest` — so a retune of the app's ramp cannot leave
 * the home screen painting last season's slices.
 *
 * The slot rules are the theme's own, restated because a widget renderer cannot
 * read the KDoc it mirrors: assign slots IN ORDER by descending weight, never
 * cycle past the last slot (fold the tail into [rest]), gold is the brand accent
 * and NEVER a series colour, gain/loss stay reserved for money deltas.
 */
object BtGlanceChartPalette {

    /** `BtLightColors.chartSeries`, verbatim. */
    val SERIES_DAY: List<Long> = listOf(
        0xFF1F6AC4, // blue
        0xFFB8431A, // orange
        0xFF12805B, // green
        0xFF96600A, // yellow
        0xFFB93A68, // magenta
        0xFF6154C6, // violet
        0xFF00887A, // teal
        0xFFA03832, // red-brown
        0xFF6B8A1A, // lime
        0xFF8E46AD, // purple
    )

    /** `BtDarkColors.chartSeries`, verbatim. */
    val SERIES_NIGHT: List<Long> = listOf(
        0xFF3987E5, // blue
        0xFFD95926, // orange
        0xFF199E70, // green
        0xFFC98500, // yellow
        0xFFD55181, // magenta
        0xFF9085E9, // violet
        0xFF0D9488, // teal
        0xFFC0453F, // red-brown
        0xFF7A9E2B, // lime
        0xFFB06FC9, // purple
    )

    /** `BtColors.chartRest` — the fold bucket ("Other"), neutral not identity. */
    const val REST_DAY: Long = 0xFF6E7276
    const val REST_NIGHT: Long = 0xFF525252

    /** `BtColors.chartCash` — the uninvested slice, quiet silver. */
    const val CASH_DAY: Long = 0xFF7A828B
    const val CASH_NIGHT: Long = 0xFF8A8A8A

    /** How many identity slots a widget donut may use before folding. */
    val slotCount: Int get() = SERIES_DAY.size

    fun series(night: Boolean): List<Long> = if (night) SERIES_NIGHT else SERIES_DAY

    /**
     * Resolve one [BtWidgetSlice.colorIndex] to ARGB. Series slots by position;
     * the two semantic slots ([BT_SLICE_REST] / [BT_SLICE_CASH]) by name. An
     * out-of-range slot takes the rest colour rather than crashing the launcher —
     * the data prep caps at [slotCount], so hitting that branch is a bug the
     * tests catch, not one the user should.
     */
    fun slice(colorIndex: Int, night: Boolean): Int = when {
        colorIndex == BT_SLICE_CASH -> (if (night) CASH_NIGHT else CASH_DAY).toInt()
        colorIndex >= 0 && colorIndex < slotCount -> series(night)[colorIndex].toInt()
        else -> (if (night) REST_NIGHT else REST_DAY).toInt()
    }

    /** The portfolio line: gold, per §4.1 — gold IS the portfolio in the app's charts. */
    fun portfolioLine(night: Boolean): Int =
        (if (night) BtGlanceColor.Gold.night else BtGlanceColor.Gold.day).toInt()

    fun gain(night: Boolean): Int =
        (if (night) BtGlanceColor.Gain.night else BtGlanceColor.Gain.day).toInt()

    fun loss(night: Boolean): Int =
        (if (night) BtGlanceColor.Loss.night else BtGlanceColor.Loss.day).toInt()

    fun textMuted(night: Boolean): Int =
        (if (night) BtGlanceColor.TextMuted.night else BtGlanceColor.TextMuted.day).toInt()

    /** The track behind a chart (donut hole ring, bar baseline): the border hairline. */
    fun track(night: Boolean): Int =
        (if (night) BtGlanceColor.Border.night else BtGlanceColor.Border.day).toInt()

    /** The card surface — the endpoint dot's ring, so the dot reads as ON the line. */
    /**
     * A readable ink for text painted ON a resolved fill.
     *
     * The widget mirror of `BtColors.chartInk`, and it exists for the same
     * reason: the fill is chosen by the data, so white cannot be assumed. On the
     * pale cash silver white is ~3.5:1 while near-black is ~5.5:1, and a widget
     * has no tooltip to compensate for a label nobody can read.
     */
    fun inkOn(fill: Int): Int {
        fun channel(shift: Int): Double {
            val c = ((fill shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        val luminance = 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        // 0.1791 is the luminance at which white and black contrast equally.
        return if (luminance > 0.1791) INK_ON_PALE.toInt() else INK_ON_FILL.toInt()
    }

    /** Near-white ink for saturated/dark fills. Mirrors `BtColors.chartInkOnFill`. */
    const val INK_ON_FILL: Long = 0xFFF7F9FB

    /** Near-black ink for pale fills. Mirrors `BtColors.chartInkOnPaleFill`. */
    const val INK_ON_PALE: Long = 0xFF0B0E14

    fun surface(night: Boolean): Int =
        (if (night) BtGlanceColor.Surface.night else BtGlanceColor.Surface.day).toInt()
}

/**
 * Which side of the palette a bitmap paints with.
 *
 * A forced theme decides outright. `System` reads the CONFIGURATION this process
 * currently sees — the same signal a day/night `ColorProvider` resolves against,
 * just resolved now instead of at host-draw time. The one behaviour this trades
 * away: a system dark-mode flip repaints the bitmap only on the next widget
 * update rather than instantly (the TEXT around it flips instantly via the
 * providers). The next update is at most one refresh cycle away, and a
 * configuration change also triggers `onUpdate` on most launchers, so in
 * practice the window is seconds.
 */
fun btWidgetIsNight(context: Context, mode: BtThemeMode): Boolean = when (mode) {
    BtThemeMode.Light -> false
    BtThemeMode.Dark -> true
    BtThemeMode.System ->
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}
