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

private val System = FontFamily.Default

/** Brand-specific styles that don't map 1:1 onto Material roles. */
@Immutable
data class BtTypography(
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
)

val LocalBtTypography = staticCompositionLocalOf { BtTypography() }

/** Material3 typography mapped to the brand rules (bold + tight for titles). */
val BtMaterialTypography = Typography().run {
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
