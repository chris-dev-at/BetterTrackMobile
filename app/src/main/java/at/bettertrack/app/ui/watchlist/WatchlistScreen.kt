package at.bettertrack.app.ui.watchlist

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.db.WatchlistItemEntity
import at.bettertrack.app.data.repo.MarketRepository
import at.bettertrack.app.data.repo.WatchlistBoard
import at.bettertrack.app.data.repo.WatchlistRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.sync.ConnectivityMonitor
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtChip
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.MoneyText
import at.bettertrack.app.ui.components.formatPercent
import at.bettertrack.app.ui.market.assetTypeLabel
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

/** A watchlist row's quote (§6.6 — price + day change). */
data class WatchQuote(val eurPrice: Double?, val dayChangePct: Double?)

@Suppress("OPT_IN_USAGE")
class WatchlistViewModel(
    private val watchlist: WatchlistRepository,
    private val market: MarketRepository,
    connectivity: ConnectivityMonitor,
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = connectivity.isOnline

    val boards: StateFlow<List<WatchlistBoard>> = watchlist.boards
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedBoardId = MutableStateFlow<String?>(null)
    val selectedBoardId: StateFlow<String?> = _selectedBoardId.asStateFlow()

    val items: StateFlow<List<WatchlistItemEntity>> = _selectedBoardId
        .flatMapLatest { id -> if (id == null) kotlinx.coroutines.flow.flowOf(emptyList()) else watchlist.items(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _quotes = MutableStateFlow<Map<String, WatchQuote>>(emptyMap())
    val quotes: StateFlow<Map<String, WatchQuote>> = _quotes.asStateFlow()

    init {
        // Default to the first board (General) once loaded.
        viewModelScope.launch {
            boards.collect { list ->
                if (_selectedBoardId.value == null || list.none { it.id == _selectedBoardId.value }) {
                    _selectedBoardId.value = list.firstOrNull()?.id
                }
            }
        }
        viewModelScope.launch { watchlist.refresh() }
        // Fetch quotes whenever the visible items change (online).
        viewModelScope.launch {
            items.collect { list -> if (isOnline.value) fetchQuotes(list) }
        }
    }

    private suspend fun fetchQuotes(list: List<WatchlistItemEntity>) {
        if (list.isEmpty()) return
        val results = viewModelScope.async {
            list.map { item ->
                async {
                    when (val r = market.quote(item.assetId)) {
                        is BtResult.Ok -> item.assetId to WatchQuote(r.value.eurPrice, r.value.dayChangePct)
                        is BtResult.Err -> null
                    }
                }
            }.awaitAll().filterNotNull()
        }.await()
        _quotes.value = _quotes.value + results.toMap()
    }

    fun selectBoard(id: String) { _selectedBoardId.value = id }

    fun refresh() = viewModelScope.launch {
        watchlist.refresh()
        fetchQuotes(items.value)
    }

    fun createBoard(name: String, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val r = watchlist.createBoard(name)
        if (r is BtResult.Ok) _selectedBoardId.value = r.value
        onDone(r is BtResult.Ok)
    }

    fun renameBoard(id: String, name: String) = viewModelScope.launch { watchlist.renameBoard(id, name) }
    fun deleteBoard(id: String) = viewModelScope.launch {
        watchlist.deleteBoard(id)
        _selectedBoardId.value = boards.value.firstOrNull()?.id
    }

    fun removeAsset(boardId: String, assetId: String) = viewModelScope.launch {
        watchlist.removeAsset(boardId, assetId)
    }
}

@Composable
fun WatchlistPanel(
    onOpenAsset: (String) -> Unit,
    onAddAsset: (boardId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: WatchlistViewModel = viewModel {
        WatchlistViewModel(
            AppGraph.watchlistRepository,
            AppGraph.marketRepository,
            AppGraph.connectivityMonitor,
        )
    }
    val bt = BtTheme.colors
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val boards by vm.boards.collectAsStateWithLifecycle()
    val selectedId by vm.selectedBoardId.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val quotes by vm.quotes.collectAsStateWithLifecycle()
    val isOnline by vm.isOnline.collectAsStateWithLifecycle()

    var createOpen by remember { mutableStateOf(false) }
    var renameBoard by remember { mutableStateOf<WatchlistBoard?>(null) }
    var deleteBoardConfirm by remember { mutableStateOf<WatchlistBoard?>(null) }

    val selectedBoard = boards.firstOrNull { it.id == selectedId }

    Column(modifier) {
        // Board selector chips.
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            boards.forEach { board ->
                BtChip(
                    text = board.name,
                    selected = board.id == selectedId,
                    onClick = { vm.selectBoard(board.id) },
                )
            }
            if (true) { // named watchlists are LIVE (V3-P5)
                IconButton(onClick = { createOpen = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.bt_watchlist_new), tint = bt.gold)
                }
            }
        }

        // Selected-board management — rename/delete for any non-default named
        // list (V3-P5 live). General (the default) is LOCKED: no row shown.
        if (selectedBoard != null && !selectedBoard.isDefault) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { renameBoard = selectedBoard }) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.bt_watchlist_rename), tint = bt.textMuted, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { deleteBoardConfirm = selectedBoard }) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.bt_watchlist_delete), tint = bt.loss, modifier = Modifier.size(20.dp))
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Items.
        if (items.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                BtEmptyState(
                    icon = Icons.Outlined.StarBorder,
                    title = stringResource(R.string.bt_watchlist_empty_title),
                    message = stringResource(R.string.bt_watchlist_empty_message),
                    action = {
                        at.bettertrack.app.ui.components.BtSecondaryButton(
                            text = stringResource(R.string.bt_watchlist_add_asset),
                            onClick = { selectedId?.let(onAddAsset) },
                            enabled = isOnline || selectedBoard?.isReal == false,
                        )
                    },
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(count = items.size, key = { items[it].id }) { i ->
                    val item = items[i]
                    WatchRow(
                        item = item,
                        quote = quotes[item.assetId],
                        locale = locale,
                        onOpen = { onOpenAsset(item.assetId) },
                        onRemove = { selectedId?.let { vm.removeAsset(it, item.assetId) } },
                    )
                }
                item(key = "add") {
                    at.bettertrack.app.ui.components.BtSecondaryButton(
                        text = stringResource(R.string.bt_watchlist_add_asset),
                        onClick = { selectedId?.let(onAddAsset) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(46.dp),
                    )
                }
            }
        }
    }

    if (createOpen) {
        WatchlistNameDialog(
            title = stringResource(R.string.bt_watchlist_new),
            initial = "",
            onConfirm = { name -> vm.createBoard(name) { ok -> if (ok) createOpen = false } },
            onDismiss = { createOpen = false },
        )
    }
    renameBoard?.let { board ->
        WatchlistNameDialog(
            title = stringResource(R.string.bt_watchlist_rename),
            initial = board.name,
            onConfirm = { name -> vm.renameBoard(board.id, name); renameBoard = null },
            onDismiss = { renameBoard = null },
        )
    }
    deleteBoardConfirm?.let { board ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteBoardConfirm = null },
            containerColor = bt.surface,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            title = { Text(stringResource(R.string.bt_watchlist_delete)) },
            text = { Text(stringResource(R.string.bt_watchlist_delete_message, board.name)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { vm.deleteBoard(board.id); deleteBoardConfirm = null }) {
                    Text(stringResource(R.string.bt_watchlist_delete), color = bt.loss)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deleteBoardConfirm = null }) {
                    Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun WatchRow(
    item: WatchlistItemEntity,
    quote: WatchQuote?,
    locale: Locale,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val bt = BtTheme.colors
    BtCard(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.assetSymbol, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = bt.textPrimary)
                    Spacer(Modifier.width(8.dp))
                    BtBadge(text = assetTypeLabel(item.assetType), kind = BtBadgeKind.Neutral)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = listOfNotNull(item.assetName, item.assetExchange).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (quote?.eurPrice != null) {
                    MoneyText(value = quote.eurPrice, style = BtTheme.type.moneySmall)
                } else {
                    Text(stringResource(R.string.bt_value_dash), style = BtTheme.type.moneySmall, color = bt.textMuted)
                }
                quote?.dayChangePct?.let { pct ->
                    Text(
                        text = formatPercent(pct, locale),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (pct >= 0) bt.gain else bt.loss,
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.bt_watchlist_remove_item), tint = bt.textMuted, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun WatchlistNameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    var name by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = bt.surface,
        titleContentColor = bt.textPrimary,
        title = { Text(title) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.bt_switcher_name_label)) },
                colors = at.bettertrack.app.ui.customassets.dialogFieldColors(),
            )
        },
        confirmButton = {
            at.bettertrack.app.ui.components.BtPrimaryButton(
                text = stringResource(R.string.bt_switcher_create_action),
                onClick = { onConfirm(name) },
                enabled = name.trim().isNotEmpty(),
            )
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
            }
        },
    )
}
