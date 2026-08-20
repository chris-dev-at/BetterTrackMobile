package at.bettertrack.app.ui.components

import androidx.compose.runtime.Composable

/**
 * True when the system requests reduced motion. Every BetterTrack animation
 * must respect this (§3.7).
 *
 * Carved out of `BtSkeleton.kt` into an `expect`/`actual` by the web port,
 * Phase W1, because [btPressScale] — on every tappable surface in the app —
 * reads it, and the two platforms disagree about where the setting lives, not
 * about what it means. Android keeps its `ANIMATOR_DURATION_SCALE` read
 * verbatim; the browser reads the `prefers-reduced-motion` media query, which
 * is the same user intent expressed by the same person in a different settings
 * panel. Package unchanged, so no call site moved.
 */
@Composable
expect fun rememberReducedMotion(): Boolean
