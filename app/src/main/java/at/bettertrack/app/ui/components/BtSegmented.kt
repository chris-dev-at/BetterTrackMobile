package at.bettertrack.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme

/**
 * A compact segmented control — **one** control presenting a small closed set of
 * mutually exclusive options, as opposed to several independent chips.
 *
 * ## Why this exists as a component
 *
 * The app had four private, byte-identical re-implementations of this idea
 * (`SocialScreen`, `WorkboardScreen`, `NotificationsInboxScreen`,
 * `NotificationDeliverySection`) and, separately, a three-[BtChip] row acting as
 * the portfolio hero's display-mode picker. That last one is what prompted this:
 * loose chips are the wrong *vocabulary* for an exclusive choice. A chip says
 * "this filter is on", and several chips imply several independent switches — so
 * a row of three chips reads as three toggles that happen to be linked by
 * convention. It also collided with the six range chips directly below the same
 * chart, leaving one screen using identical pills to mean two different things.
 *
 * A segmented control says the opposite, structurally: one track, one highlight,
 * exactly one winner. The container does the work no individual chip can.
 *
 * ## The design
 *
 * A recessed track ([BtShapes.pill], `surfaceLow` + a hairline) holding inset
 * pills. The selected segment is filled with `goldWashStrong` and inked in
 * `goldInk`; the rest are transparent with `textMuted` labels. That is
 * deliberately the **same pair the bottom bar's selection indicator uses**, so
 * "the gold wash pill is the selected thing" means one thing everywhere in the
 * app.
 *
 * There is no border on the selected segment. A hairline inside a hairline reads
 * as two nested objects, and the wash already carries the state — this is also
 * why the track, not the segment, owns the edge.
 *
 * Segments size to their own content rather than sharing equal widths. Equal
 * widths are right for word labels of similar length; they are wrong for a set
 * like `€` / `%` / `€ / %`, where forcing the two one-character options to the
 * width of the third leaves two mostly-empty pills.
 *
 * @param options every choice, in display order. Small sets only — this is a
 *   control you take in at a glance, not a list.
 * @param selected the current winner. Exactly one, always: there is no unset
 *   state, by construction.
 * @param label the visible text for an option. Keep it short.
 * @param contentDescription optional per-option accessibility text, for when the
 *   label is a glyph (`€`) that does not read aloud usefully.
 */
@Composable
fun <T> BtSegmented(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: (@Composable (T) -> String)? = null,
) {
    val bt = BtTheme.colors
    Surface(
        modifier = modifier,
        shape = BtShapes.pill,
        color = bt.surfaceLow,
        border = BorderStroke(1.dp, bt.border),
    ) {
        Row(
            // 3dp of track showing around the inset pills. Less reads as a
            // rendering artifact; more and the track stops looking like a groove
            // and starts looking like a second card.
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                val interaction = remember(option) { MutableInteractionSource() }
                val cd = contentDescription?.invoke(option)
                Box(
                    modifier = Modifier
                        .clip(BtShapes.pill)
                        .background(if (isSelected) bt.goldWashStrong else Color.Transparent)
                        .clickable(
                            interactionSource = interaction,
                            indication = ripple(bounded = true, color = bt.gold),
                            onClick = { onSelect(option) },
                        )
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                        .then(if (cd != null) Modifier.semantics { this.contentDescription = cd } else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.labelMedium,
                        // The winner is heavier as well as tinted, so the state
                        // survives being looked at in a hurry — and so it is not
                        // carried by colour alone.
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSelected) bt.goldInk else bt.textMuted,
                    )
                }
            }
        }
    }
}
