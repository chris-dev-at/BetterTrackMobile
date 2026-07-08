package at.bettertrack.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.ui.theme.BtTheme

/**
 * The shared "state" scaffold behind every empty/error surface (spec §6.13): a
 * calm, centered column with the glyph carried in a soft circular badge, a clear
 * title, a short secondary message, and an optional next action. Wrapping the
 * icon in a 64dp surface badge (instead of a bare floating glyph) gives these
 * states intentional presence and is the template ALL downstream screens inherit.
 */
@Composable
private fun BtStateScaffold(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = BtTheme.colors.textSecondary,
    badgeColor: Color = BtTheme.colors.surface,
    badgeBorder: Color = BtTheme.colors.border,
    message: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val bt = BtTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(badgeColor, CircleShape)
                    .border(1.dp, badgeBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = bt.textPrimary,
            textAlign = TextAlign.Center,
        )
        if (message != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/**
 * Empty state (spec §6.13): helpful, centered — muted glyph badge, clear title,
 * short message, optional next action.
 */
@Composable
fun BtEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    message: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    BtStateScaffold(
        title = title,
        modifier = modifier,
        icon = icon,
        message = message,
        action = action,
    )
}

/**
 * Error state with retry (spec §6.13): human-readable, never a raw error string.
 * The badge picks up the red-tinted destructive surface so the state reads as an
 * error at a glance without shouting.
 */
@Composable
fun BtErrorState(
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.bt_error_generic_title),
    message: String? = stringResource(R.string.bt_error_generic_message),
    onRetry: (() -> Unit)? = null,
) {
    val bt = BtTheme.colors
    BtStateScaffold(
        title = title,
        modifier = modifier,
        icon = Icons.Outlined.ErrorOutline,
        iconTint = bt.loss,
        badgeColor = bt.lossSurface,
        badgeBorder = bt.loss.copy(alpha = 0.35f),
        message = message,
        action = onRetry?.let {
            {
                BtSecondaryButton(
                    text = stringResource(R.string.bt_action_retry),
                    onClick = it,
                )
            }
        },
    )
}
