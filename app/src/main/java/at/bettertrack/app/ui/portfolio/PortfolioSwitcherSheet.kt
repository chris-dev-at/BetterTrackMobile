package at.bettertrack.app.ui.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.MoneyText
import at.bettertrack.app.ui.theme.BtTheme

/**
 * The portfolio switcher (spec §6.1) as a phone-shaped bottom sheet: active
 * portfolios with per-portfolio value (once their detail synced), the default
 * badge, per-row rename/archive, an archived section with restore, and create.
 * Selection works offline (it's local); create/rename/archive/restore are
 * online-only (§7.2) and show a clear requires-connection state when offline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioSwitcherSheet(
    portfolios: List<PortfolioEntity>,
    selectedId: String?,
    isOnline: Boolean,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onCreate: (String, onDone: (Boolean) -> Unit) -> Unit,
    onRename: (String, String, onDone: (Boolean) -> Unit) -> Unit,
    onArchive: (String, onDone: (Boolean) -> Unit) -> Unit,
    onRestore: (String, onDone: (Boolean) -> Unit) -> Unit,
) {
    val bt = BtTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var createOpen by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<PortfolioEntity?>(null) }
    var archiveTarget by remember { mutableStateOf<PortfolioEntity?>(null) }

    val active = portfolios.filter { it.archivedAt == null }
    val archived = portfolios.filter { it.archivedAt != null }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bt.surface,
        contentColor = bt.textPrimary,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "title") {
                Text(
                    text = stringResource(R.string.bt_switcher_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = bt.textPrimary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            if (!isOnline) {
                item(key = "offline-hint") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.CloudOff,
                            contentDescription = null,
                            tint = bt.textMuted,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.bt_switcher_requires_connection),
                            style = MaterialTheme.typography.bodySmall,
                            color = bt.textMuted,
                        )
                    }
                }
            }

            if (error != null) {
                item(key = "error") {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.loss,
                    )
                }
            }

            items(count = active.size, key = { "p-" + active[it].id }) { index ->
                val p = active[index]
                SwitcherRow(
                    portfolio = p,
                    selected = p.id == selectedId,
                    actionsEnabled = isOnline && !busy,
                    onClick = { onSelect(p.id) },
                    onRename = { renameTarget = p },
                    onArchive = { archiveTarget = p },
                )
            }

            item(key = "create") {
                BtCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = if (isOnline && !busy) {
                        { createOpen = true }
                    } else {
                        null
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = null,
                            tint = if (isOnline) bt.gold else bt.textMuted,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.bt_switcher_new),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (isOnline) bt.textPrimary else bt.textMuted,
                        )
                    }
                }
            }

            if (archived.isNotEmpty()) {
                item(key = "archived-header") {
                    Text(
                        text = stringResource(R.string.bt_switcher_archived_section),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                    )
                }
                items(count = archived.size, key = { "a-" + archived[it].id }) { index ->
                    val p = archived[index]
                    ArchivedRow(
                        portfolio = p,
                        restoreEnabled = isOnline && !busy,
                        onRestore = { onRestore(p.id) { } },
                    )
                }
            }
        }
    }

    if (createOpen) {
        PortfolioNameDialog(
            title = stringResource(R.string.bt_switcher_create_title),
            confirmLabel = stringResource(R.string.bt_switcher_create_action),
            initialName = "",
            busy = busy,
            onConfirm = { name -> onCreate(name) { ok -> if (ok) createOpen = false } },
            onDismiss = { createOpen = false },
        )
    }

    renameTarget?.let { target ->
        PortfolioNameDialog(
            title = stringResource(R.string.bt_switcher_rename_title),
            confirmLabel = stringResource(R.string.bt_switcher_rename_action),
            initialName = target.name,
            busy = busy,
            onConfirm = { name -> onRename(target.id, name) { ok -> if (ok) renameTarget = null } },
            onDismiss = { renameTarget = null },
        )
    }

    archiveTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { archiveTarget = null },
            containerColor = bt.surface,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            title = { Text(stringResource(R.string.bt_switcher_archive_title)) },
            text = {
                Text(stringResource(R.string.bt_switcher_archive_message, target.name))
            },
            confirmButton = {
                TextButton(
                    onClick = { onArchive(target.id) { ok -> if (ok) archiveTarget = null } },
                    enabled = !busy,
                ) {
                    Text(
                        text = stringResource(R.string.bt_switcher_archive_action),
                        color = bt.loss,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { archiveTarget = null }) {
                    Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun SwitcherRow(
    portfolio: PortfolioEntity,
    selected: Boolean,
    actionsEnabled: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
) {
    val bt = BtTheme.colors
    var menuOpen by remember { mutableStateOf(false) }

    BtCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = portfolio.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = bt.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (portfolio.isDefault) {
                        Spacer(Modifier.width(8.dp))
                        BtBadge(
                            text = stringResource(R.string.bt_switcher_default_badge),
                            kind = BtBadgeKind.Neutral,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                val totals = portfolio.totals
                if (totals != null) {
                    MoneyText(
                        value = totals.totalValueEur,
                        style = BtTheme.type.numberCaption,
                        color = bt.textSecondary,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.bt_switcher_value_pending),
                        style = BtTheme.type.numberCaption,
                        color = bt.textMuted,
                    )
                }
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = stringResource(R.string.bt_switcher_selected_cd),
                    tint = bt.gold,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = { menuOpen = true }, enabled = actionsEnabled) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.bt_switcher_actions_cd),
                    tint = if (actionsEnabled) bt.textSecondary else bt.border,
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = bt.surface,
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.bt_switcher_rename), color = bt.textPrimary) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.bt_switcher_archive), color = bt.loss) },
                    onClick = {
                        menuOpen = false
                        onArchive()
                    },
                )
            }
        }
    }
}

@Composable
private fun ArchivedRow(
    portfolio: PortfolioEntity,
    restoreEnabled: Boolean,
    onRestore: () -> Unit,
) {
    val bt = BtTheme.colors
    BtCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = portfolio.name,
                style = MaterialTheme.typography.titleSmall,
                color = bt.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRestore, enabled = restoreEnabled) {
                Text(
                    text = stringResource(R.string.bt_switcher_restore),
                    color = if (restoreEnabled) bt.goldEmphasis else bt.textMuted,
                )
            }
        }
    }
}

/** Shared name dialog for create + rename. */
@Composable
internal fun PortfolioNameDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    busy: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    var name by rememberSaveable { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = bt.surface,
        titleContentColor = bt.textPrimary,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.bt_switcher_name_label)) },
                singleLine = true,
                enabled = !busy,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = bt.gold,
                    unfocusedBorderColor = bt.borderStrong,
                    focusedLabelColor = bt.gold,
                    unfocusedLabelColor = bt.textMuted,
                    focusedTextColor = bt.textPrimary,
                    unfocusedTextColor = bt.textPrimary,
                    cursorColor = bt.gold,
                ),
            )
        },
        confirmButton = {
            BtPrimaryButton(
                text = confirmLabel,
                onClick = { onConfirm(name) },
                enabled = name.trim().isNotEmpty() && !busy,
                loading = busy,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
            }
        },
    )
}
