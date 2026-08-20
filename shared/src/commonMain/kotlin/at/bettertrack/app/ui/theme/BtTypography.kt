package at.bettertrack.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Typography (spec §3.4): system font only (Roboto / device default). The brand
 * look comes from WEIGHT and tight LETTER-SPACING, not a typeface. All
 * money/number styles carry tabular figures ("tnum") so digit columns align.
 */

/** Tabular figures — put on every money/number style. */
const val FONT_FEATURE_TABULAR = "tnum"

/**
 * The face the ramp is set in. `by lazy` rather than an eager `val` since the
 * web port (W1): a browser has no system font to resolve `FontFamily.Default`
 * against, so the browser host installs an embedded family into [BtFonts]
 * before the first composition, and the first read of this pins it. On Android
 * and iOS nothing installs anything and this stays `FontFamily.Default`, i.e.
 * byte-identical to the eager line it replaced. See [BtFonts].
 */
private val System: FontFamily by lazy { BtFonts.appFontFamily }

/** Brand-specific styles that don't map 1:1 onto Material roles. */
@Immutable
data class BtTypography(
    /**
     * The app's single biggest number — Home's net worth (R-arc R1, O-8).
     *
     * The mandate asks for "a bigger type ramp for hero numbers — money is the
     * product, let it breathe". This is the top of that ramp, one clear step
     * above [moneyLarge] rather than a nudge: at 44sp against 36sp the two read
     * as different roles, which is the point — Home answers "what am I worth"
     * and Portfolio answers "what is this portfolio worth", and the type should
     * say so before the label does. Tracking tightens with size so the extra
     * 8sp buys presence, not width; a 9-digit figure still fits 360dp.
     */
    val moneyHero: TextStyle = TextStyle(
        fontFamily = System,
        fontWeight = FontWeight.Bold,
        fontSize = 44.sp,
        lineHeight = 50.sp,
        letterSpacing = (-0.03).em,
        fontFeatureSettings = FONT_FEATURE_TABULAR,
    ),
    /** Hero money value (portfolio total) — large, confident, tightly tracked. */
    val moneyLarge: TextStyle = TextStyle(
        fontFamily = System,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.025).em,
        fontFeatureSettings = FONT_FEATURE_TABULAR,
    ),
    /** Stat-card / row-level prominent value. */
    val moneyMedium: TextStyle = TextStyle(
        fontFamily = System,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.01).em,
        fontFeatureSettings = FONT_FEATURE_TABULAR,
    ),
    /** In-list money values. */
    val moneySmall: TextStyle = TextStyle(
        fontFamily = System,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = FONT_FEATURE_TABULAR,
    ),
    /** Small numeric captions (deltas, percentages). */
    val numberCaption: TextStyle = TextStyle(
        fontFamily = System,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        fontFeatureSettings = FONT_FEATURE_TABULAR,
    ),
    /**
     * The bottom bar's label, and the ONE type addition B2 makes (§2 A9).
     *
     * 11sp Medium, tracking 0. The bar moved from an 80dp `NavigationBar` to a
     * 64dp `ShortNavigationBar`, and the 16dp that buys has to come from
     * somewhere: at `labelMedium`'s 12sp the label and the 24dp glyph do not both
     * fit the shorter item with the 6dp gap intact. 11sp does, and it keeps the
     * WORD — dropping labels is the exact Trade-Republic failure mode the owner
     * named ("too simplified… annoying"), so the height is bought from the type
     * ramp rather than from the information.
     *
     * Tracking is 0 rather than the brand's usual negative: at 11sp the tight
     * tracking that gives the hero numbers their presence just closes up the
     * counters and costs legibility at the smallest size the app ships.
     */
    val labelNav: TextStyle = TextStyle(
        fontFamily = System,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.sp,
    ),
)

val LocalBtTypography = staticCompositionLocalOf { BtTypography() }

/**
 * Material3 typography mapped to the brand rules (bold + tight for titles).
 *
 * `by lazy` for the same reason [System] is (web port, W1) — it bakes the face
 * into 15 styles, so it must not be built before the browser host has installed
 * one. Still exactly one instance for the process once built, which is what
 * keeps `MaterialTheme(typography = …)` from invalidating on recomposition.
 */
val BtMaterialTypography: Typography by lazy {
    Typography().run {
        copy(
            displayLarge = displayLarge.copy(fontFamily = System, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
            displayMedium = displayMedium.copy(fontFamily = System, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
            displaySmall = displaySmall.copy(fontFamily = System, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
            headlineLarge = headlineLarge.copy(fontFamily = System, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
            headlineMedium = headlineMedium.copy(fontFamily = System, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
            headlineSmall = headlineSmall.copy(fontFamily = System, fontWeight = FontWeight.Bold, letterSpacing = (-0.015).em),
            titleLarge = titleLarge.copy(fontFamily = System, fontWeight = FontWeight.Bold, letterSpacing = (-0.015).em),
            titleMedium = titleMedium.copy(fontFamily = System, fontWeight = FontWeight.SemiBold),
            titleSmall = titleSmall.copy(fontFamily = System, fontWeight = FontWeight.SemiBold),
            bodyLarge = bodyLarge.copy(fontFamily = System),
            bodyMedium = bodyMedium.copy(fontFamily = System),
            bodySmall = bodySmall.copy(fontFamily = System),
            labelLarge = labelLarge.copy(fontFamily = System, fontWeight = FontWeight.Medium),
            labelMedium = labelMedium.copy(fontFamily = System, fontWeight = FontWeight.Medium),
            labelSmall = labelSmall.copy(fontFamily = System, fontWeight = FontWeight.Medium),
        )
    }
}
