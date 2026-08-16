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
    /** Neutral context-chip fill (the study's `--chip`), one tonal step off the card. */
    val chip: ColorProvider,
    /** Flattened washes for the delta pills — see [BtGlanceColor.GainWash]. */
    val gainWash: ColorProvider,
    val lossWash: ColorProvider,
    val goldWash: ColorProvider,
    /** Ink on a gold-filled chip (the active range chip) — `BtColors.onGold`. */
    val onGold: ColorProvider,
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
    /**
     * The widget card. NIGHT mirrors `BtColors.surface`; DAY is the Codex
     * study's warm off-white (`--surface: #fbfbf9`), owner-ruled 2026-08-16 —
     * a pure-white card on a launcher read as "default-white", and the widget
     * sits on the wallpaper, not on the app's page, so it takes the study's
     * value rather than the app token. The palette-mirror test pins this
     * ruling explicitly.
     */
    Surface(0xFFFBFBF9, 0xFF161B22),

    /**
     * NIGHT mirrors the app's flattened `bt_border`; DAY is the study's
     * `--border: #d9d9d4`, part of the same owner ruling as [Surface].
     */
    Border(0xFFD9D9D4, 0xFF272C33),

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

    /**
     * The neutral context-chip fill (widget redesign round 2, per the Codex
     * study's `--chip`): one quiet tonal step off the card, carrying the "EUR" /
     * month / grouping badges. Mapped onto the brand neutrals rather than the
     * study's raw greys so it sits on OUR surfaces.
     */
    Chip(0xFFEFEFEB, 0xFF20262E),

    /**
     * The delta pills' tinted fills. A RemoteViews colour cannot carry alpha
     * (see `every token is fully opaque`), so these are the brand hue
     * PRE-FLATTENED over [Surface] at pill strength (12 % day / 14 % night —
     * night washes step up exactly as the app's own wash tokens do). Recompute
     * when Surface / Gain / Loss / Gold change; the derivation lives in this
     * KDoc so the next retune has the formula.
     */
    GainWash(0xFFDFEBE5, 0xFF1A3533),
    LossWash(0xFFF2E4E4, 0xFF362730),
    GoldWash(0xFFF6F0E0, 0xFF353124),

    /**
     * Ink on a gold-filled chip — `BtColors.onGold`, verbatim. Deliberately the
     * SAME on both sides: the substrate it sits on is the brand gold, which does
     * not flip with the theme, so the ink that must contrast with it cannot
     * flip either. The palette-mirror test carries a named exemption for it.
     */
    OnGold(0xFF171105, 0xFF171105),
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
    chip = BtGlanceColor.Chip.provider(mode),
    gainWash = BtGlanceColor.GainWash.provider(mode),
    lossWash = BtGlanceColor.LossWash.provider(mode),
    goldWash = BtGlanceColor.GoldWash.provider(mode),
    onGold = BtGlanceColor.OnGold.provider(mode),
)
