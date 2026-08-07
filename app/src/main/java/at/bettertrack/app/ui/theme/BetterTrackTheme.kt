package at.bettertrack.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import at.bettertrack.app.data.prefs.BtThemeMode

/**
 * BetterTrack theme — dual-table (B2 design spec §1.2).
 *
 * Exposes the brand tokens via [BtTheme] and simultaneously maps them onto a
 * Material3 color scheme so stock components (buttons, top bar, navigation bar,
 * …) default to correct brand colors.
 */
object BtTheme {
    val colors: BtColors
        @Composable @ReadOnlyComposable get() = LocalBtColors.current
    val type: BtTypography
        @Composable @ReadOnlyComposable get() = LocalBtTypography.current
}

/**
 * Build-time gates for the theme rollout.
 *
 * The migration order (§1.6) is deliberate: light mode becomes user-reachable
 * only **after** every shared component has been verified in it, so that no
 * intermediate build can ship a broken light screen. Package B2-A lands the
 * token tables, the plumbing and the guard tests; package B2-B does the
 * component sweep and flips this flag together with the Settings → Appearance
 * picker that exposes the choice.
 *
 * While it is `false`, [BetterTrackTheme] resolves **every** mode to dark for
 * the real app, and only the debug component gallery may opt in. A tripwire
 * test asserts the value so the flip is a deliberate, reviewed edit rather than
 * something that drifts in.
 */
object BtThemeFeatures {
    const val LIGHT_MODE_PUBLIC = false
}

/**
 * Resolve a [BtThemeMode] against the system setting and the rollout gate.
 *
 * Pure except for its [systemInDark] input, so the gate is unit-testable.
 * Returns true when the DARK table should be used.
 */
fun resolveDarkTheme(
    mode: BtThemeMode,
    systemInDark: Boolean,
    lightAllowed: Boolean = BtThemeFeatures.LIGHT_MODE_PUBLIC,
): Boolean {
    if (!lightAllowed) return true
    return when (mode) {
        BtThemeMode.System -> systemInDark
        BtThemeMode.Dark -> true
        BtThemeMode.Light -> false
    }
}

/**
 * Map the brand tokens onto Material3's 30-odd roles.
 *
 * One builder for both modes: the role assignments are semantic, so they port
 * verbatim between tables — which is the whole point of having one token set.
 *
 * `surfaceTint` is pinned to [BtColors.bg] in both modes on purpose: M3's
 * elevation tinting would fight the explicit five-step tonal ramp, quietly
 * re-tinting surfaces the ramp has already placed.
 */
fun materialSchemeFrom(bt: BtColors, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = bt.gold,
        onPrimary = bt.onGold,
        primaryContainer = bt.goldSurface,
        onPrimaryContainer = bt.goldSoft,
        inversePrimary = bt.goldSurfaceStrong,
        secondary = bt.textSecondary,
        onSecondary = bt.bg,
        // NavigationBar selection pill = secondaryContainer → gold-tinted,
        // with gold content (gold is reserved for selection).
        secondaryContainer = bt.goldSurface,
        onSecondaryContainer = bt.goldInk,
        tertiary = bt.gain,
        onTertiary = bt.bg,
        tertiaryContainer = bt.surface,
        onTertiaryContainer = bt.gainSoft,
        background = bt.bg,
        onBackground = bt.textPrimary,
        surface = bt.bg,
        onSurface = bt.textPrimary,
        surfaceVariant = bt.surface,
        onSurfaceVariant = bt.textSecondary,
        surfaceTint = bt.bg, // kill M3 elevation tinting — flat design
        inverseSurface = bt.textPrimary,
        inverseOnSurface = bt.bg,
        error = bt.loss,
        onError = bt.bg,
        errorContainer = bt.lossSurface,
        onErrorContainer = bt.lossSoft,
        outline = bt.borderStrong,
        outlineVariant = bt.border,
        scrim = bt.bgAlt,
        surfaceBright = bt.surfaceHigh,
        surfaceDim = bt.bgAlt,
        surfaceContainer = bt.surface,
        surfaceContainerLowest = bt.bgAlt,
        surfaceContainerLow = bt.surfaceLow,
        surfaceContainerHigh = bt.surfaceHigh,
        surfaceContainerHighest = bt.surfaceHighest,
    )
}

/**
 * @param mode the user's choice; resolved against the system setting and the
 *   [BtThemeFeatures.LIGHT_MODE_PUBLIC] rollout gate.
 * @param trueBlack AMOLED override, honoured only when the resolved mode is dark.
 * @param allowLight escape hatch for the debug component gallery, which must be
 *   able to render the light table before it is public. Never pass true from
 *   production UI.
 */
@Composable
fun BetterTrackTheme(
    mode: BtThemeMode = BtThemeMode.System,
    trueBlack: Boolean = false,
    allowLight: Boolean = BtThemeFeatures.LIGHT_MODE_PUBLIC,
    content: @Composable () -> Unit,
) {
    val systemInDark = isSystemInDarkTheme()
    val dark = resolveDarkTheme(mode, systemInDark, allowLight)
    val colors = remember(dark, trueBlack) {
        when {
            !dark -> BtLightColors
            trueBlack -> BtDarkColors.asTrueBlack()
            else -> BtDarkColors
        }
    }
    val scheme = remember(colors, dark) { materialSchemeFrom(colors, dark) }
    CompositionLocalProvider(
        LocalBtColors provides colors,
        LocalBtTypography provides BtTypography(),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = BtMaterialTypography,
            shapes = BtMaterialShapes,
            content = content,
        )
    }
}
