package at.bettertrack.app.ui.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import at.bettertrack.app.ui.theme.BtTheme

/**
 * True when the system requests reduced motion (animator duration scale is 0 —
 * "remove animations"). Every BetterTrack animation must respect this (§3.7).
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}

/**
 * The two tones a skeleton is built from, picked per colour table.
 *
 * A shimmer has exactly two requirements, and only one of them survives naively
 * porting the dark pairing to light:
 *
 *  1. The block must read as a **placeholder**, i.e. differ from whatever it
 *     sits on (the page or a card).
 *  2. The sweep must be **lighter than the block**. A band darker than its base
 *     does not read as light moving across a surface; it reads as a smear.
 *
 * In dark, `surfaceLow → surfaceHighest` satisfies both: the ramp rises away
 * from the page, so the block is lighter than the page and the highlight is
 * lighter still. In light the ramp is compressed into ~5 L\* and the page sits
 * in the *middle* of it — `surfaceLow` (`#F4F5F7`) is barely a hair lighter than
 * `bg` (`#EEF0F2`), and `surfaceHighest` (`#E8EAEC`) is *darker* than both. Used
 * unchanged, the light skeleton became an almost invisible block with a sweep
 * that travelled dark. (Caught in the B2-A gallery matrix, light shot 06.)
 *
 * So light inverts which end of the ramp it takes: the block is the darkest
 * step and the sweep is pure `surface`. This is the same "the ramp does not
 * separate on its own in light" fact as the tone-vs-hairline rule, and it is
 * resolved here once rather than at any call site.
 */
private data class SkeletonTones(val base: androidx.compose.ui.graphics.Color, val highlight: androidx.compose.ui.graphics.Color)

@Composable
private fun skeletonTones(): SkeletonTones {
    val bt = BtTheme.colors
    return if (bt.isLight) SkeletonTones(bt.surfaceHighest, bt.surface)
    else SkeletonTones(bt.surfaceLow, bt.surfaceHighest)
}

/**
 * Loading skeleton block with a subtle shimmer sweep. Under reduced motion the
 * shimmer is skipped and a static placeholder block is shown instead.
 */
@Composable
fun BtSkeleton(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp),
) {
    val tones = skeletonTones()
    val reducedMotion = rememberReducedMotion()
    if (reducedMotion) {
        Box(modifier.clip(shape).background(tones.base))
        return
    }
    val transition = rememberInfiniteTransition(label = "skeleton")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "skeletonSweep",
    )
    val base = tones.base
    val highlight = tones.highlight
    Box(
        modifier
            .clip(shape)
            .background(base)
            .drawBehind {
                val band = size.width * 0.6f
                val x = (size.width + 2 * band) * progress - band
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(base, highlight, base),
                        start = Offset(x - band, 0f),
                        end = Offset(x + band, size.height),
                    ),
                )
            },
    )
}
