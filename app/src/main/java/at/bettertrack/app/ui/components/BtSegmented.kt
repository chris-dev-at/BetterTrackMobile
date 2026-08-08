package at.bettertrack.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
 * ## Sizing
 *
 * Segments size to their own content by default. That is right for word labels
 * of similar length, and it was right for the chart picker's old `€` / `%` /
 * `€ / %` set, where forcing the two one-character options to the width of the
 * third left two mostly-empty pills.
 *
 * Once every label is about **one glyph wide** the argument inverts: three pills
 * of 7dp, 10dp and 17dp content read as a ragged row rather than one control, and
 * the tap targets differ by more than half. [minSegmentWidth] is the opt-in for
 * that case — a floor, never a cap, so a label that outgrows it still gets its
 * space instead of being clipped. Pass it scaled by `fontScale` if the labels are
 * text, so the row stays equal at every system font size.
 *
 * Heights are always shared: the row measures to its tallest segment
 * ([IntrinsicSize.Min]) and every segment fills it, so a [labelContent] that
 * draws a mark of its own height can never leave one pill taller than its
 * neighbours.
 *
 * [equalWidths] is the third sizing policy, for a control that spans a width it
 * was GIVEN rather than one it asks for — the chart range row under a chart,
 * which is a scale for the canvas above it and reads right only when it is as
 * wide as the thing it scales. Every segment then takes an equal share of the
 * row instead of its own content width, which is the only policy that cannot
 * overflow: the share is arithmetic on the available width, so six or eight
 * windows fit any screen at any font scale by construction. Because the width
 * arrives from outside, the side padding drops to [SEGMENT_PADDING_H_EQUAL] —
 * with a 14dp inset a 52dp share would leave a three-letter label 24dp and wrap
 * it. Callers are expected to have checked the share is generous enough for the
 * longest label first; see `BtRangeSegmented`, which does exactly that.
 *
 * @param options every choice, in display order. Small sets only — this is a
 *   control you take in at a glance, not a list.
 * @param selected the current winner. Exactly one, always: there is no unset
 *   state, by construction.
 * @param label the visible text for an option. Keep it short. Required rather
 *   than defaulted so it stays ahead of `modifier` in the signature (lint's
 *   `ModifierParameter`, and the convention it enforces: `modifier` is the first
 *   optional parameter). Pass `null` when the segments carry [labelContent]
 *   marks instead of words — exactly one of the two draws.
 * @param contentDescription optional per-option accessibility text, for when the
 *   label is a glyph (`€`) that does not read aloud usefully.
 * @param labelContent draws an option's label instead of [label], for a segment
 *   whose label is a composed MARK rather than a word — the chart picker's
 *   combined `€%`. The segment still owns the ink and the weight: the slot runs
 *   under the resolved [LocalContentColor] and text style, so a plain `Text()`
 *   inside it inherits selected/unselected state without restating it.
 * @param minSegmentWidth a floor on every segment's width, for glyph labels whose
 *   natural widths differ. Unspecified (default) means each segment keeps its own.
 * @param equalWidths divide the row's width equally between the segments instead
 *   of letting each size to its content. Only meaningful when the control is given
 *   a width (`fillMaxWidth`), and mutually exclusive with [minSegmentWidth] —
 *   an equal share is already a shared width.
 */
@Composable
fun <T> BtSegmented(
    options: List<T>,
    selected: T,
    label: (@Composable (T) -> String)?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: (@Composable (T) -> String)? = null,
    labelContent: (@Composable (T) -> Unit)? = null,
    minSegmentWidth: Dp = Dp.Unspecified,
    equalWidths: Boolean = false,
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
            //
            // `IntrinsicSize.Min` + `fillMaxHeight` below is the same pairing the
            // overview's quick-stat chips use: it makes the row as tall as its
            // tallest segment and every segment that tall, so the pills are one
            // object rather than several.
            modifier = Modifier.padding(SEGMENTED_TRACK_INSET).height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(SEGMENTED_SEGMENT_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                val interaction = remember(option) { MutableInteractionSource() }
                val cd = contentDescription?.invoke(option)
                val ink = if (isSelected) bt.goldInk else bt.textMuted
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        // One of the two width policies, never both: an equal
                        // share IS a shared width, so a floor on top of it could
                        // only push the row past the width it was handed.
                        .then(
                            if (equalWidths) Modifier.weight(1f) else Modifier.widthIn(min = minSegmentWidth),
                        )
                        .clip(BtShapes.pill)
                        .background(if (isSelected) bt.goldWashStrong else Color.Transparent)
                        .clickable(
                            interactionSource = interaction,
                            indication = ripple(bounded = true, color = bt.gold),
                            onClick = { onSelect(option) },
                        )
                        .padding(
                            horizontal = if (equalWidths) SEGMENT_PADDING_H_EQUAL else SEGMENT_PADDING_H,
                            vertical = SEGMENT_PADDING_V,
                        )
                        .then(if (cd != null) Modifier.semantics { this.contentDescription = cd } else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    val style = MaterialTheme.typography.labelMedium.copy(
                        // The winner is heavier as well as tinted, so the state
                        // survives being looked at in a hurry — and so it is not
                        // carried by colour alone.
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = ink,
                    )
                    if (labelContent != null) {
                        // The slot inherits the resolved state instead of being
                        // handed it: a `Text()` inside picks the ink up from
                        // `LocalContentColor` and the size/weight from the
                        // ambient style, so a custom mark cannot drift out of
                        // sync with the selection it is drawn inside.
                        CompositionLocalProvider(LocalContentColor provides ink) {
                            ProvideTextStyle(style) { labelContent(option) }
                        }
                    } else if (label != null) {
                        Text(text = label(option), style = style)
                    }
                }
            }
        }
    }
}

/**
 * The track showing around the inset pills, per side.
 *
 * Named rather than inlined because it is no longer only a paint decision: the
 * equal-width policy has to subtract it from the width it was given before it can
 * divide, so the number is now part of an arithmetic that is unit-tested
 * (`equalSegmentShareDp`). A literal here and a different literal in that
 * arithmetic is exactly the drift the constant prevents.
 */
internal val SEGMENTED_TRACK_INSET = 3.dp

/** The gap between two segments — same reason as [SEGMENTED_TRACK_INSET]. */
internal val SEGMENTED_SEGMENT_GAP = 2.dp

/**
 * A segment's side padding when it sizes to its own content.
 *
 * This is what makes a one-word segment a comfortable target rather than a box
 * drawn tight around a label.
 */
private val SEGMENT_PADDING_H = 14.dp

/**
 * A segment's side padding under `equalWidths`.
 *
 * Deliberately small: the width already came from the row's division, so this
 * padding is not producing the target — it is only a floor on how close a long
 * label may come to the pill's edge before the caller's fit check should have
 * sent the control down its scrolling path instead.
 */
private val SEGMENT_PADDING_H_EQUAL = 4.dp

/** Shared by both policies — the vertical rhythm never depends on the width. */
private val SEGMENT_PADDING_V = 7.dp
