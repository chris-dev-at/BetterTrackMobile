package at.bettertrack.app.ui.notifications

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.notifications.ChannelAvailability
import at.bettertrack.app.data.notifications.NotifCatalog
import at.bettertrack.app.data.notifications.NotifChannel
import at.bettertrack.app.data.notifications.NotifMatrix
import at.bettertrack.app.data.notifications.TriState
import at.bettertrack.app.data.notifications.TypePrefs
import at.bettertrack.app.data.notifications.categoryEnabled
import at.bettertrack.app.data.notifications.isMuted
import at.bettertrack.app.data.notifications.mirrorTriState
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtFormError
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtGroupRow
import at.bettertrack.app.ui.components.BtPickerSheet
import at.bettertrack.app.ui.components.BtSectionHeader
import at.bettertrack.app.ui.theme.BtTheme

/**
 * The routing matrix — the platform's 26 notification types × 6 delivery channels,
 * shaped for a phone.
 *
 * ## Why this is not a 26 × 6 grid
 *
 * Because a 156-cell table does not fit a phone, and the previous attempt to make
 * one fit is what got the whole surface handed to the browser. The information is
 * identical; the shape is three levels instead of two:
 *
 *  - **category** (8, collapsible, with the web's master toggle),
 *  - **type** (19 rendered rows — 26 wire types with the eight `mirror.*` ones
 *    collapsed into a single group row, exactly as the web collapses them),
 *  - **channel** (6, in a bottom sheet opened from the type row).
 *
 * Nothing is dropped on the way down. Every cell the web can set is settable here,
 * every locked cell is locked here for the same reason, the category master writes
 * the same body, and the mirrorchain row carries the same tri-state.
 *
 * The per-type sheet is also what makes the **per-type mute** a first-class control
 * again rather than "turn six switches off one at a time". It is not an app-local
 * flag this time: the platform contract defines a muted type as all-channels-false,
 * so muting here is muting on the web too.
 *
 * ## What the account mute does to it
 *
 * The web dims the routing grid to 0.6 and disables it while `settings.muted`
 * (`gridDisabled = busy || settings.muted`), and leaves the timing folds alone. The
 * app now does exactly that. It could not before — with the grid gone there was
 * nothing left for a mute to grey, so the treatment had been moved onto quiet
 * hours as the nearest thing. That workaround is retired along with its cause.
 */
@Composable
fun NotificationRoutingSection(
    matrix: NotifMatrix,
    serverTypes: List<String>,
    availability: ChannelAvailability,
    enabled: Boolean,
    failure: BtMessage?,
    onCell: (type: String, channel: NotifChannel, on: Boolean) -> Unit,
    onMirrorChannel: (channel: NotifChannel, on: Boolean) -> Unit,
    onCategory: (category: NotifCatalog.Category, on: Boolean) -> Unit,
    onMute: (type: String, muted: Boolean) -> Unit,
) {
    val bt = BtTheme.colors
    // Echo-verbatim, one level up: with no types from the server there is no grid
    // to draw and nothing honest to say about one. The screen's own error slot
    // covers why, so a heading over emptiness would only add noise.
    if (serverTypes.isEmpty()) return

    val visible = availability.visible
    // Which type's sheet is open. `null` = none. A wire key rather than an index,
    // so a refresh that reorders or drops a type closes the sheet instead of
    // silently retargeting it at a different setting.
    var openType by rememberSaveable { mutableStateOf<String?>(null) }
    var mirrorOpen by rememberSaveable { mutableStateOf(false) }

    BtSectionHeader(stringResource(R.string.bt_notif_routing_section))
    Text(
        stringResource(R.string.bt_notif_routing_hint),
        style = MaterialTheme.typography.bodySmall,
        color = bt.textMuted,
    )
    failure?.let { BtFormError(it, modifier = Modifier.padding(horizontal = 4.dp)) }

    Column(
        modifier = Modifier.alpha(if (enabled) 1f else MUTED_GRID_ALPHA),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (category in NotifCatalog.categories) {
            val types = category.types.filter { it in serverTypes }
            // A category the server sent nothing for is not drawn. That is not the
            // same as "empty" — it means this deployment does not have the feature,
            // and an empty accordion promising Budgets on a build without budgets
            // is worse than no row.
            if (types.isEmpty()) continue
            CategoryBlock(
                category = category,
                types = types,
                matrix = matrix,
                serverTypes = serverTypes,
                visible = visible,
                enabled = enabled,
                onCategory = onCategory,
                onOpenType = { openType = it },
                onOpenMirror = { mirrorOpen = true },
            )
        }
    }

    openType?.let { type ->
        TypeSheet(
            type = type,
            prefs = matrix.prefs(type),
            visible = visible,
            onDismiss = { openType = null },
            onCell = onCell,
            onMute = onMute,
        )
    }
    if (mirrorOpen) {
        MirrorSheet(
            matrix = matrix,
            serverTypes = serverTypes,
            visible = visible,
            onDismiss = { mirrorOpen = false },
            onMirrorChannel = onMirrorChannel,
        )
    }
}

/** The web's dimmed-grid opacity under an account-wide mute. */
private const val MUTED_GRID_ALPHA = 0.6f

@Composable
private fun CategoryBlock(
    category: NotifCatalog.Category,
    types: List<String>,
    matrix: NotifMatrix,
    serverTypes: List<String>,
    visible: List<NotifChannel>,
    enabled: Boolean,
    onCategory: (NotifCatalog.Category, Boolean) -> Unit,
    onOpenType: (String) -> Unit,
    onOpenMirror: () -> Unit,
) {
    val bt = BtTheme.colors
    // Collapsed by default. Eight expanded accordions is a very long screen for a
    // set of switches most people touch once; the header carries a count so the
    // state is legible without opening anything.
    var expanded by rememberSaveable(category.key) { mutableStateOf(false) }
    val label = stringResource(notifCategoryLabelRes(category.key))
    val isMirror = category.key == NotifCatalog.CAT_MIRRORCHAIN

    val on = categoryEnabled(category, matrix.rows, visible, serverTypes)
    // Resolved out here: `Modifier.semantics` runs outside composition and cannot
    // call `stringResource` itself.
    val toggleCd = stringResource(R.string.bt_notif_cat_toggle_cd, label)
    val expandCd = stringResource(
        if (expanded) R.string.bt_notif_collapse_cd else R.string.bt_notif_expand_cd,
        label,
    )
    // The count is over RENDERED rows, so the mirrorchain block reads "1 of 1"
    // rather than claiming eight rows the user cannot see individually.
    val rendered = if (isMirror) 1 else types.size
    val activeRows = if (isMirror) {
        if (visible.any { mirrorTriState(matrix.rows, it, serverTypes) != TriState.Off }) 1 else 0
    } else {
        types.count { !matrix.prefs(it).isMuted() }
    }

    BtGroup {
        BtGroupRow(
            title = label,
            subtitle = stringResource(R.string.bt_notif_cat_summary, activeRows, rendered),
            onClick = if (enabled) ({ expanded = !expanded }) else null,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = on,
                        onCheckedChange = if (enabled) ({ next -> onCategory(category, next) }) else null,
                        enabled = enabled,
                        colors = btSwitchColors(),
                        modifier = Modifier.semantics { contentDescription = toggleCd },
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = expandCd,
                        tint = bt.textMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        )
        AnimatedVisibility(visible = expanded) {
            Column {
                if (isMirror) {
                    MirrorGroupRow(
                        matrix = matrix,
                        serverTypes = serverTypes,
                        visible = visible,
                        enabled = enabled,
                        onOpen = onOpenMirror,
                    )
                } else {
                    types.forEach { type ->
                        TypeRow(
                            type = type,
                            prefs = matrix.prefs(type),
                            visible = visible,
                            enabled = enabled,
                            onOpen = { onOpenType(type) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeRow(
    type: String,
    prefs: TypePrefs,
    visible: List<NotifChannel>,
    enabled: Boolean,
    onOpen: () -> Unit,
) {
    val bt = BtTheme.colors
    BtGroupRow(
        title = notifTypeLabel(type),
        subtitle = channelSummary(type, prefs, visible),
        onClick = if (enabled) onOpen else null,
        trailing = {
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = bt.textMuted,
                modifier = Modifier.size(20.dp),
            )
        },
    )
}

@Composable
private fun MirrorGroupRow(
    matrix: NotifMatrix,
    serverTypes: List<String>,
    visible: List<NotifChannel>,
    enabled: Boolean,
    onOpen: () -> Unit,
) {
    val bt = BtTheme.colors
    val states = visible.associateWith { mirrorTriState(matrix.rows, it, serverTypes) }
    val summary = when {
        states.values.all { it == TriState.Off } -> stringResource(R.string.bt_notif_row_muted)
        states.values.any { it == TriState.Mixed } -> stringResource(R.string.bt_notif_row_mixed)
        // `map` first, then a plain join: `joinToString` is not inline, so a
        // `stringResource` inside its transform is not a legal composable call.
        else -> visible.filter { states[it] == TriState.On }
            .map { stringResource(notifChannelLabelRes(it)) }
            .joinToString(SUMMARY_SEPARATOR)
    }
    BtGroupRow(
        title = stringResource(R.string.bt_notif_mirror_group),
        subtitle = summary,
        onClick = if (enabled) onOpen else null,
        trailing = {
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = bt.textMuted,
                modifier = Modifier.size(20.dp),
            )
        },
    )
}

/**
 * The row's second line: the channels this type actually reaches.
 *
 * Locked cells are read through [NotifCatalog.lockedCellChecked] rather than
 * through the stored value, so the summary agrees with what the sheet will show —
 * a row saying "Email" that opens onto an unchecked email switch is the kind of
 * inconsistency that makes people stop trusting a settings screen.
 */
@Composable
private fun channelSummary(type: String, prefs: TypePrefs, visible: List<NotifChannel>): String {
    val on = visible.filter { channel ->
        if (NotifCatalog.cellLocked(type, channel)) {
            NotifCatalog.lockedCellChecked(channel)
        } else {
            prefs.get(channel)
        }
    }
    return if (on.isEmpty()) {
        stringResource(R.string.bt_notif_row_muted)
    } else {
        on.map { stringResource(notifChannelLabelRes(it)) }.joinToString(SUMMARY_SEPARATOR)
    }
}

private const val SUMMARY_SEPARATOR = " · "

/**
 * One type, all its channels, and its mute — the sheet the type row opens.
 *
 * A bottom sheet rather than a sub-screen because that is the app's idiom for
 * anything the user pops open and dismisses, and because it keeps the category
 * list underneath as context. Everything about one notification type is in one
 * place, which is the trade the phone-shaped layout buys with its third level.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeSheet(
    type: String,
    prefs: TypePrefs,
    visible: List<NotifChannel>,
    onDismiss: () -> Unit,
    onCell: (String, NotifChannel, Boolean) -> Unit,
    onMute: (String, Boolean) -> Unit,
) {
    val bt = BtTheme.colors
    val label = notifTypeLabel(type)
    val category = NotifCatalog.categoryOf(type)
    // account.invite is locked on every channel, so it has no mute either: there is
    // nothing to mute and a switch that cannot move is worse than no switch.
    val fullyLocked = type == NotifCatalog.ACCOUNT_INVITE

    BtPickerSheet(
        title = label,
        subtitle = category?.let { stringResource(notifCategoryLabelRes(it.key)) },
        onDismiss = onDismiss,
    ) {
        notifLockedNoteRes(type)?.let { note ->
            Text(
                stringResource(note),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }

        if (visible.isEmpty()) {
            Text(
                stringResource(R.string.bt_notif_no_channels),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            return@BtPickerSheet
        }

        if (!fullyLocked) {
            val muted = prefs.isMuted()
            BtGroup {
                BtGroupRow(
                    title = stringResource(R.string.bt_notif_mute_type),
                    subtitle = stringResource(R.string.bt_notif_mute_type_sub),
                    onClick = { onMute(type, !muted) },
                    trailing = {
                        Switch(
                            checked = muted,
                            onCheckedChange = { onMute(type, it) },
                            colors = btSwitchColors(),
                        )
                    },
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        BtSectionHeader(stringResource(R.string.bt_notif_type_channels))
        BtGroup {
            visible.forEach { channel ->
                val locked = NotifCatalog.cellLocked(type, channel)
                val checked = if (locked) NotifCatalog.lockedCellChecked(channel) else prefs.get(channel)
                val channelLabel = stringResource(notifChannelLabelRes(channel))
                val cellCd = stringResource(R.string.bt_notif_cell_cd, label, channelLabel)
                BtGroupRow(
                    title = channelLabel,
                    onClick = if (locked) null else ({ onCell(type, channel, !checked) }),
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (locked) {
                                BtBadge(
                                    text = stringResource(R.string.bt_notif_locked_badge),
                                    kind = BtBadgeKind.Neutral,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Switch(
                                checked = checked,
                                onCheckedChange = if (locked) null else ({ onCell(type, channel, it) }),
                                enabled = !locked,
                                colors = btSwitchColors(),
                                modifier = Modifier.semantics { contentDescription = cellCd },
                            )
                        }
                    },
                )
            }
        }

        if (!fullyLocked && prefs.isMuted()) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.bt_notif_all_off),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
        }
    }
}

/**
 * The collapsed mirrorchain row's sheet: one tri-state switch per channel,
 * governing all eight `mirror.*` types at once.
 *
 * Compose's `Switch` has no indeterminate position, so a MIXED channel renders as
 * an off switch with a "Mixed" badge beside it rather than as a lie in either
 * direction. Tapping it turns every member on — which is what the web's plain
 * boolean does from an indeterminate state, and the only resolution that makes the
 * next position predictable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MirrorSheet(
    matrix: NotifMatrix,
    serverTypes: List<String>,
    visible: List<NotifChannel>,
    onDismiss: () -> Unit,
    onMirrorChannel: (NotifChannel, Boolean) -> Unit,
) {
    val bt = BtTheme.colors
    BtPickerSheet(
        title = stringResource(R.string.bt_notif_mirror_group),
        subtitle = stringResource(notifCategoryLabelRes(NotifCatalog.CAT_MIRRORCHAIN)),
        onDismiss = onDismiss,
    ) {
        Text(
            stringResource(R.string.bt_notif_mirror_group_hint),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        if (visible.isEmpty()) {
            Text(
                stringResource(R.string.bt_notif_no_channels),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            return@BtPickerSheet
        }
        BtGroup {
            visible.forEach { channel ->
                val state = mirrorTriState(matrix.rows, channel, serverTypes)
                val checked = state == TriState.On
                BtGroupRow(
                    title = stringResource(notifChannelLabelRes(channel)),
                    onClick = { onMirrorChannel(channel, !checked) },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state == TriState.Mixed) {
                                BtBadge(
                                    text = stringResource(R.string.bt_notif_row_mixed),
                                    kind = BtBadgeKind.Gold,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Switch(
                                checked = checked,
                                onCheckedChange = { onMirrorChannel(channel, it) },
                                colors = btSwitchColors(),
                            )
                        }
                    },
                )
            }
        }
    }
}

/**
 * The app's switch palette, in one place. Six call sites in this file alone had
 * been about to grow their own copy.
 */
@Composable
internal fun btSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = BtTheme.colors.onGold,
    checkedTrackColor = BtTheme.colors.gold,
    checkedBorderColor = BtTheme.colors.gold,
    uncheckedThumbColor = BtTheme.colors.textMuted,
    uncheckedTrackColor = BtTheme.colors.surface,
    uncheckedBorderColor = BtTheme.colors.borderStrong,
)
