package at.bettertrack.app.ui.portfolio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.dto.UpdateTransactionRequest
import at.bettertrack.app.data.db.BtDatabase
import at.bettertrack.app.data.db.HoldingEntity
import at.bettertrack.app.data.db.TransactionEntity
import at.bettertrack.app.data.repo.MarketAsset
import at.bettertrack.app.data.repo.MarketRepository
import at.bettertrack.app.data.repo.PortfolioRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.navigation.TransactionFormRoute
import at.bettertrack.app.sync.ConnectivityMonitor
import at.bettertrack.app.sync.OpStatus
import at.bettertrack.app.sync.OpType
import at.bettertrack.app.sync.SyncEngine
import at.bettertrack.app.sync.SyncScheduler
import at.bettertrack.app.sync.TxOpPayload
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtDatePickerDialog
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.MoneyText
import androidx.compose.ui.text.font.FontWeight
import at.bettertrack.app.ui.components.btPressScale
import at.bettertrack.app.ui.components.formatEur
import at.bettertrack.app.ui.shell.OfflineBanner
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The Step-8 buy/sell form (spec §6.2) — phone-first, number-first, primary
 * action pinned reachable. Three modes (see [TransactionFormRoute]): record a
 * new transaction, edit a QUEUED op (mutates the queue in place, same client
 * UUID — the §7.3 edit-and-retry), or edit a SYNCED transaction (online-only
 * PATCH, §7.2).
 *
 * Every write runs through the durable outbound queue: submit enqueues, then
 * (when online) drains the engine in-process — the API call happens right in
 * the submit coroutine with M3's exactly-once reconcile machinery around it.
 * Offline, the op simply stays queued (§7.3) and the pending row is already
 * visible everywhere.
 */

// ── Modes / results ─────────────────────────────────────────────────────────

private sealed interface FormMode {
    /** New transaction into [portfolioId]. */
    data class Create(val portfolioId: String) : FormMode

    /** Editing queued op [opId] (pending or needs-attention). */
    data class EditQueued(val opId: Long, val portfolioId: String) : FormMode

    /** Editing synced transaction [txId] — online-only. */
    data class EditSynced(val txId: String, val portfolioId: String) : FormMode
}

/** What the form should do next, exposed to the screen. */
sealed interface TxFormEvent {
    /** Leave the form (submitted, queued, deleted or unusable). */
    data object Close : TxFormEvent
}

/** The asset chosen in the picker (held or, via search, the full universe). */
data class AssetPick(
    val assetId: String,
    val symbol: String,
    val name: String,
    val heldQuantity: Double?,
)

/** State of the in-form asset search (§6.5), rendered below the held section. */
sealed interface AssetSearchState {
    data object Idle : AssetSearchState
    data object Loading : AssetSearchState
    data class Results(val assets: List<MarketAsset>) : AssetSearchState
    data object Empty : AssetSearchState
    data object Offline : AssetSearchState
}

/**
 * Non-blocking hint shown under the price field about the last date↔price link
 * resolution. [PRICE_NEVER_REACHED] means a typed price never occurred in the
 * available history, so the date was left unchanged (§6.2, mirrors the web).
 */
enum class PriceLinkHint { NONE, PRICE_NEVER_REACHED }

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class TransactionFormViewModel(
    private val repo: PortfolioRepository,
    private val market: MarketRepository,
    connectivity: ConnectivityMonitor,
    private val db: BtDatabase,
    private val engine: SyncEngine,
    private val scheduler: SyncScheduler,
    private val json: Json,
    private val route: TransactionFormRoute,
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = connectivity.isOnline

    /** Device locale for formatting max/linked-price fills (matches the UI). */
    private val formLocale: Locale = Locale.getDefault()

    val isEditSynced: Boolean get() = route.transactionId != null
    val isEditQueued: Boolean get() = route.opId != null

    // ── Load state ──────────────────────────────────────────────────────────

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Non-null once the mode target resolved; null after load = target gone. */
    private var mode: FormMode? = null

    private val _targetMissing = MutableStateFlow(false)
    val targetMissing: StateFlow<Boolean> = _targetMissing.asStateFlow()

    private val _portfolioId = MutableStateFlow<String?>(null)

    val portfolioName: StateFlow<String?> =
        combine(repo.portfolios, _portfolioId) { all, pid ->
            all.firstOrNull { it.id == pid }?.name
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The MAIN/default cash source balance of the target portfolio — the wallet
     * a pay-from-cash buy actually deducts from (§6.2 owner fix). The coupling
     * math, the cash-after preview and Max all size against THIS, not the summed
     * `totals.cashEur`: sizing against the total over-estimates Max and the
     * server then rejects "insufficient" because Main alone can't cover it.
     */
    val mainCashEur: StateFlow<Double?> = _portfolioId
        .flatMapLatest { pid -> if (pid == null) flowOf(emptyList()) else repo.cashSources(pid) }
        .map { mainCashSourceBalanceEur(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The MAIN source's cash movements as (executedAtMs, signed EUR), refreshed
     * whole on every cash sync. Feeds the point-in-time affordability check so a
     * BACKDATED pay-from-cash buy is sized against the cash available ON ITS DATE,
     * exactly like the server (§6.2 owner fix — the false "insufficient cash").
     */
    private val mainCashMovements: StateFlow<List<Pair<Long, Double>>> = _portfolioId
        .flatMapLatest { pid ->
            if (pid == null) {
                flowOf(emptyList())
            } else {
                combine(repo.cashMovements(pid), repo.cashSources(pid)) { moves, sources ->
                    val mainId = sources.firstOrNull { it.isMain }?.id
                    if (mainId == null) {
                        emptyList()
                    } else {
                        moves.filter { it.sourceId == mainId }.map { it.executedAtMs to it.amountEur }
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Held assets of the target portfolio — the Step-8 asset picker universe. */
    val holdings: StateFlow<List<HoldingEntity>> = _portfolioId
        .flatMapLatest { pid -> if (pid == null) flowOf(emptyList()) else repo.holdings(pid) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Picker fallback: assets held in OTHER portfolios (distinct), so a fresh
     * portfolio can still record its first buy. "From held assets" this step —
     * TODO(step 11): search-buy opens the full universe.
     */
    val otherHeldAssets: StateFlow<List<HoldingEntity>> = combine(
        db.holdingDao().observeAll(),
        _portfolioId,
    ) { all, pid ->
        val here = all.filter { it.portfolioId == pid }.map { it.assetId }.toSet()
        all.filter { it.assetId !in here }.distinctBy { it.assetId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Form fields ─────────────────────────────────────────────────────────

    private val _isBuy = MutableStateFlow(true)
    val isBuy: StateFlow<Boolean> = _isBuy.asStateFlow()

    private val _asset = MutableStateFlow<AssetPick?>(null)
    val asset: StateFlow<AssetPick?> = _asset.asStateFlow()

    private val _quantityText = MutableStateFlow("")
    val quantityText: StateFlow<String> = _quantityText.asStateFlow()

    private val _priceText = MutableStateFlow("")
    val priceText: StateFlow<String> = _priceText.asStateFlow()

    private val _feeText = MutableStateFlow("")
    val feeText: StateFlow<String> = _feeText.asStateFlow()

    private val _date = MutableStateFlow(LocalDate.now())
    val date: StateFlow<LocalDate> = _date.asStateFlow()

    private val _noteText = MutableStateFlow("")
    val noteText: StateFlow<String> = _noteText.asStateFlow()

    private val _cashCoupled = MutableStateFlow(false)
    val cashCoupled: StateFlow<Boolean> = _cashCoupled.asStateFlow()

    /** Original server note of a synced edit — its `[bt:…]` marker is preserved. */
    private var originalSyncedNote: String? = null

    /**
     * Snapshot of the synced transaction as loaded — the PATCH body carries
     * ONLY fields the user actually changed. (The platform rejects any
     * economic change on cash-coupled transactions — an unchanged field must
     * therefore never be re-sent, especially a re-stamped executedAt.)
     */
    private var origSynced: TransactionEntity? = null

    /** Once a create was enqueued (or an op is being edited), submits bind here. */
    private var boundOpId: Long? = route.opId

    // ── Derived state ───────────────────────────────────────────────────────

    /**
     * Cash the pay-from-cash coupling may actually draw on FOR THE CHOSEN DATE:
     * the as-of running-minimum from the Main ledger (server-matching), or the
     * current Main balance when the ledger can't be reconstructed or the date is
     * current. THIS — not the raw current balance — is what the hard block, the
     * cash-after preview and Max all size against (§6.2 owner fix: a backdated buy
     * the client thought affordable but the server rejected as insufficient).
     */
    val availableCashEur: StateFlow<Double?> = combine(
        _date, mainCashEur, mainCashMovements,
    ) { date, current, moves ->
        if (current == null) null
        else availableMainCashAsOf(moves, current, executedAtMsFor(date)) ?: current
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val validation: StateFlow<TxFormValidation> = combine(
        combine(_asset, _quantityText, _priceText, _feeText) { a, q, p, f ->
            Quad(a, parseLocalizedDecimal(q), parseLocalizedDecimal(p), parseLocalizedDecimal(f))
        },
        _isBuy,
        _cashCoupled,
        availableCashEur,
        holdings,
    ) { (a, q, p, f), buy, coupled, cash, held ->
        validateTxForm(
            assetSelected = a != null,
            quantity = q,
            price = p,
            fee = f,
            isBuy = buy,
            cashCoupled = coupled,
            cachedCashEur = cash,
            heldQuantity = a?.let { pick -> held.firstOrNull { it.assetId == pick.assetId }?.quantity },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TxFormValidation(assetMissing = true))

    /** Total order value for the summary line; null until amounts parse. */
    val orderTotalEur: StateFlow<Double?> = combine(
        _quantityText, _priceText, _feeText, _isBuy,
    ) { q, p, f, buy ->
        val qty = parseLocalizedDecimal(q)
        val price = parseLocalizedDecimal(p)
        if (qty == null || price == null) null else orderTotal(buy, qty, price, parseLocalizedDecimal(f) ?: 0.0)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Live "Cash after: €…" against the as-of available cash (§6.2 owner fix). */
    val cashAfterEur: StateFlow<Double?> = combine(
        _quantityText, _priceText, _feeText, _isBuy,
        combine(_cashCoupled, availableCashEur) { c, b -> c to b },
    ) { q, p, f, buy, (coupled, balance) ->
        if (!coupled || balance == null) return@combine null
        val qty = parseLocalizedDecimal(q)
        val price = parseLocalizedDecimal(p)
        if (qty == null || price == null) null
        else cashAfterPreview(balance, buy, qty, price, parseLocalizedDecimal(f) ?: 0.0)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The chosen date when the hard block is specifically DATE-DRIVEN: a backdated
     * pay-from-cash buy the Main source couldn't afford ON THAT DATE even though it
     * can today (as-of available strictly below the current balance). Lets the card
     * name the real reason instead of a bare "insufficient" — the owner's exact
     * confusion (€360 buy, €4.8k in the bank, yet "insufficient").
     */
    val insufficientAsOfDate: StateFlow<LocalDate?> = combine(
        validation, _cashCoupled, _isBuy, _date,
        combine(mainCashEur, availableCashEur) { cur, avail -> cur to avail },
    ) { v, coupled, buy, date, (cur, avail) ->
        if (v.insufficientCash && coupled && buy && cur != null && avail != null &&
            avail < cur - CASH_LEDGER_RECONCILE_EPS
        ) {
            date
        } else {
            null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ── Submission ──────────────────────────────────────────────────────────

    private val _submitting = MutableStateFlow(false)
    val submitting: StateFlow<Boolean> = _submitting.asStateFlow()

    /** Server rejection (needs-attention reason / PATCH error) shown inline. */
    private val _serverError = MutableStateFlow<String?>(null)
    val serverError: StateFlow<String?> = _serverError.asStateFlow()

    private val _events = MutableStateFlow<TxFormEvent?>(null)
    val events: StateFlow<TxFormEvent?> = _events.asStateFlow()

    private val _deleting = MutableStateFlow(false)
    val deleting: StateFlow<Boolean> = _deleting.asStateFlow()

    init {
        viewModelScope.launch { loadMode() }
    }

    // ── Diagnostic breadcrumb: transient false "insufficient" (§6.2) ──────────

    /**
     * Latest cash-sources snapshot behind the insufficient-block breadcrumb:
     * null = not yet loaded (UNKNOWN), empty = loaded-but-none, non-empty =
     * loaded. Kept live so the breadcrumb can report the cash load state exactly.
     */
    private var latestCashSources: List<at.bettertrack.app.data.db.CashSourceEntity>? = null

    init {
        viewModelScope.launch {
            _portfolioId.flatMapLatest { pid ->
                if (pid == null) flowOf<List<at.bettertrack.app.data.db.CashSourceEntity>?>(null)
                else repo.cashSources(pid)
            }.collect { latestCashSources = it }
        }
        // If the cash-coupled BUY ever SHOWS the HARD insufficient block, log the
        // decision inputs ONCE per rising edge (no credentials) so the transient
        // false positive the owner hit once (€400 buy vs €5.3k Main) can be
        // diagnosed if it recurs. This is observation only — it does NOT change
        // the block logic (§6.2).
        viewModelScope.launch {
            var wasBlocked = false
            combine(validation, _isBuy, _cashCoupled) { v, buy, coupled ->
                v.insufficientCash && buy && coupled
            }.collect { blocked ->
                if (blocked && !wasBlocked) {
                    val sources = latestCashSources
                    val cashState = when {
                        sources == null -> "unknown"
                        sources.isEmpty() -> "empty"
                        else -> "loaded"
                    }
                    android.util.Log.i(
                        "BtTxForm",
                        "insufficient-block (cash-coupled buy): " +
                            "mainBalanceEur=${mainCashEur.value} " +
                            "availableAsOfEur=${availableCashEur.value} " +
                            "date=${_date.value} " +
                            "orderTotalEur=${orderTotalEur.value} " +
                            "priceEur=${parseLocalizedDecimal(_priceText.value)} " +
                            "cashSources=$cashState",
                    )
                }
                wasBlocked = blocked
            }
        }
    }

    private suspend fun loadMode() {
        val txId = route.transactionId
        val opId = route.opId
        when {
            // Edit a QUEUED op — prefill from its payload (§7.2 queue edit).
            opId != null -> {
                val op = db.syncOpDao().getById(opId)
                val row = op?.let { decodePendingTxRow(it, json) }
                val editable = op != null && (
                    op.status == OpStatus.PENDING.wire || op.status == OpStatus.NEEDS_ATTENTION.wire
                    )
                if (row == null || !editable || op.portfolioId == null) {
                    _targetMissing.value = true
                } else {
                    mode = FormMode.EditQueued(opId, op.portfolioId)
                    _portfolioId.value = op.portfolioId
                    _isBuy.value = row.isBuy
                    _asset.value = AssetPick(row.assetId, row.assetSymbol, row.assetName ?: row.assetSymbol, null)
                    _quantityText.value = editNumber(row.quantity)
                    _priceText.value = editNumber(row.price)
                    _feeText.value = if (row.fee > 0.0) editNumber(row.fee) else ""
                    _date.value = epochMsToLocalDate(row.executedAtMs)
                    _noteText.value = row.note.orEmpty()
                    _cashCoupled.value = row.cashCoupled
                    _serverError.value = op.serverError
                    refineAssetFromHoldings(row.assetId)
                }
            }

            // Edit a SYNCED transaction — online-only PATCH (§7.2).
            txId != null -> {
                val tx = db.transactionDao().getById(txId)
                if (tx == null) {
                    _targetMissing.value = true
                } else {
                    mode = FormMode.EditSynced(txId, tx.portfolioId)
                    origSynced = tx
                    _portfolioId.value = tx.portfolioId
                    _isBuy.value = tx.side == "buy"
                    _asset.value = AssetPick(tx.assetId, tx.assetSymbol, tx.assetName, null)
                    _quantityText.value = editNumber(tx.quantity)
                    _priceText.value = editNumber(tx.price)
                    _feeText.value = if (tx.fee > 0.0) editNumber(tx.fee) else ""
                    _date.value = epochMsToLocalDate(tx.executedAtMs)
                    originalSyncedNote = tx.note
                    _noteText.value = displayNote(tx.note).orEmpty()
                    refineAssetFromHoldings(tx.assetId)
                }
            }

            // New transaction into the routed / governing portfolio.
            else -> {
                val pid = route.portfolioId ?: withTimeoutOrNull(4_000) {
                    combine(repo.portfolios, repo.selectedPortfolioId) { all, stored ->
                        PortfolioOverviewViewModel.resolveSelection(all, stored)?.id
                    }.first { it != null }
                }
                if (pid == null) {
                    _targetMissing.value = true
                } else {
                    mode = FormMode.Create(pid)
                    _portfolioId.value = pid
                    // Cold-cache guard (§7.3): the FAB can open this form before
                    // the overview has synced cash + holdings. Refresh the target
                    // snapshot now (online) so the balance is KNOWN (never treated
                    // as zero) and the sell picker / oversell check are populated.
                    ensureFreshSnapshot(pid)
                    // Search/asset-page can preselect the Sell side (§6.5).
                    _isBuy.value = !route.sell
                    // Sticky cash-coupling default (§6.2): local sticky value,
                    // else the portfolio's server-side default.
                    val sticky = repo.cashCouplingDefault(pid).first()
                    val server = repo.portfolios.first().firstOrNull { it.id == pid }?.defaultPayFromCash
                    _cashCoupled.value = resolveCashCouplingDefault(sticky, server ?: false)
                    // Pre-fill the asset. Search-buy (Step 11) passes the identity
                    // in the route so a NOT-yet-held asset binds instantly; a
                    // holding entry passes only assetId and resolves from cache.
                    val rid = route.assetId
                    if (rid != null) {
                        if (route.assetSymbol != null) {
                            val pick = AssetPick(rid, route.assetSymbol, route.assetName ?: route.assetSymbol, null)
                            _asset.value = pick
                            loadDailyCloses(pick, autoLink = true)
                            refineAssetFromHoldings(rid)
                        } else {
                            prefillAsset(pid, rid)
                        }
                    }
                }
            }
        }
        _loading.value = false
    }

    private suspend fun prefillAsset(portfolioId: String, assetId: String) {
        val rows = withTimeoutOrNull(3_000) {
            repo.holdings(portfolioId).first { list -> list.any { it.assetId == assetId } }
        } ?: return
        rows.firstOrNull { it.assetId == assetId }?.let { h ->
            val pick = AssetPick(h.assetId, h.assetSymbol, h.assetName, h.quantity)
            _asset.value = pick
            loadDailyCloses(pick, autoLink = true)
        }
    }

    /** Fill in held quantity / display names once holdings are cached. */
    private fun refineAssetFromHoldings(assetId: String) {
        viewModelScope.launch {
            val rows = withTimeoutOrNull(3_000) {
                holdings.first { list -> list.any { it.assetId == assetId } }
            } ?: return@launch
            rows.firstOrNull { it.assetId == assetId }?.let { h ->
                _asset.value = AssetPick(h.assetId, h.assetSymbol, h.assetName, h.quantity)
            }
        }
    }

    // ── Cold-cache guard (§7.3) ─────────────────────────────────────────────

    /** Refresh the target portfolio's detail (totals + holdings) AND its cash
     *  sources when online, so the form never validates against a cold cache and
     *  the MAIN-source balance behind Max / cash-after is known (§6.2). */
    private fun ensureFreshSnapshot(portfolioId: String) {
        if (!isOnline.value) return
        viewModelScope.launch { repo.refreshPortfolioDetail(portfolioId) }
        viewModelScope.launch { repo.refreshCash(portfolioId) }
    }

    // ── Asset picker search (§6.5 — the full universe, not just held) ───────

    private val _assetQuery = MutableStateFlow("")
    val assetQuery: StateFlow<String> = _assetQuery.asStateFlow()

    private val _assetSearch = MutableStateFlow<AssetSearchState>(AssetSearchState.Idle)
    val assetSearch: StateFlow<AssetSearchState> = _assetSearch.asStateFlow()

    init {
        viewModelScope.launch {
            _assetQuery.debounce(260).collectLatest { raw ->
                val q = raw.trim()
                when {
                    q.isEmpty() -> _assetSearch.value = AssetSearchState.Idle
                    !isOnline.value -> _assetSearch.value = AssetSearchState.Offline
                    else -> {
                        _assetSearch.value = AssetSearchState.Loading
                        _assetSearch.value = when (val r = market.search(q)) {
                            is BtResult.Ok ->
                                if (r.value.results.isEmpty()) AssetSearchState.Empty
                                else AssetSearchState.Results(r.value.results)

                            is BtResult.Err -> AssetSearchState.Offline
                        }
                    }
                }
            }
        }
    }

    fun setAssetQuery(q: String) { _assetQuery.value = q }
    fun clearAssetQuery() { _assetQuery.value = "" }

    // ── Date ↔ price link (§6.2, BIDIRECTIONAL — mirrors the web #226) ───────
    // Linked, the two fields drive each other over the asset's daily-close series
    // (fetched once): picking a DATE fills the price with that day's close
    // (closeOnOrBefore), and typing a PRICE jumps the date to the MOST RECENT day
    // the series was at that price (mostRecentDateAtPrice). The chain toggle
    // detaches BOTH. Loop guard: each direction writes its partner field DIRECTLY
    // (never through the other's public setter), so a fill never bounces back.

    private val _priceLinked = MutableStateFlow(false)
    val priceLinked: StateFlow<Boolean> = _priceLinked.asStateFlow()

    private val _priceLinkAvailable = MutableStateFlow(false)
    /** Only market assets with a daily-close series can auto-fill by date. */
    val priceLinkAvailable: StateFlow<Boolean> = _priceLinkAvailable.asStateFlow()

    private val _priceHistoryLoading = MutableStateFlow(false)
    /** The daily-close series is being fetched — a subtle indicator, never a block. */
    val priceHistoryLoading: StateFlow<Boolean> = _priceHistoryLoading.asStateFlow()

    private val _priceLinkHint = MutableStateFlow(PriceLinkHint.NONE)
    /** Non-blocking hint about the last link resolution (e.g. price never reached). */
    val priceLinkHint: StateFlow<PriceLinkHint> = _priceLinkHint.asStateFlow()

    private var dailyCloses: List<Pair<LocalDate, Double>> = emptyList()
    private var closesAssetId: String? = null

    /**
     * Debounced price→date signal, fed ONLY by a user-typed price ([setPrice]) and
     * never by the date→price auto-fill — so an auto-filled price can't re-trigger
     * the reverse lookup. Debounced so the date doesn't churn per keystroke (§5.3).
     */
    private val _priceReverseSignal = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            _priceReverseSignal.debounce(450).collectLatest { raw ->
                if (raw != null) resolvePriceToDate(raw)
            }
        }
    }

    /** Load the asset's daily closes (Create only) so date↔price can auto-fill. */
    private fun loadDailyCloses(pick: AssetPick, autoLink: Boolean) {
        if (mode !is FormMode.Create || !isOnline.value || pick.assetId.isBlank()) return
        if (closesAssetId == pick.assetId) {
            if (autoLink && _priceLinkAvailable.value) enableLink()
            return
        }
        closesAssetId = pick.assetId
        dailyCloses = emptyList()
        _priceLinkAvailable.value = false
        _priceLinkHint.value = PriceLinkHint.NONE
        _priceHistoryLoading.value = true
        viewModelScope.launch {
            val r = market.assetDailyCloses(pick.assetId)
            if (closesAssetId != pick.assetId) return@launch // a newer asset pick won
            when (r) {
                is BtResult.Ok -> {
                    dailyCloses = r.value.map {
                        Instant.ofEpochMilli(it.timeMs).atZone(ZoneId.systemDefault()).toLocalDate() to it.close
                    }
                    val available = dailyCloses.isNotEmpty()
                    _priceLinkAvailable.value = available
                    if (available && autoLink && _priceText.value.isBlank()) enableLink()
                }

                is BtResult.Err -> _priceLinkAvailable.value = false
            }
            _priceHistoryLoading.value = false
        }
    }

    private fun enableLink() {
        _priceLinked.value = true
        _priceLinkHint.value = PriceLinkHint.NONE
        applyLinkedPrice()
    }

    /** DATE → price: fill the close for the selected day (or the prior trading day). */
    private fun applyLinkedPrice() {
        if (!_priceLinked.value) return
        val price = closeOnOrBefore(dailyCloses, _date.value) ?: return
        // Write the price field DIRECTLY (not via setPrice), so this auto-fill does
        // NOT feed the reverse signal and cannot bounce the date back (loop guard).
        _priceText.value = formatDecimalForInput(price, formLocale, maxDecimals = 6)
        _priceLinkHint.value = PriceLinkHint.NONE
    }

    /** PRICE → date: jump to the most recent day the series was at the typed price. */
    private fun resolvePriceToDate(raw: String) {
        if (!_priceLinked.value || !_priceLinkAvailable.value || dailyCloses.isEmpty()) return
        val price = parseLocalizedDecimal(raw)
        if (price == null || price <= 0.0) {
            _priceLinkHint.value = PriceLinkHint.NONE
            return
        }
        val matched = mostRecentDateAtPrice(dailyCloses, price)
        if (matched == null) {
            // Never at this price in the available history — leave the date, say so.
            _priceLinkHint.value = PriceLinkHint.PRICE_NEVER_REACHED
            return
        }
        _priceLinkHint.value = PriceLinkHint.NONE
        // Write the date field DIRECTLY (not via setDate), so this does NOT re-run
        // the date→price fill and clobber the user's typed price (loop guard).
        if (_date.value != matched) _date.value = matched
    }

    /** Chain toggle: detach BOTH directions, or re-attach and fill from the date. */
    fun togglePriceLink() {
        if (!_priceLinkAvailable.value) return
        if (_priceLinked.value) {
            _priceLinked.value = false
            _priceLinkHint.value = PriceLinkHint.NONE
        } else {
            enableLink()
        }
    }

    // ── Max-quantity chip (§6.2) ────────────────────────────────────────────

    /** Whether a "Max" affordance is offerable now (known held qty / known cash). */
    val maxAvailable: StateFlow<Boolean> = combine(
        combine(_isBuy, _asset, _priceText) { buy, a, p -> Triple(buy, a, p) },
        _cashCoupled, availableCashEur, holdings,
    ) { (buy, a, priceText), coupled, cash, held ->
        when {
            a == null -> false
            !buy -> (held.firstOrNull { it.assetId == a.assetId }?.quantity ?: 0.0) > 0.0
            else -> coupled && cash != null && (parseLocalizedDecimal(priceText) ?: 0.0) > 0.0
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Fill the amount with the max: sell = full held qty; buy = max affordable. */
    fun fillMaxQuantity() {
        val a = _asset.value ?: return
        if (_isBuy.value) {
            val cash = availableCashEur.value ?: return
            val price = parseLocalizedDecimal(_priceText.value) ?: return
            if (!_cashCoupled.value || price <= 0.0) return
            val fee = parseLocalizedDecimal(_feeText.value) ?: 0.0
            _quantityText.value = formatDecimalForInput(maxAffordableQuantity(cash, price, fee), formLocale)
        } else {
            val held = holdings.value.firstOrNull { it.assetId == a.assetId }?.quantity ?: return
            _quantityText.value = formatDecimalForInput(held, formLocale)
        }
    }

    // ── Field mutations ─────────────────────────────────────────────────────

    fun setSide(isBuy: Boolean) {
        _isBuy.value = isBuy
    }

    fun setAsset(pick: AssetPick) {
        val changed = _asset.value?.assetId != pick.assetId
        _asset.value = pick
        _assetQuery.value = ""
        // A different asset's price is meaningless — drop it so the date→price
        // link re-fills with the new asset's close (and re-enable the link).
        if (changed) {
            _priceText.value = ""
            closesAssetId = null
            dailyCloses = emptyList()
            _priceLinkAvailable.value = false
        }
        loadDailyCloses(pick, autoLink = true)
    }

    fun setQuantity(text: String) {
        _quantityText.value = sanitizeDecimalInput(text)
    }

    fun setPrice(text: String) {
        val sanitized = sanitizeDecimalInput(text, maxDecimals = 6)
        _priceText.value = sanitized
        // Bidirectional (#226, matches the web): a typed price stays LINKED and
        // drives the date (debounced via the reverse signal) instead of detaching.
        // The chain toggle is the only way to unlink. Non-market assets have no
        // series, so the reverse resolver simply no-ops there.
        _priceLinkHint.value = PriceLinkHint.NONE
        _priceReverseSignal.value = sanitized
    }

    fun setFee(text: String) {
        _feeText.value = sanitizeDecimalInput(text, maxDecimals = 2)
    }

    fun setDate(date: LocalDate) {
        _date.value = date
        applyLinkedPrice()
    }

    fun setNote(text: String) {
        _noteText.value = text.take(900)
    }

    /**
     * Flip the cash toggle. In Create mode the choice becomes the portfolio's
     * sticky default (§6.2) immediately — queued/synced edits correct one
     * entry and never rewrite the default.
     */
    fun setCashCoupled(value: Boolean) {
        _cashCoupled.value = value
        val m = mode
        if (m is FormMode.Create) {
            viewModelScope.launch { repo.setCashCouplingDefault(m.portfolioId, value) }
        }
    }

    // ── Submit / delete ─────────────────────────────────────────────────────

    fun submit() {
        val m = mode ?: return
        if (_submitting.value) return
        if (!validation.value.canSubmit) return
        viewModelScope.launch {
            _submitting.value = true
            _serverError.value = null
            when (m) {
                is FormMode.Create, is FormMode.EditQueued -> submitViaQueue(m)
                is FormMode.EditSynced -> submitSyncedPatch(m)
            }
            _submitting.value = false
        }
    }

    /**
     * The queue write path (§7.3): enqueue (or edit the bound op in place —
     * SAME client UUID), then drain in-process when online. DONE ⇒ recorded on
     * the server; NEEDS_ATTENTION ⇒ stay with the server's reason (edit-and-
     * retry); still open ⇒ it's queued — pending rows already show it.
     */
    private suspend fun submitViaQueue(m: FormMode) {
        val portfolioId = when (m) {
            is FormMode.Create -> m.portfolioId
            is FormMode.EditQueued -> m.portfolioId
            else -> return
        }
        val pick = _asset.value ?: return
        val payload = TxOpPayload(
            assetId = pick.assetId,
            side = if (_isBuy.value) "buy" else "sell",
            quantity = parseLocalizedDecimal(_quantityText.value) ?: return,
            price = parseLocalizedDecimal(_priceText.value) ?: return,
            fee = parseLocalizedDecimal(_feeText.value) ?: 0.0,
            executedAt = executedAtIso(_date.value),
            note = _noteText.value.trim().takeIf { it.isNotEmpty() },
            payFromCash = if (_isBuy.value && _cashCoupled.value) true else null,
            addProceedsToCash = if (!_isBuy.value && _cashCoupled.value) true else null,
            assetSymbol = pick.symbol,
            assetName = pick.name,
        )
        val payloadJson = json.encodeToString(TxOpPayload.serializer(), payload)

        val opId = boundOpId
        if (opId == null) {
            val op = engine.enqueue(
                type = if (_isBuy.value) OpType.TX_BUY else OpType.TX_SELL,
                portfolioId = portfolioId,
                payloadJson = payloadJson,
            )
            boundOpId = op.id
        } else {
            val ok = engine.updateOp(opId, payloadJson)
            if (!ok) {
                // The op resolved meanwhile (drained / discarded elsewhere).
                _events.value = TxFormEvent.Close
                return
            }
        }

        if (isOnline.value) {
            // Direct, in-process drain: the API call happens right here, with
            // the engine's exactly-once machinery (mutex, reconcile) around it.
            try {
                engine.drain()
            } catch (_: Exception) {
                // Op stays queued; WorkManager picks it up.
            }
        }

        val after = boundOpId?.let { db.syncOpDao().getById(it) }
        when {
            // Op vanished (drained + pruned, or discarded elsewhere) — nothing
            // left to fix here.
            after == null -> _events.value = TxFormEvent.Close

            // Recorded on the server — the ONLY reason to leave the form on a
            // successful submit (§6.2 owner fix: only navigate away on success).
            after.status == OpStatus.DONE.wire -> _events.value = TxFormEvent.Close

            // The server declined it WHILE the user is here (e.g. insufficient):
            // keep the form open, surface the reason inline, and let them edit +
            // resubmit IN PLACE. The bound op keeps its client UUID, so the retry
            // is the SAME entry (edit-and-retry, exactly-once — §7.3).
            after.status == OpStatus.NEEDS_ATTENTION.wire ->
                _serverError.value = after.serverError ?: DEFAULT_REJECTION_MESSAGE

            // Online and still open but already carrying a server reason: never
            // dump it into the needs-attention screen behind the user's back —
            // show it here so they can fix it in place.
            isOnline.value && after.serverError != null ->
                _serverError.value = after.serverError

            // Offline enqueue, or an online outcome we couldn't confirm yet with
            // no reason attached: the durable pending row IS the record. Park the
            // connectivity-gated drain (it reconciles / escalates to needs-
            // attention in the background, §7.3) and leave.
            else -> {
                scheduler.scheduleDrain()
                _events.value = TxFormEvent.Close
            }
        }
    }

    private suspend fun submitSyncedPatch(m: FormMode.EditSynced) {
        if (!isOnline.value) return
        val orig = origSynced ?: return

        // Delta-only PATCH: unchanged fields are omitted. The platform rejects
        // economic changes on cash-coupled transactions, so re-sending an
        // unchanged (or merely re-stamped) field must never happen.
        val newSide = if (_isBuy.value) "buy" else "sell"
        val newQty = parseLocalizedDecimal(_quantityText.value) ?: return
        val newPrice = parseLocalizedDecimal(_priceText.value) ?: return
        val newFee = parseLocalizedDecimal(_feeText.value) ?: 0.0
        val newNote = mergeNotePreservingMarker(_noteText.value, originalSyncedNote)
        val dateChanged = _date.value != epochMsToLocalDate(orig.executedAtMs)

        val body = UpdateTransactionRequest(
            side = newSide.takeIf { it != orig.side },
            quantity = newQty.takeIf { it != orig.quantity },
            price = newPrice.takeIf { it != orig.price },
            fee = newFee.takeIf { it != orig.fee },
            executedAt = if (dateChanged) executedAtIso(_date.value) else null,
            note = newNote.takeIf { it != orig.note },
        )
        if (body == UpdateTransactionRequest()) {
            // Nothing changed — nothing to send.
            _events.value = TxFormEvent.Close
            return
        }
        when (val r = repo.updateTransaction(m.portfolioId, m.txId, body)) {
            is BtResult.Ok -> _events.value = TxFormEvent.Close
            is BtResult.Err -> _serverError.value = r.error.userMessage
        }
    }

    /** Delete a synced transaction / discard the bound queued op. */
    fun deleteOrDiscard() {
        val m = mode ?: return
        if (_deleting.value) return
        viewModelScope.launch {
            _deleting.value = true
            when (m) {
                is FormMode.EditSynced -> {
                    if (isOnline.value) {
                        when (val r = repo.deleteTransaction(m.portfolioId, m.txId)) {
                            is BtResult.Ok -> _events.value = TxFormEvent.Close
                            is BtResult.Err -> _serverError.value = r.error.userMessage
                        }
                    }
                }

                is FormMode.EditQueued -> {
                    engine.discardOp(m.opId)
                    _events.value = TxFormEvent.Close
                }

                is FormMode.Create -> {
                    // A create that already bound to a needs-attention op can
                    // be discarded too (it exists in the queue).
                    boundOpId?.let { engine.discardOp(it) }
                    _events.value = TxFormEvent.Close
                }
            }
            _deleting.value = false
        }
    }

    fun consumeEvent() {
        _events.value = null
    }

    /** True when a delete/discard affordance applies in the current mode. */
    val canDelete: Boolean
        get() = isEditSynced || isEditQueued

    private companion object {
        /** Fallback when the server rejects an op without a readable reason. */
        // TODO(step 19 i18n): externalize to strings.xml.
        const val DEFAULT_REJECTION_MESSAGE = "BetterTrack rejected this entry."

        /** Plain editable number: no grouping, up to 8 decimals, dot separator. */
        fun editNumber(value: Double): String =
            java.math.BigDecimal(value).setScale(8, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString()

        fun epochMsToLocalDate(ms: Long): LocalDate =
            if (ms <= 0L) {
                LocalDate.now()
            } else {
                Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
            }
    }
}

/** Tiny tuple helper for the nested combine above. */
private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

// ═════════════════════════════════ UI ═══════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionFormScreen(
    route: TransactionFormRoute,
    onBack: () -> Unit,
) {
    val vm: TransactionFormViewModel = viewModel {
        TransactionFormViewModel(
            repo = AppGraph.portfolioRepository,
            market = AppGraph.marketRepository,
            connectivity = AppGraph.connectivityMonitor,
            db = AppGraph.database,
            engine = AppGraph.syncEngine,
            scheduler = AppGraph.syncScheduler,
            json = AppGraph.json,
            route = route,
        )
    }

    val bt = BtTheme.colors
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

    val loading by vm.loading.collectAsStateWithLifecycle()
    val targetMissing by vm.targetMissing.collectAsStateWithLifecycle()
    val portfolioName by vm.portfolioName.collectAsStateWithLifecycle()
    val isOnline by vm.isOnline.collectAsStateWithLifecycle()
    val isBuy by vm.isBuy.collectAsStateWithLifecycle()
    val asset by vm.asset.collectAsStateWithLifecycle()
    val quantityText by vm.quantityText.collectAsStateWithLifecycle()
    val priceText by vm.priceText.collectAsStateWithLifecycle()
    val priceLinked by vm.priceLinked.collectAsStateWithLifecycle()
    val priceLinkAvailable by vm.priceLinkAvailable.collectAsStateWithLifecycle()
    val priceHistoryLoading by vm.priceHistoryLoading.collectAsStateWithLifecycle()
    val priceLinkHint by vm.priceLinkHint.collectAsStateWithLifecycle()
    val maxAvailable by vm.maxAvailable.collectAsStateWithLifecycle()
    val feeText by vm.feeText.collectAsStateWithLifecycle()
    val date by vm.date.collectAsStateWithLifecycle()
    val noteText by vm.noteText.collectAsStateWithLifecycle()
    val cashCoupled by vm.cashCoupled.collectAsStateWithLifecycle()
    val validation by vm.validation.collectAsStateWithLifecycle()
    val orderTotalEur by vm.orderTotalEur.collectAsStateWithLifecycle()
    val cashAfterEur by vm.cashAfterEur.collectAsStateWithLifecycle()
    val mainCashEur by vm.mainCashEur.collectAsStateWithLifecycle()
    val availableCashEur by vm.availableCashEur.collectAsStateWithLifecycle()
    val insufficientAsOfDate by vm.insufficientAsOfDate.collectAsStateWithLifecycle()
    val holdings by vm.holdings.collectAsStateWithLifecycle()
    val submitting by vm.submitting.collectAsStateWithLifecycle()
    val serverError by vm.serverError.collectAsStateWithLifecycle()
    val deleting by vm.deleting.collectAsStateWithLifecycle()
    val event by vm.events.collectAsStateWithLifecycle()
    val dataAgeMs by AppGraph.portfolioRepository.portfolioDataAgeMs
        .collectAsStateWithLifecycle(initialValue = null)

    var assetSheetOpen by rememberSaveable { mutableStateOf(false) }
    var datePickerOpen by rememberSaveable { mutableStateOf(false) }
    var deleteConfirmOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(event) {
        if (event is TxFormEvent.Close) {
            vm.consumeEvent()
            onBack()
        }
    }
    LaunchedEffect(targetMissing) {
        if (targetMissing) onBack()
    }

    // Synced edits are online-only (§7.2) — offline shows a clear state.
    val syncedEditOffline = vm.isEditSynced && !isOnline
    val inputsEnabled = !loading && !submitting && !deleting && !syncedEditOffline

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(
                                when {
                                    vm.isEditSynced -> R.string.bt_txform_title_edit
                                    vm.isEditQueued -> R.string.bt_txform_title_edit_queued
                                    else -> R.string.bt_txform_title_new
                                },
                            ),
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
                actions = {
                    if (vm.canDelete) {
                        val deleteEnabled = !deleting && !submitting &&
                            (vm.isEditQueued || isOnline)
                        IconButton(
                            onClick = { deleteConfirmOpen = true },
                            enabled = deleteEnabled,
                        ) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = stringResource(
                                    if (vm.isEditQueued) R.string.bt_txform_discard_cd else R.string.bt_txform_delete_cd,
                                ),
                                tint = if (deleteEnabled) bt.loss else bt.border,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bt.bg,
                    titleContentColor = bt.textPrimary,
                ),
            )
        },
        bottomBar = {
            if (!loading) {
                SubmitBar(
                    isBuy = isBuy,
                    isEdit = vm.isEditSynced || vm.isEditQueued,
                    totalEur = orderTotalEur,
                    canSubmit = validation.canSubmit && inputsEnabled &&
                        !(vm.isEditSynced && !isOnline),
                    submitting = submitting,
                    locale = locale,
                    onSubmit = { vm.submit() },
                )
            }
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (!isOnline) {
                OfflineBanner(asOfMs = dataAgeMs)
            }

            if (loading) {
                FormSkeleton()
                return@Column
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Server rejection (needs-attention reason) — edit-and-retry.
                serverError?.let { message ->
                    RejectionCard(message = message, isQueuedRetry = !vm.isEditSynced)
                }

                if (syncedEditOffline) {
                    RequiresConnectionCard()
                }

                // Side toggle — the money-direction commitment affordance
                // (emerald/red reserved exactly for this, §3.3).
                SideToggle(
                    isBuy = isBuy,
                    enabled = inputsEnabled,
                    onSide = { vm.setSide(it) },
                )

                // Asset (held assets this step; locked on synced edits — the
                // PATCH contract has no assetId). TODO(step 11): search-buy
                // replaces the held-assets-only universe.
                AssetField(
                    asset = asset,
                    locked = vm.isEditSynced,
                    enabled = inputsEnabled,
                    locale = locale,
                    onClick = { assetSheetOpen = true },
                )

                // Quantity + price — number-first (§3.4 tabular digits).
                // Quantity carries a "Max" affordance (sell = full held qty; buy
                // = max affordable from cash); price carries a date-link chain.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FormNumberField(
                        value = quantityText,
                        onValue = vm::setQuantity,
                        label = stringResource(R.string.bt_txform_quantity),
                        enabled = inputsEnabled,
                        error = validation.quantityError != null && quantityText.isNotEmpty(),
                        selectAllOnFocus = true,
                        modifier = Modifier.weight(1f),
                        trailingIcon = if (maxAvailable && inputsEnabled) {
                            { MaxChip(onClick = { vm.fillMaxQuantity() }) }
                        } else {
                            null
                        },
                    )
                    FormNumberField(
                        value = priceText,
                        onValue = vm::setPrice,
                        label = stringResource(R.string.bt_txform_price),
                        enabled = inputsEnabled,
                        error = validation.priceError != null && priceText.isNotEmpty(),
                        suffix = "€",
                        selectAllOnFocus = true,
                        modifier = Modifier.weight(1f),
                        trailingIcon = if (inputsEnabled && priceHistoryLoading) {
                            { PriceLinkLoading() }
                        } else if (inputsEnabled && priceLinkAvailable) {
                            { PriceLinkToggle(linked = priceLinked, onToggle = { vm.togglePriceLink() }) }
                        } else {
                            null
                        },
                    )
                }
                // One subtle, non-blocking hint under the price/date row: a typed
                // price that never occurred leaves the date and says so; otherwise,
                // while linked, a reminder the two fields fill each other (§6.2).
                val priceHint: Pair<String, androidx.compose.ui.graphics.Color>? = when {
                    !inputsEnabled -> null
                    priceLinkHint == PriceLinkHint.PRICE_NEVER_REACHED ->
                        stringResource(R.string.bt_txform_price_never_reached) to bt.goldEmphasis
                    priceLinkAvailable && priceLinked ->
                        stringResource(R.string.bt_txform_price_linked_hint) to bt.textMuted
                    else -> null
                }
                priceHint?.let { (text, color) ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = color,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DateField(
                        date = date,
                        enabled = inputsEnabled,
                        locale = locale,
                        onClick = { datePickerOpen = true },
                        modifier = Modifier.weight(1f),
                    )
                    FormNumberField(
                        value = feeText,
                        onValue = vm::setFee,
                        label = stringResource(R.string.bt_txform_fee),
                        enabled = inputsEnabled,
                        error = validation.feeError != null && feeText.isNotEmpty(),
                        suffix = "€",
                        selectAllOnFocus = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                // Cash coupling (§6.2) — hidden on synced edits (the PATCH
                // contract can't change the original cash movement).
                if (!vm.isEditSynced) {
                    CashCouplingCard(
                        isBuy = isBuy,
                        coupled = cashCoupled,
                        enabled = inputsEnabled,
                        cashAfterEur = cashAfterEur,
                        cachedCashEur = mainCashEur,
                        availableAsOfEur = availableCashEur,
                        insufficient = validation.insufficientCash,
                        insufficientAsOfDate = insufficientAsOfDate,
                        locale = locale,
                        onToggle = vm::setCashCoupled,
                    )
                }

                // Oversell — soft warning only; the server is the final
                // validator (§7.3).
                if (validation.oversellWarning) {
                    val held = asset?.heldQuantity
                    Text(
                        text = stringResource(
                            R.string.bt_txform_oversell_warning,
                            held?.let { formatQuantity(it, locale) } ?: "0",
                            asset?.symbol ?: "",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.goldEmphasis,
                    )
                }

                OutlinedTextField(
                    value = noteText,
                    onValueChange = vm::setNote,
                    label = { Text(stringResource(R.string.bt_txform_note)) },
                    enabled = inputsEnabled,
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    colors = btFieldColors(),
                )

                Spacer(Modifier.height(4.dp))
            }
        }
    }

    val otherHeldAssets by vm.otherHeldAssets.collectAsStateWithLifecycle()
    val assetQuery by vm.assetQuery.collectAsStateWithLifecycle()
    val assetSearch by vm.assetSearch.collectAsStateWithLifecycle()
    if (assetSheetOpen && !vm.isEditSynced) {
        HeldAssetSheet(
            holdings = holdings,
            otherAssets = otherHeldAssets,
            selectedAssetId = asset?.assetId,
            locale = locale,
            query = assetQuery,
            searchState = assetSearch,
            onQueryChange = vm::setAssetQuery,
            onSelect = { pick ->
                vm.setAsset(pick) // clears the query internally
                assetSheetOpen = false
            },
            onDismiss = {
                vm.clearAssetQuery()
                assetSheetOpen = false
            },
        )
    }

    if (datePickerOpen) {
        BtDatePickerDialog(
            initial = date,
            onPick = { picked ->
                vm.setDate(picked)
                datePickerOpen = false
            },
            onDismiss = { datePickerOpen = false },
        )
    }

    if (deleteConfirmOpen) {
        val isDiscard = vm.isEditQueued
        AlertDialog(
            onDismissRequest = { deleteConfirmOpen = false },
            containerColor = bt.surface,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            title = {
                Text(
                    stringResource(
                        if (isDiscard) R.string.bt_txform_discard_title else R.string.bt_txform_delete_title,
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (isDiscard) R.string.bt_txform_discard_message else R.string.bt_txform_delete_message,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteConfirmOpen = false
                        vm.deleteOrDiscard()
                    },
                ) {
                    Text(
                        stringResource(
                            if (isDiscard) R.string.bt_txform_discard_action else R.string.bt_txform_delete_action,
                        ),
                        color = bt.loss,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmOpen = false }) {
                    Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
                }
            },
        )
    }
}

// ── Pieces ───────────────────────────────────────────────────────────────────

/** Buy | Sell segmented toggle, emerald/red selection (§3.3 money direction). */
@Composable
private fun SideToggle(
    isBuy: Boolean,
    enabled: Boolean,
    onSide: (Boolean) -> Unit,
) {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SideOption(
            text = stringResource(R.string.bt_tx_side_buy),
            selected = isBuy,
            accent = bt.gain,
            enabled = enabled,
            onClick = { onSide(true) },
            modifier = Modifier.weight(1f),
        )
        SideOption(
            text = stringResource(R.string.bt_tx_side_sell),
            selected = !isBuy,
            accent = bt.loss,
            enabled = enabled,
            onClick = { onSide(false) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SideOption(
    text: String,
    selected: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val cd = stringResource(
        if (selected) R.string.bt_txform_side_selected_cd else R.string.bt_txform_side_cd,
        text,
    )
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = BtShapes.control,
        color = if (selected) accent.copy(alpha = 0.14f) else bt.surface,
        contentColor = if (selected) accent else bt.textSecondary,
        border = BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.55f) else bt.border),
        interactionSource = interaction,
        modifier = modifier
            .height(48.dp)
            .btPressScale(interaction, pressedScale = 0.97f)
            .semantics { contentDescription = cd },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

@Composable
private fun AssetField(
    asset: AssetPick?,
    locked: Boolean,
    enabled: Boolean,
    locale: Locale,
    onClick: () -> Unit,
) {
    val bt = BtTheme.colors
    BtCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = if (locked || !enabled) null else onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.bt_txform_asset),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
                Spacer(Modifier.height(2.dp))
                if (asset != null) {
                    Text(
                        text = asset.symbol,
                        style = MaterialTheme.typography.titleSmall,
                        color = bt.textPrimary,
                    )
                    val sub = listOfNotNull(
                        asset.name.takeIf { it != asset.symbol },
                        asset.heldQuantity?.let {
                            stringResource(R.string.bt_txform_asset_held, formatQuantity(it, locale))
                        },
                    ).joinToString(" · ")
                    if (sub.isNotEmpty()) {
                        Text(
                            text = sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = bt.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.bt_txform_asset_choose),
                        style = MaterialTheme.typography.titleSmall,
                        color = bt.textSecondary,
                    )
                }
            }
            Icon(
                imageVector = if (locked) Icons.Outlined.Lock else Icons.Outlined.ExpandMore,
                contentDescription = if (locked) {
                    stringResource(R.string.bt_txform_asset_locked_cd)
                } else {
                    stringResource(R.string.bt_txform_asset_choose)
                },
                tint = if (locked) bt.textMuted else bt.gold,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun FormNumberField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    enabled: Boolean,
    error: Boolean,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    selectAllOnFocus: Boolean = false,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    // A TextFieldValue mirror of the external [value] String, so focusing a filled
    // field can SELECT-ALL for instant type-over (owner request) while the VM keeps
    // its plain-String state (sanitization + the date↔price link stay untouched).
    var tfv by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // Re-sync when the source String changes from OUTSIDE the field (Max fill,
    // date→price auto-link, queued-edit prefill): adopt it, caret to the end.
    if (tfv.text != value) {
        tfv = tfv.copy(text = value, selection = TextRange(value.length))
    }
    var wasFocused by remember { mutableStateOf(false) }
    // On the rising edge of focus we select-all AND "arm": the tap that focused the
    // field fires a caret-placing (text-unchanged) onValueChange right after
    // onFocusChanged, which would otherwise collapse the selection — we override
    // that single event back to a full selection so the value is type-over-ready.
    // Real edits (text changed) and every later caret move pass straight through.
    var selectAllArmed by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = tfv,
        onValueChange = { next ->
            if (selectAllArmed && next.text == tfv.text && next.text.isNotEmpty()) {
                // The focusing tap's caret drop — swallow it, keep everything selected.
                selectAllArmed = false
                tfv = next.copy(selection = TextRange(0, next.text.length))
            } else {
                selectAllArmed = false
                tfv = next
                // Only push real text edits back to the VM; a selection-only change
                // never fires this, so it can't perturb the date↔price link.
                if (next.text != value) onValue(next.text)
            }
        },
        label = { Text(label) },
        enabled = enabled,
        isError = error,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
            imeAction = androidx.compose.ui.text.input.ImeAction.Next,
        ),
        suffix = suffix?.let { { Text(it, color = BtTheme.colors.textMuted) } },
        trailingIcon = trailingIcon,
        textStyle = BtTheme.type.moneySmall.copy(fontSize = 17.sp),
        modifier = if (selectAllOnFocus) {
            modifier.onFocusChanged { state ->
                // Select the whole value only on the RISING edge of focus with
                // content present — later taps inside keep normal caret placement.
                if (state.isFocused && !wasFocused && tfv.text.isNotEmpty()) {
                    tfv = tfv.copy(selection = TextRange(0, tfv.text.length))
                    selectAllArmed = true
                } else if (!state.isFocused) {
                    selectAllArmed = false
                }
                wasFocused = state.isFocused
            }
        } else {
            modifier
        },
        colors = btFieldColors(),
    )
}

/** Compact "Max" affordance inside the quantity field (§6.2 owner request). */
@Composable
private fun MaxChip(onClick: () -> Unit) {
    val bt = BtTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = BtShapes.pill,
        color = bt.gold.copy(alpha = 0.14f),
        contentColor = bt.goldEmphasis,
        interactionSource = interaction,
        modifier = Modifier
            .padding(end = 8.dp)
            .btPressScale(interaction, pressedScale = 0.92f),
    ) {
        Text(
            text = stringResource(R.string.bt_txform_max),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** Date ↔ price link chain (§6.2) — gold + linked, muted + unlinked. */
@Composable
private fun PriceLinkToggle(linked: Boolean, onToggle: () -> Unit) {
    val bt = BtTheme.colors
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (linked) Icons.Outlined.Link else Icons.Outlined.LinkOff,
            contentDescription = stringResource(
                if (linked) R.string.bt_txform_price_unlink else R.string.bt_txform_price_link,
            ),
            tint = if (linked) bt.gold else bt.textMuted,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Subtle indeterminate spinner shown in the price field while the daily-close series loads. */
@Composable
private fun PriceLinkLoading() {
    CircularProgressIndicator(
        color = BtTheme.colors.textMuted,
        strokeWidth = 2.dp,
        modifier = Modifier
            .padding(end = 12.dp)
            .size(18.dp),
    )
}

@Composable
private fun DateField(
    date: LocalDate,
    enabled: Boolean,
    locale: Locale,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    val interaction = remember { MutableInteractionSource() }
    // A read-only text field that opens the date picker on press.
    LaunchedEffect(interaction) {
        interaction.interactions.collect {
            if (it is PressInteraction.Release && enabled) onClick()
        }
    }
    OutlinedTextField(
        value = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)),
        onValueChange = {},
        readOnly = true,
        enabled = enabled,
        label = { Text(stringResource(R.string.bt_txform_date)) },
        singleLine = true,
        trailingIcon = {
            Icon(
                Icons.Outlined.CalendarToday,
                contentDescription = stringResource(R.string.bt_txform_date_cd),
                tint = bt.textMuted,
                modifier = Modifier.size(18.dp),
            )
        },
        interactionSource = interaction,
        modifier = modifier,
        colors = btFieldColors(),
    )
}

@Composable
private fun CashCouplingCard(
    isBuy: Boolean,
    coupled: Boolean,
    enabled: Boolean,
    cashAfterEur: Double?,
    cachedCashEur: Double?,
    availableAsOfEur: Double?,
    insufficient: Boolean,
    insufficientAsOfDate: LocalDate?,
    locale: Locale,
    onToggle: (Boolean) -> Unit,
) {
    val bt = BtTheme.colors
    BtCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            if (isBuy) R.string.bt_txform_pay_from_cash else R.string.bt_txform_add_proceeds,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = bt.textPrimary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = when {
                            !coupled && cachedCashEur != null -> stringResource(
                                R.string.bt_txform_cash_balance,
                                formatEur(cachedCashEur, locale),
                            )

                            !coupled -> stringResource(R.string.bt_txform_cash_off_hint)

                            cashAfterEur != null -> stringResource(
                                R.string.bt_txform_cash_after,
                                formatEur(cashAfterEur, locale),
                            )

                            cachedCashEur != null -> stringResource(
                                R.string.bt_txform_cash_balance,
                                formatEur(cachedCashEur, locale),
                            )

                            else -> stringResource(R.string.bt_txform_cash_unknown)
                        },
                        style = BtTheme.type.numberCaption,
                        color = when {
                            coupled && insufficient -> bt.loss
                            coupled && cashAfterEur != null -> bt.textSecondary
                            else -> bt.textMuted
                        },
                    )
                }
                Switch(
                    checked = coupled,
                    onCheckedChange = onToggle,
                    enabled = enabled,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = bt.gold,
                        checkedThumbColor = bt.onGold,
                        uncheckedTrackColor = bt.border,
                        uncheckedThumbColor = bt.textMuted,
                        uncheckedBorderColor = bt.borderStrong,
                    ),
                )
            }
            // The §6.2 hard stop: never silently-negative cash. Inline, clear, and
            // sized against the cash available ON THE CHOSEN DATE (the server's own
            // point-in-time rule); a backdated shortfall names the date so it never
            // reads as a mysterious "insufficient" when the balance is fat today.
            if (insufficient && cachedCashEur != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = bt.loss,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (insufficientAsOfDate != null) {
                            stringResource(
                                R.string.bt_txform_insufficient_cash_asof,
                                insufficientAsOfDate.format(
                                    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale),
                                ),
                                formatEur(availableAsOfEur ?: 0.0, locale),
                                formatEur(cachedCashEur, locale),
                            )
                        } else {
                            stringResource(
                                R.string.bt_txform_insufficient_cash,
                                formatEur(cachedCashEur, locale),
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.loss,
                    )
                }
            }
        }
    }
}

@Composable
private fun RejectionCard(message: String, isQueuedRetry: Boolean) {
    val bt = BtTheme.colors
    Surface(
        shape = BtShapes.card,
        color = bt.lossSurface,
        contentColor = bt.textPrimary,
        border = BorderStroke(1.dp, bt.loss.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = bt.loss,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = stringResource(R.string.bt_txform_rejected_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = bt.lossSoft,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textSecondary,
                )
                if (isQueuedRetry) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.bt_txform_rejected_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun RequiresConnectionCard() {
    val bt = BtTheme.colors
    BtCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.bt_txform_synced_offline_title),
                style = MaterialTheme.typography.titleSmall,
                color = bt.textPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.bt_txform_synced_offline_message),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textSecondary,
            )
        }
    }
}

/** Pinned bottom summary + primary action (one-handed reach, §6.2). */
@Composable
private fun SubmitBar(
    isBuy: Boolean,
    isEdit: Boolean,
    totalEur: Double?,
    canSubmit: Boolean,
    submitting: Boolean,
    locale: Locale,
    onSubmit: () -> Unit,
) {
    val bt = BtTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(thickness = 1.dp, color = bt.border)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.bt_txform_total),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                    modifier = Modifier.weight(1f),
                )
                if (totalEur != null) {
                    MoneyText(value = totalEur, style = BtTheme.type.moneyMedium)
                } else {
                    Text(
                        text = stringResource(R.string.bt_switcher_value_pending),
                        style = BtTheme.type.moneyMedium,
                        color = bt.textMuted,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            BtPrimaryButton(
                text = stringResource(
                    when {
                        isEdit -> R.string.bt_txform_save
                        isBuy -> R.string.bt_txform_record_buy
                        else -> R.string.bt_txform_record_sell
                    },
                ),
                onClick = onSubmit,
                enabled = canSubmit,
                loading = submitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeldAssetSheet(
    holdings: List<HoldingEntity>,
    otherAssets: List<HoldingEntity>,
    selectedAssetId: String?,
    locale: Locale,
    query: String,
    searchState: AssetSearchState,
    onQueryChange: (String) -> Unit,
    onSelect: (AssetPick) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val q = query.trim()
    val active = q.isNotEmpty()
    fun matches(sym: String, name: String) = sym.contains(q, true) || name.contains(q, true)
    val heldShown = if (active) holdings.filter { matches(it.assetSymbol, it.assetName) } else holdings
    val otherShown = if (active) otherAssets.filter { matches(it.assetSymbol, it.assetName) } else otherAssets
    val ownedIds = (holdings + otherAssets).map { it.assetId }.toSet()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bt.surface,
        contentColor = bt.textPrimary,
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = stringResource(R.string.bt_txform_asset_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.bt_txform_asset_search_hint), color = bt.textMuted) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = bt.textMuted) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.bt_search_clear),
                                tint = bt.textMuted,
                            )
                        }
                    }
                },
                colors = btFieldColors(),
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 460.dp),
            ) {
                // Your assets (held here + other portfolios), filtered live.
                if (heldShown.isNotEmpty() || otherShown.isNotEmpty()) {
                    item(key = "your-header") { SheetSectionLabel(stringResource(R.string.bt_txform_asset_your_section)) }
                }
                items(count = heldShown.size, key = { heldShown[it].assetId }) { index ->
                    val h = heldShown[index]
                    AssetSheetRow(
                        symbol = h.assetSymbol,
                        subtitle = h.assetName + " · " + stringResource(
                            R.string.bt_txform_asset_held,
                            formatQuantity(h.quantity, locale),
                        ),
                        selected = h.assetId == selectedAssetId,
                        onClick = { onSelect(AssetPick(h.assetId, h.assetSymbol, h.assetName, h.quantity)) },
                    )
                }
                items(count = otherShown.size, key = { "other-" + otherShown[it].assetId }) { index ->
                    val h = otherShown[index]
                    AssetSheetRow(
                        symbol = h.assetSymbol,
                        subtitle = h.assetName,
                        selected = h.assetId == selectedAssetId,
                        onClick = { onSelect(AssetPick(h.assetId, h.assetSymbol, h.assetName, 0.0)) },
                    )
                }
                // All assets (server search) when a query is active (§6.5).
                if (active) {
                    item(key = "all-header") { SheetSectionLabel(stringResource(R.string.bt_txform_asset_all_section)) }
                    when (val s = searchState) {
                        AssetSearchState.Loading, AssetSearchState.Idle ->
                            item(key = "s-load") { SheetLoadingRow() }

                        AssetSearchState.Offline ->
                            item(key = "s-off") { SheetNote(stringResource(R.string.bt_txform_asset_search_offline)) }

                        AssetSearchState.Empty ->
                            if (heldShown.isEmpty() && otherShown.isEmpty()) {
                                item(key = "s-empty") { SheetNote(stringResource(R.string.bt_search_no_results_title)) }
                            }

                        is AssetSearchState.Results -> {
                            val results = s.assets.filter { it.id !in ownedIds }
                            items(count = results.size, key = { "sr-" + results[it].id }) { index ->
                                val a = results[index]
                                AssetSheetRow(
                                    symbol = a.symbol,
                                    subtitle = listOfNotNull(a.name, a.exchange).joinToString(" · "),
                                    selected = a.id == selectedAssetId,
                                    onClick = { onSelect(AssetPick(a.id, a.symbol, a.name, null)) },
                                )
                            }
                        }
                    }
                } else if (holdings.isEmpty() && otherAssets.isEmpty()) {
                    item(key = "prompt") { SheetNote(stringResource(R.string.bt_txform_asset_search_prompt)) }
                }
            }
        }
    }
}

@Composable
private fun SheetSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = BtTheme.colors.textMuted,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun SheetNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = BtTheme.colors.textMuted,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun SheetLoadingRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = BtTheme.colors.gold, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun AssetSheetRow(
    symbol: String,
    subtitle: String,
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
                    text = symbol,
                    style = MaterialTheme.typography.titleSmall,
                    color = bt.textPrimary,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
private fun FormSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BtSkeleton(Modifier.fillMaxWidth().height(48.dp))
        BtSkeleton(Modifier.fillMaxWidth().height(64.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BtSkeleton(Modifier.weight(1f).height(56.dp))
            BtSkeleton(Modifier.weight(1f).height(56.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BtSkeleton(Modifier.weight(1f).height(56.dp))
            BtSkeleton(Modifier.weight(1f).height(56.dp))
        }
        BtSkeleton(Modifier.fillMaxWidth().height(72.dp))
    }
}

@Composable
private fun btFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BtTheme.colors.gold,
    unfocusedBorderColor = BtTheme.colors.borderStrong,
    disabledBorderColor = BtTheme.colors.border,
    errorBorderColor = BtTheme.colors.loss,
    focusedLabelColor = BtTheme.colors.gold,
    unfocusedLabelColor = BtTheme.colors.textMuted,
    disabledLabelColor = BtTheme.colors.textMuted,
    errorLabelColor = BtTheme.colors.loss,
    focusedTextColor = BtTheme.colors.textPrimary,
    unfocusedTextColor = BtTheme.colors.textPrimary,
    disabledTextColor = BtTheme.colors.textMuted,
    cursorColor = BtTheme.colors.gold,
)
