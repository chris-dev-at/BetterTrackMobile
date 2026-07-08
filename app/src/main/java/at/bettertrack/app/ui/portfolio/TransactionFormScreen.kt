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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.MoneyText
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
import kotlinx.coroutines.flow.combine
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

/** The asset chosen in the picker (a held asset this step — TODO(step 11)). */
data class AssetPick(
    val assetId: String,
    val symbol: String,
    val name: String,
    val heldQuantity: Double?,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionFormViewModel(
    private val repo: PortfolioRepository,
    connectivity: ConnectivityMonitor,
    private val db: BtDatabase,
    private val engine: SyncEngine,
    private val scheduler: SyncScheduler,
    private val json: Json,
    private val route: TransactionFormRoute,
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = connectivity.isOnline

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

    /** Cached cash balance of the target portfolio (server totals, §7.1). */
    val cachedCashEur: StateFlow<Double?> =
        combine(repo.portfolios, _portfolioId) { all, pid ->
            all.firstOrNull { it.id == pid }?.totals?.cashEur
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    val validation: StateFlow<TxFormValidation> = combine(
        combine(_asset, _quantityText, _priceText, _feeText) { a, q, p, f ->
            Quad(a, parseLocalizedDecimal(q), parseLocalizedDecimal(p), parseLocalizedDecimal(f))
        },
        _isBuy,
        _cashCoupled,
        cachedCashEur,
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

    /** Live "Cash after: €…" against the cached balance (§6.2). */
    val cashAfterEur: StateFlow<Double?> = combine(
        _quantityText, _priceText, _feeText, _isBuy,
        combine(_cashCoupled, cachedCashEur) { c, b -> c to b },
    ) { q, p, f, buy, (coupled, balance) ->
        if (!coupled || balance == null) return@combine null
        val qty = parseLocalizedDecimal(q)
        val price = parseLocalizedDecimal(p)
        if (qty == null || price == null) null
        else cashAfterPreview(balance, buy, qty, price, parseLocalizedDecimal(f) ?: 0.0)
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
                    // Sticky cash-coupling default (§6.2): local sticky value,
                    // else the portfolio's server-side default.
                    val sticky = repo.cashCouplingDefault(pid).first()
                    val server = repo.portfolios.first().firstOrNull { it.id == pid }?.defaultPayFromCash
                    _cashCoupled.value = resolveCashCouplingDefault(sticky, server ?: false)
                    // Pre-fill the asset when opened from a holding.
                    route.assetId?.let { prefillAsset(pid, it) }
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
            _asset.value = AssetPick(h.assetId, h.assetSymbol, h.assetName, h.quantity)
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

    // ── Field mutations ─────────────────────────────────────────────────────

    fun setSide(isBuy: Boolean) {
        _isBuy.value = isBuy
    }

    fun setAsset(pick: AssetPick) {
        _asset.value = pick
    }

    fun setQuantity(text: String) {
        _quantityText.value = sanitizeDecimalInput(text)
    }

    fun setPrice(text: String) {
        _priceText.value = sanitizeDecimalInput(text, maxDecimals = 6)
    }

    fun setFee(text: String) {
        _feeText.value = sanitizeDecimalInput(text, maxDecimals = 2)
    }

    fun setDate(date: LocalDate) {
        _date.value = date
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
        when (after?.status) {
            OpStatus.DONE.wire -> _events.value = TxFormEvent.Close

            OpStatus.NEEDS_ATTENTION.wire -> {
                // Bind future submits to this op: edit-and-retry, same UUID.
                _serverError.value = after.serverError
                    ?: "BetterTrack rejected this entry."
            }

            else -> {
                // Still open (offline / backoff / ambiguous): park the
                // CONNECTED-gated drain and leave — the pending row shows.
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
    val feeText by vm.feeText.collectAsStateWithLifecycle()
    val date by vm.date.collectAsStateWithLifecycle()
    val noteText by vm.noteText.collectAsStateWithLifecycle()
    val cashCoupled by vm.cashCoupled.collectAsStateWithLifecycle()
    val validation by vm.validation.collectAsStateWithLifecycle()
    val orderTotalEur by vm.orderTotalEur.collectAsStateWithLifecycle()
    val cashAfterEur by vm.cashAfterEur.collectAsStateWithLifecycle()
    val cachedCashEur by vm.cachedCashEur.collectAsStateWithLifecycle()
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
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FormNumberField(
                        value = quantityText,
                        onValue = vm::setQuantity,
                        label = stringResource(R.string.bt_txform_quantity),
                        enabled = inputsEnabled,
                        error = validation.quantityError != null && quantityText.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    )
                    FormNumberField(
                        value = priceText,
                        onValue = vm::setPrice,
                        label = stringResource(R.string.bt_txform_price),
                        enabled = inputsEnabled,
                        error = validation.priceError != null && priceText.isNotEmpty(),
                        suffix = "€",
                        modifier = Modifier.weight(1f),
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
                        cachedCashEur = cachedCashEur,
                        insufficient = validation.insufficientCash,
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
    if (assetSheetOpen && !vm.isEditSynced) {
        HeldAssetSheet(
            holdings = holdings,
            otherAssets = otherHeldAssets,
            selectedAssetId = asset?.assetId,
            locale = locale,
            onSelect = { pick ->
                vm.setAsset(pick)
                assetSheetOpen = false
            },
            onDismiss = { assetSheetOpen = false },
        )
    }

    if (datePickerOpen) {
        TxDatePickerDialog(
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
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        enabled = enabled,
        isError = error,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
            imeAction = androidx.compose.ui.text.input.ImeAction.Next,
        ),
        suffix = suffix?.let { { Text(it, color = BtTheme.colors.textMuted) } },
        textStyle = BtTheme.type.moneySmall.copy(fontSize = 17.sp),
        modifier = modifier,
        colors = btFieldColors(),
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
    insufficient: Boolean,
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
            // The §6.2 hard stop: never silently-negative cash. Inline, clear,
            // computed against the CACHED balance; the server stays final.
            if (insufficient && cachedCashEur != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = bt.loss,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(
                            R.string.bt_txform_insufficient_cash,
                            formatEur(cachedCashEur, locale),
                        ),
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
    onSelect: (AssetPick) -> Unit,
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "title") {
                Text(
                    text = stringResource(R.string.bt_txform_asset_sheet_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = bt.textPrimary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            if (holdings.isEmpty() && otherAssets.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.bt_txform_asset_sheet_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            items(count = holdings.size, key = { holdings[it].assetId }) { index ->
                val h = holdings[index]
                AssetSheetRow(
                    symbol = h.assetSymbol,
                    subtitle = h.assetName + " · " + stringResource(
                        R.string.bt_txform_asset_held,
                        formatQuantity(h.quantity, locale),
                    ),
                    selected = h.assetId == selectedAssetId,
                    onClick = {
                        onSelect(AssetPick(h.assetId, h.assetSymbol, h.assetName, h.quantity))
                    },
                )
            }
            // Fallback universe: assets held in the user's OTHER portfolios —
            // a fresh portfolio can record its first buy. Held-here qty is 0.
            if (otherAssets.isNotEmpty()) {
                item(key = "other-header") {
                    Text(
                        text = stringResource(R.string.bt_txform_asset_other_section),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                items(count = otherAssets.size, key = { "other-" + otherAssets[it].assetId }) { index ->
                    val h = otherAssets[index]
                    AssetSheetRow(
                        symbol = h.assetSymbol,
                        subtitle = h.assetName,
                        selected = h.assetId == selectedAssetId,
                        onClick = {
                            onSelect(AssetPick(h.assetId, h.assetSymbol, h.assetName, 0.0))
                        },
                    )
                }
            }
            // TODO(step 11): search-buy opens the full asset universe here.
            item(key = "search-soon") {
                Text(
                    text = stringResource(R.string.bt_txform_asset_search_soon),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TxDatePickerDialog(
    initial: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val zone = remember { ZoneId.systemDefault() }
    // No future dates (§6.2): selectable up to the end of today, UTC-keyed as
    // the M3 date picker expects.
    val todayEndUtc = remember {
        LocalDate.now(zone).plusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli() - 1
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= todayEndUtc
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val ms = state.selectedDateMillis ?: return@TextButton
                    onPick(Instant.ofEpochMilli(ms).atZone(ZoneId.of("UTC")).toLocalDate())
                },
            ) { Text(stringResource(R.string.bt_txform_date_ok), color = bt.gold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
            }
        },
    ) {
        DatePicker(state = state, showModeToggle = false)
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
