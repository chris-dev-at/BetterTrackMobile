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
    Button(
        onClick = onClick,
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
