package at.bettertrack.app.ui.applock

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.bettertrack.app.R
import at.bettertrack.app.ui.components.btPressScale
import at.bettertrack.app.ui.components.rememberReducedMotion
import at.bettertrack.app.ui.theme.BtTheme

/**
 * The PIN entry progress dots (spec §5 lock screen). [filled] gold dots out of
 * [total], the rest hollow. On a wrong entry [error] pulses them red — paired
 * with the shake in [AppLockScreen] (both reduced-motion aware upstream).
 */
@Composable
fun PinDots(
    filled: Int,
    total: Int,
    error: Boolean,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    val reducedMotion = rememberReducedMotion()
    val filledColor by animateColorAsState(if (error) bt.loss else bt.gold, label = "pinDotFill")
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { i ->
            val on = i < filled
            // The gold fill scales + fades in over the hollow base as each digit
            // lands, so entry feels responsive (snapped under reduced motion).
            val fill by animateFloatAsState(
                targetValue = if (on) 1f else 0f,
                animationSpec = if (reducedMotion) {
                    snap()
                } else {
                    spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    )
                },
                label = "pinDotScale",
            )
            Box(Modifier.size(15.dp), contentAlignment = Alignment.Center) {
                // Hollow base dot — always present so the row never reflows.
                Box(Modifier.matchParentSize().clip(CircleShape).background(bt.border))
                // Gold fill overlay that pops in.
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            scaleX = fill
                            scaleY = fill
                            alpha = fill
                        }
                        .clip(CircleShape)
                        .background(filledColor),
                )
            }
        }
    }
}

/**
 * The numeric keypad (1–9, 0, backspace) with an optional [leadingSlot] in the
 * bottom-left corner — the lock screen fills it with a biometric button, the
 * setup screen leaves it empty. Keys are big borderless circles with a gold
 * ripple + the shared press-scale, so the pad matches the app's tactile feel.
 */
@Composable
fun PinKeypad(
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingSlot: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val rows = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                row.forEach { d -> DigitKey(digit = d, enabled = enabled, onClick = { onDigit(d) }) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            // Bottom-left: biometric (lock screen) or an empty spacer (setup).
            Box(Modifier.size(KEY_SIZE), contentAlignment = Alignment.Center) {
                leadingSlot?.invoke()
            }
            DigitKey(digit = 0, enabled = enabled, onClick = { onDigit(0) })
            KeyButton(enabled = enabled, onClick = onBackspace) {
                Icon(
                    Icons.AutoMirrored.Outlined.Backspace,
                    contentDescription = stringResource(R.string.bt_applock_backspace),
                    tint = BtTheme.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun DigitKey(digit: Int, enabled: Boolean, onClick: () -> Unit) {
    val bt = BtTheme.colors
    KeyButton(enabled = enabled, onClick = onClick) {
        Text(
            text = digit.toString(),
            color = if (enabled) bt.textPrimary else bt.textMuted,
            fontSize = 29.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun KeyButton(
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val bt = BtTheme.colors
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(KEY_SIZE)
            .clip(CircleShape)
            .btPressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true, color = bt.gold),
                enabled = enabled,
                onClick = {
                    // Light per-keypress tick so entry feels organic. KEYBOARD_TAP
                    // is NOT forced, so it honours the system haptic setting (silent
                    // when the user has disabled touch feedback). The stronger
                    // wrong-PIN LongPress buzz stays upstream in the lock screen.
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

// Comfortable one-handed thumb target (spec §5 ≥48dp; enlarged in the Step-17
// refinement for reachability).
private val KEY_SIZE = 78.dp
