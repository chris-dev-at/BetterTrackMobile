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
 *
 * ## The layer exists only while it is doing something (perf claw-back 2026-08-06)
 *
 * `Modifier.graphicsLayer` is not free the way a colour or a padding is: it
 * allocates a **RenderNode**, and the composition it wraps gets its own display
 * list that the renderer has to visit and composite separately every frame.
 *
 * This modifier is on *every tappable surface in the app* — every button, chip,
 * card, list row and the portfolio selector pill (twice, since M3 composes a
 * collapsing header's title slot for both the collapsed and the expanded row).
 * A screenful of holdings is therefore a screenful of RenderNodes whose transform
 * is the identity, because a finger can only press one of them and presses last
 * about 200ms. That is a per-frame cost paid, on a scroll, entirely for controls
 * nobody is touching.
 *
 * So the layer is attached only when `scale != 1f` — i.e. while a press is
 * animating in, held, or springing back — and dropped again once the spring
 * settles. The identity case, which is ~all of the time, becomes a plain
 * `Modifier` and disappears from the tree.
 *
 * This is safe because the layer carries *nothing but* the scale: no alpha, no
 * clip, no elevation, no compositing strategy. Attaching it at the first frame
 * of a press changes nothing visible — `animateFloatAsState` starts that
 * animation AT 1f, so the frame where the node appears draws the identity
 * transform, exactly as the frame before it did.
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
    if (scale == 1f) return this
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
