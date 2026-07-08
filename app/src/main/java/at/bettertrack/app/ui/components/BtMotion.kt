package at.bettertrack.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * BetterTrack interaction motion (spec §3.7 — subtle, quick, reduced-motion
 * aware). A tactile press-scale that every tappable surface (buttons, cards,
 * chips) shares, so pressing anything in the app feels consistently responsive.
 *
 * The scale is driven off the SAME [interactionSource] the component uses for its
 * ripple, so press feedback and scale stay perfectly in sync. Under reduced
 * motion the scale is pinned to 1f (no movement), while the ripple still fires.
 */
@Composable
fun Modifier.btPressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f,
): Modifier {
    val reducedMotion = rememberReducedMotion()
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reducedMotion) pressedScale else 1f,
        // A quick, lightly-damped spring — springy but never bouncy.
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 620f),
        label = "btPressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
