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
import androidx.compose.foundation.Canvas
import at.bettertrack.app.data.repo.AssetRange
import at.bettertrack.app.data.repo.MarketRepository
import at.bettertrack.app.data.repo.PricePoint
import at.bettertrack.app.ui.charts.BtPriceChart
import at.bettertrack.app.ui.charts.ChartMarker
import at.bettertrack.app.ui.charts.rangeLabel
import at.bettertrack.app.ui.components.BtRangeSegmented
import at.bettertrack.app.ui.market.formatPrice
import at.bettertrack.app.ui.market.rangePerformancePct
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Path
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
import at.bettertrack.app.ui.shell.BtSheetRefreshBox
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
 * The position's price chart — the surface the buy/sell markers hang on.
 *
 * [Unavailable] is a real answer and not a failure: a custom asset has no market
 * series to draw, and this mode may have no live prices at all. The screen
 * renders nothing in that case rather than an empty frame apologising.
 */
sealed interface HoldingChartState {
    data object Loading : HoldingChartState
    data class Loaded(val points: List<PricePoint>) : HoldingChartState
    data object Unavailable : HoldingChartState
}

/**
 * Holding detail (Step 7, spec §6.1): the position view — value, P/L, amount,
 * that asset's transactions — for one asset inside the governing portfolio.
 * All numbers are the server's (§7.1); renders offline from Room.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HoldingDetailViewModel(
    private val repo: PortfolioRepository,
    private val market: MarketRepository,
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

    // ── The price chart behind the buy/sell markers ─────────────────────────

    private val _chartRange = MutableStateFlow(HOLDING_CHART_DEFAULT_RANGE)
    val chartRange: StateFlow<AssetRange> = _chartRange.asStateFlow()

    private val _chart = MutableStateFlow<HoldingChartState>(HoldingChartState.Loading)
    val chart: StateFlow<HoldingChartState> = _chart.asStateFlow()

    init {
        viewModelScope.launch {
            portfolioId.collect { pid ->
                if (pid != null && !refreshedOnce) {
                    refreshedOnce = true
                    refresh()
                }
            }
        }
        viewModelScope.launch {
            // Gated on the HOLDING rather than fired blind: a custom asset has no
            // market history, and asking for it anyway would be a request whose
            // only possible outcome is an error we already know the answer to.
            val h = holding.filterNotNull().first()
            if (h.assetIsCustom) _chart.value = HoldingChartState.Unavailable
            else loadChart(_chartRange.value)
        }
    }

    fun setChartRange(range: AssetRange) {
        if (range == _chartRange.value) return
        _chartRange.value = range
        loadChart(range)
    }

    private fun loadChart(range: AssetRange) {
        viewModelScope.launch {
            _chart.value = HoldingChartState.Loading
            _chart.value = when (val r = market.assetHistory(assetId, range)) {
                is BtResult.Ok ->
                    if (r.value.points.size < 2) HoldingChartState.Unavailable
                    else HoldingChartState.Loaded(r.value.points)

                // Every failure lands on Unavailable on purpose. This chart is a
                // bonus on a page whose real content (value, P/L, ledger) renders
                // from Room offline; a retry button for it would be the third
                // error affordance on one screen.
                is BtResult.Err -> HoldingChartState.Unavailable
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
            AppGraph.marketRepository,
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
    val chart by vm.chart.collectAsStateWithLifecycle()
    val chartRange by vm.chartRange.collectAsStateWithLifecycle()
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

            BtSheetRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize(),
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
                        chart = chart,
                        chartRange = chartRange,
                        onChartRange = { vm.setChartRange(it) },
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
    chart: HoldingChartState,
    chartRange: AssetRange,
    onChartRange: (AssetRange) -> Unit,
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
                // Captured into a local so the null-guard narrows the type: since
                // the KMP port, HoldingEntity lives in :shared and Kotlin will not
                // smart-cast a public `val` across a module boundary.
                val marketValueEur = holding.marketValueEur
                if (marketValueEur != null) {
                    MoneyText(value = marketValueEur, style = BtTheme.type.moneyLarge)
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

        // The timing chart (owner order 2026-08-10): the asset's price with THIS
        // position's buys and sells marked on it, so "how well did I time this"
        // is a picture rather than an arithmetic exercise over the ledger below.
        if (chart !is HoldingChartState.Unavailable) {
            item(key = "timing-chart") {
                HoldingTimingChart(
                    state = chart,
                    range = chartRange,
                    onRange = onChartRange,
                    transactions = transactions,
                    currency = holding.assetCurrency,
                    locale = locale,
                )
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
                        val unrealizedPnlEur = holding.unrealizedPnlEur
                        if (unrealizedPnlEur != null) {
                            MoneyText(
                                value = unrealizedPnlEur,
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
                        val dayChangeEur = holding.dayChangeEur
                        if (dayChangeEur != null) {
                            MoneyText(
                                value = dayChangeEur,
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

/**
 * The position's price curve with its own buys and sells marked on it.
 *
 * **Owner order 2026-08-10:** *"make a nice graph that shows you where you bought
 * at what time and where you sold so you see how good you hit the sell and buy
 * timings."*
 *
 * ## The two honesty rules this section is built around
 *
 * 1. **A marker's y is the price the user actually paid**, not the curve's close
 *    at that instant. The gap between the glyph and the line IS the content — a
 *    fill above the line is a worse entry than the day's close, and flattening
 *    the mark onto the curve would erase exactly the thing being asked about.
 *    The two are commensurable because they are the same unit: the transaction
 *    price is entered in (and auto-filled from) the asset's own quote currency,
 *    which is what `assetHistory` returns.
 * 2. **Trades outside the plotted window are not drawn at all** — see
 *    [placeMarkers]. They are COUNTED instead, in a line under the chart, so a
 *    1M window over a two-year position says "9 outside this range" rather than
 *    quietly implying the position began last month.
 */
@Composable
private fun HoldingTimingChart(
    state: HoldingChartState,
    range: AssetRange,
    onRange: (AssetRange) -> Unit,
    transactions: List<TransactionEntity>,
    currency: String,
    locale: Locale,
) {
    val bt = BtTheme.colors
    val markers = remember(transactions) { holdingChartMarkers(transactions) }
    var scrub by remember { mutableStateOf<PricePoint?>(null) }
    var focused by remember { mutableStateOf<List<ChartMarker>>(emptyList()) }
    LaunchedEffect(range, state) {
        scrub = null
        focused = emptyList()
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.bt_holding_timing_section),
            style = MaterialTheme.typography.titleMedium,
            color = bt.textPrimary,
        )
        Spacer(Modifier.height(10.dp))
        when (state) {
            HoldingChartState.Loading ->
                BtSkeleton(Modifier.fillMaxWidth().height(HOLDING_CHART_HEIGHT))

            // Never rendered — the caller drops the whole item — but a `when`
            // over a sealed type says so out loud rather than by omission.
            HoldingChartState.Unavailable -> Unit

            is HoldingChartState.Loaded -> {
                val points = state.points
                val cd = stringResource(R.string.bt_holding_timing_cd)
                BtPriceChart(
                    points = points,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(HOLDING_CHART_HEIGHT)
                        .semantics { contentDescription = cd },
                    lineColor = rangeAccent(rangePerformancePct(points)),
                    minimal = true,
                    markers = markers,
                    scrimColor = bt.bg,
                    onScrub = { scrub = it },
                    onMarkerFocus = { focused = it },
                )
                Spacer(Modifier.height(10.dp))
                HoldingTimingReadout(
                    focused = focused,
                    scrub = scrub,
                    markers = markers,
                    points = points,
                    currency = currency,
                    locale = locale,
                )
                Spacer(Modifier.height(12.dp))
                BtRangeSegmented(
                    options = AssetRange.entries,
                    selected = range,
                    label = { rangeLabel(it) },
                    onSelect = onRange,
                    modifier = Modifier.fillMaxWidth(),
                    contentDescription = stringResource(R.string.bt_chart_range_cd),
                )
            }
        }
    }
}

/**
 * The line under the timing chart. It has three jobs and does exactly one at a
 * time, because they answer the same question at different levels of zoom:
 * the trade under the finger, else the price under the finger, else what the
 * glyphs mean and how many trades the window is leaving out.
 */
@Composable
private fun HoldingTimingReadout(
    focused: List<ChartMarker>,
    scrub: PricePoint?,
    markers: List<ChartMarker>,
    points: List<PricePoint>,
    currency: String,
    locale: Locale,
) {
    val bt = BtTheme.colors
    when {
        focused.isNotEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            focused.take(HOLDING_FOCUS_ROWS).forEach { marker ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MarkerSwatch(marker.kind)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            if (marker.kind == ChartMarker.Kind.BUY) R.string.bt_tx_side_buy
                            else R.string.bt_tx_side_sell,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textSecondary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = listOfNotNull(
                            marker.quantity?.let { formatQuantity(it, locale) },
                            formatPrice(marker.price, currency, locale),
                            holdingMarkerDate(marker.timeMs, locale),
                        ).joinToString(" · "),
                        style = BtTheme.type.numberCaption,
                        color = bt.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        scrub != null -> Text(
            text = formatPrice(scrub.close, currency, locale) + " · " +
                holdingMarkerDate(scrub.timeMs, locale),
            style = BtTheme.type.numberCaption,
            color = bt.textPrimary,
        )

        else -> {
            val from = points.first().timeMs
            val to = points.last().timeMs
            val inRange = markers.count { it.timeMs in from..to }
            val outside = markers.size - inRange
            // Nothing in the window is ONE sentence, not a legend for glyphs that
            // are not there plus a right-aligned footnote colliding with it.
            if (inRange == 0) {
                Text(
                    text = listOfNotNull(
                        stringResource(R.string.bt_holding_timing_empty),
                        outside.takeIf { it > 0 }
                            ?.let { stringResource(R.string.bt_holding_timing_outside, it) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
                return
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                run {
                    MarkerSwatch(ChartMarker.Kind.BUY)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.bt_holding_timing_buys),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                    )
                    Spacer(Modifier.width(14.dp))
                    MarkerSwatch(ChartMarker.Kind.SELL)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.bt_holding_timing_sells),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                    )
                }
                if (outside > 0) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.bt_holding_timing_outside, outside),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** The legend's glyph — the chart's own triangle, drawn at legend size. */
@Composable
private fun MarkerSwatch(kind: ChartMarker.Kind) {
    val bt = BtTheme.colors
    val color = if (kind == ChartMarker.Kind.BUY) bt.gain else bt.loss
    Canvas(Modifier.size(9.dp)) {
        val up = kind == ChartMarker.Kind.BUY
        val path = Path().apply {
            if (up) {
                moveTo(size.width / 2f, 0f)
                lineTo(0f, size.height)
                lineTo(size.width, size.height)
            } else {
                moveTo(size.width / 2f, size.height)
                lineTo(0f, 0f)
                lineTo(size.width, 0f)
            }
            close()
        }
        drawPath(path, color)
    }
}

/**
 * The ledger as chart markers.
 *
 * Rows whose `executedAtMs` never parsed (0L) are dropped: a trade with no
 * timestamp has no x, and the marker's whole claim is about *when*.
 */
internal fun holdingChartMarkers(transactions: List<TransactionEntity>): List<ChartMarker> =
    transactions.mapNotNull { tx ->
        if (tx.executedAtMs <= 0L || !tx.price.isFinite()) return@mapNotNull null
        ChartMarker(
            timeMs = tx.executedAtMs,
            price = tx.price,
            kind = if (tx.side.equals("sell", ignoreCase = true)) {
                ChartMarker.Kind.SELL
            } else {
                ChartMarker.Kind.BUY
            },
            quantity = tx.quantity,
            id = tx.id,
        )
    }

private fun holdingMarkerDate(timeMs: Long, locale: Locale): String =
    java.time.Instant.ofEpochMilli(timeMs)
        .atZone(java.time.ZoneId.systemDefault())
        .format(
            java.time.format.DateTimeFormatter
                .ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
                .withLocale(locale),
        )

/**
 * The timing chart's default window.
 *
 * A year, not the asset page's month: this chart exists to show entries and
 * exits, and most positions were opened further back than four weeks. The 1M
 * default would have opened on a chart with no marks on it for the majority of
 * holdings — which reads as a broken feature rather than as a short window.
 */
private val HOLDING_CHART_DEFAULT_RANGE = AssetRange.Y1

/** Shorter than the page heroes: this is a section, not the page's subject. */
private val HOLDING_CHART_HEIGHT = 200.dp

/** How many trades one slot's readout names before it stops. */
private const val HOLDING_FOCUS_ROWS = 3

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
