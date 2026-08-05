package at.bettertrack.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme

/**
 * The R2 grouping primitives — mandate §4: *"tonal elevation instead of divider
 * lines … more whitespace, fewer boxes-in-boxes"*.
 *
 * ## The problem these solve
 *
 * Before R2 the app had exactly one container idea — [BtCard], a bordered
 * surface — and used it for everything. On a list that reads fine; on Settings
 * it produced ten identically-bordered boxes stacked one per row, so the
 * *border* became the dominant visual rhythm and nothing said which rows belong
 * together. Adding dividers would have been the same mistake with thinner lines.
 *
 * ## The two tiers
 *
 * R2 splits containment in two, and the split is what carries meaning:
 *
 *  - **A row is a [BtCard]** — bordered, `BtShapes.card` (12dp). Used when rows
 *    are *peers competing for a tap*: holdings, watchlist entries, alerts.
 *  - **A group is a [BtGroup]** — border-LESS, `BtShapes.group` (16dp), one
 *    tonal step up from the page. Used when rows are *parts of one subject*:
 *    a settings section, the Needs-you block. The tonal step does the
 *    separating, so there is no border and no divider to draw at all.
 *
 * A group therefore contains [BtGroupRow]s, which have no chrome of their own —
 * they are shaped by the group around them. That is the "fewer boxes-in-boxes"
 * line made structural rather than a thing each screen has to remember.
 */
@Composable
fun BtGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = BtShapes.group,
        color = BtTheme.colors.surface,
        contentColor = BtTheme.colors.textPrimary,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) { Column(content = content) }
}

/**
 * A row inside a [BtGroup]: no background, no border, no divider — the group
 * owns all of that.
 *
 * @param icon the leading glyph. Muted by default: on a settings list the icons
 *   are a scanning aid, not content, and eleven gold glyphs in a column would
 *   spend the app's only accent on navigation furniture.
 * @param trailing replaces the default chevron. A chevron is drawn only when the
 *   row navigates AND nothing else claims the end of the row, so a toggle row
 *   never shows a chevron that lies about where the tap goes.
 */
@Composable
fun BtGroupRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: androidx.compose.ui.graphics.Color? = null,
    titleColor: androidx.compose.ui.graphics.Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val bt = BtTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val clickable = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interaction,
            indication = ripple(),
            onClick = onClick,
        )
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clickable)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint ?: bt.textSecondary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = titleColor ?: bt.textPrimary,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            trailing != null -> {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
            onClick != null -> {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = bt.textMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * The one section header for the whole app.
 *
 * Three private copies of this existed before R2 (Settings' `SectionLabel`,
 * People's `SectionHeader`, and the same idea inline on other screens), each
 * with its own casing and spacing, which is precisely how a screen ends up
 * looking hand-assembled. Uppercased `labelMedium` in muted grey stays — it is
 * the quietest thing that still reads as structure, and quiet is the point: a
 * header that competes with its own content inverts the hierarchy R2 is for.
 */
@Composable
fun BtSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val bt = BtTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = bt.textMuted,
        )
        if (count != null && count > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = bt.textMuted,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

/**
 * The **Needs you** block — the §3 "actionable first" lead, shared by Workbench
 * and People so the two screens make the same promise in the same shape.
 *
 * ## Why it is gold, and why that is not a contradiction
 *
 * Gold is the app's only accent and the brief says keep it sparse. This block is
 * where that budget is *meant* to be spent: it is the one thing on the screen
 * that is genuinely waiting on the user, it renders at zero height the rest of
 * the time, and a screen shows at most one. So the accent is rare by
 * construction — not rationed by discipline. The wash is 7% gold over the group
 * surface (a tint, not a fill) with the title in gold; the rows underneath stay
 * neutral so the user reads *what* needs them, not a wall of amber.
 *
 * ## Self-hiding is the caller's job, deliberately
 *
 * This composable draws whatever it is given. Callers guard it with the same
 * emptiness check they use to build the rows, because "is there anything to do"
 * is a question about *their* data — a component that quietly renders nothing
 * would hide a wiring bug instead of surfacing it.
 */
@Composable
fun BtNeedsYouGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val bt = BtTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = BtShapes.group,
        color = bt.gold.copy(alpha = 0.07f),
        contentColor = bt.textPrimary,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = bt.goldEmphasis,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
            )
            content()
        }
    }
}
