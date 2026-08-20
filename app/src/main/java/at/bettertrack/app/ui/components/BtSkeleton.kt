package at.bettertrack.app.ui.components

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import at.bettertrack.app.ui.theme.BtTheme

// `rememberReducedMotion()` moved to :shared as an expect/actual (web port, W1)
// — the Android reading of ANIMATOR_DURATION_SCALE went with it unchanged, the
// browser reads `prefers-reduced-motion` instead. Same package, so no call site
// here or anywhere else moved.

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
 * Both modes take the **same two ends of the neutral ramp**,
 * `surfaceLow`/`surfaceHighest` — and light takes them in the opposite order,
 * because since the white-page flip light's ramp runs the opposite way.
 * `BtColors` states that rule in full; the consequence here is one line:
 *
 *  - dark raises by getting lighter, so `surfaceHighest` is its bright end →
 *    `surfaceLow → surfaceHighest`.
 *  - light raises by getting more tinted, so `surfaceLow` is its bright end →
 *    `surfaceHighest → surfaceLow`.
 *
 * Both give a block that is ~3 L\* off the card it sits in (requirement 1) and
 * a sweep ~5 L\* brighter than that block (requirement 2). Reading the pairing
 * off the ramp in both directions is what keeps the two honest: the earlier
 * light pairing topped out at exactly `surface`, so the sweep vanished into the
 * card at its own peak. (The naive un-inverted port was caught in the B2-A
 * gallery matrix, light shot 06 — an invisible block with a sweep that
 * travelled dark. This keeps it caught.)
 */
private data class SkeletonTones(val base: androidx.compose.ui.graphics.Color, val highlight: androidx.compose.ui.graphics.Color)

@Composable
private fun skeletonTones(): SkeletonTones {
    val bt = BtTheme.colors
    return if (bt.isLight) SkeletonTones(bt.surfaceHighest, bt.surfaceLow)
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
