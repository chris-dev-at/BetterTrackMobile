package at.bettertrack.app.ui.cash

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.SouthWest
import androidx.compose.material.icons.outlined.SwapHoriz
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtErrorCopy
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.api.dto.CashBudgetProgressDto
import at.bettertrack.app.data.cash.CashClassificationRepository
import at.bettertrack.app.data.cash.decodeTagIds
import at.bettertrack.app.data.db.BtDatabase
import at.bettertrack.app.data.db.CashMovementEntity
import at.bettertrack.app.data.db.CashSourceEntity
import at.bettertrack.app.data.db.CashTagEntity
import at.bettertrack.app.data.db.SyncOpEntity
import at.bettertrack.app.data.repo.PortfolioRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.sync.CashOpPayload
import at.bettertrack.app.sync.CashTransferOpPayload
import at.bettertrack.app.sync.ConnectivityMonitor
import at.bettertrack.app.sync.OpStatus
import at.bettertrack.app.sync.OpType
import at.bettertrack.app.sync.SyncEngine
import at.bettertrack.app.sync.SyncScheduler
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtChip
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtGroupRow
import at.bettertrack.app.ui.components.BtSectionHeader
import at.bettertrack.app.ui.components.BtDateField
import at.bettertrack.app.ui.components.BtDatePickerDialog
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtFormError
import at.bettertrack.app.ui.components.BtInlineEmpty
import at.bettertrack.app.ui.components.BtInlineError
import at.bettertrack.app.ui.components.BtListSurface
import at.bettertrack.app.ui.components.BtOfflineState
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.MirrorAttributionChip
import at.bettertrack.app.ui.components.MoneyColorMode
import at.bettertrack.app.ui.components.MoneyText
import at.bettertrack.app.ui.components.SourceBadge
import at.bettertrack.app.ui.components.formatEur
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.components.rememberParkReason
import at.bettertrack.app.ui.components.resolveListSurface
import at.bettertrack.app.ui.components.resolveWithDiagnostic
import at.bettertrack.app.ui.format.isBadgeWorthy
import at.bettertrack.app.ui.format.parseRowSource
import at.bettertrack.app.ui.portfolio.PendingStatusBadge
import at.bettertrack.app.ui.portfolio.PendingUiStatus
import at.bettertrack.app.ui.portfolio.PortfolioOverviewViewModel
import at.bettertrack.app.ui.portfolio.formatTxDate
import at.bettertrack.app.ui.portfolio.parseLocalizedDecimal
import at.bettertrack.app.ui.portfolio.sanitizeDecimalInput
import at.bettertrack.app.ui.shell.OfflineBanner
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Surface
import at.bettertrack.app.ui.components.rememberBtHaptics
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
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
 * The Step-9 cash screen (spec §6.3): Main + named sources with per-source
 * balances and typed labels, the movement stream (filterable per source),
 * deposit / withdraw / transfer with live balance-after previews (cached
 * balances — the server stays final, §7.3), and source management
 * (create / rename / archive — online-only per §7.2). All three movement
 * types enqueue through the M3 queue and work offline.
 */

/**
 * A refused correction, surfaced as a designed dialog rather than a toast.
 *
 * [notEditable] separates the ONE refusal that is not the user's mistake — the
 * row is derived from a parent (trade, dividend, tax settlement, transfer) and
 * must be corrected there — from ordinary failures like an insufficient balance.
 */
data class CashCorrectionNotice(val notEditable: Boolean, val message: BtMessage)

/** Which sheet is open. */
/**
 * Debounce before asking the server what its rules would tag a note as.
 *
 * Long enough that typing "Groceries" is one request rather than nine, short
 * enough that the chips feel like they answer the keystroke. The call is a read
 * and fail-silent, so the cost of being slightly wrong here is invisible.
 */
private const val PREVIEW_DEBOUNCE_MS = 350L

/**
 * How many months the trend chart asks for (server allows 1..24).
 *
 * Six is what fits legibly across a ~360dp phone as paired bars with readable
 * month labels; asking for more and cropping would just mean paying for data the
 * chart cannot show.
 */
private const val TREND_MONTHS = 6

/**
 * The budgets block's three honest states. A budget list is a network read that
 * can legitimately be empty, so "no budgets" and "couldn't load" must never
 * collapse into the same blank area.
 */
sealed interface BudgetsUi {
    data object Loading : BudgetsUi
    data class Ready(val rows: List<CashBudgetProgressDto>) : BudgetsUi

    /**
     * [message] rather than a payload-less marker: the block used to answer
     * every refusal with the same fixed line and throw the server's actual
     * reason away. See [CashSummaryUi.Failed] for the full argument.
     */
    data class Failed(val message: BtMessage) : BudgetsUi
}

/**
 * Whether the ledger's first read is still unanswered — the `firstLoadPending`
 * input [resolveListSurface] needs to tell "we have not asked yet" from "we
 * asked and there is nothing".
 *
 * The screen reads movements from Room, and an empty table cannot say WHY it is
 * empty. Until now that ambiguity was resolved by always picking the kinder
 * reading and rendering "No cash movements yet" — so a portfolio whose very
 * first fetch was refused was told, in the app's calmest voice, that its money
 * had no history. That is the same conflation R3 found behind the eternal
 * shimmer on this screen, one branch further down.
 *
 * Two things end the wait, and the second one is why this is a function rather
 * than a bare `!loaded`:
 *
 *  · [loaded] — the first refresh finished, either way.
 *  · [hasPortfolio] false once [sourcesSeen] — there is no portfolio to read, so
 *    no request will ever be sent, so nothing will ever set [loaded]. A flag only
 *    the request could clear would leave the list shimmering forever, which is
 *    exactly the trap [CashViewModel.sourcesLoaded] was added for. `sourcesSeen`
 *    is that same signal reused: the sources flow emits for a null portfolio too,
 *    so it answers "the local reads have run" without claiming anything about the
 *    network.
 */
fun cashLedgerPending(loaded: Boolean, hasPortfolio: Boolean, sourcesSeen: Boolean): Boolean =
    !loaded && (hasPortfolio || !sourcesSeen)

private sealed interface CashSheet {
    /** Create (or edit a queued) deposit / withdrawal / fee. */
    data class Entry(val kind: CashKind, val editOpId: Long? = null) : CashSheet
    data class Transfer(val editOpId: Long? = null) : CashSheet
    /** Edit an already-SYNCED movement via the v5 correction endpoints. */
    data class EditSynced(val movementId: String) : CashSheet
}

@OptIn(ExperimentalCoroutinesApi::class)
class CashViewModel(
    private val repo: PortfolioRepository,
    connectivity: ConnectivityMonitor,
    private val db: BtDatabase,
    private val engine: SyncEngine,
    private val scheduler: SyncScheduler,
    private val json: Json,
    routePortfolioId: String?,
    /**
     * V5 S2c cash-classification layer (tags / budgets / rules). Server-only —
     * it has no Drive equivalent yet, so it deliberately does NOT ride the
     * storage-mode seam that [repo] goes through.
     */
    private val classification: CashClassificationRepository,
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = connectivity.isOnline

    val portfolioId: StateFlow<String?> =
        combine(repo.portfolios, repo.selectedPortfolioId) { all, stored ->
            routePortfolioId ?: PortfolioOverviewViewModel.resolveSelection(all, stored)?.id
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), routePortfolioId)

    val portfolioName: StateFlow<String?> =
        combine(repo.portfolios, portfolioId) { all, pid ->
            all.firstOrNull { it.id == pid }?.name
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Server-computed cash roll-up of the portfolio (§7.1). */
    val totalCashEur: StateFlow<Double?> =
        combine(repo.portfolios, portfolioId) { all, pid ->
            all.firstOrNull { it.id == pid }?.totals?.cashEur
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * R3 §2: true once [sources] has emitted at least once.
     *
     * `sources` is a Room flow seeded with `emptyList()`, so "empty" meant two
     * different things — *not loaded yet* and *there are none* — and the screen
     * rendered a shimmer for both. A portfolio with no named cash sources
     * therefore shimmered forever: a loading state that can never resolve, which
     * is the worst kind because the user waits for something that will not come.
     * `PortfolioSwitcherSheet` documents this exact trap and guards against it;
     * this is the same guard.
     */
    private val _sourcesLoaded = MutableStateFlow(false)
    val sourcesLoaded: StateFlow<Boolean> = _sourcesLoaded.asStateFlow()

    val sources: StateFlow<List<CashSourceEntity>> = portfolioId
        .flatMapLatest { pid -> if (pid == null) flowOf(emptyList()) else repo.cashSources(pid) }
        .onEach { _sourcesLoaded.value = true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Movement-list source filter; null = all sources. */
    private val _sourceFilter = MutableStateFlow<String?>(null)
    val sourceFilter: StateFlow<String?> = _sourceFilter.asStateFlow()

    val movements: StateFlow<List<CashMovementEntity>> = combine(
        portfolioId.flatMapLatest { pid ->
            if (pid == null) flowOf(emptyList()) else repo.cashMovements(pid)
        },
        _sourceFilter,
    ) { rows, filter ->
        if (filter == null) rows else rows.filter { it.sourceId == filter }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Queued cash ops of this portfolio (§7.4 pending rows). */
    val pendingRows: StateFlow<List<PendingCashRow>> = combine(
        db.syncOpDao().observeAll(),
        portfolioId,
    ) { ops, pid ->
        if (pid == null) emptyList() else decodePendingCashRows(ops, json, pid)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /**
     * Why the last cash refresh failed, or null when it succeeded / never ran.
     *
     * Only `refreshCash` feeds this. `refreshPortfolioDetail` populates the hero
     * total, and a failure there must not make the movement list below claim it
     * could not load — the two reads answer different questions.
     */
    private val _ledgerError = MutableStateFlow<BtMessage?>(null)
    val ledgerError: StateFlow<BtMessage?> = _ledgerError.asStateFlow()

    /**
     * True once a refresh has finished, whichever way it went.
     *
     * Room answers "no rows" instantly and identically for a cold cache and an
     * empty ledger, so without this the screen cannot tell "we have not asked
     * yet" from "we asked and there is nothing" — see [cashLedgerPending].
     */
    private val _ledgerLoaded = MutableStateFlow(false)
    val ledgerLoaded: StateFlow<Boolean> = _ledgerLoaded.asStateFlow()

    /** Busy/error state of the online-only source-management actions. */
    private val _manageBusy = MutableStateFlow(false)
    val manageBusy: StateFlow<Boolean> = _manageBusy.asStateFlow()
    private val _manageError = MutableStateFlow<BtMessage?>(null)
    val manageError: StateFlow<BtMessage?> = _manageError.asStateFlow()

    /** Sheet submission state. */
    private val _submitting = MutableStateFlow(false)
    val submitting: StateFlow<Boolean> = _submitting.asStateFlow()
    private val _sheetError = MutableStateFlow<BtMessage?>(null)
    val sheetError: StateFlow<BtMessage?> = _sheetError.asStateFlow()

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
            val cash = repo.refreshCash(pid)
            // Deliberately not folded into the ledger's error: this read feeds
            // the hero total, and its failure says nothing about the movements.
            repo.refreshPortfolioDetail(pid)
            _ledgerError.value = if (cash is BtResult.Err) cash.error.asMessage() else null
            _ledgerLoaded.value = true
            _refreshing.value = false
        }
    }

    fun setSourceFilter(sourceId: String?) {
        _sourceFilter.value = if (_sourceFilter.value == sourceId) null else sourceId
    }

    fun clearSheetError() {
        _sheetError.value = null
    }

    fun clearManageError() {
        _manageError.value = null
    }

    // ── Source management (online-only, §7.2) ───────────────────────────────

    fun createSource(name: String, type: String, onDone: (Boolean) -> Unit) =
        manageAction(onDone) { repo.createCashSource(portfolioId.value ?: return@manageAction null, name.trim(), type) }

    fun renameSource(sourceId: String, name: String, type: String, onDone: (Boolean) -> Unit) =
        manageAction(onDone) {
            repo.updateCashSource(portfolioId.value ?: return@manageAction null, sourceId, name.trim(), type)
        }

    fun archiveSource(sourceId: String, onDone: (Boolean) -> Unit) =
        manageAction(onDone) { repo.archiveCashSource(portfolioId.value ?: return@manageAction null, sourceId) }

    fun restoreSource(sourceId: String, onDone: (Boolean) -> Unit) =
        manageAction(onDone) { repo.restoreCashSource(portfolioId.value ?: return@manageAction null, sourceId) }

    private fun manageAction(onDone: (Boolean) -> Unit, action: suspend () -> BtResult<Unit>?) {
        if (_manageBusy.value) return
        viewModelScope.launch {
            _manageBusy.value = true
            _manageError.value = null
            val r = action()
            if (r is BtResult.Err) _manageError.value = r.error.asMessage()
            _manageBusy.value = false
            onDone(r is BtResult.Ok)
        }
    }

    // ── Corrections on SYNCED movements (v5, online-only) ───────────────────
    // Deliberately NOT queued. A correction is defined relative to a row that
    // exists server-side, and the server re-checks solvency by replaying the
    // whole ledger — so an offline correction could only be validated on
    // arrival, long after the user walked away from the screen that could
    // explain the refusal. Same call as the transaction editor makes.

    private val _correctionBusy = MutableStateFlow(false)
    val correctionBusy: StateFlow<Boolean> = _correctionBusy.asStateFlow()

    private val _correctionNotice = MutableStateFlow<CashCorrectionNotice?>(null)
    val correctionNotice: StateFlow<CashCorrectionNotice?> = _correctionNotice.asStateFlow()

    /**
     * One stable Idempotency-Key per movement id, minted on first use and reused
     * across retries so a retry after a lost 200 replays the server's stored
     * response instead of 404-ing on the already-deleted row.
     */
    private val deleteKeys = mutableMapOf<String, String>()

    fun clearCorrectionNotice() {
        _correctionNotice.value = null
    }

    // ── V5 S2c: cash classification (tags + live rule preview) ───────────────

    /**
     * The user's tag catalog, keyed by id for O(1) row lookups.
     *
     * Read from Room, not from the network: it is joined against EVERY ledger row
     * on every recomposition, so it has to be a local, offline-capable read. The
     * movement DTO carries tag IDS ONLY (verified on the wire — the census's
     * "tags[] with systemKey" describes the tag resource, not the movement), so
     * without this map a tagged row could render nothing but UUIDs.
     */
    val tagsById: StateFlow<Map<String, CashTagEntity>> = classification.observeTags()
        .map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _tagBusy = MutableStateFlow(false)
    val tagBusy: StateFlow<Boolean> = _tagBusy.asStateFlow()

    /** Tag ids the user's rules WOULD apply to the note being typed. */
    private val _previewTagIds = MutableStateFlow<List<String>>(emptyList())
    val previewTagIds: StateFlow<List<String>> = _previewTagIds.asStateFlow()

    private var previewJob: Job? = null

    init {
        // Warm the catalog once per screen open. Fail-soft: an offline open still
        // renders whatever Room already holds.
        viewModelScope.launch { classification.refreshTags() }
    }

    /**
     * Ask the server what its rules would tag [note] as, debounced.
     *
     * Deliberately a round trip rather than a client-side matcher: the platform
     * runs patterns through RE2 precisely so a pathological regex cannot stall
     * anything, and a second implementation here would disagree with the server
     * the first time a user wrote one. Fail-silent by contract — this is a
     * decoration on a form that must stay usable offline, so an error clears the
     * chips rather than surfacing anything.
     */
    fun previewNote(note: String) {
        previewJob?.cancel()
        val trimmed = note.trim()
        if (trimmed.isEmpty()) {
            _previewTagIds.value = emptyList()
            return
        }
        previewJob = viewModelScope.launch {
            delay(PREVIEW_DEBOUNCE_MS)
            _previewTagIds.value = when (val r = classification.previewRules(trimmed)) {
                is BtResult.Ok -> r.value
                is BtResult.Err -> emptyList()
            }
        }
    }

    /** Drop any pending preview (sheet closed / submitted). */
    fun clearPreview() {
        previewJob?.cancel()
        _previewTagIds.value = emptyList()
    }

    // ── V5 S2c: budgets ──────────────────────────────────────────────────────

    /** The month the budgets block is showing; also the summary's bucket. */
    private val _budgetMonth = MutableStateFlow(YearMonth.now())
    val budgetMonth: StateFlow<YearMonth> = _budgetMonth.asStateFlow()

    private val _budgets = MutableStateFlow<BudgetsUi>(BudgetsUi.Loading)
    val budgets: StateFlow<BudgetsUi> = _budgets.asStateFlow()

    private var budgetJob: Job? = null

    fun stepBudgetMonth(delta: Long) {
        _budgetMonth.value = _budgetMonth.value.plusMonths(delta)
        loadBudgets()
        loadSummary()
    }

    /**
     * Load the selected month's budgets for the selected portfolio.
     *
     * Network-only by design: a budget is an evaluated projection of the month's
     * movements, not a stored figure, so a cached copy would go stale the moment
     * anything is booked. The block therefore carries its own explicit
     * loading/empty/error states rather than pretending to be offline data.
     */
    fun loadBudgets() {
        val pid = portfolioId.value ?: return
        val month = wireMonth(_budgetMonth.value)
        budgetJob?.cancel()
        budgetJob = viewModelScope.launch {
            _budgets.value = BudgetsUi.Loading
            _budgets.value = when (val r = classification.budgets(pid, month)) {
                is BtResult.Ok -> BudgetsUi.Ready(r.value.budgets)
                is BtResult.Err -> BudgetsUi.Failed(r.asMessage())
            }
        }
    }

    fun deleteBudget(id: String) {
        viewModelScope.launch {
            if (classification.deleteBudget(id) is BtResult.Ok) loadBudgets()
        }
    }

    fun createBudget(tagId: String, amount: Double, recurring: Boolean, onDone: (Boolean) -> Unit) {
        val pid = portfolioId.value ?: return onDone(false)
        viewModelScope.launch {
            val period = if (recurring) null else wireMonth(_budgetMonth.value)
            val ok = classification.createBudget(pid, tagId, amount, period) is BtResult.Ok
            if (ok) loadBudgets()
            onDone(ok)
        }
    }

    fun updateBudget(id: String, amount: Double, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = classification.updateBudgetAmount(id, amount) is BtResult.Ok
            if (ok) loadBudgets()
            onDone(ok)
        }
    }

    // ── V5 S2c: month summary + inflow/outflow trends ────────────────────────
    //
    // Both are server-computed analytics over the same movements the ledger
    // shows, and — like budgets — they are evaluated, not stored, so they are
    // network-only with their own loading/empty/error states. The summary shares
    // the budgets' month stepper (one month control governs the whole block);
    // the trend window is fixed and portfolio-keyed, so it survives stepping.

    private val _summary = MutableStateFlow<CashSummaryUi>(CashSummaryUi.Loading)
    val summary: StateFlow<CashSummaryUi> = _summary.asStateFlow()

    private val _trends = MutableStateFlow<CashTrendsUi>(CashTrendsUi.Loading)
    val trends: StateFlow<CashTrendsUi> = _trends.asStateFlow()

    private var summaryJob: Job? = null
    private var trendsJob: Job? = null

    fun loadSummary() {
        val pid = portfolioId.value ?: return
        val month = wireMonth(_budgetMonth.value)
        summaryJob?.cancel()
        summaryJob = viewModelScope.launch {
            _summary.value = CashSummaryUi.Loading
            _summary.value = when (val r = classification.summary(pid, month)) {
                is BtResult.Ok -> CashSummaryUi.Ready(r.value)
                is BtResult.Err -> CashSummaryUi.Failed(r.asMessage())
            }
        }
    }

    fun loadTrends() {
        val pid = portfolioId.value ?: return
        trendsJob?.cancel()
        trendsJob = viewModelScope.launch {
            _trends.value = CashTrendsUi.Loading
            _trends.value = when (val r = classification.trends(pid, TREND_MONTHS)) {
                is BtResult.Ok -> CashTrendsUi.Ready(r.value.points)
                is BtResult.Err -> CashTrendsUi.Failed(r.asMessage())
            }
        }
    }

    /**
     * Replace a movement's whole tag set. The repository writes the accepted ids
     * straight back into the local row, so the chips repaint without a refetch.
     */
    fun setMovementTags(movementId: String, tagIds: List<String>, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            _tagBusy.value = true
            val ok = when (classification.setMovementTags(movementId, tagIds)) {
                is BtResult.Ok -> true
                is BtResult.Err -> false
            }
            _tagBusy.value = false
            onDone(ok)
        }
    }

    /** PATCH a synced movement. [onDone] true closes the sheet. */
    fun submitCorrection(movementId: String, intent: CashEditIntent, onDone: (Boolean) -> Unit) {
        val pid = portfolioId.value ?: return
        val patch = buildCashMovementPatch(intent)
        if (patch == null) {
            // Nothing actually changed — an empty body is a 400, so don't send one.
            onDone(true)
            return
        }
        runCorrection(onDone) {
            // Fresh key per submission: the edit is field-absolute, so a resend of
            // the SAME body replays, while a corrected retry is a new request.
            repo.updateCashMovement(pid, movementId, patch, java.util.UUID.randomUUID().toString())
        }
    }

    fun deleteCorrection(movementId: String, onDone: (Boolean) -> Unit) {
        val pid = portfolioId.value ?: return
        val key = deleteKeys.getOrPut(movementId) { java.util.UUID.randomUUID().toString() }
        runCorrection(onDone) { repo.deleteCashMovement(pid, movementId, key) }
    }

    private fun runCorrection(onDone: (Boolean) -> Unit, action: suspend () -> BtResult<Unit>) {
        if (_correctionBusy.value) return
        viewModelScope.launch {
            _correctionBusy.value = true
            _correctionNotice.value = null
            val r = action()
            if (r is BtResult.Err) {
                _correctionNotice.value = CashCorrectionNotice(
                    // The server folds four "why not" cases (trade / dividend /
                    // transfer / other) behind this one code, and only its English
                    // sentence named which. The dialog now answers with the app's
                    // translated copy plus its own hint — a specific sentence in a
                    // language the user does not read is not the better trade.
                    notEditable = r.error.code == at.bettertrack.app.data.api.BtApiError.Codes
                        .CASH_MOVEMENT_NOT_EDITABLE,
                    message = r.error.asMessage(),
                )
            }
            _correctionBusy.value = false
            onDone(r is BtResult.Ok)
        }
    }

    // ── Movement writes (offline-capable via the queue, §7.2) ────────────────

    /**
     * Deposit / withdraw. [editOpId] rebinds a queued op in place (same client
     * UUID — §7.3 edit-and-retry). Returns via [onDone]: true = sheet closes.
     */
    fun submitEntry(
        kind: CashKind,
        amount: Double,
        sourceId: String?,
        note: String?,
        /** Chosen movement date; today omits `executedAt`, a past day backdates it. */
        date: LocalDate,
        editOpId: Long?,
        onDone: (Boolean) -> Unit,
    ) {
        val pid = portfolioId.value ?: return
        if (_submitting.value) return
        viewModelScope.launch {
            _submitting.value = true
            _sheetError.value = null
            val payload = CashOpPayload(
                amountEur = amount,
                executedAt = cashExecutedAtOrNull(date),
                note = note?.trim()?.takeIf { it.isNotEmpty() },
                sourceId = sourceId,
            )
            val payloadJson = json.encodeToString(CashOpPayload.serializer(), payload)
            val type = when (kind) {
                CashKind.DEPOSIT -> OpType.CASH_DEPOSIT
                CashKind.FEE -> OpType.CASH_FEE
                else -> OpType.CASH_WITHDRAW
            }
            submitViaQueue(pid, type, payloadJson, editOpId, onDone)
            _submitting.value = false
        }
    }

    /** Transfer between two sources (§6.3), queued like every ledger event. */
    fun submitTransfer(
        fromSourceId: String,
        toSourceId: String,
        amount: Double,
        note: String?,
        editOpId: Long?,
        onDone: (Boolean) -> Unit,
    ) {
        val pid = portfolioId.value ?: return
        if (_submitting.value) return
        viewModelScope.launch {
            _submitting.value = true
            _sheetError.value = null
            val payload = CashTransferOpPayload(
                fromSourceId = fromSourceId,
                toSourceId = toSourceId,
                amountEur = amount,
                executedAt = cashExecutedAtNow(),
                note = note?.trim()?.takeIf { it.isNotEmpty() },
            )
            val payloadJson = json.encodeToString(CashTransferOpPayload.serializer(), payload)
            submitViaQueue(pid, OpType.CASH_TRANSFER, payloadJson, editOpId, onDone)
            _submitting.value = false
        }
    }

    private suspend fun submitViaQueue(
        pid: String,
        type: OpType,
        payloadJson: String,
        editOpId: Long?,
        onDone: (Boolean) -> Unit,
    ) {
        val opId = if (editOpId == null) {
            engine.enqueue(type, pid, payloadJson).id
        } else {
            if (!engine.updateOp(editOpId, payloadJson)) {
                onDone(true) // resolved meanwhile — nothing to edit
                return
            }
            editOpId
        }
        if (isOnline.value) {
            try {
                engine.drain()
            } catch (_: Exception) {
                // stays queued; WorkManager resumes
            }
        }
        val after = db.syncOpDao().getById(opId)
        when (after?.status) {
            OpStatus.NEEDS_ATTENTION.wire -> {
                _sheetError.value = after.rejectionMessage()
                onDone(false)
            }

            else -> {
                if (after?.status == OpStatus.PENDING.wire || after?.status == OpStatus.IN_FLIGHT.wire) {
                    scheduler.scheduleDrain()
                }
                onDone(true)
            }
        }
    }

    /** Prefill for editing a queued cash op (pending / needs-attention). */
    suspend fun loadPendingRow(opId: Long): PendingCashRow? =
        db.syncOpDao().getById(opId)?.let { decodePendingCashRow(it, json) }
}

/**
 * Why the queue parked the op the sheet just submitted, as app-owned copy.
 *
 * The row stores a CODE since DB v10, so the sentence is resolved from resources
 * here rather than replayed from whatever English the server sent. A row parked
 * before that migration has no code: its prose rides along as the diagnostic so
 * the sheet still says something true, just untranslated.
 */
private fun SyncOpEntity.rejectionMessage(): BtMessage = BtMessage(
    res = BtErrorCopy.resFor(errorCode) ?: R.string.bt_err_app_rejected,
    diagnostic = if (errorCode == null) serverError else null,
)

// ═════════════════════════════════ UI ═══════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashScreen(
    routePortfolioId: String?,
    editOpId: Long?,
    onBack: () -> Unit,
    onOpenPendingSync: () -> Unit,
    onOpenTags: () -> Unit = {},
    onOpenRules: () -> Unit = {},
    onOpenStandingOrders: () -> Unit = {},
) {
    val vm: CashViewModel = viewModel {
        CashViewModel(
            repo = AppGraph.portfolioRepository,
            connectivity = AppGraph.connectivityMonitor,
            db = AppGraph.database,
            engine = AppGraph.syncEngine,
            scheduler = AppGraph.syncScheduler,
            json = AppGraph.json,
            routePortfolioId = routePortfolioId,
            classification = AppGraph.cashClassificationRepository,
        )
    }

    val bt = BtTheme.colors
    val locale = rememberBtLocale()
    val isOnline by vm.isOnline.collectAsStateWithLifecycle()
    val portfolioName by vm.portfolioName.collectAsStateWithLifecycle()
    val totalCashEur by vm.totalCashEur.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val sourceFilter by vm.sourceFilter.collectAsStateWithLifecycle()
    val movements by vm.movements.collectAsStateWithLifecycle()
    val pendingRows by vm.pendingRows.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val sourcesLoaded by vm.sourcesLoaded.collectAsStateWithLifecycle()
    val ledgerError by vm.ledgerError.collectAsStateWithLifecycle()
    val ledgerLoaded by vm.ledgerLoaded.collectAsStateWithLifecycle()
    val manageBusy by vm.manageBusy.collectAsStateWithLifecycle()
    val manageError by vm.manageError.collectAsStateWithLifecycle()
    val dataAgeMs by AppGraph.portfolioRepository.portfolioDataAgeMs
        .collectAsStateWithLifecycle(initialValue = null)

    var sheet by remember { mutableStateOf<CashSheet?>(null) }
    var newSourceOpen by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<CashSourceEntity?>(null) }
    var archiveTarget by remember { mutableStateOf<CashSourceEntity?>(null) }
    /** Prefill when editing a queued op. */
    var editPrefill by remember { mutableStateOf<PendingCashRow?>(null) }
    /** The synced movement awaiting delete confirmation. */
    var deleteTarget by remember { mutableStateOf<CashMovementEntity?>(null) }
    val correctionBusy by vm.correctionBusy.collectAsStateWithLifecycle()
    val correctionNotice by vm.correctionNotice.collectAsStateWithLifecycle()
    val tagsById by vm.tagsById.collectAsStateWithLifecycle()
    /** The synced movement whose tag set is being edited. */
    var tagTarget by remember { mutableStateOf<CashMovementEntity?>(null) }
    val budgets by vm.budgets.collectAsStateWithLifecycle()
    val budgetMonth by vm.budgetMonth.collectAsStateWithLifecycle()
    var newBudgetOpen by remember { mutableStateOf(false) }
    var budgetTarget by remember { mutableStateOf<CashBudgetProgressDto?>(null) }
    val summary by vm.summary.collectAsStateWithLifecycle()
    val trends by vm.trends.collectAsStateWithLifecycle()

    // The budgets block is a network read keyed on (portfolio, month), so it
    // reloads when the resolved portfolio arrives or changes — not just once.
    // The summary and trend analytics are the same kind of read and load with it.
    val resolvedPid by vm.portfolioId.collectAsStateWithLifecycle()
    LaunchedEffect(resolvedPid) {
        if (resolvedPid != null) {
            vm.loadBudgets()
            vm.loadSummary()
            vm.loadTrends()
        }
    }

    val active = activeSources(sources)
    val archived = sources.filter { it.archivedAt != null }
    val sourceNames = sources.associate { it.id to it.name }

    // A source filter can empty the visible list all by itself, and a failed
    // fetch must not be blamed for a view the user narrowed on purpose.
    val ledgerFailure = ledgerError.takeIf { sourceFilter == null }
    val ledgerSurface = resolveListSurface(
        hasContent = movements.isNotEmpty() || pendingRows.isNotEmpty(),
        firstLoadPending = cashLedgerPending(
            loaded = ledgerLoaded,
            hasPortfolio = resolvedPid != null,
            sourcesSeen = sourcesLoaded,
        ),
        failed = ledgerFailure != null,
        isOnline = isOnline,
    )

    // Entry via the pending screen's "Edit & retry" (deep-linked edit).
    LaunchedEffect(editOpId) {
        if (editOpId != null) {
            val row = vm.loadPendingRow(editOpId)
            if (row != null && (row.status == PendingUiStatus.PENDING || row.status == PendingUiStatus.NEEDS_ATTENTION)) {
                editPrefill = row
                sheet = when (row.type) {
                    OpType.CASH_TRANSFER -> CashSheet.Transfer(editOpId)
                    OpType.CASH_WITHDRAW -> CashSheet.Entry(CashKind.WITHDRAWAL, editOpId)
                    OpType.CASH_FEE -> CashSheet.Entry(CashKind.FEE, editOpId)
                    else -> CashSheet.Entry(CashKind.DEPOSIT, editOpId)
                }
            }
        }
    }

    // R2: the two-line bar title was always one claim — "cash, of THIS portfolio"
    // — made once on arrival. It is now the header's title/subtitle pair, so the
    // portfolio name gets real size while the user is orienting and gives the
    // space back the moment they start reading movements.
    val scrollBehavior = rememberBtCollapsingHeaderBehavior()
    Scaffold(
        // The header collapses against the LazyColumn far below, which is not its
        // descendant in the layout tree — hanging the connection on the Scaffold
        // itself is what puts it on a common ancestor of both.
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = bt.bg,
        topBar = {
            BtCollapsingHeader(
                title = stringResource(R.string.bt_cash_title),
                // Empty rather than null while the name resolves: `portfolioName`
                // is null for the first composition (the flow starts on
                // subscription), and a subtitle that appears a frame later would
                // grow the bar from 112dp to 132dp right under the reader's eyes.
                subtitle = portfolioName ?: "",
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
                // ── No ⋮ here any more (nav restoration 2026-08-06) ──────────
                //
                // V5 S2c put tags, auto-tag rules and standing orders behind one
                // overflow on the reasoning that they are per-account management
                // screens rather than per-visit actions, and should not add
                // permanent chrome to a screen whose job is recording money. The
                // reasoning about chrome was right; the conclusion was wrong.
                //
                // These three were the ONLY entries in the whole app's five
                // top-bar menus with no in-content second path — three entire
                // screens whose sole door was a glyph that names nothing. Audited
                // during the dissolution: `onOpenTags`/`onOpenRules`/
                // `onOpenStandingOrders` had exactly one call site each, this
                // menu. So they are not dissolved into an existing path, they are
                // PROMOTED to one — the doorways group at the foot of the
                // movements list, which is the pattern People and Overview
                // already use and which costs the top bar nothing.
            )
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            // The banner stays in the content Column rather than riding inside the
            // header: it is chrome about the whole screen's freshness, not part of
            // the screen's identity, so it belongs BELOW the bar and must remain
            // legible after the title has collapsed away. Sitting here it is
            // pinned — it never scrolls under the header — while the list below it
            // is what drives the collapse.
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
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Total cash (server roll-up of all sources, §7.1).
                    item(key = "hero") {
                        Column {
                            Text(
                                text = stringResource(R.string.bt_cash_total),
                                style = MaterialTheme.typography.bodySmall,
                                color = bt.textMuted,
                            )
                            Spacer(Modifier.height(2.dp))
                            if (totalCashEur != null) {
                                MoneyText(value = totalCashEur!!, style = BtTheme.type.moneyLarge)
                            } else {
                                BtSkeleton(Modifier.width(180.dp).height(36.dp))
                            }
                        }
                    }

                    // Deposit · Withdraw / Fee · Transfer (§6.3 + v5 fee).
                    // Two rows rather than one row of four: at four-up the labels
                    // ellipsize on a narrow phone, and pairing them keeps the two
                    // money-in/out actions visually apart from the two "other" ones.
                    //
                    // S6 P1-15: the four used to be four IDENTICAL outlined buttons,
                    // which is the design saying "these are equally likely" about a
                    // set where they plainly are not. Deposit is the action people
                    // come to this screen for, so it is the one filled primary; the
                    // other three stay outlined secondaries.
                    item(key = "actions") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BtPrimaryButton(
                                    text = stringResource(R.string.bt_cash_deposit),
                                    onClick = {
                                        editPrefill = null
                                        sheet = CashSheet.Entry(CashKind.DEPOSIT)
                                    },
                                    modifier = Modifier.weight(1f).height(44.dp),
                                )
                                BtSecondaryButton(
                                    text = stringResource(R.string.bt_cash_withdraw),
                                    onClick = {
                                        editPrefill = null
                                        sheet = CashSheet.Entry(CashKind.WITHDRAWAL)
                                    },
                                    modifier = Modifier.weight(1f).height(44.dp),
                                )
                            }
                            // No Fee button any more (web parity, owner
                            // 2026-08-07). A fee is not a third destination, it
                            // is a PROPERTY of an outflow — "was this spent on
                            // investing?" — so it is now the Holding-cost tick
                            // inside the withdraw sheet, exactly as on the web.
                            // See [cashEntryKind].
                            BtSecondaryButton(
                                text = stringResource(R.string.bt_cash_transfer),
                                onClick = {
                                    editPrefill = null
                                    sheet = CashSheet.Transfer()
                                },
                                enabled = active.size >= 2,
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                            )
                        }
                    }

                    // Sources (Main first), tap = filter movements.
                    // V5 S2c budgets: month stepper + one bar per budgeted tag.
                    // Placed above Sources because it answers "how am I doing
                    // this month", which is the question the hero total raises.
                    item(key = "budgets") {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.bt_budgets_section),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = bt.textPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                                CashMonthStepper(
                                    month = budgetMonth,
                                    onPrev = { vm.stepBudgetMonth(-1) },
                                    onNext = { vm.stepBudgetMonth(1) },
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            when (val b = budgets) {
                                is BudgetsUi.Loading -> Column(
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    CashBudgetSkeletonRow()
                                    CashBudgetSkeletonRow()
                                }

                                is BudgetsUi.Failed -> BtInlineError(
                                    message = b.message,
                                    onRetry = { vm.loadBudgets() },
                                )

                                is BudgetsUi.Ready -> if (b.rows.isEmpty()) {
                                    CashBudgetsEmpty()
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                        b.rows.forEach { row ->
                                            CashBudgetRow(
                                                budget = row,
                                                locale = locale,
                                                onEdit = { budgetTarget = row },
                                                onDelete = { vm.deleteBudget(row.id) },
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            BtSecondaryButton(
                                text = stringResource(R.string.bt_budgets_new),
                                onClick = { newBudgetOpen = true },
                                enabled = isOnline,
                            )
                        }
                    }

                    // The month summary deliberately carries NO stepper of its
                    // own: it reads the same month as the budgets block directly
                    // above, and a second month control on one screen would be
                    // two sources of truth for one question.
                    item(key = "cash-summary") {
                        Column {
                            Text(
                                text = stringResource(R.string.bt_cash_summary_section),
                                style = MaterialTheme.typography.titleSmall,
                                color = bt.textPrimary,
                            )
                            Spacer(Modifier.height(8.dp))
                            when (val s = summary) {
                                is CashSummaryUi.Loading -> CashSummarySkeleton()
                                is CashSummaryUi.Failed -> BtInlineError(
                                    message = s.message,
                                    onRetry = { vm.loadSummary() },
                                )

                                is CashSummaryUi.Ready -> CashSummaryBlock(s.summary, locale)
                            }
                        }
                    }

                    item(key = "cash-trends") {
                        Column {
                            Text(
                                text = stringResource(R.string.bt_cash_trends_section),
                                style = MaterialTheme.typography.titleSmall,
                                color = bt.textPrimary,
                            )
                            Spacer(Modifier.height(8.dp))
                            when (val t = trends) {
                                is CashTrendsUi.Loading -> CashTrendsSkeleton()
                                is CashTrendsUi.Failed -> BtInlineError(
                                    message = t.message,
                                    onRetry = { vm.loadTrends() },
                                )

                                is CashTrendsUi.Ready -> CashTrendsBlock(t.points, locale)
                            }
                        }
                    }

                    item(key = "sources-header") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.bt_cash_sources_section),
                                style = MaterialTheme.typography.titleMedium,
                                color = bt.textPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            BtChip(
                                text = stringResource(R.string.bt_cash_new_source),
                                enabled = isOnline && !manageBusy,
                                onClick = { newSourceOpen = true },
                            )
                        }
                    }
                    manageError?.let { message ->
                        item(key = "manage-error") {
                            // Retry re-READS the sources rather than replaying the
                            // write: which write failed is not held here, and
                            // re-sending a create or an archive on a tap labelled
                            // "Try again" could book a second one. What the user
                            // actually needs after a refused management call is the
                            // server's own answer about what the list now is — and
                            // it clears a line that otherwise had NO way to
                            // disappear at all after a failed archive or restore
                            // (the only clear ran on a dialog's dismiss, and those
                            // two actions have no dialog).
                            BtInlineError(
                                message = message,
                                onRetry = {
                                    vm.clearManageError()
                                    vm.refresh()
                                },
                            )
                        }
                    }
                    // Only while the flow has genuinely not answered yet — see
                    // CashViewModel.sourcesLoaded. An empty list AFTER the first
                    // emission is an answer, not a wait.
                    if (sources.isEmpty() && !sourcesLoaded) {
                        item(key = "sources-skeleton") {
                            BtSkeleton(Modifier.fillMaxWidth().height(64.dp))
                        }
                    }
                    items(count = active.size, key = { active[it].id }) { i ->
                        val s = active[i]
                        SourceRow(
                            source = s,
                            selected = sourceFilter == s.id,
                            actionsEnabled = isOnline && !manageBusy,
                            onClick = { vm.setSourceFilter(s.id) },
                            onRename = { renameTarget = s },
                            onArchive = { archiveTarget = s },
                        )
                    }
                    if (archived.isNotEmpty()) {
                        item(key = "archived-header") {
                            Text(
                                text = stringResource(R.string.bt_switcher_archived_section),
                                style = MaterialTheme.typography.bodySmall,
                                color = bt.textMuted,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        items(count = archived.size, key = { "arch-" + archived[it].id }) { i ->
                            val s = archived[i]
                            BtCard(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(start = 14.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = s.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = bt.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        onClick = { vm.restoreSource(s.id) { } },
                                        enabled = isOnline && !manageBusy,
                                    ) {
                                        Text(
                                            stringResource(R.string.bt_switcher_restore),
                                            color = if (isOnline) bt.goldEmphasis else bt.textMuted,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Queued cash ops (§7.4) — clearly-pending rows, tap to edit.
                    if (pendingRows.isNotEmpty()) {
                        item(key = "pending-header") {
                            Text(
                                text = stringResource(R.string.bt_pending_section),
                                style = MaterialTheme.typography.titleSmall,
                                color = bt.textPrimary,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        items(count = pendingRows.size, key = { "pending-" + pendingRows[it].opId }) { i ->
                            val row = pendingRows[i]
                            PendingCashRowCard(
                                row = row,
                                sourceNames = sourceNames,
                                locale = locale,
                                onEdit = {
                                    if (row.status == PendingUiStatus.PENDING ||
                                        row.status == PendingUiStatus.NEEDS_ATTENTION
                                    ) {
                                        editPrefill = row
                                        sheet = when (row.type) {
                                            OpType.CASH_TRANSFER -> CashSheet.Transfer(row.opId)
                                            OpType.CASH_WITHDRAW -> CashSheet.Entry(CashKind.WITHDRAWAL, row.opId)
                                            OpType.CASH_FEE -> CashSheet.Entry(CashKind.FEE, row.opId)
                                            else -> CashSheet.Entry(CashKind.DEPOSIT, row.opId)
                                        }
                                    }
                                },
                            )
                        }
                    }

                    // Movement stream (filtered per source when selected).
                    item(key = "movements-header") {
                        Text(
                            text = if (sourceFilter == null) {
                                stringResource(R.string.bt_cash_movements_section)
                            } else {
                                stringResource(
                                    R.string.bt_cash_movements_for,
                                    sourceNames[sourceFilter] ?: "",
                                )
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = bt.textPrimary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    when (ledgerSurface) {
                        // The items() below are the CONTENT branch.
                        BtListSurface.CONTENT -> Unit

                        BtListSurface.SKELETON -> item(key = "movements-loading") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                repeat(3) { BtSkeleton(Modifier.fillMaxWidth().height(64.dp)) }
                            }
                        }

                        BtListSurface.EMPTY -> item(key = "movements-empty") {
                            BtEmptyState(
                                icon = Icons.Outlined.AccountBalanceWallet,
                                title = stringResource(R.string.bt_cash_empty_title),
                                message = stringResource(R.string.bt_cash_empty_message),
                            )
                        }

                        BtListSurface.OFFLINE -> item(key = "movements-offline") {
                            BtOfflineState(
                                message = stringResource(R.string.bt_cash_requires_connection),
                                onRetry = { vm.refresh() },
                            )
                        }

                        BtListSurface.ERROR -> item(key = "movements-error") {
                            BtErrorState(
                                title = stringResource(R.string.bt_cash_movements_error_title),
                                message = ledgerFailure ?: BtMessage.generic,
                                onRetry = { vm.refresh() },
                            )
                        }
                    }
                    items(count = movements.size, key = { movements[it].id }) { i ->
                        val m = movements[i]
                        // Corrections are online-only and exist only for the three
                        // hand-typed kinds — a derived row gets no menu at all
                        // rather than a menu that is certain to be refused.
                        val correctable = isEditableCashKind(m.kind) && isOnline
                        MovementRow(
                            movement = m,
                            sourceNames = sourceNames,
                            locale = locale,
                            tagsById = tagsById,
                            onEdit = if (correctable) {
                                { sheet = CashSheet.EditSynced(m.id) }
                            } else {
                                null
                            },
                            onEditTags = if (isOnline) {
                                { tagTarget = m }
                            } else {
                                null
                            },
                            onDelete = if (correctable) {
                                { deleteTarget = m }
                            } else {
                                null
                            },
                        )
                    }

                    // ── The three management screens, as doors ───────────────
                    //
                    // Promoted out of the retired top-bar ⋮ (see the header). At
                    // the FOOT of the list on purpose: they configure how the
                    // movements above them get classified, so they read as "and
                    // here is how this is organised" after the thing being
                    // organised, and they cost nothing above the fold. Same
                    // BtGroup/BtGroupRow vocabulary as Settings and People's
                    // doorways, so a row that opens a screen looks identical
                    // wherever the user meets one.
                    item(key = "manage-doors") {
                        Column(Modifier.padding(top = 10.dp)) {
                            BtSectionHeader(stringResource(R.string.bt_cash_manage_section))
                            BtGroup {
                                BtGroupRow(
                                    icon = Icons.Outlined.Sell,
                                    title = stringResource(R.string.bt_cash_menu_manage_tags),
                                    subtitle = stringResource(R.string.bt_cash_menu_manage_tags_sub),
                                    onClick = onOpenTags,
                                )
                                BtGroupRow(
                                    icon = Icons.Outlined.AutoAwesome,
                                    title = stringResource(R.string.bt_cash_menu_rules),
                                    subtitle = stringResource(R.string.bt_cash_menu_rules_sub),
                                    onClick = onOpenRules,
                                )
                                BtGroupRow(
                                    icon = Icons.Outlined.EventRepeat,
                                    title = stringResource(R.string.bt_cash_menu_standing_orders),
                                    subtitle = stringResource(R.string.bt_cash_menu_standing_orders_sub),
                                    onClick = onOpenStandingOrders,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Sheets & dialogs ────────────────────────────────────────────────────

    when (val s = sheet) {
        is CashSheet.Entry -> CashEntrySheet(
            vm = vm,
            kind = s.kind,
            sources = active,
            prefill = editPrefill,
            editOpId = s.editOpId,
            locale = locale,
            onDismiss = {
                sheet = null
                editPrefill = null
                vm.clearSheetError()
            },
        )

        is CashSheet.Transfer -> TransferSheet(
            vm = vm,
            sources = active,
            prefill = editPrefill,
            editOpId = s.editOpId,
            locale = locale,
            onDismiss = {
                sheet = null
                editPrefill = null
                vm.clearSheetError()
            },
        )

        is CashSheet.EditSynced -> {
            val target = movements.firstOrNull { it.id == s.movementId }
            if (target == null) {
                // The row vanished under us (a refresh landed while the sheet was
                // opening). Close rather than show an editor for nothing.
                sheet = null
            } else {
                CashCorrectionSheet(
                    vm = vm,
                    movement = target,
                    sources = active,
                    locale = locale,
                    onDismiss = {
                        sheet = null
                        vm.clearCorrectionNotice()
                    },
                )
            }
        }

        null -> Unit
    }

    tagTarget?.let { target ->
        CashMovementTagsSheet(
            vm = vm,
            movement = target,
            allTags = tagsById,
            onDismiss = { tagTarget = null },
        )
    }

    if (newBudgetOpen) {
        CashBudgetSheet(
            vm = vm,
            existing = null,
            allTags = tagsById,
            // One budget per (portfolio, tag, period) — offering a tag that is
            // already budgeted this month would only earn a 409, so filter them
            // out of the picker instead of letting the user hit the wall.
            takenTagIds = (budgets as? BudgetsUi.Ready)?.rows?.map { it.tagId }?.toSet().orEmpty(),
            locale = locale,
            onDismiss = { newBudgetOpen = false },
        )
    }

    budgetTarget?.let { target ->
        CashBudgetSheet(
            vm = vm,
            existing = target,
            allTags = tagsById,
            takenTagIds = emptySet(),
            locale = locale,
            onDismiss = { budgetTarget = null },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!correctionBusy) deleteTarget = null },
            containerColor = bt.surface,
            title = { Text(stringResource(R.string.bt_cash_delete_title), color = bt.textPrimary) },
            text = {
                Text(stringResource(R.string.bt_cash_delete_message), color = bt.textSecondary)
            },
            confirmButton = {
                TextButton(
                    enabled = !correctionBusy,
                    onClick = {
                        vm.deleteCorrection(target.id) { ok -> if (ok) deleteTarget = null }
                    },
                ) {
                    Text(stringResource(R.string.bt_cash_delete_action), color = bt.loss)
                }
            },
            dismissButton = {
                TextButton(enabled = !correctionBusy, onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
                }
            },
        )
    }

    // A refusal the user cannot fix by retrying gets its own designed state, not
    // a red line under a form field.
    correctionNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = { vm.clearCorrectionNotice() },
            containerColor = bt.surface,
            title = {
                Text(
                    text = stringResource(
                        if (notice.notEditable) {
                            R.string.bt_cash_not_editable_title
                        } else {
                            R.string.bt_cash_correction_failed_title
                        },
                    ),
                    color = bt.textPrimary,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (notice.notEditable) {
                        Text(
                            text = stringResource(R.string.bt_cash_not_editable_hint),
                            color = bt.textSecondary,
                        )
                    }
                    Text(text = notice.message.resolveWithDiagnostic(), color = bt.textMuted)
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.clearCorrectionNotice() }) {
                    Text(stringResource(R.string.bt_action_done), color = bt.gold)
                }
            },
        )
    }

    if (newSourceOpen) {
        SourceDialog(
            title = stringResource(R.string.bt_cash_new_source_title),
            confirmLabel = stringResource(R.string.bt_switcher_create_action),
            initialName = "",
            initialType = "bank",
            busy = manageBusy,
            onConfirm = { name, type ->
                vm.createSource(name, type) { ok -> if (ok) newSourceOpen = false }
            },
            onDismiss = {
                newSourceOpen = false
                vm.clearManageError()
            },
        )
    }

    renameTarget?.let { target ->
        SourceDialog(
            title = stringResource(R.string.bt_cash_rename_source_title),
            confirmLabel = stringResource(R.string.bt_switcher_rename_action),
            initialName = target.name,
            initialType = target.kind,
            busy = manageBusy,
            onConfirm = { name, type ->
                vm.renameSource(target.id, name, type) { ok -> if (ok) renameTarget = null }
            },
            onDismiss = {
                renameTarget = null
                vm.clearManageError()
            },
        )
    }

    archiveTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { archiveTarget = null },
            containerColor = bt.surface,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            title = { Text(stringResource(R.string.bt_cash_archive_title)) },
            text = { Text(stringResource(R.string.bt_cash_archive_message, target.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.archiveSource(target.id) { ok -> if (ok) archiveTarget = null }
                    },
                    enabled = !manageBusy,
                ) { Text(stringResource(R.string.bt_switcher_archive_action), color = bt.loss) }
            },
            dismissButton = {
                TextButton(onClick = { archiveTarget = null }) {
                    Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
                }
            },
        )
    }
}

// ── Rows ─────────────────────────────────────────────────────────────────────

@Composable
private fun SourceRow(
    source: CashSourceEntity,
    selected: Boolean,
    actionsEnabled: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
) {
    val bt = BtTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    BtCard(modifier = Modifier.fillMaxWidth(), selected = selected, onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = source.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = bt.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (source.isMain) {
                        Spacer(Modifier.width(8.dp))
                        BtBadge(
                            text = stringResource(R.string.bt_cash_primary_badge),
                            kind = BtBadgeKind.Gold,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = sourceTypeLabel(source.kind),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }
            MoneyText(value = source.balanceEur, style = BtTheme.type.moneySmall)
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = actionsEnabled) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.bt_cash_source_actions_cd),
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
                    if (!source.isMain) {
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
    }
}

/**
 * Create or retarget a budget.
 *
 * On EDIT only the amount is offered, because portfolio, tag and period are
 * fixed at creation server-side (moving a budget is delete + create) — showing
 * a tag picker that silently could not move anything would be a lie.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CashBudgetSheet(
    vm: CashViewModel,
    existing: CashBudgetProgressDto?,
    allTags: Map<String, CashTagEntity>,
    takenTagIds: Set<String>,
    locale: Locale,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val editing = existing != null
    var tagId by remember { mutableStateOf(existing?.tagId) }
    var amount by remember { mutableStateOf(existing?.amount?.let { trimNumber(it) } ?: "") }
    var recurring by remember { mutableStateOf(existing?.recurring ?: true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }

    val choosable = remember(allTags, takenTagIds) {
        allTags.values
            .filter { it.id !in takenTagIds }
            .sortedWith(compareBy({ !it.system }, { it.name.lowercase() }))
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = bt.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
                // A ModalBottomSheet ships no content insets: the 28dp above is a
                // content margin, not clearance for the system bars. Without these
                // the amount field is typed under the keyboard and the Save button
                // sits under a 3-button nav bar.
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(
                    if (editing) R.string.bt_budgets_edit_title else R.string.bt_budgets_new_title,
                ),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
            )

            if (editing) {
                // The immutable half, shown read-only so the row still reads as
                // "this budget" rather than a bare amount box.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CashTagChip(name = existing.tagName, color = existing.tagColor)
                }
            } else if (choosable.isEmpty()) {
                // Two different empties, one surface: no catalog at all, or a
                // catalog every one of whose tags is already budgeted.
                BtInlineEmpty(
                    text = stringResource(
                        if (allTags.isEmpty()) {
                            R.string.bt_cash_tags_empty_catalog
                        } else {
                            R.string.bt_budgets_all_tags_budgeted
                        },
                    ),
                )
            } else {
                Text(
                    text = stringResource(R.string.bt_budgets_tag),
                    style = MaterialTheme.typography.labelMedium,
                    color = bt.textMuted,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    choosable.forEach { tag ->
                        CashTagChip(
                            name = tag.name,
                            color = tag.color,
                            selected = tag.id == tagId,
                            onClick = { tagId = tag.id },
                        )
                    }
                }
            }

            SheetNumberField(
                value = amount,
                onValue = { amount = it; error = null },
                label = stringResource(R.string.bt_budgets_amount),
                error = error == R.string.bt_budgets_amount_required,
            )

            if (!editing) {
                // period null = the recurring monthly target; a YYYY-MM period is
                // a one-month override. Both are real server states, so both are
                // offered rather than defaulting silently.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BtChip(
                        text = stringResource(R.string.bt_budgets_period_recurring),
                        selected = recurring,
                        onClick = { recurring = true },
                    )
                    BtChip(
                        text = stringResource(R.string.bt_budgets_period_single),
                        selected = !recurring,
                        onClick = { recurring = false },
                    )
                }
            }

            // Form validation, not a queue refusal — so BtFormError rather than
            // RejectionText: "Rejected by BetterTrack" over "Enter an amount"
            // blamed the server for a field the user simply had not filled in.
            error?.let { BtFormError(BtMessage(it)) }

            BtPrimaryButton(
                text = stringResource(
                    if (editing) R.string.bt_budgets_save_action else R.string.bt_budgets_create_action,
                ),
                onClick = {
                    val parsed = amount.replace(',', '.').toDoubleOrNull()
                    val chosen = tagId
                    when {
                        parsed == null || parsed <= 0.0 -> error = R.string.bt_budgets_amount_required
                        !editing && chosen == null -> error = R.string.bt_budgets_tag_required
                        else -> {
                            busy = true
                            if (editing) {
                                vm.updateBudget(existing.id, parsed) { ok ->
                                    busy = false
                                    if (ok) onDismiss() else error = R.string.bt_budgets_duplicate
                                }
                            } else {
                                vm.createBudget(chosen!!, parsed, recurring) { ok ->
                                    busy = false
                                    if (ok) onDismiss() else error = R.string.bt_budgets_duplicate
                                }
                            }
                        }
                    }
                },
                enabled = !busy && (editing || choosable.isNotEmpty()),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Edit the tag set of one synced movement.
 *
 * The wire operation is a WHOLE-SET REPLACE (`PUT .../tags` with the full id
 * list, `[]` to clear), so the sheet mirrors that exactly: a grid of every tag
 * with the current ones selected, and one Save. Modelling it as add/remove
 * deltas would need a round trip per chip and could leave the row half-applied
 * if one failed; a single replace either lands or does not.
 *
 * Local selection is held in the sheet and only sent on Save, so tapping four
 * chips is one request, and dismissing without saving changes nothing.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CashMovementTagsSheet(
    vm: CashViewModel,
    movement: CashMovementEntity,
    allTags: Map<String, CashTagEntity>,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val busy by vm.tagBusy.collectAsStateWithLifecycle()
    // Keyed on the movement so reopening on another row starts from ITS tags.
    var selected by remember(movement.id) {
        mutableStateOf(decodeTagIds(movement.tagIds).toSet())
    }
    // System tags first is the catalog's own order; keep it stable so a chip
    // does not jump under the finger when it is selected.
    val ordered = remember(allTags) {
        allTags.values.sortedWith(compareBy({ !it.system }, { it.name.lowercase() }))
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = bt.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
                // A ModalBottomSheet ships no content insets — the 28dp above is a
                // content margin, not nav-bar clearance. This sheet is chips + Save
                // only (no text input), so it needs the nav bar and not the IME.
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.bt_cash_tags_edit),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
            )
            Text(
                text = movementLabel(movement, emptyMap()),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (ordered.isEmpty()) {
                // No catalog yet — say where tags come from instead of showing a
                // blank area with a dead Save button.
                BtInlineEmpty(text = stringResource(R.string.bt_cash_tags_empty_catalog))
            } else {
                Text(
                    text = stringResource(R.string.bt_cash_tags_none_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ordered.forEach { tag ->
                        CashTagChip(
                            name = tag.name,
                            color = tag.color,
                            selected = tag.id in selected,
                            onClick = {
                                selected = if (tag.id in selected) {
                                    selected - tag.id
                                } else {
                                    selected + tag.id
                                }
                            },
                        )
                    }
                }
            }

            BtPrimaryButton(
                text = stringResource(R.string.bt_cash_tags_save),
                onClick = {
                    // Send in catalog order for a stable, diff-friendly body.
                    val ids = ordered.map { it.id }.filter { it in selected }
                    vm.setMovementTags(movement.id, ids) { ok -> if (ok) onDismiss() }
                },
                enabled = !busy && ordered.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MovementRow(
    movement: CashMovementEntity,
    sourceNames: Map<String, String>,
    locale: Locale,
    /** The tag catalog, for resolving this row's stored tag ids to name + tint. */
    tagsById: Map<String, CashTagEntity>,
    /** Non-null only on a hand-typed row while online — derived rows get no menu. */
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    /**
     * Non-null whenever tagging is possible (online). Unlike edit/delete this is
     * offered on DERIVED rows too: a dividend or a tax settlement cannot be
     * corrected, but it can absolutely be classified, and those are exactly the
     * rows a budget wants to count.
     */
    onEditTags: (() -> Unit)? = null,
) {
    val bt = BtTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    val correctable = onEdit != null && onDelete != null
    val hasActions = correctable || onEditTags != null
    BtCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                start = 14.dp,
                end = if (hasActions) 4.dp else 14.dp,
                top = 11.dp,
                bottom = 11.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = movementIcon(movement.kind),
                contentDescription = null,
                tint = bt.textMuted,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = movementLabel(movement, sourceNames),
                        style = MaterialTheme.typography.titleSmall,
                        color = bt.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (parseRowSource(movement.source).isBadgeWorthy()) {
                        Spacer(Modifier.width(8.dp))
                        SourceBadge(movement.source)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = listOfNotNull(
                        formatTxDate(movement.executedAtMs, locale),
                        sourceNames[movement.sourceId],
                    ).joinToString(" · "),
                    style = BtTheme.type.numberCaption,
                    color = bt.textMuted,
                )
                movement.mirror?.mirrorAddedByName?.let { who ->
                    Spacer(Modifier.height(2.dp))
                    MirrorAttributionChip(who)
                }
                // V5 S2c tag chips. Rendered only when the row actually carries
                // tags (CashTagChipRow returns early otherwise), because most
                // rows in a real ledger are untagged and an empty chip strip on
                // every one of them would be pure noise.
                val tagIds = decodeTagIds(movement.tagIds)
                if (tagIds.isNotEmpty()) {
                    Spacer(Modifier.height(5.dp))
                    CashTagChipRow(tagIds = tagIds, tagsById = tagsById)
                }
            }
            Spacer(Modifier.width(8.dp))
            MoneyText(
                value = movement.amountEur,
                style = BtTheme.type.moneySmall,
                colorMode = MoneyColorMode.GainLoss,
                showSign = true,
            )
            if (hasActions) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.bt_cash_movement_actions_cd),
                        tint = bt.textSecondary,
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = bt.surface,
                ) {
                    onEditTags?.let { editTags ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.bt_cash_tags_edit),
                                    color = bt.textPrimary,
                                )
                            },
                            onClick = { menuOpen = false; editTags() },
                        )
                    }
                    if (correctable) {
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(R.string.bt_cash_edit), color = bt.textPrimary)
                            },
                            onClick = {
                                menuOpen = false
                                onEdit?.invoke()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.bt_cash_delete), color = bt.loss) },
                            onClick = {
                                menuOpen = false
                                onDelete?.invoke()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingCashRowCard(
    row: PendingCashRow,
    sourceNames: Map<String, String>,
    locale: Locale,
    onEdit: () -> Unit,
) {
    val bt = BtTheme.colors
    val editable = row.status == PendingUiStatus.PENDING || row.status == PendingUiStatus.NEEDS_ATTENTION
    BtCard(modifier = Modifier.fillMaxWidth(), onClick = if (editable) onEdit else null) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = pendingCashLabel(row, sourceNames),
                            style = MaterialTheme.typography.titleSmall,
                            color = bt.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(8.dp))
                        PendingStatusBadge(row.status)
                    }
                }
                Spacer(Modifier.width(10.dp))
                MoneyText(
                    value = if (row.type == OpType.CASH_WITHDRAW || row.type == OpType.CASH_FEE) {
                        -row.amountEur
                    } else {
                        row.amountEur
                    },
                    style = BtTheme.type.moneySmall,
                    colorMode = if (row.type == OpType.CASH_TRANSFER) MoneyColorMode.Neutral else MoneyColorMode.GainLoss,
                    showSign = row.type != OpType.CASH_TRANSFER,
                )
            }
            // Resolved from the stored code at render time, so a row parked in
            // another language still reads in the phone's current one.
            if (row.status == PendingUiStatus.NEEDS_ATTENTION &&
                (row.errorCode != null || row.serverError != null)
            ) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = rememberParkReason(row.errorCode, row.serverError),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.lossSoft,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── Sheets ───────────────────────────────────────────────────────────────────

/**
 * Edit an already-SYNCED cash movement (v5 `PATCH .../cash/movements/{id}`).
 *
 * Separate from [CashEntrySheet] because the two do genuinely different things:
 * the entry sheet enqueues a new op that works offline, this one PATCHes a row
 * that already exists server-side and can only run online. It also sends a
 * DIFF — only the fields the user actually touched — because the server schema
 * is strict and an empty body is an error.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CashCorrectionSheet(
    vm: CashViewModel,
    movement: CashMovementEntity,
    sources: List<CashSourceEntity>,
    locale: Locale,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val busy by vm.correctionBusy.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val originalKind = remember(movement.id) { editableKindOrNull(movement.kind) ?: CashKind.DEPOSIT }
    val originalAmount = remember(movement.id) { editAmountMagnitude(movement.amountEur) }
    val originalNote = movement.note.orEmpty()

    var kind by rememberSaveable(movement.id) { mutableStateOf(originalKind) }
    var amountText by rememberSaveable(movement.id) { mutableStateOf(trimNumber(originalAmount)) }
    var noteText by rememberSaveable(movement.id) { mutableStateOf(originalNote) }
    var sourceId by rememberSaveable(movement.id) { mutableStateOf(movement.sourceId) }

    val amount = parseLocalizedDecimal(amountText)
    val selectedSource = sources.firstOrNull { it.id == sourceId }
    val amountValid = amount != null && amount > 0.0

    // Only send what changed. Note the deliberate asymmetry on `note`: cleared
    // text must travel as an explicit JSON null, blank-to-blank must not travel
    // at all.
    val intent = CashEditIntent(
        kind = kind.takeIf { it != originalKind },
        amountEur = amount?.takeIf { it != originalAmount },
        sourceId = sourceId?.takeIf { it != movement.sourceId },
        note = noteText.trim().takeIf { it.isNotEmpty() && it != originalNote },
        clearNote = noteText.isBlank() && originalNote.isNotEmpty(),
        baseSeq = movement.mirror?.mirrorVersion,
    )
    val dirty = buildCashMovementPatch(intent) != null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bt.surface,
        contentColor = bt.textPrimary,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScrollIfNeeded()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.bt_cash_edit_title),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
            )

            // Kind is editable between the three hand-typed kinds — the server
            // accepts exactly that set, so a wrongly-booked withdrawal can become
            // a fee without deleting and re-entering it.
            Text(
                text = stringResource(R.string.bt_cash_edit_kind_label),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CASH_ENTRY_KINDS.forEach { candidate ->
                    BtChip(
                        text = stringResource(cashKindLabelRes(candidate)),
                        selected = kind == candidate,
                        onClick = { kind = candidate },
                    )
                }
            }
            if (kind == CashKind.FEE) {
                Text(
                    text = stringResource(R.string.bt_cash_fee_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }

            SheetNumberField(
                value = amountText,
                onValue = { amountText = sanitizeDecimalInput(it, maxDecimals = 2) },
                label = stringResource(R.string.bt_cash_amount),
                error = amountText.isNotEmpty() && !amountValid,
            )

            Text(
                text = stringResource(R.string.bt_cash_source_picker),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            SourceChips(sources = sources, selectedId = sourceId, onSelect = { sourceId = it })

            selectedSource?.let {
                Text(
                    text = stringResource(
                        R.string.bt_txform_cash_balance,
                        formatEur(it.balanceEur, locale),
                    ),
                    style = BtTheme.type.numberCaption,
                    color = bt.textSecondary,
                )
            }

            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it.take(900) },
                label = { Text(stringResource(R.string.bt_txform_note)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = sheetFieldColors(),
            )

            BtPrimaryButton(
                text = stringResource(R.string.bt_txform_save),
                onClick = {
                    vm.submitCorrection(movement.id, intent) { ok -> if (ok) onDismiss() }
                },
                enabled = dirty && amountValid && !busy,
                loading = busy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )
        }
    }
}

/** Label resource for one of the three hand-typed kinds. */
private fun cashKindLabelRes(kind: CashKind): Int = when (kind) {
    CashKind.DEPOSIT -> R.string.bt_cash_kind_deposit
    CashKind.WITHDRAWAL -> R.string.bt_cash_kind_withdrawal
    else -> R.string.bt_cash_kind_fee
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CashEntrySheet(
    vm: CashViewModel,
    kind: CashKind,
    sources: List<CashSourceEntity>,
    prefill: PendingCashRow?,
    editOpId: Long?,
    locale: Locale,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val submitting by vm.submitting.collectAsStateWithLifecycle()
    val sheetError by vm.sheetError.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val previewTags by vm.previewTagIds.collectAsStateWithLifecycle()
    val previewCatalog by vm.tagsById.collectAsStateWithLifecycle()

    // A preview belongs to the note being typed in THIS sheet; leaving must not
    // strand chips that outlive the text that produced them.
    DisposableEffect(Unit) { onDispose { vm.clearPreview() } }

    var amountText by rememberSaveable { mutableStateOf(prefill?.amountEur?.let { trimNumber(it) } ?: "") }
    var noteText by rememberSaveable { mutableStateOf(prefill?.note ?: "") }
    var sourceId by rememberSaveable {
        mutableStateOf(prefill?.sourceId ?: sources.firstOrNull { it.isMain }?.id)
    }
    // Movement date — defaults to today; a queued backdated entry restores its day.
    val initialDate = remember(prefill) {
        prefill?.executedAt?.let { iso ->
            try {
                Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate()
            } catch (_: Exception) {
                null
            }
        } ?: LocalDate.now()
    }
    var pickedEpochDay by rememberSaveable { mutableStateOf(initialDate.toEpochDay()) }
    val pickedDate = LocalDate.ofEpochDay(pickedEpochDay)
    var datePickerOpen by rememberSaveable { mutableStateOf(false) }

    // Web parity (owner 2026-08-07): an outflow asks ONE more question — is this
    // a holding cost, i.e. does it belong against performance? Seeded from the
    // kind the sheet opened with, which is how a queued FEE op re-opens with the
    // tick already set. The web seeds the identical way:
    // `useState(editing?.kind === 'fee')`.
    var holdingCost by rememberSaveable { mutableStateOf(kind == CashKind.FEE) }

    val amount = parseLocalizedDecimal(amountText)
    val selectedSource = sources.firstOrNull { it.id == sourceId }
    // A fee is an outflow like a withdrawal — same overdraw gate, same preview.
    val inflow = kind == CashKind.DEPOSIT
    // What will actually be booked. The `kind` parameter is only the sheet's
    // STARTING point now; the tick is what decides between the two outflows.
    val effectiveKind = cashEntryKind(inflow = inflow, holdingCost = holdingCost)
    val validation = validateCashEntry(amount, inflow, selectedSource?.balanceEur)
    val after = if (amount != null && selectedSource != null) {
        balanceAfterEntry(selectedSource.balanceEur, amount, inflow)
    } else {
        null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bt.surface,
        contentColor = bt.textPrimary,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScrollIfNeeded()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(
                    when (effectiveKind) {
                        CashKind.DEPOSIT -> R.string.bt_cash_deposit_title
                        CashKind.FEE -> R.string.bt_cash_fee_title
                        else -> R.string.bt_cash_withdraw_title
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
            )
            sheetError?.let { RejectionText(it) }

            SheetNumberField(
                value = amountText,
                onValue = { amountText = sanitizeDecimalInput(it, maxDecimals = 2) },
                label = stringResource(R.string.bt_cash_amount),
                error = validation.insufficient,
            )

            // Source picker (§6.3).
            Text(
                text = stringResource(R.string.bt_cash_source_picker),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            SourceChips(
                sources = sources,
                selectedId = sourceId,
                onSelect = { sourceId = it },
            )

            // Live balance-after preview vs the CACHED balance (§6.3).
            if (selectedSource != null) {
                Text(
                    text = if (after != null) {
                        stringResource(
                            R.string.bt_cash_balance_after,
                            selectedSource.name,
                            formatEur(after, locale),
                        )
                    } else {
                        stringResource(
                            R.string.bt_txform_cash_balance,
                            formatEur(selectedSource.balanceEur, locale),
                        )
                    },
                    style = BtTheme.type.numberCaption,
                    color = if (validation.insufficient) bt.loss else bt.textSecondary,
                )
            }
            if (validation.insufficient && selectedSource != null) {
                Text(
                    text = stringResource(
                        R.string.bt_txform_insufficient_cash,
                        formatEur(selectedSource.balanceEur, locale),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.loss,
                )
            }

            // Optional movement date — prefilled with today, one tap to backdate a
            // cash movement that happened earlier (no future dates, §6.3).
            BtDateField(
                date = pickedDate,
                label = stringResource(R.string.bt_txform_date),
                enabled = !submitting,
                locale = locale,
                onClick = { datePickerOpen = true },
                modifier = Modifier.fillMaxWidth(),
            )

            // The holding-cost tick — outflows only, exactly as on the web
            // (`direction === 'out' ? <label><input type="checkbox" …`). It sits
            // right after the date for the same reason it does there: it is the
            // last thing you decide about a movement you have already described.
            if (!inflow) {
                HoldingCostToggle(
                    checked = holdingCost,
                    enabled = !submitting,
                    onCheckedChange = { holdingCost = it },
                )
            }

            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it.take(900); vm.previewNote(it) },
                label = { Text(stringResource(R.string.bt_txform_note)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = sheetFieldColors(),
            )

            // Live auto-tag preview: what the user's rules WOULD apply to this
            // note. Debounced and fail-silent in the VM, and purely advisory —
            // the server does the real tagging when the movement is booked, so
            // an offline or failed preview simply shows nothing rather than
            // blocking an entry that is otherwise perfectly valid.
            if (previewTags.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.bt_cash_tags_suggested),
                        style = MaterialTheme.typography.labelSmall,
                        color = bt.textMuted,
                    )
                    CashTagChipRow(tagIds = previewTags, tagsById = previewCatalog, max = 4)
                }
            }

            BtPrimaryButton(
                text = stringResource(
                    when {
                        editOpId != null -> R.string.bt_txform_save
                        effectiveKind == CashKind.DEPOSIT -> R.string.bt_cash_deposit_action
                        effectiveKind == CashKind.FEE -> R.string.bt_cash_fee_action
                        else -> R.string.bt_cash_withdraw_action
                    },
                ),
                onClick = {
                    val a = amount ?: return@BtPrimaryButton
                    vm.submitEntry(effectiveKind, a, sourceId, noteText, pickedDate, editOpId) { ok ->
                        if (ok) onDismiss()
                    }
                },
                enabled = validation.canSubmit && !submitting,
                loading = submitting,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )
        }
    }

    if (datePickerOpen) {
        BtDatePickerDialog(
            initial = pickedDate,
            onPick = { picked ->
                pickedEpochDay = picked.toEpochDay()
                datePickerOpen = false
            },
            onDismiss = { datePickerOpen = false },
        )
    }
}

/**
 * The "Holding cost" tick — the app's half of the web's fee/withdrawal parity
 * (owner 2026-08-07; see [cashEntryKind] for the rule it feeds).
 *
 * A bordered row rather than a bare checkbox, because the sentence underneath it
 * is the point: the difference between a fee and a withdrawal is invisible on the
 * cash ledger and only shows up in the return, so a user who is not told will
 * pick wrong. The web pairs its checkbox with the same explanation behind an info
 * dot; this app has the room to just say it, and does.
 *
 * The whole row is the target (48dp+), so the sentence is tappable and not merely
 * a label the checkbox happens to sit beside.
 */
@Composable
private fun HoldingCostToggle(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val bt = BtTheme.colors
    val haptics = rememberBtHaptics()
    val commit: (Boolean) -> Unit = { on -> haptics.toggle(on); onCheckedChange(on) }
    Surface(
        shape = BtShapes.card,
        color = if (checked) bt.wash(bt.gold, 0.1f) else bt.bg,
        border = BorderStroke(1.dp, if (checked) bt.edge(bt.gold, 0.45f) else bt.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { commit(!checked) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { if (enabled) commit(it) },
                enabled = enabled,
                colors = CheckboxDefaults.colors(
                    checkedColor = bt.gold,
                    checkmarkColor = bt.onGold,
                    uncheckedColor = bt.borderStrong,
                ),
            )
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.bt_cash_holding_cost),
                    style = MaterialTheme.typography.titleSmall,
                    color = bt.textPrimary,
                )
                Text(
                    text = stringResource(R.string.bt_cash_holding_cost_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferSheet(
    vm: CashViewModel,
    sources: List<CashSourceEntity>,
    prefill: PendingCashRow?,
    editOpId: Long?,
    locale: Locale,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val submitting by vm.submitting.collectAsStateWithLifecycle()
    val sheetError by vm.sheetError.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var amountText by rememberSaveable { mutableStateOf(prefill?.amountEur?.let { trimNumber(it) } ?: "") }
    var noteText by rememberSaveable { mutableStateOf(prefill?.note ?: "") }
    var fromId by rememberSaveable {
        mutableStateOf(prefill?.sourceId ?: sources.firstOrNull { it.isMain }?.id)
    }
    var toId by rememberSaveable {
        mutableStateOf(prefill?.toSourceId ?: sources.firstOrNull { !it.isMain }?.id)
    }

    val amount = parseLocalizedDecimal(amountText)
    val from = sources.firstOrNull { it.id == fromId }
    val to = sources.firstOrNull { it.id == toId }
    val validation = validateTransfer(amount, fromId, toId, from?.balanceEur)
    val preview = if (amount != null && from != null && to != null && !validation.sameSource) {
        transferPreview(from.balanceEur, to.balanceEur, amount)
    } else {
        null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bt.surface,
        contentColor = bt.textPrimary,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScrollIfNeeded()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.bt_cash_transfer_title),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
            )
            sheetError?.let { RejectionText(it) }

            SheetNumberField(
                value = amountText,
                onValue = { amountText = sanitizeDecimalInput(it, maxDecimals = 2) },
                label = stringResource(R.string.bt_cash_amount),
                error = validation.insufficient,
            )

            Text(
                text = stringResource(R.string.bt_cash_transfer_from),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            SourceChips(sources = sources, selectedId = fromId, onSelect = { fromId = it })

            Text(
                text = stringResource(R.string.bt_cash_transfer_to),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            SourceChips(sources = sources, selectedId = toId, onSelect = { toId = it })

            if (validation.sameSource) {
                Text(
                    text = stringResource(R.string.bt_cash_transfer_same_source),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.loss,
                )
            }
            // Dual live preview (§6.3) against cached balances.
            if (preview != null && from != null && to != null) {
                Text(
                    text = stringResource(
                        R.string.bt_cash_balance_after,
                        from.name,
                        formatEur(preview.fromAfterEur, locale),
                    ) + "  ·  " + stringResource(
                        R.string.bt_cash_balance_after,
                        to.name,
                        formatEur(preview.toAfterEur, locale),
                    ),
                    style = BtTheme.type.numberCaption,
                    color = if (validation.insufficient) bt.loss else bt.textSecondary,
                )
            }
            if (validation.insufficient && from != null) {
                Text(
                    text = stringResource(
                        R.string.bt_txform_insufficient_cash,
                        formatEur(from.balanceEur, locale),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.loss,
                )
            }

            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it.take(900) },
                label = { Text(stringResource(R.string.bt_txform_note)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = sheetFieldColors(),
            )

            BtPrimaryButton(
                text = stringResource(
                    if (editOpId != null) R.string.bt_txform_save else R.string.bt_cash_transfer_action,
                ),
                onClick = {
                    val a = amount ?: return@BtPrimaryButton
                    val f = fromId ?: return@BtPrimaryButton
                    val t = toId ?: return@BtPrimaryButton
                    vm.submitTransfer(f, t, a, noteText, editOpId) { ok -> if (ok) onDismiss() }
                },
                enabled = validation.canSubmit && !submitting,
                loading = submitting,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )
        }
    }
}

// ── Small shared pieces ──────────────────────────────────────────────────────

@Composable
private fun SourceChips(
    sources: List<CashSourceEntity>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sources.forEach { s ->
            BtChip(
                text = s.name,
                selected = s.id == selectedId,
                onClick = { onSelect(s.id) },
            )
        }
    }
}

@Composable
private fun SheetNumberField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    error: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        isError = error,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done,
        ),
        suffix = { Text("€", color = BtTheme.colors.textMuted) },
        textStyle = BtTheme.type.moneySmall.copy(fontSize = 17.sp),
        modifier = Modifier.fillMaxWidth(),
        colors = sheetFieldColors(),
    )
}

/**
 * A refusal of the thing the user just submitted, headed by WHO refused it.
 *
 * Deliberately not [BtFormError]: that component is one body line, and the line
 * this block cannot afford to lose is the heading — "Rejected by BetterTrack"
 * is the difference between "your connection dropped, try again" and "the
 * server looked at this and said no", and only the second one tells the user to
 * change the form rather than tap Save again. The submit button directly beneath
 * is the retry, so there is no action here, exactly as [BtFormError] argues.
 *
 * [message] is a [BtMessage] — the same typed contract the rest of the app
 * moved onto in P0-4 — so there is no longer a `String` parameter a raw server
 * sentence could be handed to. Local form validation passes its own resource
 * wrapped the same way.
 */
@Composable
private fun RejectionText(message: BtMessage) {
    val bt = BtTheme.colors
    Column {
        Text(
            text = stringResource(R.string.bt_txform_rejected_title),
            style = MaterialTheme.typography.titleSmall,
            color = bt.lossSoft,
        )
        Text(
            text = message.resolveWithDiagnostic(),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textSecondary,
        )
    }
}

/** Name + type dialog shared by create + rename (§6.3 typed labels). */
@Composable
private fun SourceDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    initialType: String,
    busy: Boolean,
    onConfirm: (name: String, type: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    var name by rememberSaveable { mutableStateOf(initialName) }
    var type by rememberSaveable { mutableStateOf(initialType) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = bt.surface,
        titleContentColor = bt.textPrimary,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.bt_switcher_name_label)) },
                    singleLine = true,
                    enabled = !busy,
                    colors = sheetFieldColors(),
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("bank", "retirement", "cash", "custom").forEach { t ->
                        BtChip(
                            text = sourceTypeLabel(t),
                            selected = type == t,
                            enabled = !busy,
                            onClick = { type = t },
                        )
                    }
                }
            }
        },
        confirmButton = {
            BtPrimaryButton(
                text = confirmLabel,
                onClick = { onConfirm(name, type) },
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

@Composable
private fun sheetFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BtTheme.colors.gold,
    unfocusedBorderColor = BtTheme.colors.borderStrong,
    errorBorderColor = BtTheme.colors.loss,
    focusedLabelColor = BtTheme.colors.gold,
    unfocusedLabelColor = BtTheme.colors.textMuted,
    errorLabelColor = BtTheme.colors.loss,
    focusedTextColor = BtTheme.colors.textPrimary,
    unfocusedTextColor = BtTheme.colors.textPrimary,
    cursorColor = BtTheme.colors.gold,
)

/** No-op placeholder so sheets stay simple (content is short). */
private fun Modifier.verticalScrollIfNeeded(): Modifier = this

// ── Display mapping ──────────────────────────────────────────────────────────

@Composable
fun sourceTypeLabel(kind: String): String = when (kind) {
    "bank" -> stringResource(R.string.bt_cash_type_bank)
    "retirement" -> stringResource(R.string.bt_cash_type_retirement)
    "cash" -> stringResource(R.string.bt_cash_type_cash)
    "custom" -> stringResource(R.string.bt_cash_type_custom)
    else -> kind.replaceFirstChar { it.uppercase() }
}

/**
 * Localized label for a ledger row. Every wire kind the v5 backend can emit is
 * mapped; an UNKNOWN (future) kind falls back to its raw token rather than
 * crashing or rendering blank — a forward-compat escape hatch, not the norm.
 */
@Composable
private fun movementLabel(m: CashMovementEntity, sourceNames: Map<String, String>): String =
    when (CashKind.fromWire(m.kind)) {
        CashKind.DEPOSIT -> stringResource(R.string.bt_cash_kind_deposit)
        CashKind.WITHDRAWAL -> stringResource(R.string.bt_cash_kind_withdrawal)
        CashKind.FEE -> stringResource(R.string.bt_cash_kind_fee)
        CashKind.BUY -> stringResource(R.string.bt_cash_kind_buy)
        CashKind.SELL_PROCEEDS -> stringResource(R.string.bt_cash_kind_sell)
        CashKind.DIVIDEND -> stringResource(R.string.bt_cash_kind_dividend)
        CashKind.TAX_WITHHOLDING -> stringResource(R.string.bt_cash_kind_tax_withholding)
        CashKind.TAX_REFUND -> stringResource(R.string.bt_cash_kind_tax_refund)
        CashKind.TRANSFER_OUT -> stringResource(
            R.string.bt_cash_kind_transfer_out,
            m.counterpartSourceId?.let { sourceNames[it] } ?: "…",
        )

        CashKind.TRANSFER_IN -> stringResource(
            R.string.bt_cash_kind_transfer_in,
            m.counterpartSourceId?.let { sourceNames[it] } ?: "…",
        )

        null -> m.kind
    }

/**
 * Leading glyph for a ledger row.
 *
 * ONE rule, two families (S6 P1-15 — the column used to mix unrelated metaphors,
 * so it read as decoration rather than information):
 *
 *  · Rows the USER books by hand — deposit, withdrawal, fee, transfer — carry a
 *    DIRECTION glyph and nothing else: in (↙), out (↗), sideways (⇄). WHICH kind
 *    of outflow a row is (a withdrawal or a fee) is the label's job; making the
 *    glyph answer that too just meant two different questions were being answered
 *    in the same 18dp.
 *  · Rows the SYSTEM derives from a parent event carry that event's own glyph
 *    (trade, sale, dividend) — they are not something you did to your cash, they
 *    are the cash side of something else. The institution glyph is RESERVED for
 *    tax rows: those are the only ones an authority books.
 *
 * An unknown wire kind gets the neutral placeholder — never a wrong glyph.
 */
private fun movementIcon(kind: String): ImageVector = when (CashKind.fromWire(kind)) {
    // User-booked → direction only.
    CashKind.DEPOSIT -> Icons.Outlined.SouthWest
    CashKind.WITHDRAWAL, CashKind.FEE -> Icons.Outlined.NorthEast
    // Derived → the parent event's glyph.
    CashKind.BUY -> Icons.Outlined.ShoppingCart
    CashKind.SELL_PROCEEDS -> Icons.Outlined.Sell
    CashKind.DIVIDEND -> Icons.Outlined.Savings
    // Institution glyph — tax only.
    CashKind.TAX_WITHHOLDING, CashKind.TAX_REFUND -> Icons.Outlined.AccountBalance
    // User-booked, lateral: neither in nor out of the portfolio.
    CashKind.TRANSFER_OUT, CashKind.TRANSFER_IN -> Icons.Outlined.SwapHoriz
    null -> Icons.AutoMirrored.Outlined.HelpOutline
}

@Composable
private fun pendingCashLabel(row: PendingCashRow, sourceNames: Map<String, String>): String =
    when (row.type) {
        OpType.CASH_DEPOSIT -> stringResource(R.string.bt_cash_kind_deposit)
        OpType.CASH_WITHDRAW -> stringResource(R.string.bt_cash_kind_withdrawal)
        OpType.CASH_FEE -> stringResource(R.string.bt_cash_kind_fee)
        OpType.CASH_TRANSFER -> stringResource(
            R.string.bt_cash_pending_transfer,
            row.sourceId?.let { sourceNames[it] } ?: "…",
            row.toSourceId?.let { sourceNames[it] } ?: "…",
        )

        else -> row.type.wire
    }

/** Plain editable number for prefill. */
private fun trimNumber(value: Double): String =
    java.math.BigDecimal(value).setScale(2, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString()
