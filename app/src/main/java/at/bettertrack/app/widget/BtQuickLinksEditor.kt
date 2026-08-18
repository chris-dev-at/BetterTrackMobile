package at.bettertrack.app.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.bettertrack.app.R
import at.bettertrack.app.data.db.CashSourceEntity
import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.ui.components.BtPickerRow
import at.bettertrack.app.ui.theme.BtTheme

/**
 * The Quick Links tile editor, shared verbatim by the widget's config Activity
 * and the in-app builder.
 *
 * ## Why one composable instead of two screens
 *
 * The owner asked for the action set to be editable in BOTH places. Two
 * implementations of an ordered-list editor would drift the first time one
 * gained a capacity rule the other did not, and the capacity rule is exactly
 * the thing that stops a user building a grid the widget cannot draw. So the
 * editor is written once and hosted twice.
 *
 * ## What it shows
 *
 * A live PREVIEW of the grid as configured (the real glyphs, the real
 * monograms, the real order), then the chosen tiles with reorder/remove
 * controls, then the rest of the catalog to add from. Reordering is arrows
 * rather than drag: this is hosted inside two different scrolling parents, and
 * a drag gesture that fights the scroll is worse than two obvious buttons.
 */
@Composable
fun BtQuickLinksEditor(
    config: BtQuickLinksConfig,
    portfolios: List<PortfolioEntity>,
    sources: List<CashSourceEntity>,
    onChange: (BtQuickLinksConfig) -> Unit,
) {
    val bt = BtTheme.colors
    val actions = config.actions
    // Which chosen tile currently has its target picker open. One at a time:
    // the whole editor lives in someone else's scroll, and several open lists
    // would push the catalog off the bottom of it.
    var aiming by remember { mutableStateOf<Int?>(null) }

    fun update(next: List<BtQuickLinkAction>) = onChange(config.copy(actions = next))

    BtQuickLinksPreview(config)
    Spacer(Modifier.height(14.dp))

    Text(
        text = stringResource(R.string.bt_ql_config_chosen, actions.size, BT_QUICK_LINKS_MAX),
        style = MaterialTheme.typography.bodySmall,
        color = bt.textSecondary,
    )
    Spacer(Modifier.height(4.dp))

    actions.forEachIndexed { index, action ->
        val targetable = action.link.targeting != BtQuickLinkTargeting.NONE
        EditorRow(
            title = btQuickLinkTitle(action),
            // The tile's aim, stated on the row. Without it three Cash tiles
            // are three identical rows and the editor cannot be used at all.
            subtitle = when {
                !targetable -> null
                else -> btQuickLinkTargetLabel(action)
                    ?: stringResource(R.string.bt_ql_config_target_any)
            },
            leading = { QuickLinkGlyph(action, size = 26.dp) },
            onClick = if (targetable) ({ aiming = if (aiming == index) null else index }) else null,
            trailing = {
                // Up / down / remove. The ends are disabled rather than hidden
                // so the row's controls do not reflow as the list is reordered.
                ArrowButton(
                    icon = Icons.Outlined.KeyboardArrowUp,
                    description = stringResource(R.string.bt_ql_config_move_up),
                    enabled = index > 0,
                ) {
                    aiming = null
                    update(actions.toMutableList().apply { add(index - 1, removeAt(index)) })
                }
                ArrowButton(
                    icon = Icons.Outlined.KeyboardArrowDown,
                    description = stringResource(R.string.bt_ql_config_move_down),
                    enabled = index < actions.lastIndex,
                ) {
                    aiming = null
                    update(actions.toMutableList().apply { add(index + 1, removeAt(index)) })
                }
                ArrowButton(
                    icon = Icons.Outlined.Close,
                    description = stringResource(R.string.bt_ql_config_remove),
                    // The last tile cannot be removed: an empty grid is not a
                    // configuration a user should be able to save.
                    enabled = actions.size > 1,
                ) {
                    aiming = null
                    update(actions.filterIndexed { i, _ -> i != index })
                }
            },
        )
        if (targetable && aiming == index) {
            TargetPicker(
                current = action,
                choices = btQuickLinkTargetChoices(action.link, portfolios, sources),
                onPick = { picked ->
                    aiming = null
                    update(actions.toMutableList().apply { set(index, picked) })
                },
            )
        }
    }

    Spacer(Modifier.height(10.dp))
    Text(
        text = stringResource(R.string.bt_ql_config_add),
        style = MaterialTheme.typography.bodySmall,
        color = bt.textSecondary,
    )
    Spacer(Modifier.height(4.dp))

    val full = actions.size >= BT_QUICK_LINKS_MAX
    // Every destination the user has not already placed WITH THAT AIM. A
    // targetable entry contributes one candidate per target, which is what lets
    // the owner's three Cash tiles exist at all — the old rule was one tile per
    // catalog entry, so a second Cash tile could not be added no matter what it
    // pointed at.
    val candidates = BtQuickLink.entries.flatMap { link ->
        when (link.targeting) {
            BtQuickLinkTargeting.NONE -> listOf(BtQuickLinkAction(link))
            else -> btQuickLinkTargetChoices(link, portfolios, sources)
        }
    }.filterNot { candidate -> actions.any { btQuickLinkSameTarget(it, candidate) } }

    if (candidates.isEmpty()) {
        Text(
            text = stringResource(R.string.bt_ql_config_all_added),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
        )
    } else {
        candidates.forEach { candidate ->
            EditorRow(
                title = btQuickLinkTitle(candidate),
                // Qualified, not bare: several wallets share the name "Main".
                subtitle = btQuickLinkTargetLabel(candidate),
                dimmed = full,
                leading = { QuickLinkGlyph(candidate, size = 26.dp) },
                onClick = if (full) null else ({ update(actions + candidate) }),
                trailing = {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = null,
                        tint = if (full) bt.textMuted else bt.goldInk,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
        if (full) {
            Text(
                text = stringResource(R.string.bt_ql_config_full, BT_QUICK_LINKS_MAX),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
        }
    }
}

/**
 * The inline "where to?" list for one tile.
 *
 * Inline rather than a bottom sheet, even though the house pops user-facing
 * choices from the bottom: this editor is hosted inside a widget CONFIG
 * Activity as well as the in-app builder, and the config Activity is a bare
 * `ComponentActivity` with no sheet host. One editor, two hosts (see this
 * file's header) is worth more than one consistent gesture here, and the list
 * is short enough to sit under its row without burying the rest.
 */
@Composable
private fun TargetPicker(
    current: BtQuickLinkAction,
    choices: List<BtQuickLinkAction>,
    onPick: (BtQuickLinkAction) -> Unit,
) {
    if (choices.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 36.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        choices.forEach { choice ->
            BtPickerRow(
                label = choice.targetName ?: stringResource(R.string.bt_ql_config_target_any),
                supporting = choice.targetSupporting(),
                selected = btQuickLinkSameTarget(current, choice),
                onClick = { onPick(choice) },
            )
        }
    }
}

/**
 * The second line of a target row: for a wallet, the portfolio that owns it.
 * Two portfolios routinely both have a source called "Bank", and without the
 * owner named the two rows are indistinguishable.
 */
private fun BtQuickLinkAction.targetSupporting(): String? =
    portfolioName.takeIf {
        link.targeting == BtQuickLinkTargeting.CASH_SOURCE && sourceName.isNotBlank() && it.isNotBlank()
    }

/**
 * The live grid preview: the tiles as configured, at the 4-across geometry of
 * the largest rendition, so the user sees the real order and the real
 * monograms before anything is pinned.
 */
@Composable
fun BtQuickLinksPreview(config: BtQuickLinksConfig) {
    val bt = BtTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bt.surface, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        config.actions.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { action ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .background(bt.surfaceLow, RoundedCornerShape(11.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            QuickLinkGlyph(action, size = 20.dp)
                        }
                        if (config.captions) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = btQuickLinkCaption(action, stringResource(action.link.label)),
                                style = MaterialTheme.typography.labelSmall,
                                color = bt.textMuted,
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.width(46.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One tile's mark: the catalog pictogram, or a portfolio's monogram. */
@Composable
private fun QuickLinkGlyph(action: BtQuickLinkAction, size: androidx.compose.ui.unit.Dp) {
    val bt = BtTheme.colors
    val title = btQuickLinkDescription(action, stringResource(action.link.label))
    val monogram = btQuickLinkTileMonogram(action)
    if (monogram != null) {
        Box(
            modifier = Modifier.size(size).semantics { contentDescription = title },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = monogram,
                color = bt.goldInk,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.72f).sp,
                maxLines = 1,
            )
        }
    } else {
        Icon(
            painter = painterResource(action.link.icon),
            contentDescription = title,
            tint = bt.goldInk,
            modifier = Modifier.size(size),
        )
    }
}

/**
 * A tile's user-facing name — the DESTINATION only.
 *
 * The target used to be glued on here ("Depot · Langfristig"). It is now the
 * row's second line and the widget caption's own concern, because with every
 * entry targetable a single glued string would be the only thing distinguishing
 * eight rows and would truncate before it got to the part that differs.
 */
@Composable
private fun btQuickLinkTitle(action: BtQuickLinkAction): String =
    stringResource(action.link.label)

@Composable
private fun EditorRow(
    title: String,
    leading: @Composable () -> Unit,
    trailing: @Composable RowScopeMarker.() -> Unit,
    subtitle: String? = null,
    dimmed: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(10.dp))
        // Destination over target, stacked: the pair answers "what does this
        // tile do, and where does it go" in one glance, which is the whole
        // point of per-tile targets.
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (dimmed) bt.textMuted else bt.textPrimary,
                maxLines = 1,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                    maxLines = 1,
                )
            }
        }
        RowScopeMarker.trailing()
    }
}

/**
 * A marker receiver so [EditorRow]'s trailing slot reads as a row of controls
 * without leaking Compose's own `RowScope` (which would let a caller call
 * `weight` on a control and collapse the title).
 */
object RowScopeMarker

@Composable
private fun RowScopeMarker.ArrowButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bt = BtTheme.colors
    Box(
        modifier = Modifier
            .size(34.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) bt.textSecondary else bt.border,
            modifier = Modifier.size(18.dp),
        )
    }
}
