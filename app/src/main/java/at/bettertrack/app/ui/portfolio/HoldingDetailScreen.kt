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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.db.HoldingEntity
import at.bettertrack.app.data.db.TransactionEntity
import at.bettertrack.app.data.repo.PortfolioRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.sync.ConnectivityMonitor
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtInlineEmpty
import at.bettertrack.app.ui.components.BtListSurface
import at.bettertrack.app.ui.components.BtOfflineState
import at.bettertrack.app.ui.components.BtScrollFill
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.BtStateFill
import at.bettertrack.app.ui.components.MoneyColorMode
import at.bettertrack.app.ui.components.MoneyText
import at.bettertrack.app.ui.components.StatCard
import at.bettertrack.app.ui.components.formatEur
import at.bettertrack.app.ui.components.formatPercent
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.components.resolveListSurface
import at.bettertrack.app.ui.shell.OfflineBanner
import at.bettertrack.app.ui.shell.btRefreshAttempt
import at.bettertrack.app.ui.shell.btRefreshTimedOutMessage
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import java.util.Locale
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

/**
 * Holding detail (Step 7, spec §6.1): the position view — value, P/L, amount,
 * that asset's transactions — for one asset inside the governing portfolio.
 * All numbers are the server's (§7.1); renders offline from Room.
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

    /**
     * The app-owned message from the most recent failed refresh, or null when the
     * last one landed.
     *
     * [refresh] used to call both endpoints and read neither result, so a dropped
     * first fetch left the screen showing "Holding not found" — the same lie the
     * transactions ledger told, on a position the user definitely owns.
     */
    private val _loadFailure = MutableStateFlow<BtMessage?>(null)
    val loadFailure: StateFlow<BtMessage?> = _loadFailure.asStateFlow()

    /** False until the first refresh has come back, either way. */
    private val _firstLoadDone = MutableStateFlow(false)
    val firstLoadDone: StateFlow<Boolean> = _firstLoadDone.asStateFlow()

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
            // Both calls feed this one screen, so the FIRST failure is the one
            // worth reporting: the position hero and its ledger are equally
            // missing either way, and two error surfaces for one dropped
            // connection would say nothing the first does not. They also share
            // ONE bounded attempt, so the pair cannot cost two timeouts.
            // A LIST, not a nullable message: [btRefreshAttempt] returns null for
            // the ceiling, so the block must never answer null itself or the two
            // would be the same value. An empty list is "both landed".
            val failures = btRefreshAttempt(_refreshing) {
                val detail = repo.refreshPortfolioDetail(pid)
                val transactions = repo.refreshTransactions(pid)
                listOfNotNull(
                    (detail as? BtResult.Err)?.asMessage(),
                    (transactions as? BtResult.Err)?.asMessage(),
                )
            }
            _loadFailure.value =
                if (failures == null) btRefreshTimedOutMessage() else failures.firstOrNull()
            _firstLoadDone.value = true
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

    // W6 — manual price entry. Drive-only: it is the one mode whose valuation
    // reads `price_cache`, so it is the only mode where a typed price changes
    // anything on screen.
    val manualPricesAvailable = at.bettertrack.app.ui.prices.manualEntryAvailable(
        AppGraph.gatedStorageMode(AppGraph.storageModeStore.mode.collectAsStateWithLifecycle().value),
    )
    val priceVm: at.bettertrack.app.ui.prices.ManualPriceViewModel = viewModel(key = "price-$assetId") {
        at.bettertrack.app.ui.prices.ManualPriceViewModel(
            assetId = assetId,
            store = AppGraph.manualPriceStore,
            recompute = { AppGraph.recomputeAfterPriceChange() },
        )
    }
    val manualPricePoints by priceVm.points.collectAsStateWithLifecycle()
    val priceSheetOpen by priceVm.sheetOpen.collectAsStateWithLifecycle()
    val priceBusy by priceVm.busy.collectAsStateWithLifecycle()

    val bt = BtTheme.colors
    val locale = rememberBtLocale()
    val holding by vm.holding.collectAsStateWithLifecycle()
    val transactions by vm.transactions.collectAsStateWithLifecycle()
    val pendingRows by vm.pendingRows.collectAsStateWithLifecycle()
    val portfolioId by vm.portfolioId.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val loadFailure by vm.loadFailure.collectAsStateWithLifecycle()
    val firstLoadDone by vm.firstLoadDone.collectAsStateWithLifecycle()
    val isOnline by vm.isOnline.collectAsStateWithLifecycle()
    val dataAgeMs by AppGraph.portfolioRepository.portfolioDataAgeMs
        .collectAsStateWithLifecycle(initialValue = null)

    // R2: the asset's name over "symbol · exchange" was the two-line bar title and
    // is now the header's title/subtitle. The name is user/exchange data of
    // arbitrary length — BtCollapsingHeader ellipsizes it to one line rather than
    // letting a long name change the bar's height.
    val scrollBehavior = rememberBtCollapsingHeaderBehavior()
    Scaffold(
        // The scrolling list lives inside HoldingContent, two composables down.
        // nestedScroll propagates from any ancestor, so hanging it here reaches
        // that list without HoldingContent having to know a header exists.
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = bt.bg,
        topBar = {
            BtCollapsingHeader(
                title = holding?.assetName ?: "",
                // Resolved to a (possibly empty) String rather than left null
                // while `holding` is: the DB flow starts null, and a subtitle that
                // arrived a frame later would grow the bar from 112dp to 132dp
                // under content the reader has already started on.
                subtitle = holding
                    ?.let { listOfNotNull(it.assetSymbol, it.assetExchange).joinToString(" · ") }
                    ?: "",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.bt_action_back),
                            tint = bt.textSecondary,
                        )
                    }
                },
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
                        color = bt.goldInk,
                    )
                },
            ) {
                val h = holding
                val failure = loadFailure
                // "We have not looked yet" is not "there is nothing here": until
                // the first fetch comes back the screen owes the reader a
                // skeleton, not a verdict about a position they just tapped.
                val surface = resolveListSurface(
                    hasContent = h != null,
                    firstLoadPending = portfolioId != null && !firstLoadDone,
                    failed = failure != null,
                    isOnline = isOnline,
                )
                when {
                    h != null -> HoldingContent(
                        holding = h,
                        transactions = transactions,
                        pendingRows = pendingRows,
                        locale = locale,
                        manualPricesAvailable = manualPricesAvailable,
                        manualPricePoints = manualPricePoints,
                        onAddPrice = { priceVm.openSheet() },
                        onEditSynced = onEditSynced,
                        onEditQueued = onEditQueued,
                        onOpenCustomAsset = onOpenCustomAsset,
                        onOpenAssetPage = onOpenAssetPage,
                    )

                    surface == BtListSurface.SKELETON -> HoldingSkeleton()

                    surface == BtListSurface.OFFLINE -> BtStateFill {
                        BtOfflineState(
                            message = stringResource(R.string.bt_holding_requires_connection),
                            onRetry = { vm.refresh() },
                        )
                    }

                    surface == BtListSurface.ERROR -> BtStateFill {
                        BtErrorState(
                            message = failure ?: BtMessage.generic,
                            onRetry = { vm.refresh() },
                        )
                    }

                    // A fetch came back and this asset was not in it: the
                    // position really is gone. The only branch entitled to say so.
                    else -> BtStateFill {
                        BtEmptyState(
                            icon = Icons.Outlined.PieChart,
                            title = stringResource(R.string.bt_holding_not_found_title),
                            message = stringResource(R.string.bt_holding_not_found_message),
                        )
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

    if (priceSheetOpen && manualPricesAvailable) {
        val h = holding
        at.bettertrack.app.ui.prices.ManualPriceSheet(
            assetSymbol = h?.assetSymbol ?: assetId,
            assetId = assetId,
            // The currency the projector will actually value this asset in. Not
            // a default the user may freely override into a silent mis-valuation
            // — see ManualPriceError.NO_RATE.
            valuationCurrency = h?.assetCurrency ?: "EUR",
            points = manualPricePoints,
            busy = priceBusy,
            locale = locale,
            onSubmit = { date, value, currency ->
                priceVm.record(date, value, currency, h?.assetCurrency ?: "EUR")
            },
            onDelete = { priceVm.delete(it) },
            onDismiss = { priceVm.closeSheet() },
        )
    }
}

@Composable
private fun HoldingContent(
    holding: HoldingEntity,
    transactions: List<TransactionEntity>,
    pendingRows: List<PendingTxRow>,
    locale: Locale,
    manualPricesAvailable: Boolean,
    manualPricePoints: List<at.bettertrack.app.data.storage.ManualPricePoint>,
    onAddPrice: () -> Unit,
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
                } else if (manualPricesAvailable) {
                    // W6: in Drive mode a missing value has a cause the user can
                    // act on, so it says so instead of showing a bare dash.
                    Text(
                        text = stringResource(R.string.bt_price_none_hint),
                        style = BtTheme.type.moneyMedium,
                        color = bt.textMuted,
                    )
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

        // W6 — the manual price book for this asset (Drive mode only).
        //
        // Placed directly under the hero because it is the *cause* of whatever
        // the hero just said: either the price that produced the value, or the
        // absence that produced the empty state. Putting it further down would
        // make the user hunt for the explanation of the number they are looking at.
        if (manualPricesAvailable) {
            item(key = "manual-price") {
                val latest = manualPricePoints.maxByOrNull { it.dateIso }
                BtCard(modifier = Modifier.fillMaxWidth(), onClick = onAddPrice) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.bt_price_history_title),
                                style = MaterialTheme.typography.bodySmall,
                                color = bt.textMuted,
                            )
                            Spacer(Modifier.height(3.dp))
                            at.bettertrack.app.ui.prices.AssetPriceLine(
                                state = at.bettertrack.app.ui.prices.assetPriceState(
                                    mode = at.bettertrack.app.data.storage.StorageMode.DRIVE,
                                    livePrice = null,
                                    liveCurrency = null,
                                    liveAsOfIso = null,
                                    manualPrice = latest?.close,
                                    manualCurrency = latest?.currency,
                                    manualAsOfIso = latest?.dateIso,
                                ),
                                locale = locale,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(
                                if (latest == null) R.string.bt_price_add else R.string.bt_price_update,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = bt.goldInk,
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
                            tint = bt.goldInk,
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
                            tint = bt.goldInk,
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
                // BtInlineEmpty, not BtEmptyState: this is the last of five
                // sections on a scrolling position page, and a 64dp badge with
                // 32dp of padding here would out-weigh the hero it sits under.
                BtInlineEmpty(text = stringResource(R.string.bt_holding_no_tx))
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
    // BtScrollFill, not a bare Column: the sheet's pull-down dismiss is taken
    // from the content's nested scroll, so a skeleton that does not scroll is a
    // screen the reader cannot back out of by pulling.
    BtScrollFill {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
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
}
