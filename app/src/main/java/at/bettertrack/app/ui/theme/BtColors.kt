package at.bettertrack.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * First-class BetterTrack color tokens (spec §3.3, dark-only).
 *
 * These are THE source of truth for brand colors. They are also mapped onto a
 * Material3 [androidx.compose.material3.darkColorScheme] in [BetterTrackTheme]
 * so stock M3 components default sensibly, but app code should prefer
 * `BtTheme.colors` for anything brand-specific.
 */
@Immutable
data class BtColors(
    /** Page background — near-black. */
    val bg: Color = Color(0xFF0B0E14),
    /** Alternative near-black (neutral-950). */
    val bgAlt: Color = Color(0xFF0A0A0A),
    /** Card / surface (neutral-900). */
    val surface: Color = Color(0xFF171717),
    /** Dominant separator — 1px borders, not shadows (neutral-800). */
    val border: Color = Color(0xFF262626),
    /** Stronger border (neutral-700). */
    val borderStrong: Color = Color(0xFF404040),
    val textPrimary: Color = Color(0xFFFFFFFF),
    /** Secondary text (neutral-400). */
    val textSecondary: Color = Color(0xFFA3A3A3),
    /** Muted text / hints / edition label. */
    val textMuted: Color = Color(0xFF8A8A8A),
    /** Brand accent — the ONLY accent. Reserved for brand + primary actions/selection. */
    val gold: Color = Color(0xFFF6B82E),
    /** Amber tint for emphasis. */
    val goldEmphasis: Color = Color(0xFFFBBF24),
    /** Softer amber tint. */
    val goldSoft: Color = Color(0xFFFCD34D),
    /** Dark amber-tinted surface for highlighted/selected cards (amber-950 equivalent). */
    val goldSurface: Color = Color(0xFF451A03),
    /** Amber-900 equivalent, e.g. borders of selected cards. */
    val goldSurfaceStrong: Color = Color(0xFF78350F),
    /** Content color on top of gold fills. */
    val onGold: Color = Color(0xFF0B0E14),
    /** Gains / positive (strong). */
    val gain: Color = Color(0xFF34D399),
    /** Gains / positive (soft). */
    val gainSoft: Color = Color(0xFF6EE7B7),
    /** Losses / negative / destructive (strong). */
    val loss: Color = Color(0xFFF87171),
    /** Losses / negative (soft). */
    val lossSoft: Color = Color(0xFFFCA5A5),
    /** Dark red-tinted surface for destructive confirms (red-950 equivalent). */
    val lossSurface: Color = Color(0xFF450A0A),
    /** Skeleton base / subtle raised fill. */
    val skeletonBase: Color = Color(0xFF1C1C1C),
    /** Skeleton shimmer highlight. */
    val skeletonHighlight: Color = Color(0xFF262626),
)

val LocalBtColors = staticCompositionLocalOf { BtColors() }
