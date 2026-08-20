package at.bettertrack.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme

/**
 * Primary action button: gold container, near-black content, 8dp corners, flat
 * (spec §3.3/§3.5 — gold is reserved for primary actions).
 *
 * R3 §4: this is also where the app's confirmation haptic lives. Gold is
 * reserved for primary actions by spec, so "is this a primary confirmation?" is
 * already answered by the fact that the caller reached for this component —
 * which makes one edit here the whole of "consistent light haptics on primary
 * confirmations", and makes it impossible for a new screen to ship a gold button
 * that feels different from every other gold button. [BtSecondaryButton] stays
 * silent on purpose; see [at.bettertrack.app.ui.components.BtHaptics].
 */
@Composable
fun BtPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val bt = BtTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberBtHaptics()
    Button(
        onClick = { haptics.confirm(); onClick() },
        modifier = modifier.btPressScale(interaction),
        enabled = enabled && !loading,
        shape = BtShapes.control,
        colors = ButtonDefaults.buttonColors(
            containerColor = bt.gold,
            contentColor = bt.onGold,
            disabledContainerColor = bt.border,
            disabledContentColor = bt.textMuted,
        ),
        elevation = null,
        interactionSource = interaction,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = bt.textMuted,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
}

/**
 * Secondary action button: outlined, white content on transparent, 8dp corners.
 */
@Composable
fun BtSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val bt = BtTheme.colors
    val interaction = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.btPressScale(interaction),
        enabled = enabled,
        shape = BtShapes.control,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = bt.textPrimary,
            disabledContentColor = bt.textMuted,
        ),
        border = BorderStroke(1.dp, if (enabled) bt.borderStrong else bt.border),
        interactionSource = interaction,
    ) {
        Text(text)
    }
}
