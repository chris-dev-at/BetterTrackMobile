package at.bettertrack.app.ui.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.db.BtDatabase
import at.bettertrack.app.data.db.HoldingEntity
import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.data.repo.HistoryRange
import at.bettertrack.app.data.repo.PortfolioHistory
import at.bettertrack.app.data.repo.PortfolioRepository
import at.bettertrack.app.sync.ConnectivityMonitor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import kotlinx.serialization.json.Json

/**
 * State holder for the Portfolio tab (Step 6): resolves the selected portfolio
 * (persisted choice → default → first active), exposes the Room-first flows the
 * overview renders, and orchestrates the network→Room refreshes (on start, on
 * focus, on pull-to-refresh, on switch). Switcher management actions are
 * online-only (§7.2) and guarded here.
 *
 * Every number rendered from this state is server-computed (§7.1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PortfolioOverviewViewModel(
    private val repo: PortfolioRepository,
    connectivity: ConnectivityMonitor,
    db: BtDatabase,
    json: Json,
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = connectivity.isOnline

    /** All portfolios, active and archived (the switcher shows both). */
    val portfolios: StateFlow<List<PortfolioEntity>> = repo.portfolios
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The governing selection (§6.1): the persisted choice while it exists and
     * is active, else the platform default, else the first active portfolio.
     */
    val selected: StateFlow<PortfolioEntity?> =
        combine(repo.portfolios, repo.selectedPortfolioId) { all, storedId ->
            resolveSelection(all, storedId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** True once the portfolio scope has synced at least once (skeleton gate). */
    val hasEverSynced: StateFlow<Boolean> = repo.portfolioDataAgeMs
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _range = MutableStateFlow(HistoryRange.DEFAULT)
    val range: StateFlow<HistoryRange> = _range.asStateFlow()

    val holdings: StateFlow<List<HoldingEntity>> = selected
        .flatMapLatest { p -> if (p == null) flowOf(emptyList()) else repo.holdings(p.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val history: StateFlow<PortfolioHistory?> =
        combine(selected, _range) { p, r -> p?.id to r }
            .flatMapLatest { (pid, r) -> if (pid == null) flowOf(null) else repo.history(pid, r) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Open queued buy/sells of the selected portfolio (§7.1: pending entries
     * are listed alongside, clearly marked — never folded into the totals).
     * Feeds the overview's pending strip → Pending-sync screen.
     */
    val pendingTx: StateFlow<List<PendingTxRow>> = combine(
        db.syncOpDao().observeAll(),
        selected,
    ) { ops, sel ->
        if (sel == null) emptyList() else decodePendingTxRows(ops, json, sel.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Last refresh error — the screen surfaces it only when nothing is cached. */
    private val _loadError = MutableStateFlow<BtApiError?>(null)
    val loadError: StateFlow<BtApiError?> = _loadError.asStateFlow()

    // Switcher action state (create/rename/archive/restore).
    private val _switcherBusy = MutableStateFlow(false)
    val switcherBusy: StateFlow<Boolean> = _switcherBusy.asStateFlow()
    private val _switcherError = MutableStateFlow<String?>(null)
    val switcherError: StateFlow<String?> = _switcherError.asStateFlow()

    /**
     * Whether the switcher sheet is open. Hoisted into the VM (not local screen
     * state) so the shared top-bar selector — which lives in the app shell,
     * outside this screen's composition — can open the same sheet the overview
     * hosts. The overview observes this and renders the sheet.
     */
    private val _switcherVisible = MutableStateFlow(false)
    val switcherVisible: StateFlow<Boolean> = _switcherVisible.asStateFlow()

    fun openSwitcher() { _switcherVisible.value = true }

    fun dismissSwitcher() {
        _switcherVisible.value = false
        _switcherError.value = null
    }

    private var lastRefreshAtMs = 0L

    init {
        refresh()
    }

    // ── Refresh orchestration ───────────────────────────────────────────────

    /** Full refresh: list + the selected portfolio's detail/graph/ledger/cash. */
    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            val result = repo.refreshPortfolios()
            if (result is BtResult.Err) {
                _loadError.value = result.error
            } else {
                _loadError.value = null
                lastRefreshAtMs = System.currentTimeMillis()
            }
            // Resolve the governing portfolio from a ONE-SHOT read, not the
            // `selected` StateFlow: right after the list write, that
            // WhileSubscribed flow may not have recomputed yet, so reading it
            // here races to null on a fresh login and the dependent cascade
            // (detail/holdings/history/cash) never fires — the reported "stuck
            // on skeletons until pull-to-refresh" bug. Persist the auto-pick so
            // the selection sticks and every screen agrees on the default.
            val chosen = repo.defaultSelection()
            if (chosen != null) {
                if (repo.selectedPortfolioIdNow() != chosen.id) repo.selectPortfolio(chosen.id)
                refreshSelectedScope(chosen.id)
            }
            _refreshing.value = false
        }
    }

    /** Refetch-on-focus (§6.13), throttled so tab hops stay quiet. */
    fun onScreenResumed() {
        if (!isOnline.value) return
        if (System.currentTimeMillis() - lastRefreshAtMs < FOCUS_REFRESH_MIN_INTERVAL_MS) return
        refresh()
    }

    fun setRange(range: HistoryRange) {
        if (_range.value == range) return
        _range.value = range
        // The cached series shows instantly; refetch it quietly when online.
        val pid = selected.value?.id ?: return
        if (isOnline.value) viewModelScope.launch { repo.refreshHistory(pid, range) }
    }

    fun selectPortfolio(portfolioId: String) {
        viewModelScope.launch {
            repo.selectPortfolio(portfolioId)
            if (isOnline.value) refreshSelectedScope(portfolioId)
        }
    }

    /** Everything the overview + offline cache need for one portfolio. */
    private suspend fun refreshSelectedScope(portfolioId: String) = coroutineScope {
        // Parallel: independent endpoints; Room flows update as each lands.
        val detail = async { repo.refreshPortfolioDetail(portfolioId) }
        val graph = async { repo.refreshHistory(portfolioId, _range.value) }
        // Ledger page 1 + cash keep the §7.2 offline cache warm even if the
        // user never opens those screens before going offline.
        val ledger = async { repo.refreshTransactions(portfolioId) }
        val cash = async { repo.refreshCash(portfolioId) }
        val results = listOf(detail.await(), graph.await(), ledger.await(), cash.await())
        results.filterIsInstance<BtResult.Err>().firstOrNull()?.let { err ->
            if (_loadError.value == null) _loadError.value = err.error
        }
    }

    // ── Switcher management (online-only, §7.2) ─────────────────────────────

    fun createPortfolio(name: String, onDone: (Boolean) -> Unit = {}) =
        switcherAction(onDone) { repo.createPortfolio(name.trim()) }

    fun renamePortfolio(portfolioId: String, name: String, onDone: (Boolean) -> Unit = {}) =
        switcherAction(onDone) { repo.renamePortfolio(portfolioId, name.trim()) }

    fun archivePortfolio(portfolioId: String, onDone: (Boolean) -> Unit = {}) =
        switcherAction(onDone) {
            val r = repo.archivePortfolio(portfolioId)
            if (r is BtResult.Ok && repo.selectedPortfolioIdNow() == portfolioId) {
                // Archiving the governing portfolio: hand selection to the
                // default / first active so the overview never shows an
                // archived portfolio.
                resolveSelection(portfolios.value.filter { it.id != portfolioId }, null)
                    ?.let { repo.selectPortfolio(it.id) }
            }
            r
        }

    fun restorePortfolio(portfolioId: String, onDone: (Boolean) -> Unit = {}) =
        switcherAction(onDone) { repo.restorePortfolio(portfolioId) }

    /**
     * Hard-delete a portfolio (platform #412). Reports a rich [PortfolioDeleteResult]
     * so the type-to-confirm dialog can surface the LAST_ACTIVE_PORTFOLIO case
     * inline (dialog stays open) versus closing on success. The repository already
     * purged the cache + re-pulled the list; if the deleted portfolio was the
     * current one we re-resolve selection to the server-promoted default.
     */
    fun deletePortfolio(portfolioId: String, onResult: (PortfolioDeleteResult) -> Unit = {}) {
        if (_switcherBusy.value) return
        viewModelScope.launch {
            _switcherBusy.value = true
            _switcherError.value = null
            val r = repo.deletePortfolio(portfolioId)
            if (r is BtResult.Ok && repo.selectedPortfolioIdNow() == portfolioId) {
                repo.defaultSelection()?.let { repo.selectPortfolio(it.id) }
            }
            _switcherBusy.value = false
            onResult(portfolioDeleteResult(r))
        }
    }

    fun clearSwitcherError() {
        _switcherError.value = null
    }

    private fun switcherAction(
        onDone: (Boolean) -> Unit,
        action: suspend () -> BtResult<*>,
    ) {
        if (_switcherBusy.value) return
        viewModelScope.launch {
            _switcherBusy.value = true
            _switcherError.value = null
            val result = action()
            if (result is BtResult.Err) _switcherError.value = result.error.userMessage
            _switcherBusy.value = false
            onDone(result is BtResult.Ok)
        }
    }

    companion object {
        private const val FOCUS_REFRESH_MIN_INTERVAL_MS = 60_000L

        /**
         * Selection rule (§6.1) — delegates to the repository's canonical rule so
         * the overview, the initial-load path and the transaction form all agree
         * on which portfolio governs.
         */
        fun resolveSelection(
            all: List<PortfolioEntity>,
            storedId: String?,
        ): PortfolioEntity? = PortfolioRepository.resolveSelection(all, storedId)
    }
}
