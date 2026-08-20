package at.bettertrack.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

/**
 * iOS: Settings → Accessibility → Motion → Reduce Motion, i.e. the same user
 * intent Android spells as an animator duration scale of 0. Sampled once per
 * composition, matching the other two actuals.
 */
@Composable
actual fun rememberReducedMotion(): Boolean = remember { UIAccessibilityIsReduceMotionEnabled() }
