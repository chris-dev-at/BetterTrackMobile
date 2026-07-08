package at.bettertrack.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme

/**
 * Base BetterTrack card (spec §3.5): dark surface with a 1px border and 6–8dp
 * radius — flat, borders instead of elevation shadows. `selected` switches to
 * the amber-tinted highlighted-card surface (gold is the selection accent).
 */
@Composable
fun BtCard(
    modifier: Modifier = Modifier,
    shape: Shape = BtShapes.card,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val bt = BtTheme.colors
    val container = if (selected) bt.goldSurface else bt.surface
    val border = BorderStroke(1.dp, if (selected) bt.goldSurfaceStrong else bt.border)
    if (onClick != null) {
        val interaction = remember { MutableInteractionSource() }
        Surface(
            onClick = onClick,
            modifier = modifier.btPressScale(interaction, pressedScale = 0.985f),
            shape = shape,
            color = container,
            contentColor = bt.textPrimary,
            border = border,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            interactionSource = interaction,
        ) { Column(content = content) }
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = container,
            contentColor = bt.textPrimary,
            border = border,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
        ) { Column(content = content) }
    }
}

/**
 * Stat card: small muted label over a big bold (tabular-digit) value, with an
 * optional delta line (e.g. gain/loss since a range).
 */
@Composable
fun StatCard(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    deltaContent: (@Composable () -> Unit)? = null,
    valueContent: @Composable () -> Unit,
) {
    val bt = BtTheme.colors
    BtCard(modifier = modifier, selected = selected) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            valueContent()
            if (deltaContent != null) {
                Spacer(Modifier.height(2.dp))
                deltaContent()
            }
        }
    }
}

/**
 * List card: a bordered row card with optional leading/trailing slots — the
 * base of holdings rows, watchlist rows, movement rows etc.
 */
@Composable
fun ListCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val bt = BtTheme.colors
    BtCard(modifier = modifier.fillMaxWidth(), selected = selected, onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = bt.textPrimary,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textSecondary,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
        }
    }
}
