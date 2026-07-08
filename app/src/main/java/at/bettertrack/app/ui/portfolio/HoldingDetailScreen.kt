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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.db.HoldingEntity
import at.bettertrack.app.data.db.TransactionEntity
import at.bettertrack.app.data.repo.PortfolioRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.sync.ConnectivityMonitor
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.MoneyColorMode
import at.bettertrack.app.ui.components.MoneyText
import at.bettertrack.app.ui.components.StatCard
import at.bettertrack.app.ui.components.formatEur
import at.bettertrack.app.ui.components.formatPercent
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
 * Holding detail (Step 7, spec §6.1): the position view — value, P/L, amount,
 * that asset's transactions — for one asset inside the governing portfolio.
 * All numbers are the server's (§7.1); renders offline from Room.
 * TODO(step 11): the link-out row below becomes the real asset-page entry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HoldingDetailViewModel(
    private val repo: PortfolioRepository,
    connectivity: ConnectivityMonitor,
    db: at.bettertrack.app.data.db.BtDatabase,
    json: kotlinx.serialization.json.Json,
    private val assetId: String,
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = connectivity.isOnline

    val portfolioId: StateFlow<String?> =
        combine(repo.portfolios, repo.selectedPortfolioId) { all, stored ->
            PortfolioOverviewViewModel.resolveSelection(all, stored)?.id
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val holding: StateFlow<HoldingEntity?> = portfolioId
        .flatMapLatest { pid -> if (pid == null) flowOf(emptyList()) else repo.holdings(pid) }
        .map { rows -> rows.firstOrNull { it.assetId == assetId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val transactions: StateFlow<List<TransactionEntity>> = portfolioId
        .flatMapLatest { pid ->
            if (pid == null) flowOf(emptyList()) else repo.transactionsForAsset(pid, assetId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Queued buy/sells of THIS asset (§7.4 — pending rows wherever they appear). */
    val pendingRows: StateFlow<List<PendingTxRow>> = combine(
        db.syncOpDao().observeAll(),
        portfolioId,
    ) { ops, pid ->
        if (pid == null) {
            emptyList()
        } else {
            decodePendingTxRows(ops, json, pid).filter { it.assetId == assetId }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private var refreshedOnce = false

    init {
        viewModelScope.launch {
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
            repo.refreshPortfolioDetail(pid)
            repo.refreshTransactions(pid)
            _refreshing.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoldingDetailScreen(
    assetId: String,
    onBack: () -> Unit,
    onNewTransaction: (portfolioId: String?, assetId: String) -> Unit,
    onEditSynced: (String) -> Unit,
    onEditQueued: (Long) -> Unit,
    onOpenPendingSync: () -> Unit,
    onOpenCustomAsset: (String) -> Unit,
    onOpenAssetPage: (String) -> Unit,
) {
    val vm: HoldingDetailViewModel = viewModel {
        HoldingDetailViewModel(
            AppGraph.portfolioRepository,
            AppGraph.connectivityMonitor,
            AppGraph.database,
            AppGraph.json,
            assetId,
        )
    }

    val bt = BtTheme.colors
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val holding by vm.holding.collectAsStateWithLifecycle()
    val transactions by vm.transactions.collectAsStateWithLifecycle()
    val pendingRows by vm.pendingRows.collectAsStateWithLifecycle()
    val portfolioId by vm.portfolioId.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val isOnline by vm.isOnline.collectAsStateWithLifecycle()
    val dataAgeMs by AppGraph.portfolioRepository.portfolioDataAgeMs
        .collectAsStateWithLifecycle(initialValue = null)

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = holding?.assetName ?: "",
                            style = MaterialTheme.typography.titleLarge,
                            color = bt.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        holding?.let {
                            Text(
                                text = listOfNotNull(it.assetSymbol, it.assetExchange)
                                    .joinToString(" · "),
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
                val h = holding
                when {
                    h != null -> HoldingContent(
                        holding = h,
                        transactions = transactions,
                        pendingRows = pendingRows,
                        locale = locale,
                        onEditSynced = onEditSynced,
                        onEditQueued = onEditQueued,
                        onOpenCustomAsset = onOpenCustomAsset,
                        onOpenAssetPage = onOpenAssetPage,
                    )

                    refreshing -> HoldingSkeleton()

                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            Box(
                                Modifier.fillParentMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                BtEmptyState(
                                    icon = Icons.Outlined.PieChart,
                                    title = stringResource(R.string.bt_holding_not_found_title),
                                    message = stringResource(R.string.bt_holding_not_found_message),
                                )
                            }
                        }
                    }
                }

                // Step 8 (§6.2): record a buy/sell of THIS asset in one tap —
                // the form opens pre-filled from the holding.
                if (holding != null) {
                    val fabCd = stringResource(R.string.bt_holding_fab_cd)
                    FloatingActionButton(
                        onClick = { onNewTransaction(portfolioId, assetId) },
                        containerColor = bt.gold,
                        contentColor = bt.onGold,
                        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(20.dp)
                            .semantics { contentDescription = fabCd },
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun HoldingContent(
    holding: HoldingEntity,
    transactions: List<TransactionEntity>,
    pendingRows: List<PendingTxRow>,
    locale: Locale,
    onEditSynced: (String) -> Unit,
    onEditQueued: (Long) -> Unit,
    onOpenCustomAsset: (String) -> Unit,
    onOpenAssetPage: (String) -> Unit,
) {
    val bt = BtTheme.colors
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // bottom clears the buy/sell FAB (56dp + 20dp inset + margin) so the last
        // transaction row scrolls fully into view instead of under it.
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Position hero (server-computed market value + day change).
        item(key = "hero") {
            Column {
                Text(
                    text = stringResource(R.string.bt_holding_position_value),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
                Spacer(Modifier.height(2.dp))
                if (holding.marketValueEur != null) {
                    MoneyText(value = holding.marketValueEur, style = BtTheme.type.moneyLarge)
                } else {
                    Text(
                        text = stringResource(R.string.bt_switcher_value_pending),
                        style = BtTheme.type.moneyLarge,
                        color = bt.textMuted,
                    )
                }
                val dayEur = holding.dayChangeEur
                if (dayEur != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MoneyText(
                            value = dayEur,
                            style = BtTheme.type.numberCaption,
                            colorMode = MoneyColorMode.GainLoss,
                            showSign = true,
                        )
                        holding.dayChangePct?.let { pct ->
                            Text(
                                text = " (${formatPercent(pct, locale)})",
                                style = BtTheme.type.numberCaption,
                                color = deltaColor(pct),
                            )
                        }
                        Text(
                            text = " · " + stringResource(R.string.bt_overview_today),
                            style = BtTheme.type.numberCaption,
                            color = bt.textMuted,
                        )
                    }
                }
            }
        }

        // Position stats (2×2, all server values).
        item(key = "stats") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(
                        label = stringResource(R.string.bt_holding_amount),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = "${formatQuantity(holding.quantity, locale)} ${holding.assetSymbol}",
                            style = BtTheme.type.moneyMedium,
                            color = bt.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    StatCard(
                        label = stringResource(R.string.bt_holding_avg_cost),
                        modifier = Modifier.weight(1f),
                    ) {
                        MoneyText(value = holding.avgCost, style = BtTheme.type.moneyMedium)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(
                        label = stringResource(R.string.bt_holding_pl),
                        modifier = Modifier.weight(1f),
                        deltaContent = holding.unrealizedPnlPct?.let { pct ->
                            {
                                Text(
                                    text = formatPercent(pct, locale),
                                    style = BtTheme.type.numberCaption,
                                    color = deltaColor(pct),
                                )
                            }
                        },
                    ) {
                        if (holding.unrealizedPnlEur != null) {
                            MoneyText(
                                value = holding.unrealizedPnlEur,
                                style = BtTheme.type.moneyMedium,
                                colorMode = MoneyColorMode.GainLoss,
                                showSign = true,
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.bt_switcher_value_pending),
                                style = BtTheme.type.moneyMedium,
                                color = bt.textMuted,
                            )
                        }
                    }
                    StatCard(
                        label = stringResource(R.string.bt_holding_day),
                        modifier = Modifier.weight(1f),
                        deltaContent = holding.dayChangePct?.let { pct ->
                            {
                                Text(
                                    text = formatPercent(pct, locale),
                                    style = BtTheme.type.numberCaption,
                                    color = deltaColor(pct),
                                )
                            }
                        },
                    ) {
                        if (holding.dayChangeEur != null) {
                            MoneyText(
                                value = holding.dayChangeEur,
                                style = BtTheme.type.moneyMedium,
                                colorMode = MoneyColorMode.GainLoss,
                                showSign = true,
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.bt_switcher_value_pending),
                                style = BtTheme.type.moneyMedium,
                                color = bt.textMuted,
                            )
                        }
                    }
                }
            }
        }

        // Custom holdings get the §6.4 "update value now" quick action opening
        // the custom-asset detail; market holdings link out to the §6.5 asset
        // page (Step 11).
        item(key = "asset-link") {
            if (holding.assetIsCustom) {
                BtCard(modifier = Modifier.fillMaxWidth(), onClick = { onOpenCustomAsset(holding.assetId) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ShowChart,
                            contentDescription = null,
                            tint = bt.gold,
                            modifier = Modifier.width(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.bt_custom_update_now),
                            style = MaterialTheme.typography.titleSmall,
                            color = bt.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = bt.textMuted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            } else {
                BtCard(modifier = Modifier.fillMaxWidth(), onClick = { onOpenAssetPage(holding.assetId) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = null,
                            tint = bt.gold,
                            modifier = Modifier.width(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.bt_holding_asset_page_link),
                            style = MaterialTheme.typography.titleSmall,
                            color = bt.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
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

        // That asset's transactions — queued entries first, clearly marked
        // (§7.1/§7.4), then the synced ledger rows (tap to edit, §6.2).
        item(key = "tx-header") {
            Text(
                text = stringResource(R.string.bt_holding_transactions_section),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        items(count = pendingRows.size, key = { "pending-" + pendingRows[it].opId }) { index ->
            PendingTransactionRow(
                row = pendingRows[index],
                showAsset = false,
                onEdit = { onEditQueued(it.opId) },
            )
        }
        if (transactions.isEmpty() && pendingRows.isEmpty()) {
            item(key = "tx-empty") {
                Text(
                    text = stringResource(R.string.bt_holding_no_tx),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }
        } else {
            items(count = transactions.size, key = { transactions[it].id }) { index ->
                val tx = transactions[index]
                TransactionRow(tx, showAsset = false, onClick = { onEditSynced(tx.id) })
            }
        }
    }
}

@Composable
private fun HoldingSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BtSkeleton(Modifier.width(220.dp).height(40.dp))
        BtSkeleton(Modifier.width(120.dp).height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BtSkeleton(Modifier.weight(1f).height(72.dp))
            BtSkeleton(Modifier.weight(1f).height(72.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BtSkeleton(Modifier.weight(1f).height(72.dp))
            BtSkeleton(Modifier.weight(1f).height(72.dp))
        }
        BtSkeleton(Modifier.fillMaxWidth().height(56.dp))
        BtSkeleton(Modifier.fillMaxWidth().height(56.dp))
    }
}
