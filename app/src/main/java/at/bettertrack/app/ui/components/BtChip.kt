package at.bettertrack.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme

/**
 * Pill chip (spec §3.5 — full-round shape is reserved for chips/badges/small
 * state buttons). Selection uses the amber-tinted surface + gold text.
 */
@Composable
fun BtChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val bt = BtTheme.colors
    // Selection reads as a clean translucent-gold highlight (consistent with
    // BtBadge's tint language) rather than a muddy filled surface — the deeper
    // amber-tinted fill stays reserved for large highlighted cards.
    val container = if (selected) bt.gold.copy(alpha = 0.14f) else bt.surface
    val content = if (selected) bt.goldEmphasis else bt.textSecondary
    val border = BorderStroke(1.dp, if (selected) bt.gold.copy(alpha = 0.45f) else bt.border)
    val label: @Composable () -> Unit = {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
    if (onClick != null) {
        val interaction = remember { MutableInteractionSource() }
        Surface(
            onClick = onClick,
            modifier = modifier.btPressScale(interaction, pressedScale = 0.94f),
            enabled = enabled,
            shape = BtShapes.pill,
            color = container,
            contentColor = content,
            border = border,
            interactionSource = interaction,
        ) { label() }
    } else {
        Surface(
            modifier = modifier,
            shape = BtShapes.pill,
            color = container,
            contentColor = content,
            border = border,
        ) { label() }
    }
}

/** Semantic tone of a [BtBadge]. */
enum class BtBadgeKind { Neutral, Gold, Gain, Loss }

/**
 * Small status badge (pill): pending-sync, gains/losses, counts, "default" tags…
 * Tinted translucent container + soft text, Tailwind-/10 style.
 */
@Composable
fun BtBadge(
    text: String,
    modifier: Modifier = Modifier,
    kind: BtBadgeKind = BtBadgeKind.Neutral,
) {
    val bt = BtTheme.colors
    val (container, content) = when (kind) {
        BtBadgeKind.Neutral -> bt.border to bt.textSecondary
        BtBadgeKind.Gold -> bt.gold.copy(alpha = 0.14f) to bt.goldEmphasis
        BtBadgeKind.Gain -> bt.gain.copy(alpha = 0.14f) to bt.gainSoft
        BtBadgeKind.Loss -> bt.loss.copy(alpha = 0.14f) to bt.lossSoft
    }
    Surface(
        modifier = modifier,
        shape = BtShapes.pill,
        color = container,
        contentColor = content,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
