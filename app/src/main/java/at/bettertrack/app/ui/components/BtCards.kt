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
 * Base BetterTrack card (spec §3.5): a raised surface at `BtShapes.card` radius
 * — flat, tone instead of elevation shadows. `selected` switches to the
 * amber-tinted highlighted-card surface (gold is the selection accent).
 *
 * ## The border, and why it left dark (§2 A3)
 *
 * This card drew a 1px `border` hairline in every mode until B2-B, and it was
 * the app's single most-repeated visual device — 88 sites, and the reason the
 * whole UI read as "old". The diagnosis is arithmetic, not taste: the old
 * `#262626` hairline on the old `#171717` card is a **1.28:1** luminance step,
 * which the eye resolves as a smudge rather than as a rule. The app was paying
 * for a border everywhere and getting a slight blur.
 *
 * With the five-step ramp the dark card is already ΔL\* 6.0 above the page, so
 * tone states the raise cleanly and the hairline is pure noise — exactly the
 * case `BtGroup` made in R2 and only half-finished. Light cannot lean on tone
 * (~5 L\* across the entire ramp), so it keeps the hairline. That is the one
 * app-wide rule, carried by one token:
 * [at.bettertrack.app.ui.theme.BtColors.groupBorder].
 *
 * **Selection is not covered by the rule.** A selected card keeps its
 * `goldSurfaceStrong` edge in BOTH modes, because that stroke is not separating
 * the card from the page — it is saying *this one is chosen*, which tone alone
 * has never said here and which must survive in dark.
 *
 * ## `quiet` — a card that stands down (owner, 2026-08-17)
 *
 * *"die holdings einfach weniger prominentere hintergrund farbe. **nicht gleich
 * die hintergrund farbe entfernen. sondern nur leichter machen.**"*
 *
 * That sentence is the whole specification, and the emphasis is his. A first
 * pass answered "make the holdings less important" by deleting the container
 * outright — transparent fill, no hairline, a plain list on the page — and he
 * rejected it before it shipped. A `quiet` card is therefore still a **card**:
 * it keeps its fill, its [BtColors.groupBorder] hairline, its shape, its inset,
 * its press feedback and its touch target. The only thing that changes is WHICH
 * fill, [BtColors.surfaceQuiet] instead of [BtColors.surface] — roughly half a
 * normal card's lift off the page in dark, and identical to a normal card in
 * light, where there is no tonal room to spend and the hairline was always the
 * separator.
 *
 * Rank is bought here and only here. Sizes are what the owner has already
 * corrected twice, so a quiet card is deliberately not a small card: a `quiet`
 * row and a normal row set the same words at the same size, and only the
 * surface under them ranks the two.
 *
 * `selected` still wins — a chosen card must look chosen even in a quiet list.
 */
@Composable
fun BtCard(
    modifier: Modifier = Modifier,
    shape: Shape = BtShapes.card,
    selected: Boolean = false,
    quiet: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val bt = BtTheme.colors
    val container = when {
        selected -> bt.goldSurface
        quiet -> bt.surfaceQuiet
        else -> bt.surface
    }
    // Note there is no `quiet` arm: a quiet card keeps the hairline. It is the
    // fill that ranks the card, and in light it is the ONLY separator there is.
    val border = BorderStroke(1.dp, if (selected) bt.goldSurfaceStrong else bt.groupBorder)
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
