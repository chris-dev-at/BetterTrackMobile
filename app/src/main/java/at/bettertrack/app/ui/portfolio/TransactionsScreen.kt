package at.bettertrack.app.ui.portfolio

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.db.TransactionEntity
import at.bettertrack.app.data.repo.PortfolioRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.sync.ConnectivityMonitor
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtChip
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.MoneyText
import at.bettertrack.app.ui.components.formatEur
import at.bettertrack.app.ui.shell.OfflineBanner
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Per-portfolio transaction history (Step 7, spec §6.2 read-only; Step 8 adds
 * writes): the cached ledger, filterable by type (buy/sell) and asset, with
 * cursor-paged incremental loading of older entries. Step 8: queued buy/sells
 * render as a clearly-pending section ABOVE the synced ledger (§7.1/§7.4),
 * tapping a synced row edits it (online-only) and tapping a pending row edits
 * the queue in place.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModel(
    private val repo: PortfolioRepository,
    connectivity: ConnectivityMonitor,
    db: at.bettertrack.app.data.db.BtDatabase,
    json: kotlinx.serialization.json.Json,
    routePortfolioId: String?,
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = connectivity.isOnline

    /** Route arg wins; otherwise the governing switcher selection (§6.1). */
    val portfolioId: StateFlow<String?> =
        combine(repo.portfolios, repo.selectedPortfolioId) { all, stored ->
            routePortfolioId
                ?: PortfolioOverviewViewModel.resolveSelection(all, stored)?.id
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), routePortfolioId)

    val portfolioName: StateFlow<String?> =
        combine(repo.portfolios, portfolioId) { all, pid ->
            all.firstOrNull { it.id == pid }?.name
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val allTransactions: StateFlow<List<TransactionEntity>> = portfolioId
        .flatMapLatest { pid -> if (pid == null) flowOf(emptyList()) else repo.transactions(pid) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _sideFilter = MutableStateFlow(TxSideFilter.ALL)
    val sideFilter: StateFlow<TxSideFilter> = _sideFilter.asStateFlow()

    private val _assetFilter = MutableStateFlow<TxAssetOption?>(null)
    val assetFilter: StateFlow<TxAssetOption?> = _assetFilter.asStateFlow()

    val transactions: StateFlow<List<TransactionEntity>> =
        combine(allTransactions, _sideFilter, _assetFilter) { list, side, asset ->
            filterTransactions(list, side, asset?.assetId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Queued buy/sells for this portfolio (§7.4), same display filters applied. */
    val pendingRows: StateFlow<List<PendingTxRow>> = combine(
        combine(db.syncOpDao().observeAll(), portfolioId) { ops, pid ->
            if (pid == null) emptyList() else decodePendingTxRows(ops, json, pid)
        },
        _sideFilter,
        _assetFilter,
    ) { rows, side, asset ->
        filterPendingTxRows(rows, side, asset?.assetId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hasAnyCached: StateFlow<Boolean> = allTransactions
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val assetOptions: StateFlow<List<TxAssetOption>> = allTransactions
        .map { distinctTxAssets(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    /** Null after a refresh that reached the ledger's end. */
    private val _nextCursor = MutableStateFlow<String?>(null)
    val hasMore: StateFlow<Boolean> = _nextCursor
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var refreshedOnce = false

    init {
        viewModelScope.launch {
            // Refresh as soon as the governing portfolio is known.
            portfolioId.collect { pid ->
                if (pid != null && !refreshedOnce) {
                    refreshedOnce = true
                    refresh()
                }
            }
        }
    }

    fun refresh() {
        val pid = portfolioId.value ?: return
        viewModelScope.launch {
            _refreshing.value = true
            when (val r = repo.refreshTransactions(pid)) {
                is BtResult.Ok -> _nextCursor.value = r.value
                is BtResult.Err -> Unit // cached rows stay; banner explains offline
            }
            _refreshing.value = false
        }
    }

    fun loadMore() {
        val pid = portfolioId.value ?: return
        val cursor = _nextCursor.value ?: return
        if (_loadingMore.value || !isOnline.value) return
        viewModelScope.launch {
            _loadingMore.value = true
            when (val r = repo.loadMoreTransactions(pid, cursor)) {
                is BtResult.Ok -> _nextCursor.value = r.value
                is BtResult.Err -> Unit
            }
            _loadingMore.value = false
        }
    }

    fun setSideFilter(filter: TxSideFilter) {
        _sideFilter.value = filter
    }

    fun setAssetFilter(option: TxAssetOption?) {
        _assetFilter.value = option
    }

    fun clearFilters() {
        _sideFilter.value = TxSideFilter.ALL
        _assetFilter.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    routePortfolioId: String?,
    onBack: () -> Unit,
    onEditSynced: (String) -> Unit,
    onEditQueued: (Long) -> Unit,
    onOpenPendingSync: () -> Unit,
) {
    val vm: TransactionsViewModel = viewModel {
        TransactionsViewModel(
            AppGraph.portfolioRepository,
            AppGraph.connectivityMonitor,
            AppGraph.database,
            AppGraph.json,
            routePortfolioId,
        )
    }

    val bt = BtTheme.colors
    val transactions by vm.transactions.collectAsStateWithLifecycle()
    val pendingRows by vm.pendingRows.collectAsStateWithLifecycle()
    val hasAnyCached by vm.hasAnyCached.collectAsStateWithLifecycle()
    val assetOptions by vm.assetOptions.collectAsStateWithLifecycle()
    val sideFilter by vm.sideFilter.collectAsStateWithLifecycle()
    val assetFilter by vm.assetFilter.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val loadingMore by vm.loadingMore.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()
    val isOnline by vm.isOnline.collectAsStateWithLifecycle()
    val portfolioName by vm.portfolioName.collectAsStateWithLifecycle()
    val dataAgeMs by AppGraph.portfolioRepository.portfolioDataAgeMs
        .collectAsStateWithLifecycle(initialValue = null)

    var assetSheetOpen by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.bt_tx_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = bt.textPrimary,
                        )
                        portfolioName?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = bt.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
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
            if (!isOnline) OfflineBanner(asOfMs = dataAgeMs, onClick = onOpenPendingSync)

            // Filter row: type chips + asset picker (§6.2).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BtChip(
                    text = stringResource(R.string.bt_tx_filter_all),
                    selected = sideFilter == TxSideFilter.ALL,
                    onClick = { vm.setSideFilter(TxSideFilter.ALL) },
                )
                BtChip(
                    text = stringResource(R.string.bt_tx_filter_buys),
                    selected = sideFilter == TxSideFilter.BUY,
                    onClick = { vm.setSideFilter(TxSideFilter.BUY) },
                )
                BtChip(
                    text = stringResource(R.string.bt_tx_filter_sells),
                    selected = sideFilter == TxSideFilter.SELL,
                    onClick = { vm.setSideFilter(TxSideFilter.SELL) },
                )
                Spacer(Modifier.weight(1f))
                BtChip(
                    text = assetFilter?.symbol
                        ?: stringResource(R.string.bt_tx_filter_all_assets),
                    selected = assetFilter != null,
                    onClick = { assetSheetOpen = true },
                )
            }

            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { vm.refresh() },
                state = pullState,
                modifier = Modifier.fillMaxSize(),
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullState,
                        isRefreshing = refreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                        containerColor = bt.surface,
                        color = bt.gold,
                    )
                },
            ) {
                when {
                    // Nothing cached yet and the first fetch is running.
                    !hasAnyCached && pendingRows.isEmpty() && refreshing -> TransactionsSkeleton()

                    !hasAnyCached && pendingRows.isEmpty() -> EmptyFill {
                        BtEmptyState(
                            icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                            title = stringResource(R.string.bt_tx_empty_title),
                            message = stringResource(R.string.bt_tx_empty_message),
                        )
                    }

                    transactions.isEmpty() && pendingRows.isEmpty() -> EmptyFill {
                        BtEmptyState(
                            icon = Icons.Outlined.FilterList,
                            title = stringResource(R.string.bt_tx_no_matches_title),
                            message = stringResource(R.string.bt_tx_no_matches_message),
                            action = {
                                BtSecondaryButton(
                                    text = stringResource(R.string.bt_tx_clear_filters),
                                    onClick = { vm.clearFilters() },
                                )
                            },
                        )
                    }

                    else -> {
                        val listState = rememberLazyListState()
                        // Incremental load: fetch older pages as the end nears.
                        LaunchedEffect(listState, transactions.size, hasMore) {
                            snapshotFlow {
                                val info = listState.layoutInfo
                                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                                last >= info.totalItemsCount - 4
                            }.collect { nearEnd ->
                                if (nearEnd && hasMore) vm.loadMore()
                            }
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                bottom = 24.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // §7.1/§7.4: queued entries sit ABOVE the synced
                            // ledger as a clearly-pending tray — annotations
                            // beside server truth, never merged into it.
                            if (pendingRows.isNotEmpty()) {
                                item(key = "pending-header") {
                                    Text(
                                        text = stringResource(R.string.bt_pending_section),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = bt.textPrimary,
                                        modifier = Modifier.padding(bottom = 2.dp),
                                    )
                                }
                                items(
                                    count = pendingRows.size,
                                    key = { "pending-" + pendingRows[it].opId },
                                ) { index ->
                                    PendingTransactionRow(
                                        row = pendingRows[index],
                                        onEdit = { onEditQueued(it.opId) },
                                    )
                                }
                                if (transactions.isNotEmpty()) {
                                    item(key = "synced-header") {
                                        Text(
                                            text = stringResource(R.string.bt_pending_synced_section),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = bt.textPrimary,
                                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                                        )
                                    }
                                }
                            }
                            items(
                                count = transactions.size,
                                key = { transactions[it].id },
                            ) { index ->
                                val tx = transactions[index]
                                TransactionRow(tx, onClick = { onEditSynced(tx.id) })
                            }
                            if (hasMore) {
                                item(key = "more") {
                                    Box(
                                        Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (loadingMore) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(22.dp),
                                                strokeWidth = 2.dp,
                                                color = bt.gold,
                                            )
                                        } else if (isOnline) {
                                            BtSecondaryButton(
                                                text = stringResource(R.string.bt_tx_load_more),
                                                onClick = { vm.loadMore() },
                                            )
                                        } else {
                                            Text(
                                                text = stringResource(
                                                    R.string.bt_switcher_requires_connection,
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = bt.textMuted,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (assetSheetOpen) {
        AssetFilterSheet(
            options = assetOptions,
            selected = assetFilter,
            onSelect = { option ->
                vm.setAssetFilter(option)
                assetSheetOpen = false
            },
            onDismiss = { assetSheetOpen = false },
        )
    }
}

// ── Shared ledger row (also used by the holding detail, Step 7) ─────────────

@Composable
fun TransactionRow(
    tx: TransactionEntity,
    showAsset: Boolean = true,
    /** Step 8: tapping a synced row opens its editor (online-only, §7.2). */
    onClick: (() -> Unit)? = null,
) {
    val bt = BtTheme.colors
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val isBuy = tx.side == "buy"
    BtCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BtBadge(
                text = stringResource(if (isBuy) R.string.bt_tx_side_buy else R.string.bt_tx_side_sell),
                kind = if (isBuy) BtBadgeKind.Gain else BtBadgeKind.Loss,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (showAsset) tx.assetSymbol else formatTxDate(tx.executedAtMs, locale),
                    style = MaterialTheme.typography.titleSmall,
                    color = bt.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                val amountPrice =
                    "${formatQuantity(tx.quantity, locale)} × ${formatEur(tx.price, locale)}"
                Text(
                    text = if (showAsset) {
                        formatTxDate(tx.executedAtMs, locale) + " · " + amountPrice
                    } else {
                        amountPrice
                    },
                    style = BtTheme.type.numberCaption,
                    color = bt.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(
                    value = transactionNotional(tx.quantity, tx.price),
                    style = BtTheme.type.moneySmall,
                )
                if (tx.fee > 0.0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.bt_tx_fee, formatEur(tx.fee, locale)),
                        style = BtTheme.type.numberCaption,
                        color = bt.textMuted,
                    )
                }
            }
        }
    }
}

// ── Sheet + fills ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssetFilterSheet(
    options: List<TxAssetOption>,
    selected: TxAssetOption?,
    onSelect: (TxAssetOption?) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bt.surface,
        contentColor = bt.textPrimary,
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "title") {
                Text(
                    text = stringResource(R.string.bt_tx_filter_asset_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = bt.textPrimary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            item(key = "all") {
                AssetFilterRow(
                    title = stringResource(R.string.bt_tx_filter_all_assets),
                    subtitle = null,
                    selected = selected == null,
                    onClick = { onSelect(null) },
                )
            }
            items(count = options.size, key = { options[it].assetId }) { index ->
                val option = options[index]
                AssetFilterRow(
                    title = option.symbol,
                    subtitle = option.name,
                    selected = selected?.assetId == option.assetId,
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun AssetFilterRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bt = BtTheme.colors
    BtCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                        color = bt.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = bt.gold,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyFill(content: @Composable () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { content() }
        }
    }
}

@Composable
private fun TransactionsSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(8) { BtSkeleton(Modifier.fillMaxWidth().height(64.dp)) }
    }
}
