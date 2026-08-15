package at.bettertrack.app.widget

import androidx.compose.ui.graphics.Color
import androidx.glance.unit.ColorProvider
import at.bettertrack.app.data.prefs.BtThemeMode
import androidx.glance.color.ColorProvider as dayNightColorProvider

/**
 * The brand palette, mirrored for Glance.
 *
 * ## Why the widget cannot just read `BtTheme.colors`
 *
 * `BtColors` is delivered through a `staticCompositionLocalOf` inside the app's
 * Compose tree. A widget is not in that tree and is not even Compose UI — Glance
 * emits `RemoteViews` that the LAUNCHER inflates in its own process, where the
 * app's composition, its `CompositionLocal`s and its `Color` objects do not
 * exist. Every colour therefore has to cross the process boundary as either a
 * literal ARGB value or a resource id, which is exactly what [BtGlanceColor]
 * carries.
 *
 * ## Two tables, and why both
 *
 * Each token carries BOTH of its values, and how they are used depends on the
 * app's theme setting:
 *
 *  * following the system ⇒ a **day/night** provider. Glance hands that to the
 *    host as a pair, so on API 31+ the LAUNCHER picks the side — a mid-session
 *    dark-mode flip repaints correctly with no widget update at all.
 *  * a **forced** `BtThemeMode` ⇒ a fixed provider on the chosen side. The
 *    setting is a deliberate disagreement with the system ("light, on a dark
 *    phone"), which a day/night pair cannot express, and a widget that ignored it
 *    would sit next to a forced-dark app looking broken.
 *
 * These literals are a MIRROR of `BtColors`, and a mirror drifts. `BtWidgetPaletteMirrorTest`
 * reads `BtColors.kt` itself and asserts every value here still matches the theme
 * it was copied from, so a palette change in the app cannot silently leave the
 * widget behind.
 *
 * (Glance's resource-backed `ColorProvider(resId)` would have been the obvious
 * alternative to the literals. It is `@RestrictTo(LIBRARY_GROUP)` — lint fails
 * the build on it — so the public day/night factory is the supported route.)
 *
 * ## What is deliberately absent
 *
 * The AMOLED true-black sub-toggle. `BtColors.asTrueBlack()` overrides `bg`,
 * `bgAlt`, `scrim` and `surfaceLow` — and a widget paints none of those. Its card
 * is `surface` (`#161B22`), which `asTrueBlack()` leaves untouched, so honouring
 * the toggle here would be code that cannot change a pixel.
 */
class BtGlanceColors(
    val surface: ColorProvider,
    val border: ColorProvider,
    val textPrimary: ColorProvider,
    val textSecondary: ColorProvider,
    val textMuted: ColorProvider,
    val gold: ColorProvider,
    val gain: ColorProvider,
    val loss: ColorProvider,
) {
    /**
     * The colour of a signed number. Flat is [textSecondary], not [gain]: a day
     * change of exactly zero is not a gain, and painting it green would be the
     * widget's own small lie.
     */
    fun tone(tone: BtWidgetTone): ColorProvider = when (tone) {
        BtWidgetTone.UP -> gain
        BtWidgetTone.DOWN -> loss
        BtWidgetTone.FLAT -> textSecondary
    }
}

/**
 * One token and its two values, copied from `BtLightColors` / `BtDarkColors`.
 *
 * [Border] is the one that is not copied verbatim, because it cannot be: the
 * Compose token is an alpha hairline over a known substrate, and a `RemoteViews`
 * colour has nothing to composite against. It therefore takes the FLATTENED
 * values the app's own XML mirror already computed (`res/values/colors.xml`'s
 * `bt_border` and its night twin) — same reasoning, same numbers.
 */
enum class BtGlanceColor(val day: Long, val night: Long) {
    /** `BtColors.surface` — the widget card. */
    Surface(0xFFFFFFFF, 0xFF161B22),

    /** `BtColors.border`, flattened — see the enum KDoc. */
    Border(0xFFDCDEE2, 0xFF272C33),

    TextPrimary(0xFF131820, 0xFFF4F6F8),
    TextSecondary(0xFF3E4650, 0xFFC7CDD5),
    TextMuted(0xFF56616D, 0xFF8B949F),

    /**
     * `BtColors.goldInk` — the READABLE gold, not the brand fill. Light steps it
     * down to `#D49E28` because `#F6B82E` on white is a contrast failure; that is
     * `BtLightColors.goldInk`'s whole reason for existing.
     */
    Gold(0xFFD49E28, 0xFFF6B82E),

    Gain(0xFF0F7853, 0xFF34D399),
    Loss(0xFFB23A4E, 0xFFFB7185),
    ;

    fun provider(mode: BtThemeMode): ColorProvider = when (mode) {
        // A pair, so the host resolves it in the configuration it draws in.
        BtThemeMode.System -> dayNightColorProvider(day = Color(day), night = Color(night))
        BtThemeMode.Light -> ColorProvider(Color(day))
        BtThemeMode.Dark -> ColorProvider(Color(night))
    }
}

/** The palette for the app's current theme preference. */
fun btGlanceColors(mode: BtThemeMode): BtGlanceColors = BtGlanceColors(
    surface = BtGlanceColor.Surface.provider(mode),
    border = BtGlanceColor.Border.provider(mode),
    textPrimary = BtGlanceColor.TextPrimary.provider(mode),
    textSecondary = BtGlanceColor.TextSecondary.provider(mode),
    textMuted = BtGlanceColor.TextMuted.provider(mode),
    gold = BtGlanceColor.Gold.provider(mode),
    gain = BtGlanceColor.Gain.provider(mode),
    loss = BtGlanceColor.Loss.provider(mode),
)
