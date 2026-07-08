package at.bettertrack.app.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.db.BtDatabase
import at.bettertrack.app.data.db.SyncOpEntity
import at.bettertrack.app.data.repo.PortfolioRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.sync.ConnectivityMonitor
import at.bettertrack.app.sync.OpStatus
import at.bettertrack.app.sync.OpType
import at.bettertrack.app.sync.SyncEngine
import at.bettertrack.app.sync.SyncScheduler
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtChip
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.MoneyText
import at.bettertrack.app.ui.components.formatEur
import at.bettertrack.app.ui.cash.PendingCashRow
import at.bettertrack.app.ui.cash.decodePendingCashRow
import at.bettertrack.app.ui.portfolio.PendingStatusBadge
import at.bettertrack.app.ui.portfolio.PendingTxRow
import at.bettertrack.app.ui.portfolio.PendingUiStatus
import at.bettertrack.app.ui.portfolio.decodePendingTxRow
import at.bettertrack.app.ui.portfolio.formatQuantity
import at.bettertrack.app.ui.portfolio.pendingUiStatus
import at.bettertrack.app.ui.portfolio.transactionNotional
import at.bettertrack.app.ui.shell.OfflineBanner
import at.bettertrack.app.ui.shell.formatAsOf
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * The Pending-sync screen (spec §7.4): every queued/failed outbound op with
 * per-item status, timestamps, the server's rejection reason, retry / edit /
 * discard, a drain-now affordance and an "all synced" empty state. Reachable
 * from the offline banner, the debug screen and the overview's pending strip.
 */

/** One list row: a rich transaction / cash op or a generic fallback. */
data class PendingListItem(
    val op: SyncOpEntity,
    val status: PendingUiStatus,
    val tx: PendingTxRow?,
    val cash: PendingCashRow?,
)

class PendingSyncViewModel(
    db: BtDatabase,
    repo: PortfolioRepository,
    private val engine: SyncEngine,
    private val scheduler: SyncScheduler,
    connectivity: ConnectivityMonitor,
    json: Json,
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = connectivity.isOnline

    val dataAgeMs: StateFlow<Long?> = repo.portfolioDataAgeMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** All queue rows, newest first, decoded for display. */
    val items: StateFlow<List<PendingListItem>> = db.syncOpDao().observeAll()
        .map { ops ->
            ops.sortedByDescending { it.id }.mapNotNull { op ->
                val status = OpStatus.fromWire(op.status) ?: return@mapNotNull null
                PendingListItem(
                    op = op,
                    status = pendingUiStatus(status),
                    tx = decodePendingTxRow(op, json),
                    cash = decodePendingCashRow(op, json),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun syncNow() = scheduler.scheduleDrain(manual = true)

    fun retry(opId: Long) {
        viewModelScope.launch {
            engine.retryOp(opId)
            scheduler.scheduleDrain()
        }
    }

    fun discard(opId: Long) {
        viewModelScope.launch { engine.discardOp(opId) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingSyncScreen(
    onBack: () -> Unit,
    onEditTxOp: (Long) -> Unit,
    /** Step 9: edit-and-retry for queued cash ops (opId, portfolioId). */
    onEditCashOp: (Long, String?) -> Unit = { _, _ -> },
) {
    val vm: PendingSyncViewModel = viewModel {
        PendingSyncViewModel(
            db = AppGraph.database,
            repo = AppGraph.portfolioRepository,
            engine = AppGraph.syncEngine,
            scheduler = AppGraph.syncScheduler,
            connectivity = AppGraph.connectivityMonitor,
            json = AppGraph.json,
        )
    }

    val bt = BtTheme.colors
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val items by vm.items.collectAsStateWithLifecycle()
    val isOnline by vm.isOnline.collectAsStateWithLifecycle()
    val dataAgeMs by vm.dataAgeMs.collectAsStateWithLifecycle()

    var discardTarget by remember { mutableStateOf<Long?>(null) }

    val attention = items.filter { it.status == PendingUiStatus.NEEDS_ATTENTION }
    val open = items.filter {
        it.status == PendingUiStatus.PENDING || it.status == PendingUiStatus.SYNCING
    }
    val done = items.filter { it.status == PendingUiStatus.DONE }.take(5)

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.bt_pending_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = bt.textPrimary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.bt_action_back),
                            tint = bt.textSecondary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bt.bg,
                    titleContentColor = bt.textPrimary,
                ),
            )
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            if (!isOnline) OfflineBanner(asOfMs = dataAgeMs)

            if (attention.isEmpty() && open.isEmpty() && done.isEmpty()) {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            BtEmptyState(
                                icon = Icons.Outlined.CloudDone,
                                title = stringResource(R.string.bt_pending_empty_title),
                                message = stringResource(R.string.bt_pending_empty_message),
                            )
                        }
                    }
                }
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Header: status summary + drain-now affordance (§7.4).
                item(key = "header") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (isOnline) Icons.Outlined.Sync else Icons.Outlined.CloudOff,
                            contentDescription = null,
                            tint = if (isOnline) bt.gold else bt.goldEmphasis,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = when {
                                !isOnline -> stringResource(R.string.bt_pending_offline_hint)
                                open.isEmpty() && attention.isEmpty() ->
                                    stringResource(R.string.bt_pending_empty_title)

                                else -> stringResource(
                                    R.string.bt_pending_summary,
                                    open.size + attention.size,
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = bt.textSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        if (open.isNotEmpty() || attention.isNotEmpty()) {
                            BtChip(
                                text = stringResource(R.string.bt_pending_sync_now),
                                selected = true,
                                enabled = isOnline,
                                onClick = { vm.syncNow() },
                            )
                        }
                    }
                }

                if (attention.isNotEmpty()) {
                    item(key = "attention-header") {
                        SectionHeader(stringResource(R.string.bt_pending_needs_attention_section))
                    }
                    items(count = attention.size, key = { "a-" + attention[it].op.id }) { i ->
                        PendingOpCard(
                            item = attention[i],
                            locale = locale,
                            onEdit = onEditTxOp,
                            onEditCash = onEditCashOp,
                            onRetry = { vm.retry(it) },
                            onDiscard = { discardTarget = it },
                        )
                    }
                }

                if (open.isNotEmpty()) {
                    item(key = "open-header") {
                        SectionHeader(stringResource(R.string.bt_pending_section))
                    }
                    items(count = open.size, key = { "o-" + open[it].op.id }) { i ->
                        PendingOpCard(
                            item = open[i],
                            locale = locale,
                            onEdit = onEditTxOp,
                            onEditCash = onEditCashOp,
                            onRetry = { vm.retry(it) },
                            onDiscard = { discardTarget = it },
                        )
                    }
                }

                if (done.isNotEmpty()) {
                    item(key = "done-header") {
                        SectionHeader(stringResource(R.string.bt_pending_done_section))
                    }
                    items(count = done.size, key = { "d-" + done[it].op.id }) { i ->
                        PendingOpCard(
                            item = done[i],
                            locale = locale,
                            onEdit = null,
                            onEditCash = null,
                            onRetry = null,
                            onDiscard = null,
                        )
                    }
                }
            }
        }
    }

    discardTarget?.let { opId ->
        AlertDialog(
            onDismissRequest = { discardTarget = null },
            containerColor = bt.surface,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            title = { Text(stringResource(R.string.bt_txform_discard_title)) },
            text = { Text(stringResource(R.string.bt_txform_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.discard(opId)
                        discardTarget = null
                    },
                ) { Text(stringResource(R.string.bt_txform_discard_action), color = bt.loss) }
            },
            dismissButton = {
                TextButton(onClick = { discardTarget = null }) {
                    Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = BtTheme.colors.textPrimary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

/**
 * One queue entry: what it is, when it was added, its status chip, the server
 * reason when rejected, and the per-item actions (§7.4). Buy/sell ops render
 * rich (asset, qty × price, value); other op types render generically.
 */
@Composable
private fun PendingOpCard(
    item: PendingListItem,
    locale: Locale,
    onEdit: ((Long) -> Unit)?,
    onEditCash: ((Long, String?) -> Unit)?,
    onRetry: ((Long) -> Unit)?,
    onDiscard: ((Long) -> Unit)?,
) {
    val bt = BtTheme.colors
    val tx = item.tx
    val cash = item.cash
    val editable = item.status == PendingUiStatus.PENDING ||
        item.status == PendingUiStatus.NEEDS_ATTENTION

    BtCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (tx != null) {
                            BtBadge(
                                text = stringResource(
                                    if (tx.isBuy) R.string.bt_tx_side_buy else R.string.bt_tx_side_sell,
                                ),
                                kind = if (tx.isBuy) BtBadgeKind.Gain else BtBadgeKind.Loss,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = tx.assetSymbol,
                                style = MaterialTheme.typography.titleSmall,
                                color = bt.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Text(
                                text = genericOpLabel(item.op.opType),
                                style = MaterialTheme.typography.titleSmall,
                                color = bt.textPrimary,
                            )
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    val detail = when {
                        tx != null ->
                            "${formatQuantity(tx.quantity, locale)} × ${formatEur(tx.price, locale)}"

                        cash != null -> formatEur(cash.amountEur, locale)

                        else -> null
                    }
                    Text(
                        text = listOfNotNull(
                            detail,
                            stringResource(R.string.bt_pending_added_at, formatAsOf(item.op.createdAtMs)),
                        ).joinToString(" · "),
                        style = BtTheme.type.numberCaption,
                        color = bt.textMuted,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End) {
                    PendingStatusBadge(item.status)
                    if (tx != null) {
                        Spacer(Modifier.height(4.dp))
                        MoneyText(
                            value = transactionNotional(tx.quantity, tx.price),
                            style = BtTheme.type.moneySmall,
                        )
                    }
                }
            }

            // The server's rejection reason (§7.3) — verbatim, human-readable.
            if (item.status == PendingUiStatus.NEEDS_ATTENTION && item.op.serverError != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = item.op.serverError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.lossSoft,
                )
            }

            // Per-item actions (§7.4): edit-and-retry / retry / discard.
            if (editable && (onEdit != null || onEditCash != null || onRetry != null || onDiscard != null)) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val editLabel = stringResource(
                        if (item.status == PendingUiStatus.NEEDS_ATTENTION) {
                            R.string.bt_pending_edit_retry
                        } else {
                            R.string.bt_pending_edit
                        },
                    )
                    if (tx != null && onEdit != null) {
                        BtChip(
                            text = editLabel,
                            selected = item.status == PendingUiStatus.NEEDS_ATTENTION,
                            onClick = { onEdit(item.op.id) },
                        )
                    }
                    if (cash != null && onEditCash != null) {
                        BtChip(
                            text = editLabel,
                            selected = item.status == PendingUiStatus.NEEDS_ATTENTION,
                            onClick = { onEditCash(item.op.id, item.op.portfolioId) },
                        )
                    }
                    if (tx == null && cash == null &&
                        item.status == PendingUiStatus.NEEDS_ATTENTION && onRetry != null
                    ) {
                        BtChip(
                            text = stringResource(R.string.bt_pending_retry),
                            onClick = { onRetry(item.op.id) },
                        )
                    }
                    if (onDiscard != null) {
                        BtChip(
                            text = stringResource(R.string.bt_pending_discard),
                            onClick = { onDiscard(item.op.id) },
                        )
                    }
                }
            }
        }
    }
}

/** Labels for non-transaction op types (queued by Steps 9–10 flows). */
@Composable
private fun genericOpLabel(opType: String): String = when (OpType.fromWire(opType)) {
    OpType.CASH_DEPOSIT -> stringResource(R.string.bt_pending_op_cash_deposit)
    OpType.CASH_WITHDRAW -> stringResource(R.string.bt_pending_op_cash_withdraw)
    OpType.CASH_TRANSFER -> stringResource(R.string.bt_pending_op_cash_transfer)
    OpType.CUSTOM_ASSET_VALUE_POINT -> stringResource(R.string.bt_pending_op_value_point)
    else -> opType
}
