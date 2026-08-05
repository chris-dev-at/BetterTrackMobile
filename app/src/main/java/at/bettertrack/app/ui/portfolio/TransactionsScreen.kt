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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
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
import at.bettertrack.app.data.db.TransactionEntity
import at.bettertrack.app.data.repo.PortfolioRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.sync.ConnectivityMonitor
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtChip
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtListSurface
import at.bettertrack.app.ui.components.BtOfflineState
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.MirrorAttributionChip
import at.bettertrack.app.ui.components.MoneyText
import at.bettertrack.app.ui.components.SourceBadge
import at.bettertrack.app.ui.components.formatEur
import at.bettertrack.app.ui.components.resolveListSurface
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.format.isBadgeWorthy
import at.bettertrack.app.ui.format.parseRowSource
import at.bettertrack.app.ui.shell.OfflineBanner
import at.bettertrack.app.ui.shell.RefreshFailedBanner
import at.bettertrack.app.ui.shell.RefreshNoticeState
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
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
 * Which single surface the ledger area shows.
 *
 * ## Why this exists next to [resolveListSurface]
 *
 * [resolveListSurface] owns the hard part and is not restated here: content
 * beats a failure, unknown beats empty, and "you have none" is claimed only by a
 * read that actually succeeded. This type adds the one thing that rule cannot
 * know — that this screen's content can be present but hidden by the type/asset
 * filters, which is a fifth situation with its own copy and its own way out
 * (clear the filters) — and it carries the failure's [BtMessage] so the error
 * surface can say what went wrong rather than shrug.
 *
 * The branch this replaces was the app's worst state lie: "nothing cached and
 * not refreshing" was ONE branch rendering "You have no transactions yet", so a
 * first fetch that dropped told an account with hundreds of entries that it had
 * none — and the ViewModel had thrown the `BtApiError` away before the screen
 * could have known better.
 */
sealed interface TxLedgerSurface {
    /** Nothing cached yet and the first fetch is still out. */
    data object Loading : TxLedgerSurface

    /** Nothing to show because the fetch FAILED — not because the ledger is empty. */
    data class Failed(val message: BtMessage) : TxLedgerSurface

    /** Nothing to show, and no connection to go and get it with. */
    data object Offline : TxLedgerSurface

    /** The ledger really is empty. The one case allowed to say so. */
    data object Empty : TxLedgerSurface

    /** Rows exist, but this filter combination matches none of them. */
    data object NoMatches : TxLedgerSurface

    /** Rows to render. */
    data object Ledger : TxLedgerSurface
}

/**
 * True when the surface being shown is real content — the only situation in
 * which the dismissible "couldn't refresh" strip belongs on screen. That banner
 * exists for *stale content*; over an error or an empty surface it would be a
 * second report of a failure the surface itself is already explaining.
 */
val TxLedgerSurface.showsContent: Boolean
    get() = this is TxLedgerSurface.Ledger || this is TxLedgerSurface.NoMatches

/** Everything [txLedgerSurface] needs, as plain values. */
data class TxLedgerState(
    /** Any transaction for this portfolio is in Room (BEFORE the display filters). */
    val hasAnyCached: Boolean = false,
    /** Any queued buy/sell survives the display filters. */
    val hasPendingRows: Boolean = false,
    /** Any synced row survives the display filters. */
    val hasVisibleRows: Boolean = false,
    /** The first refresh for the governing portfolio has come back, either way. */
    val firstLoadDone: Boolean = false,
    /**
     * A governing portfolio is known. Without one no fetch is ever sent, so
     * "not loaded yet" would otherwise mean a skeleton that never resolves.
     */
    val hasPortfolio: Boolean = true,
    val isOnline: Boolean = true,
    /** The message from the most recent failed fetch, or null if the last one landed. */
    val loadFailure: BtMessage? = null,
)

/**
 * Pick the ledger's surface: the shared rule, plus this screen's filters.
 *
 * Content is decided first because it is the one thing the shared rule cannot
 * see the shape of — "there are rows, but this filter matches none of them" is
 * content, not emptiness, and it wants the clear-filters action rather than the
 * "record your first buy" copy. Everything past that is [resolveListSurface]'s
 * call, translated back into a message-carrying verdict.
 */
fun txLedgerSurface(state: TxLedgerState): TxLedgerSurface {
    if (state.hasAnyCached || state.hasPendingRows) {
        return if (state.hasVisibleRows || state.hasPendingRows) {
            TxLedgerSurface.Ledger
        } else {
            TxLedgerSurface.NoMatches
        }
    }
    val shared = resolveListSurface(
        hasContent = false,
        firstLoadPending = state.hasPortfolio && !state.firstLoadDone,
        failed = state.loadFailure != null,
        isOnline = state.isOnline,
    )
    return when (shared) {
        BtListSurface.SKELETON -> TxLedgerSurface.Loading
        BtListSurface.OFFLINE -> TxLedgerSurface.Offline
        BtListSurface.ERROR -> TxLedgerSurface.Failed(state.loadFailure ?: BtMessage.generic)
        // CONTENT cannot come back from `hasContent = false`; it collapses into
        // the same "nothing to show and nothing went wrong" answer as EMPTY.
        BtListSurface.EMPTY, BtListSurface.CONTENT -> TxLedgerSurface.Empty
    }
}

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

    /**
     * S6 P0-5: a failed refresh/loadMore is SURFACED. The cached rows keep
     * rendering (they are real, just older), and an inline dismissible row says
     * so instead of the old `is BtResult.Err -> Unit`.
     */
    private val _refreshNotice = MutableStateFlow(RefreshNoticeState())
    val refreshNotice: StateFlow<RefreshNoticeState> = _refreshNotice.asStateFlow()

    /**
     * The app-owned message from the most recent failed fetch — null whenever the
     * last one landed.
     *
     * The ViewModel used to have no error channel at all: both failure sites did
     * `is BtResult.Err -> _refreshNotice.onFailure()` and dropped the error on
     * the floor. That was survivable while there was content to fall back on, and
     * a lie when there was not — see [txLedgerSurface]. A [BtMessage] rather than
     * a String, so the raw server sentence is not one assignment away (S6 P0-4).
     */
    private val _loadFailure = MutableStateFlow<BtMessage?>(null)
    val loadFailure: StateFlow<BtMessage?> = _loadFailure.asStateFlow()

    /**
     * False until the first refresh for the governing portfolio has come back —
     * successfully or not. Distinguishes "we have not looked yet" from "we looked
     * and there is nothing", which is the difference between a skeleton and the
     * app telling the user they own no transactions.
     */
    private val _firstLoadDone = MutableStateFlow(false)
    val firstLoadDone: StateFlow<Boolean> = _firstLoadDone.asStateFlow()

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
                is BtResult.Ok -> {
                    _nextCursor.value = r.value
                    _refreshNotice.value = _refreshNotice.value.onSuccess()
                    _loadFailure.value = null
                }
                // Cached rows stay — and the user is told they are cached. With
                // nothing cached the message is the whole surface, so it is kept
                // rather than reduced to a boolean.
                is BtResult.Err -> {
                    _refreshNotice.value = _refreshNotice.value.onFailure()
                    _loadFailure.value = r.asMessage()
                }
            }
            _refreshing.value = false
            _firstLoadDone.value = true
        }
    }

    /** The user dismissed the "couldn't refresh" row. */
    fun dismissRefreshNotice() {
        _refreshNotice.value = _refreshNotice.value.onDismiss()
    }

    fun loadMore() {
        val pid = portfolioId.value ?: return
        val cursor = _nextCursor.value ?: return
        if (_loadingMore.value || !isOnline.value) return
        viewModelScope.launch {
            _loadingMore.value = true
            when (val r = repo.loadMoreTransactions(pid, cursor)) {
                is BtResult.Ok -> {
                    _nextCursor.value = r.value
                    _refreshNotice.value = _refreshNotice.value.onSuccess()
                    _loadFailure.value = null
                }
                // A swallowed loadMore looked like "you have reached the end".
                is BtResult.Err -> {
                    _refreshNotice.value = _refreshNotice.value.onFailure()
                    _loadFailure.value = r.asMessage()
                }
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
    val refreshNotice by vm.refreshNotice.collectAsStateWithLifecycle()
    val loadingMore by vm.loadingMore.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()
    val isOnline by vm.isOnline.collectAsStateWithLifecycle()
    val portfolioName by vm.portfolioName.collectAsStateWithLifecycle()
    val portfolioId by vm.portfolioId.collectAsStateWithLifecycle()
    val loadFailure by vm.loadFailure.collectAsStateWithLifecycle()
    val firstLoadDone by vm.firstLoadDone.collectAsStateWithLifecycle()
    val dataAgeMs by AppGraph.portfolioRepository.portfolioDataAgeMs
        .collectAsStateWithLifecycle(initialValue = null)

    // One verdict, taken once, read by both the banner and the ledger area — so
    // the strip cannot contradict the surface underneath it.
    val surface = txLedgerSurface(
        TxLedgerState(
            hasAnyCached = hasAnyCached,
            hasPendingRows = pendingRows.isNotEmpty(),
            hasVisibleRows = transactions.isNotEmpty(),
            firstLoadDone = firstLoadDone,
            hasPortfolio = portfolioId != null,
            isOnline = isOnline,
            loadFailure = loadFailure,
        ),
    )

    var assetSheetOpen by rememberSaveable { mutableStateOf(false) }

    // R2: the two-line title becomes the header's title/subtitle — "transactions,
    // of THIS portfolio" is orienting information the reader needs on arrival and
    // not once they are deep in the ledger, which is exactly what the fading
    // subtitle line is for.
    val scrollBehavior = rememberBtCollapsingHeaderBehavior()
    Scaffold(
        // The scrollable that drives the collapse is several composables down
        // (inside the PullToRefreshBox's `else` branch), so the connection goes on
        // the Scaffold — the nearest thing that is an ancestor of BOTH the header
        // and every branch's content.
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = bt.bg,
        topBar = {
            BtCollapsingHeader(
                title = stringResource(R.string.bt_tx_title),
                // Empty rather than null while the name resolves — see CashScreen:
                // the flow's first emission is null, and a late subtitle would grow
                // the bar 112dp → 132dp a frame after the screen appears.
                subtitle = portfolioName ?: "",
                scrollBehavior = scrollBehavior,
                // No action, no overflow — both slots left null on purpose:
                // everything this screen does is either a row tap or one of the
                // filter chips below, and none of that belongs in the bar.
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
        // Everything in this Column is pinned by construction — only the LazyColumn
        // at the bottom scrolls, so the header collapses against the ledger while
        // the banners and, crucially, the filter row stay exactly where the user
        // left them. The filter row is this screen's control surface: a user who
        // has scrolled 200 rows into a filtered ledger must be able to change the
        // filter without scrolling back up to find it.
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            if (!isOnline) OfflineBanner(asOfMs = dataAgeMs, onClick = onOpenPendingSync)
            // S6 P0-5: online but the fetch failed — say so instead of leaving
            // the stale ledger looking freshly loaded. (Offline is already
            // covered by the banner above, so the row suppresses itself there.)
            // Only over CONTENT: with nothing to show, the surface below is
            // already a full error state with its own retry, and two reports of
            // one failure is noise, not honesty.
            if (surface.showsContent && refreshNotice.visible(isOnline)) {
                RefreshFailedBanner(
                    onDismiss = { vm.dismissRefreshNotice() },
                    onRetry = { vm.refresh() },
                )
            }

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
                when (surface) {
                    // Nothing cached yet and the first fetch is still out.
                    TxLedgerSurface.Loading -> TransactionsSkeleton()

                    // The fetch failed and there is nothing cached to fall back
                    // on. This used to render as "You have no transactions yet".
                    is TxLedgerSurface.Failed -> EmptyFill {
                        BtErrorState(
                            message = surface.message,
                            onRetry = { vm.refresh() },
                        )
                    }

                    TxLedgerSurface.Offline -> EmptyFill {
                        BtOfflineState(
                            message = stringResource(R.string.bt_tx_requires_connection),
                            onRetry = { vm.refresh() },
                        )
                    }

                    TxLedgerSurface.Empty -> EmptyFill {
                        BtEmptyState(
                            icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                            title = stringResource(R.string.bt_tx_empty_title),
                            message = stringResource(R.string.bt_tx_empty_message),
                        )
                    }

                    TxLedgerSurface.NoMatches -> EmptyFill {
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

                    TxLedgerSurface.Ledger -> {
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
    val locale = rememberBtLocale()
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (showAsset) tx.assetSymbol else formatTxDate(tx.executedAtMs, locale),
                        style = MaterialTheme.typography.titleSmall,
                        color = bt.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // v5 provenance — absent for the manual rows that dominate.
                    if (parseRowSource(tx.source).isBadgeWorthy()) {
                        Spacer(Modifier.width(8.dp))
                        SourceBadge(tx.source)
                    }
                }
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
                tx.mirror?.mirrorAddedByName?.let { who ->
                    Spacer(Modifier.height(2.dp))
                    MirrorAttributionChip(who)
                }
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
            // A ModalBottomSheet ships no content insets, so the 28dp below is a
            // content margin only — the last asset row would sit behind a 3-button
            // nav bar without this.
            modifier = Modifier.navigationBarsPadding(),
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
