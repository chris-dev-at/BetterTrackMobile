package at.bettertrack.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * BetterTrack theme — DARK ONLY (spec §3.3: the app renders dark regardless of
 * the system setting; no light theme in v1).
 *
 * Exposes the brand tokens via [BtTheme] and simultaneously maps them onto a
 * Material3 dark color scheme so stock components (buttons, top bar, navigation
 * bar, …) default to correct brand colors.
 */
object BtTheme {
    val colors: BtColors
        @Composable @ReadOnlyComposable get() = LocalBtColors.current
    val type: BtTypography
        @Composable @ReadOnlyComposable get() = LocalBtTypography.current
}

private val Bt = BtColors()

private val BtDarkColorScheme = darkColorScheme(
    primary = Bt.gold,
    onPrimary = Bt.onGold,
    primaryContainer = Bt.goldSurface,
    onPrimaryContainer = Bt.goldSoft,
    inversePrimary = Bt.goldSurfaceStrong,
    secondary = Bt.textSecondary,
    onSecondary = Bt.bg,
    // NavigationBar selection pill = secondaryContainer → amber-tinted dark,
    // with gold content (gold is reserved for selection).
    secondaryContainer = Bt.goldSurface,
    onSecondaryContainer = Bt.gold,
    tertiary = Bt.gain,
    onTertiary = Bt.bg,
    tertiaryContainer = Bt.surface,
    onTertiaryContainer = Bt.gainSoft,
    background = Bt.bg,
    onBackground = Bt.textPrimary,
    surface = Bt.bg,
    onSurface = Bt.textPrimary,
    surfaceVariant = Bt.surface,
    onSurfaceVariant = Bt.textSecondary,
    surfaceTint = Bt.bg, // kill M3 elevation tinting — flat design
    inverseSurface = Bt.textPrimary,
    inverseOnSurface = Bt.bg,
    error = Bt.loss,
    onError = Bt.bg,
    errorContainer = Bt.lossSurface,
    onErrorContainer = Bt.lossSoft,
    outline = Bt.borderStrong,
    outlineVariant = Bt.border,
    scrim = Bt.bgAlt,
    surfaceBright = Bt.surface,
    surfaceDim = Bt.bgAlt,
    surfaceContainer = Bt.surface,
    surfaceContainerLowest = Bt.bgAlt,
    surfaceContainerLow = Bt.bg,
    surfaceContainerHigh = Bt.surface,
    surfaceContainerHighest = Bt.border,
)

@Composable
fun BetterTrackTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalBtColors provides Bt,
        LocalBtTypography provides BtTypography(),
    ) {
        MaterialTheme(
            colorScheme = BtDarkColorScheme,
            typography = BtMaterialTypography,
            shapes = BtMaterialShapes,
            content = content,
        )
    }
}
